package dev.rgonz.payments.platform.clearing;

import dev.rgonz.payments.platform.shared.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Currency;
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
   * trailer's figures and its content hash, in place of a refused file with its ID. When the step
   * runs again for the same file (it committed, then the platform stopped before Spring Batch
   * recorded it complete), the file's record is already there, and stays. Joins the step's
   * transaction.
   */
  void receive(ClearingFileLayout.Envelope file, String contentHash) {
    var header = file.header();
    var trailer = file.trailer();
    var received =
        jdbc.sql(
                """
                insert into clearing.clearing_file
                       (id, sequence_number, business_date, status, record_count,
                        amount_total_minor, net_settlement_amount_minor, totals_currency,
                        content_hash)
                values (:id, :sequenceNumber, :businessDate, :status, :recordCount, :amountTotal,
                        :netSettlementAmount, :currency, :contentHash)
                on conflict (id) do update
                   set sequence_number = excluded.sequence_number,
                       business_date = excluded.business_date, status = excluded.status,
                       record_count = excluded.record_count,
                       amount_total_minor = excluded.amount_total_minor,
                       net_settlement_amount_minor = excluded.net_settlement_amount_minor,
                       totals_currency = excluded.totals_currency,
                       content_hash = excluded.content_hash, refusal_reason = null,
                       received_at = case when clearing_file.status = :refused then now()
                                          else clearing_file.received_at end
                 where clearing_file.status = :refused
                    or (clearing_file.status = excluded.status
                        and clearing_file.content_hash = excluded.content_hash)
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
            .param("refused", ClearingFileSummary.Status.REFUSED.name())
            .update();
    // Another file with this ID is importing or imported, which the repeat check before the job
    // keeps from happening.
    if (received != 1) {
      throw new IllegalStateException("Clearing File " + header.fileId() + " is already received");
    }
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
   * failed, in place of an earlier refused file with its ID.
   */
  void refuse(FileHeader header, String reason) {
    jdbc.sql(
            """
            insert into clearing.clearing_file
                   (id, sequence_number, business_date, status, refusal_reason)
            values (:id, :sequenceNumber, :businessDate, :status, :reason)
            on conflict (id) do update
               set sequence_number = excluded.sequence_number,
                   business_date = excluded.business_date,
                   refusal_reason = excluded.refusal_reason, received_at = now()
             where clearing_file.status = excluded.status
            """)
        .param("id", header.fileId())
        .param("sequenceNumber", header.sequenceNumber())
        .param("businessDate", header.businessDate())
        .param("status", ClearingFileSummary.Status.REFUSED.name())
        .param("reason", reason)
        .update();
  }

  /**
   * The ID of the file one with this file ID and content hash repeats: a file imported, or being
   * imported, with the same file ID or the same content hash. Never a refused file, which wasn't
   * imported, nor the file whose import stopped partway, which has both and is still {@code
   * IMPORTING}: importing it again restarts that import.
   */
  Optional<String> repeatedBy(String fileId, String contentHash) {
    return jdbc.sql(
            """
            select id from clearing.clearing_file
             where status <> :refused
               and (id = :id or content_hash = :contentHash)
               and not (id = :id and content_hash = :contentHash and status = :importing)
             limit 1
            """)
        .param("id", fileId)
        .param("contentHash", contentHash)
        .param("refused", ClearingFileSummary.Status.REFUSED.name())
        .param("importing", ClearingFileSummary.Status.IMPORTING.name())
        .query(String.class)
        .optional();
  }

  /** The file with its Settlement, once it has one. */
  Optional<ClearingFileSummary> find(String fileId) {
    return jdbc.sql(
            """
            select f.id, f.sequence_number, f.business_date, f.status, f.record_count,
                   f.skipped_count, f.refusal_reason,
                   s.amount_minor, s.imported_total_minor, s.currency, s.matched
              from clearing.clearing_file f
              left join clearing.settlement s on s.file_id = f.id
             where f.id = :id
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
                    settlement(row),
                    row.getString("refusal_reason")))
        .optional();
  }

  private static ClearingFileSummary.Settlement settlement(ResultSet row) throws SQLException {
    var currency = row.getString("currency");
    if (currency == null) {
      return null;
    }
    return new ClearingFileSummary.Settlement(
        new Money(row.getLong("amount_minor"), Currency.getInstance(currency)),
        new Money(row.getLong("imported_total_minor"), Currency.getInstance(currency)),
        row.getBoolean("matched"));
  }
}
