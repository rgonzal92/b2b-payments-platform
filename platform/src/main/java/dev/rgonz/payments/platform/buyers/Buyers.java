package dev.rgonz.payments.platform.buyers;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class Buyers {

  private final JdbcClient jdbc;

  Buyers(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Creates the Buyer, or replaces the one with the same ID; returns true when it was created. */
  boolean save(Buyer buyer) {
    return jdbc.sql(
            """
            insert into buyers.buyer (id, name, approval_threshold_minor, currency)
            values (:id, :name, :threshold, :currency)
            on conflict (id) do update
               set name = excluded.name,
                   approval_threshold_minor = excluded.approval_threshold_minor,
                   currency = excluded.currency
            returning (xmax = 0) as created
            """)
        .param("id", buyer.id())
        .param("name", buyer.name())
        .param("threshold", buyer.approvalThreshold().minorUnits())
        .param("currency", buyer.approvalThreshold().currency().getCurrencyCode())
        .query(Boolean.class)
        .single();
  }
}
