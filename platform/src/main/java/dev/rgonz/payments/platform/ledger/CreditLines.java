package dev.rgonz.payments.platform.ledger;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

@Repository
class CreditLines implements Holds {

  private final JdbcClient jdbc;
  private final JournalEntries journal;

  CreditLines(JdbcClient jdbc, JournalEntries journal) {
    this.jdbc = jdbc;
    this.journal = journal;
  }

  /** Sets the Buyer's Credit Line, replacing any it has; true when the Buyer had none before. */
  boolean set(BuyerId buyerId, Money creditLine) {
    // PostgreSQL 18's RETURNING old: old's columns are null when the row was inserted, not updated.
    return jdbc.sql(
            """
            insert into ledger.credit_line (buyer_id, amount_minor, currency)
            values (:buyerId, :amount, :currency)
            on conflict (buyer_id) do update
               set amount_minor = excluded.amount_minor,
                   currency     = excluded.currency,
                   updated_at   = now()
            returning old.buyer_id is null as created
            """)
        .param("buyerId", buyerId.value())
        .param("amount", creditLine.minorUnits())
        .param("currency", creditLine.currency().getCurrencyCode())
        .query(Boolean.class)
        .single();
  }

  /**
   * The Buyer's Credit Line, open Holds and Buyer Receivable; empty when the Buyer has no Credit
   * Line.
   */
  Optional<Credit> findCredit(BuyerId buyerId) {
    return jdbc.sql(
            "select amount_minor, currency from ledger.credit_line where buyer_id = :buyerId")
        .param("buyerId", buyerId.value())
        .query(CreditLines::toMoney)
        .optional()
        .map(creditLine -> credit(buyerId, creditLine));
  }

  /**
   * Records the Buyer's Repayment of the amount, as Issuer staff sent it: posts its Journal Entry,
   * which debits Issuer Cash and credits the Buyer Receivable, and returns it. The Idempotency-Key
   * is the entry's: sent again with the same Buyer and amount, it returns the first Repayment and
   * posts nothing. Empty when the Buyer has no Credit Line; answers 422 for an amount of zero or
   * less, in another currency than the Credit Line's, above the Buyer Receivable, or a key already
   * used for another Buyer or amount.
   */
  @Transactional
  Optional<Repayment> repay(
      BuyerId buyerId, String idempotencyKey, long minorUnits, String currency) {
    // The same lock as placing a Hold, so two Repayments can't both repay the last of what the
    // Buyer owes.
    var creditLine = lockCreditLine(buyerId);
    if (creditLine.isEmpty()) {
      return Optional.empty();
    }
    var credit = credit(buyerId, creditLine.get());
    // A key already posted gives back its Repayment and posts nothing. Looked up before the checks,
    // so a resend isn't refused for the balance its own first posting lowered.
    var repayment =
        journal
            .findRepayment(idempotencyKey)
            .orElseGet(() -> checkAndPost(credit, buyerId, idempotencyKey, minorUnits, currency));
    // Also catches another Buyer's Repayment that posted this key first: postRepayment returns it.
    if (!repayment.isFor(buyerId, minorUnits, currency)) {
      throw refused("This Idempotency-Key was already used for a different request.");
    }
    return Optional.of(repayment);
  }

  private Repayment checkAndPost(
      Credit credit, BuyerId buyerId, String idempotencyKey, long minorUnits, String currency) {
    if (minorUnits <= 0) {
      throw refused("A Repayment's amount must be more than zero.");
    }
    // Compared as sent, so a code that isn't a currency at all is refused the same way.
    if (!currency.equals(credit.creditLine().currency().getCurrencyCode())) {
      throw refused("A Repayment must be in the currency of the Buyer's Credit Line.");
    }
    var amount = new Money(minorUnits, credit.creditLine().currency());
    if (amount.isMoreThan(credit.buyerReceivable())) {
      throw refused("A Repayment can't be more than the Buyer Receivable, what the Buyer owes.");
    }
    return journal.postRepayment(idempotencyKey, buyerId, amount);
  }

