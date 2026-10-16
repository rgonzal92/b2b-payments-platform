package dev.rgonz.payments.platform.clearing;

import dev.rgonz.payments.platform.shared.Money;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Currency;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The Clearing Files the platform received: each one's header and trailer figures, content hash and
 * import status, or why it was refused.
 */
@Repository
class ClearingFiles {

  // A file with its Settlement, once it has one.
  private static final String SUMMARY =
      """
      select f.id, f.sequence_number, f.business_date, f.status, f.record_count, f.skipped_count,
             f.refusal_reason, f.received_at,
             s.amount_minor, s.imported_total_minor, s.currency, s.matched
        from clearing.clearing_file f
        left join clearing.settlement s on s.file_id = f.id
      """;
  private static final String FILE = SUMMARY + " where f.id = :id";
  // Keyset paging: a page starts after the earlier page's last file, compared on the ordering
  // columns, which clearing_file_received_idx keeps in order, so it never reads and skips the files
  // before it, as OFFSET would.
  private static final String PAGE = " order by f.received_at desc, f.id desc limit :limit";
  private static final String FIRST_PAGE = SUMMARY + PAGE;
  private static final String NEXT_PAGE =
      SUMMARY + " where (f.received_at, f.id) < (:receivedAt, :id)" + PAGE;

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
      throw new IllegalStateException("Clearing File " + header.fileId() + " is already imported");
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
    var rows = jdbc.sql(FILE).param("id", fileId).query(ClearingFiles::row).list();
    return withOutcomes(rows).stream().findFirst();
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
    var rows = query.param("limit", limit + 1).query(ClearingFiles::row).list();
    var page = rows.subList(0, Math.min(limit, rows.size()));
    var next = rows.size() > limit ? page.getLast().position() : null;
    return new Page(withOutcomes(page), next);
  }

  /**
   * The Network's sequence numbers between the lowest and the highest of the imported files' that
   * no imported file has, lowest first: the first {@code limit} of them. A file still importing, or
   * refused, doesn't fill its number.
   */
  List<Long> missingSequenceNumbers(int limit) {
    // Each imported number and the next one up mark a gap between them, and only the first
    // `limit` numbers of each gap are listed: the work stays one row per imported number however
    // far apart two numbers are, where a series from the lowest to the highest would not.
    return jdbc.sql(
            """
            with imported as (
              select distinct sequence_number from clearing.clearing_file where status = :imported),
            gaps as (
              select first_missing, last_missing
                from (select sequence_number + 1 as first_missing,
                             lead(sequence_number) over (order by sequence_number) - 1 as last_missing
                        from imported) as runs
               where last_missing >= first_missing)
            select n
              from gaps
             cross join lateral
                   generate_series(first_missing, least(last_missing, first_missing + :limit - 1)) as n
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
  record Position(Instant receivedAt, String fileId) {

    /** The position as a cursor, opaque to callers: its fields, in base64url. */
    String cursor() {
      var fields = receivedAt + " " + fileId;
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(fields.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The position a cursor names.
     *
     * @throws IllegalArgumentException when the cursor isn't one {@link #cursor()} could make
     */
    static Position of(String cursor) {
      try {
        var fields = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        var separator = fields.indexOf(' ');
        return new Position(
            Instant.parse(fields.substring(0, separator)), fields.substring(separator + 1));
      } catch (IndexOutOfBoundsException | DateTimeParseException e) {
        throw new IllegalArgumentException("Not a Clearing File list cursor", e);
      }
    }
  }

  // A file as its row has it, its Clearings not yet counted: they're counted for all the rows at
  // once afterwards, not from inside the row mapper, which would hold a second connection.
  private record Row(ClearingFileSummary file, Instant receivedAt) {

    Position position() {
      return new Position(receivedAt, file.id());
    }
  }

  private static Row row(ResultSet row, int rowNumber) throws SQLException {
    var file =
        new ClearingFileSummary(
            row.getString("id"),
            row.getLong("sequence_number"),
            row.getObject("business_date", LocalDate.class),
            ClearingFileSummary.Status.valueOf(row.getString("status")),
            row.getObject("record_count", Long.class),
            row.getLong("skipped_count"),
            Map.of(),
            settlement(row),
            row.getString("refusal_reason"));
    return new Row(file, row.getObject("received_at", OffsetDateTime.class).toInstant());
  }

  private List<ClearingFileSummary> withOutcomes(List<Row> rows) {
    if (rows.isEmpty()) {
      return List.of();
    }
    var outcomes = outcomes(rows.stream().map(row -> row.file().id()).toList());
    return rows.stream()
        .map(row -> row.file().withOutcomes(outcomes.get(row.file().id())))
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
