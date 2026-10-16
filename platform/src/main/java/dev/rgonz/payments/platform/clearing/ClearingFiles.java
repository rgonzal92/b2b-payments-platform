package dev.rgonz.payments.platform.clearing;

import dev.rgonz.payments.platform.shared.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The Clearing Files the platform received: each one's header figures and import status. */
@Repository
class ClearingFiles {

  // A file with its Settlement, once it has one.
  private static final String SUMMARY =
      """
      select f.id, f.sequence_number, f.business_date, f.status, f.record_count, f.received_at,
             s.amount_minor, s.imported_total_minor, s.currency, s.matched
        from clearing.clearing_file f
        left join clearing.settlement s on s.file_id = f.id
      """;
  private static final String FILE = SUMMARY + " where f.id = :id";
  // Keyset paging: a page starts after the earlier page's last file, compared on the ordering
  // columns, so it never reads and skips the files before it, as OFFSET would.
  private static final String FIRST_PAGE =
      SUMMARY
          + """
           order by f.received_at desc, f.id desc
           limit :limit
          """;
  private static final String NEXT_PAGE =
      SUMMARY
          + """
           where (f.received_at, f.id) < (:receivedAt, :id)
           order by f.received_at desc, f.id desc
           limit :limit
          """;

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
    var files = jdbc.sql(FILE).param("id", fileId).query(ClearingFiles::received).list();
    return withOutcomes(files).stream().findFirst();
  }

  /**
   * A page of the files, newest first by when the platform received them, ties broken by file ID:
   * the first {@code limit} files, or the {@code limit} after an earlier page's last file.
   *
   * @param after the position of the earlier page's last file; null for the first page
   */
  Page list(Position after, int limit) {
    var query =
        after == null
            ? jdbc.sql(FIRST_PAGE)
            : jdbc.sql(NEXT_PAGE)
                .param("receivedAt", after.receivedAt().atOffset(ZoneOffset.UTC))
                .param("id", after.fileId());
    // One file more than the page holds tells whether another page follows.
    var files = query.param("limit", limit + 1).query(ClearingFiles::received).list();
    var page = files.subList(0, Math.min(limit, files.size()));
    var next = files.size() > limit ? page.getLast().position() : null;
    return new Page(withOutcomes(page), next);
  }

  /**
   * The Network's sequence numbers between the lowest and the highest of the imported files' that
   * no imported file has, lowest first: the first {@code limit} of them. A file still importing, or
   * refused, doesn't fill its number.
   */
  List<Long> missingSequenceNumbers(int limit) {
    return jdbc.sql(
            """
            with imported as (
              select sequence_number from clearing.clearing_file where status = :imported)
            select n
              from generate_series((select min(sequence_number) from imported),
                                   (select max(sequence_number) from imported)) as n
             where n not in (select sequence_number from imported)
             order by n
             limit :limit
            """)
        .param("imported", ClearingFileSummary.Status.IMPORTED.name())
        .param("limit", limit)
        .query(Long.class)
        .list();
  }

  /**
   * A page of files.
   *
   * @param next the position of the page's last file, to ask for the page after it; null on the
   *     last page
   */
  record Page(List<ClearingFileSummary> files, Position next) {

    Page {
      files = List.copyOf(files);
    }
  }

  /** Where a file stands in the list: when the platform received it, and its ID. */
  record Position(Instant receivedAt, String fileId) {}

  // A file's row before its Clearings are counted: they're counted for all the rows at once, not
  // from inside the row mapper, which would hold a second connection.
  private record Received(
      String id,
      long sequenceNumber,
      LocalDate businessDate,
      ClearingFileSummary.Status status,
      long recordCount,
      ClearingFileSummary.Settlement settlement,
      Instant receivedAt) {

    Position position() {
      return new Position(receivedAt, id);
    }
  }

  private static Received received(ResultSet row, int rowNumber) throws SQLException {
    return new Received(
        row.getString("id"),
        row.getLong("sequence_number"),
        row.getObject("business_date", LocalDate.class),
        ClearingFileSummary.Status.valueOf(row.getString("status")),
        row.getLong("record_count"),
        settlement(row),
        row.getObject("received_at", OffsetDateTime.class).toInstant());
  }

  private List<ClearingFileSummary> withOutcomes(List<Received> files) {
    if (files.isEmpty()) {
      return List.of();
    }
    var outcomes = outcomes(files.stream().map(Received::id).toList());
    return files.stream()
        .map(
            file ->
                new ClearingFileSummary(
                    file.id(),
                    file.sequenceNumber(),
                    file.businessDate(),
                    file.status(),
                    file.recordCount(),
                    outcomes.get(file.id()),
                    file.settlement()))
        .toList();
  }

  // How many of each file's Clearings ended with each outcome; zero for an outcome none had.
  private Map<String, Map<ClearingOutcome, Long>> outcomes(List<String> fileIds) {
    var outcomes = new HashMap<String, Map<ClearingOutcome, Long>>();
    for (var fileId : fileIds) {
      var counts = new EnumMap<ClearingOutcome, Long>(ClearingOutcome.class);
      for (var outcome : ClearingOutcome.values()) {
        counts.put(outcome, 0L);
      }
      outcomes.put(fileId, counts);
    }
    jdbc.sql(
            """
            select file_id, outcome, count(*) as clearings
              from clearing.clearing
             where file_id in (:fileIds)
             group by file_id, outcome
            """)
        .param("fileIds", fileIds)
        // A block: an expression lambda would also fit query's ResultSetExtractor overload.
        .query(
            row -> {
              outcomes
                  .get(row.getString("file_id"))
                  .put(ClearingOutcome.valueOf(row.getString("outcome")), row.getLong("clearings"));
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
