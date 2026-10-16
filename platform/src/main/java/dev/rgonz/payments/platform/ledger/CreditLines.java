package dev.rgonz.payments.platform.ledger;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
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

  /** The Buyer's Credit Line and open Holds; empty when the Buyer has no Credit Line. */
  Optional<Credit> findCredit(BuyerId buyerId) {
    return jdbc.sql(
            """
            select c.amount_minor, c.currency,
                   (select coalesce(sum(h.amount_minor), 0)
                      from ledger.hold h
                     where h.buyer_id = c.buyer_id) as held_minor
              from ledger.credit_line c
             where c.buyer_id = :buyerId
            """)
        .param("buyerId", buyerId.value())
        .query(
            (row, rowNumber) -> {
              var currency = Currency.getInstance(row.getString("currency"));
              return new Credit(
                  new Money(row.getLong("amount_minor"), currency),
                  new Money(row.getLong("held_minor"), currency));
            })
        .optional();
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
            .query(
                (row, rowNumber) ->
                    new Money(
                        row.getLong("amount_minor"),
                        Currency.getInstance(row.getString("currency"))))
            .optional();
    if (creditLine.isEmpty()) {
      // A Buyer without a Credit Line has no credit: a refusal like any other, not an error.
      return Outcome.INSUFFICIENT_CREDIT;
    }
    var alreadyPlaced =
        jdbc.sql("select exists (select 1 from ledger.hold where id = :id)")
            .param("id", holdId)
            .query(Boolean.class)
            .single();
    if (alreadyPlaced) {
      return Outcome.PLACED;
    }
    // Statements after the lock see what other transactions committed while this one waited for
    // it (READ COMMITTED); a subquery inside the locking statement would not.
    var held =
        jdbc.sql("select coalesce(sum(amount_minor), 0) from ledger.hold where buyer_id = :buyerId")
            .param("buyerId", buyerId.value())
            .query(Long.class)
            .single();
    var credit = new Credit(creditLine.get(), new Money(held, creditLine.get().currency()));
    if (amount.isMoreThan(credit.availableCredit())) {
      return Outcome.INSUFFICIENT_CREDIT;
    }
    jdbc.sql(
            """
            insert into ledger.hold (id, buyer_id, amount_minor, currency)
            values (:id, :buyerId, :amount, :currency)
            """)
        .param("id", holdId)
        .param("buyerId", buyerId.value())
        .param("amount", amount.minorUnits())
        .param("currency", amount.currency().getCurrencyCode())
        .update();
    return Outcome.PLACED;
  }
}
