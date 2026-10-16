package dev.rgonz.payments.platform.buyers;

import dev.rgonz.payments.platform.shared.Money;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class Buyers {

  private final JdbcClient jdbc;

  Buyers(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts the Buyer unless one with its ID already exists; true when it was inserted. */
  boolean insertIfAbsent(Buyer buyer) {
    return jdbc.sql(
                """
                insert into buyers.buyer (id, name, approval_threshold_minor, currency)
                values (:id, :name, :threshold, :currency)
                on conflict (id) do nothing
                """)
            .param("id", buyer.id())
            .param("name", buyer.name())
            .param("threshold", buyer.approvalThreshold().minorUnits())
            .param("currency", buyer.approvalThreshold().currency().getCurrencyCode())
            .update()
        == 1;
  }

  Optional<Buyer> find(UUID id) {
    return jdbc.sql(
            """
            select id, name, approval_threshold_minor, currency
              from buyers.buyer
             where id = :id
            """)
        .param("id", id)
        .query(
            (row, rowNumber) ->
                new Buyer(
                    row.getObject("id", UUID.class),
                    row.getString("name"),
                    new Money(
                        row.getLong("approval_threshold_minor"),
                        Currency.getInstance(row.getString("currency")))))
        .optional();
  }
}
