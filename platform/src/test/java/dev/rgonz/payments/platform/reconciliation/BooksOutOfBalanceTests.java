package dev.rgonz.payments.platform.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.rgonz.payments.platform.TestcontainersConfiguration;
import dev.rgonz.payments.platform.ledger.Journal;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

/**
 * A Reconciliation run checks the books, on PostgreSQL: balanced books raise nothing, and a failed
 * check raises one Break per Business Date, check and currency. The checks read the whole journal,
 * so this class gets a context, and with it a PostgreSQL, of its own: no other test's entries or
 * Clearing Files reach it. Each test leaves the books balanced again, by appending, as the journal
 * refuses to change an entry.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class BooksOutOfBalanceTests {

  private static final Currency USD = Currency.getInstance("USD");

  @Autowired Reconciliations reconciliations;
  @Autowired Journal journal;
  @Autowired JdbcClient jdbc;

  @Test
  void balancedBooksRaiseNoBooksBreak() {
    var date = LocalDate.of(2002, 1, 1);
    // A Clearing and its file's Settlement at the same figure: the Payable is back to zero.
    journal.postClearing("R-balanced", new BuyerId(UUID.randomUUID()), new Money(1_000, USD));
    journal.postSettlement("F-balanced", new Money(1_000, USD));

    reconciliations.run(date);

    assertThat(booksBreaksOn(date)).isEmpty();
  }

  // The journal refuses an unbalanced entry, so the test writes one straight into ledger's tables:
  // a lone debit of Issuer Cash.
  @Test
  void anUnbalancedJournalRaisesOneBooksBreakAndARunAgainNoMore() {
    var date = LocalDate.of(2002, 1, 2);
    loneLine("DEBIT", 500);
    try {
      reconciliations.run(date);
      var again = reconciliations.run(date);

      assertThat(again.newBreaks()).isZero();
      assertThat(booksBreaksOn(date))
          .containsExactly(
              tuple("BOOKS_OUT_OF_BALANCE", date + "/DEBITS_AGAINST_CREDITS/USD", 500L, "USD"));
    } finally {
      loneLine("CREDIT", 500);
    }
  }

  // No Clearing File waits to be settled, yet the Network settled less than was cleared.
  @Test
  void aNetworkSettlementPayableNotZeroOnceEveryFileIsSettledRaisesABooksBreak() {
    var date = LocalDate.of(2002, 1, 3);
    journal.postClearing("R-unsettled", new BuyerId(UUID.randomUUID()), new Money(1_000, USD));
    journal.postSettlement("F-short", new Money(940, USD));
    try {
      reconciliations.run(date);

      assertThat(booksBreaksOn(date))
          .containsExactly(
              tuple("BOOKS_OUT_OF_BALANCE", date + "/NETWORK_SETTLEMENT_PAYABLE/USD", 60L, "USD"));
    } finally {
      journal.postSettlement("F-rest", new Money(60, USD));
    }
  }

  private List<Tuple> booksBreaksOn(LocalDate date) {
    return jdbc.sql(
            """
            select kind, record_id, gap_minor, gap_currency from reconciliation.break
             where business_date = :date and kind = 'BOOKS_OUT_OF_BALANCE'
            """)
        .param("date", Date.valueOf(date))
        .query(
            (row, n) ->
                tuple(
                    row.getString("kind"),
                    row.getString("record_id"),
                    row.getLong("gap_minor"),
                    row.getString("gap_currency")))
        .list();
  }

  // A Journal Entry of one line to Issuer Cash, which balances nothing.
  private void loneLine(String side, long amountMinor) {
    jdbc.sql(
            """
            insert into ledger.account (id, kind, currency) values (:id, 'ISSUER_CASH', 'USD')
            on conflict on constraint account_unique do nothing
            """)
        .param("id", UUID.randomUUID())
        .update();
    var entryId = UUID.randomUUID();
    jdbc.sql(
            """
            insert into ledger.journal_entry (id, kind, idempotency_key)
            values (:id, 'SETTLEMENT', :key)
            """)
        .param("id", entryId)
        .param("key", "lone-" + entryId)
        .update();
    jdbc.sql(
            """
            insert into ledger.journal_line (entry_id, line_number, account_id, side, amount_minor)
            select :entryId, 1, id, :side, :amount from ledger.account
             where kind = 'ISSUER_CASH' and currency = 'USD'
            """)
        .param("entryId", entryId)
        .param("side", side)
        .param("amount", amountMinor)
        .update();
  }
}
