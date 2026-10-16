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
   * Records a file the validate step accepted, as {@code IMPORTING}. Joins the step's transaction.
   */
  void receive(FileHeader header, long recordCount) {
    jdbc.sql(
            """
            insert into clearing.clearing_file
                   (id, sequence_number, business_date, status, record_count)
            values (:id, :sequenceNumber, :businessDate, :status, :recordCount)
            """)
        .param("id", header.fileId())
        .param("sequenceNumber", header.sequenceNumber())
        .param("businessDate", header.businessDate())
        .param("status", ClearingFileSummary.Status.IMPORTING.name())
        .param("recordCount", recordCount)
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

  Optional<ClearingFileSummary> find(String fileId) {
    return jdbc.sql(
            """
            select id, sequence_number, business_date, status, record_count
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
                    row.getLong("record_count")))
        .optional();
  }
}
