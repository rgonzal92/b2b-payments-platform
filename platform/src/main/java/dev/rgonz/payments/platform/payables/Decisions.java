package dev.rgonz.payments.platform.payables;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Every Approver's approvals and rejections of Invoices, kept as the audit trail. */
@Repository
class Decisions {

  private final JdbcClient jdbc;

  Decisions(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  void record(UUID invoiceId, Approver approver, Decision.Outcome outcome) {
    jdbc.sql(
            """
            insert into payables.invoice_decision (invoice_id, approver_id, approver_name, decision)
            values (:invoiceId, :approverId, :approverName, :decision)
            """)
        .param("invoiceId", invoiceId)
        .param("approverId", approver.id())
        .param("approverName", approver.name())
        .param("decision", outcome.name())
        .update();
  }

  /** The Invoice's decisions, oldest first. */
  List<Decision> of(UUID invoiceId) {
    return jdbc.sql(
            """
            select approver_id, approver_name, decision, decided_at
              from payables.invoice_decision
             where invoice_id = :invoiceId
             order by decided_at
            """)
        .param("invoiceId", invoiceId)
        .query(
            (row, rowNumber) ->
                new Decision(
                    new Approver(row.getString("approver_id"), row.getString("approver_name")),
                    Decision.Outcome.valueOf(row.getString("decision")),
                    row.getObject("decided_at", OffsetDateTime.class).toInstant()))
        .list();
  }
}
