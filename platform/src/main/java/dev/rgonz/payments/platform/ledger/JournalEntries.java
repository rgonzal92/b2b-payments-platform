package dev.rgonz.payments.platform.ledger;

import dev.rgonz.payments.platform.ledger.JournalEntry.Line;
import dev.rgonz.payments.platform.ledger.JournalEntry.Side;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Issuer's double-entry journal (ADR-0015): Journal Entries and their lines, append-only, and
 * the Accounts they post to, each created the first time an entry posts to it.
 */
@Repository
class JournalEntries implements Journal {

  private final JdbcClient jdbc;

  JournalEntries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public UUID postClearing(String networkRecordId, BuyerId buyerId, Money amount) {
    return postClearing(networkRecordId, Account.buyerReceivable(buyerId), amount);
  }

  @Override
  @Transactional
  public UUID postClearingToSuspense(String networkRecordId, Money amount) {
    return postClearing(networkRecordId, Account.clearingSuspense(), amount);
  }

  // A Clearing debits the Account that carries it and credits the Network Settlement Payable.
  private UUID postClearing(String networkRecordId, Account debited, Money amount) {
    return post(
        new JournalEntry(
            JournalEntry.Kind.CLEARING,
            networkRecordId,
            List.of(
                new Line(debited, Side.DEBIT, amount),
                new Line(Account.networkSettlementPayable(), Side.CREDIT, amount))));
  }

  @Override
  @Transactional
  public UUID postSettlement(String fileId, Money amount) {
    return post(
        new JournalEntry(
            JournalEntry.Kind.SETTLEMENT,
            fileId,
            List.of(
                new Line(Account.networkSettlementPayable(), Side.DEBIT, amount),
                new Line(Account.issuerCash(), Side.CREDIT, amount))));
  }

  // Posts the entry and returns its ID; an entry of the same kind and idempotency key posted before
  // returns that entry's ID instead, and nothing is posted. Runs in the caller's transaction, so
  // the entry and its lines commit together.
  private UUID post(JournalEntry entry) {
    var id = UUID.randomUUID();
    // The unique key decides, not a read first: a second post of one key waits for the first to
    // commit, then inserts nothing and finds the first entry.
    var inserted =
        jdbc.sql(
                    """
                    insert into ledger.journal_entry (id, kind, idempotency_key)
                    values (:id, :kind, :idempotencyKey)
                    on conflict (kind, idempotency_key) do nothing
                    """)
                .param("id", id)
                .param("kind", entry.kind().name())
                .param("idempotencyKey", entry.idempotencyKey())
                .update()
            == 1;
    if (!inserted) {
      return jdbc.sql(
              """
              select id from ledger.journal_entry
               where kind = :kind and idempotency_key = :idempotencyKey
              """)
          .param("kind", entry.kind().name())
          .param("idempotencyKey", entry.idempotencyKey())
          .query(UUID.class)
          .single();
    }
    var lineNumber = 0;
    for (var line : entry.lines()) {
      jdbc.sql(
              """
              insert into ledger.journal_line (entry_id, line_number, account_id, side, amount_minor)
              values (:entryId, :lineNumber, :accountId, :side, :amount)
              """)
          .param("entryId", id)
          .param("lineNumber", ++lineNumber)
          .param("accountId", accountId(line.account(), line.amount().currency()))
          .param("side", line.side().name())
          .param("amount", line.amount().minorUnits())
          .update();
    }
    return id;
  }

  // Finds the Account, or adds it on its first entry. Of two transactions adding one Account at
  // once, the second's insert waits for the first to commit, then inserts nothing; the select, a
  // new statement, then sees the first one's row.
  private UUID accountId(Account account, Currency currency) {
    var buyerId = account.buyerId() == null ? null : account.buyerId().value();
    jdbc.sql(
            """
            insert into ledger.account (id, kind, buyer_id, currency)
            values (:id, :kind, :buyerId, :currency)
            on conflict on constraint account_unique do nothing
            """)
        .param("id", UUID.randomUUID())
        .param("kind", account.kind().name())
        .param("buyerId", buyerId)
        .param("currency", currency.getCurrencyCode())
        .update();
    return jdbc.sql(
            """
            select id from ledger.account
             where kind = :kind and buyer_id is not distinct from :buyerId and currency = :currency
            """)
        .param("kind", account.kind().name())
        .param("buyerId", buyerId)
        .param("currency", currency.getCurrencyCode())
        .query(UUID.class)
        .single();
  }
}
