package dev.rgonz.payments.platform.clearing;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The Clearing Files the platform received: each one's header figures and import status. */
@Repository
class ClearingFiles {

  private final JdbcClient jdbc;

  ClearingFiles(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Records a file the validate step accepted, as {@code IMPORTING}, with its header's and its
   * trailer's figures and its content hash. Joins the step's transaction.
   */
  void receive(ClearingFileLayout.Envelope file, String contentHash) {
    var header = file.header();
    var trailer = file.trailer();
    jdbc.sql(
            """
            insert into clearing.clearing_file
                   (id, sequence_number, business_date, status, record_count, amount_total_minor,
                    net_settlement_amount_minor, totals_currency, content_hash)
            values (:id, :sequenceNumber, :businessDate, :status, :recordCount, :amountTotal,
                    :netSettlementAmount, :currency, :contentHash)
            """)
        .param("id", header.fileId())
        .param("sequenceNumber", header.sequenceNumber())
        .param("businessDate", header.businessDate())
        .param("status", ClearingFileSummary.Status.IMPORTING.name())
        .param("recordCount", trailer.recordCount())
        .param("amountTotal", trailer.amountTotal().minorUnits())
        .param("netSettlementAmount", trailer.netSettlementAmount().minorUnits())
        .param("currency", trailer.amountTotal().currency().getCurrencyCode())
        .param("contentHash", contentHash)
        .update();
  }

  /**
   * Marks the file {@code IMPORTED}: every Clearing in it is recorded. Joins the step's
   * transaction.
   */
  void markImported(String fileId) {
    jdbc.sql(
            """
            update clearing.clearing_file set status = :imported, imported_at = now()
             where id = :id
            """)
        .param("imported", ClearingFileSummary.Status.IMPORTED.name())
        .param("id", fileId)
        .update();
  }

  /**
   * Records a file that failed a check of the validate step, as {@code REFUSED}, with the check it
   * failed.
   */
  void refuse(FileHeader header, String reason) {
    jdbc.sql(
            """
            insert into clearing.clearing_file
                   (id, sequence_number, business_date, status, refusal_reason)
            values (:id, :sequenceNumber, :businessDate, :status, :reason)
            """)
        .param("id", header.fileId())
        .param("sequenceNumber", header.sequenceNumber())
        .param("businessDate", header.businessDate())
        .param("status", ClearingFileSummary.Status.REFUSED.name())
        .param("reason", reason)
        .update();
  }

  Optional<ClearingFileSummary> find(String fileId) {
    return jdbc.sql(
            """
            select id, sequence_number, business_date, status, record_count, refusal_reason
              from clearing.clearing_file
             where id = :id
            """)
        .param("id", fileId)
        .query(
            (row, rowNumber) ->
                new ClearingFileSummary(
                    row.getString("id"),
                    row.getLong("sequence_number"),
                    row.getObject("business_date", LocalDate.class),
                    ClearingFileSummary.Status.valueOf(row.getString("status")),
                    row.getObject("record_count", Long.class),
                    row.getString("refusal_reason")))
        .optional();
  }
}