  private static ErrorResponseException refused(String detail) {
    return new ErrorResponseException(
        HttpStatus.UNPROCESSABLE_CONTENT,
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, detail),
        null);
  }

  @Override
  @Transactional
  public Outcome place(UUID holdId, BuyerId buyerId, Money amount) {
    var creditLine = lockCreditLine(buyerId);
    if (creditLine.isEmpty()) {
      // A Buyer without a Credit Line has no credit: a refusal like any other, not an error.
      return Outcome.INSUFFICIENT_CREDIT;
    }
    var alreadyPlaced =
        jdbc.sql("select exists (select 1 from ledger.hold where id = :id and status = 'PLACED')")
            .param("id", holdId)
            .query(Boolean.class)
            .single();
    if (alreadyPlaced) {
      return Outcome.PLACED;
    }
    var credit = credit(buyerId, creditLine.get());
    if (amount.isMoreThan(credit.availableCredit())) {
      return Outcome.INSUFFICIENT_CREDIT;
    }
    // A released Hold's row is placed again: its credit is reserved afresh, after the same check.
    jdbc.sql(
            """
            insert into ledger.hold (id, buyer_id, amount_minor, currency)
            values (:id, :buyerId, :amount, :currency)
            on conflict (id) do update
               set status       = 'PLACED',
                   amount_minor = excluded.amount_minor,
                   currency     = excluded.currency,
                   placed_at    = now(),
                   released_at  = null
            """)
        .param("id", holdId)
        .param("buyerId", buyerId.value())
        .param("amount", amount.minorUnits())
        .param("currency", amount.currency().getCurrencyCode())
        .update();
    return Outcome.PLACED;
  }

  // The row stays, marked released; releasing it again, or a cleared one, finds no placed Hold and
  // changes nothing.
  @Override
  public void release(UUID holdId) {
    jdbc.sql(
            """
            update ledger.hold
               set status = 'RELEASED', released_at = now()
             where id = :id
               and status = 'PLACED'
            """)
        .param("id", holdId)
        .update();
  }

  // The row stays, marked cleared; only a placed Hold clears, so a released one stays released.
  @Override
  public void clear(UUID holdId) {
    jdbc.sql(
            """
            update ledger.hold
               set status = 'CLEARED', cleared_at = now()
             where id = :id
               and status = 'PLACED'
            """)
        .param("id", holdId)
        .update();
  }

  // Locks the row, so a release or clear by another transaction waits for the caller's to end.
  @Override
  public boolean isReleased(UUID holdId) {
    return jdbc.sql("select status = 'RELEASED' from ledger.hold where id = :id for update")
        .param("id", holdId)
        .query(Boolean.class)
        .optional()
        .orElse(false);
  }

  // Locking the Buyer's Credit Line row makes Holds and Repayments for one Buyer take turns until
  // each transaction ends, so two can't both take the last of its credit, or repay the last of what
  // it owes. Empty when the Buyer has no Credit Line.
  private Optional<Money> lockCreditLine(BuyerId buyerId) {
    return jdbc.sql(
            """
            select amount_minor, currency
              from ledger.credit_line
             where buyer_id = :buyerId
               for update
            """)
        .param("buyerId", buyerId.value())
        .query(CreditLines::toMoney)
        .optional();
  }

  // A statement of its own: after the lock, it sees what other transactions committed while this
  // one waited (READ COMMITTED); a subquery inside the locking statement would not. One
  // statement for both sums: a Clearing that commits between two statements would clear its Hold
  // in one read and miss its Buyer Receivable debit in the other.
  private Credit credit(BuyerId buyerId, Money creditLine) {
    return jdbc.sql(
            """
            select (select coalesce(sum(amount_minor), 0)
                      from ledger.hold
                     where buyer_id = :buyerId
                       and status = 'PLACED') as open_holds,
                   (select coalesce(sum(case l.side when 'DEBIT' then l.amount_minor
                                                    else -l.amount_minor end), 0)
                      from ledger.journal_line l
                      join ledger.account a on a.id = l.account_id
                     where a.kind = 'BUYER_RECEIVABLE'
                       and a.buyer_id = :buyerId
                       and a.currency = :currency) as buyer_receivable
            """)
        .param("buyerId", buyerId.value())
        .param("currency", creditLine.currency().getCurrencyCode())
        .query(
            (row, rowNumber) ->
                new Credit(
                    creditLine,
                    new Money(row.getLong("open_holds"), creditLine.currency()),
                    new Money(row.getLong("buyer_receivable"), creditLine.currency())))
        .single();
  }

  private static Money toMoney(ResultSet row, int rowNumber) throws SQLException {
    return new Money(row.getLong("amount_minor"), Currency.getInstance(row.getString("currency")));
  }
}
