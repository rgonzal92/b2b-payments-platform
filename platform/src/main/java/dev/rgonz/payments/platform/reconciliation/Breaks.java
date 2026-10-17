package dev.rgonz.payments.platform.reconciliation;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Optional;
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

  /**
   * Marks the Break {@code RESOLVED} this way, by this Reconciler, now, and says whether it did:
   * false when no {@code OPEN} Break has this ID. Conditional on its status, not read first: of two
   * resolutions at once, the second waits for the first to commit, then finds it resolved and
   * changes nothing. Joins the caller's transaction.
   */
  boolean resolve(UUID id, Resolution.Kind kind, String note, Reconciler reconciler) {
    return jdbc.sql(
                """
                update reconciliation.break
                   set status = 'RESOLVED', resolution_kind = :kind, resolution_note = :note,
                       resolved_by = :resolvedBy, resolved_by_name = :resolvedByName,
                       resolved_at = now()
                 where id = :id and status = 'OPEN'
                """)
            .param("id", id)
            .param("kind", kind.name())
            .param("note", note)
            .param("resolvedBy", reconciler.id())
            .param("resolvedByName", reconciler.name())
            .update()
        == 1;
  }

  /** Records the Correcting Entry the Break's resolution posted. Joins the caller's transaction. */
  void recordCorrectingEntry(UUID id, UUID entryId) {
    jdbc.sql("update reconciliation.break set correcting_entry_id = :entryId where id = :id")
        .param("id", id)
        .param("entryId", entryId)
        .update();
  }

  Optional<RaisedBreak> find(UUID id) {
    return jdbc.sql(
            """
            select id, kind, record_id, status, business_date, buyer_id, gap_minor, gap_currency,
                   resolution_kind, resolution_note, resolved_by, resolved_by_name, resolved_at,
                   correcting_entry_id
              from reconciliation.break
             where id = :id
            """)
        .param("id", id)
        .query(
            (row, rowNumber) -> {
              var buyerId = row.getObject("buyer_id", UUID.class);
              var resolutionKind = row.getString("resolution_kind");
              return new RaisedBreak(
                  row.getObject("id", UUID.class),
                  BreakKind.valueOf(row.getString("kind")),
                  row.getString("record_id"),
                  RaisedBreak.Status.valueOf(row.getString("status")),
                  row.getObject("business_date", LocalDate.class),
                  buyerId == null ? null : new BuyerId(buyerId),
                  new Money(
                      row.getLong("gap_minor"),
                      Currency.getInstance(row.getString("gap_currency"))),
                  resolutionKind == null
                      ? null
                      : new RaisedBreak.Resolved(
                          Resolution.Kind.valueOf(resolutionKind),
                          row.getString("resolution_note"),
                          row.getString("resolved_by"),
                          row.getString("resolved_by_name"),
                          row.getObject("resolved_at", OffsetDateTime.class).toInstant(),
                          row.getObject("correcting_entry_id", UUID.class)));
            })
        .optional();
  }
}
