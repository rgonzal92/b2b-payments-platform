package dev.rgonz.payments.platform.buyers;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class Buyers implements BuyerDirectory {

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
            .param("id", buyer.id().value())
            .param("name", buyer.name())
            .param("threshold", buyer.approvalThreshold().minorUnits())
            .param("currency", buyer.approvalThreshold().currency().getCurrencyCode())
            .update()
        == 1;
  }

  Optional<Buyer> find(BuyerId id) {
    return jdbc.sql(
            """
            select id, name, approval_threshold_minor, currency
              from buyers.buyer
             where id = :id
            """)
        .param("id", id.value())
        .query(
            (row, rowNumber) ->
                new Buyer(
                    new BuyerId(row.getObject("id", UUID.class)),
                    row.getString("name"),
                    new Money(
                        row.getLong("approval_threshold_minor"),
                        Currency.getInstance(row.getString("currency")))))
        .optional();
  }

  @Override
  public boolean exists(BuyerId id) {
    return jdbc.sql("select exists (select 1 from buyers.buyer where id = :id)")
        .param("id", id.value())
        .query(Boolean.class)
        .single();
  }
}
