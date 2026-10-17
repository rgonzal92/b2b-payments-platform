package dev.rgonz.payments.platform.reconciliation;

import java.sql.Date;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** The Breaks Reconciliation raised, each {@code OPEN} until a Reconciler resolves it. */
@Repository
class Breaks {

  private final JdbcClient jdbc;
  private final JsonMapper json;

  Breaks(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * Raises the Break, {@code OPEN}, and says whether it did: false when a Break of its kind already
   * names its record, which is then left as it is, whatever its status. Two runs raising one Break
   * at once can't both raise it (the natural key). Joins the caller's transaction.
   */
  boolean raise(Break found) {
    return jdbc.sql(
                """
                insert into reconciliation.break
                       (id, kind, record_id, status, business_date, buyer_id, gap_minor,
                        gap_currency, facts)
                values (:id, :kind, :recordId, 'OPEN', :businessDate, :buyerId, :gapMinor,
                        :gapCurrency, cast(:facts as jsonb))
                on conflict (kind, record_id) do nothing
                """)
            .param("id", UUID.randomUUID())
            .param("kind", found.kind().name())
            .param("recordId", found.recordId())
            .param("businessDate", Date.valueOf(found.businessDate()))
            .param("buyerId", found.buyerId() == null ? null : found.buyerId().value())
            .param("gapMinor", found.gap().minorUnits())
            .param("gapCurrency", found.gap().currency().getCurrencyCode())
            .param("facts", json.writeValueAsString(found.facts()))
            .update()
        == 1;
  }
}
