package dev.rgonz.payments.platform.clearing;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The Clearing Files the platform received: each one's header and trailer figures, content hash and
 * import status, or why it was refused.
 */
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
   * Counts records of the file skipped because their Network record ID was already recorded. Joins
   * the chunk's transaction.
   */
  void countSkipped(String fileId, long skipped) {
    jdbc.sql(
            """
            update clearing.clearing_file set skipped_count = skipped_count + :skipped
             where id = :id
            """)
        .param("skipped", skipped)
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

  /**
   * The ID of the file one with this file ID and content hash repeats: a file received earlier with
   * the same file ID, refused ones included, or the same content hash. Never the file whose import
   * stopped partway, which has both and is still {@code IMPORTING}: importing it again restarts
   * that import.
   */
  Optional<String> repeatedBy(String fileId, String contentHash) {
    return jdbc.sql(
            """
            select id from clearing.clearing_file
             where (id = :id or content_hash = :contentHash)
               and not (id = :id and content_hash = :contentHash and status = :importing)
             limit 1
            """)
        .param("id", fileId)
        .param("contentHash", contentHash)
        .param("importing", ClearingFileSummary.Status.IMPORTING.name())
        .query(String.class)
        .optional();
  }

  Optional<ClearingFileSummary> find(String fileId) {
    return jdbc.sql(
            """
            select id, sequence_number, business_date, status, record_count, skipped_count,
                   refusal_reason
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
                    row.getLong("skipped_count"),
                    row.getString("refusal_reason")))
        .optional();
  }
}
