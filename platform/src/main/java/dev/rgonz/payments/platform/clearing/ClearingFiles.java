package dev.rgonz.payments.platform.clearing;

import dev.rgonz.payments.platform.shared.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Currency;
import java.util.EnumMap;
import java.util.Map;
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

  /** The file with its Settlement, once it has one. */
  Optional<ClearingFileSummary> find(String fileId) {
    // Counted first, not from inside the row mapper, which would hold a second connection.
    var outcomes = outcomes(fileId);
    return jdbc.sql(
            """
            select f.id, f.sequence_number, f.business_date, f.status, f.record_count,
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
                    row.getLong("record_count"),
                    outcomes,
                    settlement(row)))
        .optional();
  }

  // How many of the file's Clearings ended with each outcome; zero for an outcome none had.
  private Map<ClearingOutcome, Long> outcomes(String fileId) {
    var outcomes = new EnumMap<ClearingOutcome, Long>(ClearingOutcome.class);
    for (var outcome : ClearingOutcome.values()) {
      outcomes.put(outcome, 0L);
    }
    jdbc.sql(
            """
            select outcome, count(*) as clearings
              from clearing.clearing
             where file_id = :fileId
             group by outcome
            """)
        .param("fileId", fileId)
        // A block: an expression lambda would also fit query's ResultSetExtractor overload.
        .query(
            row -> {
              outcomes.put(
                  ClearingOutcome.valueOf(row.getString("outcome")), row.getLong("clearings"));
            });
    return outcomes;
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
