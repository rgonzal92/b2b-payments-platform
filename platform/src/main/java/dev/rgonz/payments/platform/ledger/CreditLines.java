package dev.rgonz.payments.platform.ledger;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class CreditLines implements Holds {

  private final JdbcClient jdbc;

  CreditLines(JdbcClient jdbc) {
    this.jdbc = jdbc;
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

  @Override
  @Transactional
  public Outcome place(UUID holdId, BuyerId buyerId, Money amount) {
    // Locking the Buyer's Credit Line row makes Holds for one Buyer take turns until each
    // transaction ends, so two can't both take the last of its credit.
    var creditLine =
        jdbc.sql(
                """
                select amount_minor, currency
                  from ledger.credit_line
                 where buyer_id = :buyerId
                   for update
                """)
            .param("buyerId", buyerId.value())
            .query(CreditLines::toMoney)
            .optional();
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

  // A statement of its own: after place's lock, it sees what other transactions committed while
  // that one waited (READ COMMITTED); a subquery inside the locking statement would not. One
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
