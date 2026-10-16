package dev.rgonz.payments.platform.ledger;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.util.Currency;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class CreditLines {

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
            select amount_minor, currency
              from ledger.credit_line
             where buyer_id = :buyerId
            """)
        .param("buyerId", buyerId.value())
        .query(
            (row, rowNumber) -> {
              var currency = Currency.getInstance(row.getString("currency"));
              // Nothing is held yet: placing Holds arrives later in M1.
              return new Credit(
                  new Money(row.getLong("amount_minor"), currency), new Money(0, currency));
            })
        .optional();
  }
}
