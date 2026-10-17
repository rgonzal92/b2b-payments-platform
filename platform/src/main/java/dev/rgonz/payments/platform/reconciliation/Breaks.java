package dev.rgonz.payments.platform.reconciliation;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The Breaks Reconciliation raised, each {@code OPEN} until a Reconciler resolves it. */
@Repository
class Breaks {

  private static final String SELECT =
      """
      select id, kind, record_id, status, business_date, buyer_id, gap_minor, gap_currency,
             facts::text as facts, created_at, resolution_kind, resolution_note, resolved_by,
             resolved_by_name, resolved_at, correcting_entry_id
        from reconciliation.break
      """;
  // A filter left out, or the first page's missing position, is a null parameter, which matches
  // every row: one fixed statement for every combination. The casts give PostgreSQL the type a
  // null alone doesn't. Keyset paging: a page starts after the earlier page's last Break, compared
  // on the ordering columns, which break_created_at_id_idx keeps in order, so it never reads and
  // skips the Breaks before it, as OFFSET would.
  private static final String PAGE =
      SELECT
          + """
           where (cast(:status as text) is null or status = :status)
             and (cast(:kind as text) is null or kind = :kind)
             and (cast(:buyerId as uuid) is null or buyer_id = :buyerId)
             and (cast(:raisedAt as timestamptz) is null or (created_at, id) < (:raisedAt, :id))
           order by created_at desc, id desc
           limit :limit
          """;
  private static final TypeReference<Map<String, Object>> FACTS = new TypeReference<>() {};

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

  /** The raised Break with this ID, if any. */
  Optional<RaisedBreak> find(UUID id) {
    return jdbc.sql(SELECT + " where id = :id").param("id", id).query(this::raised).optional();
  }

  /**
   * A page of the Breaks that match the filter, newest first by when they were raised, ties broken
   * by ID: the first {@code limit}, or the {@code limit} after an earlier page's last Break.
   *
   * @param after the position of the earlier page's last Break; null for the first page
   */
  Page list(Filter filter, Position after, int limit) {
    var rows =
        jdbc.sql(PAGE)
            .param("status", filter.status() == null ? null : filter.status().name())
            .param("kind", filter.kind() == null ? null : filter.kind().name())
            .param("buyerId", filter.buyerId() == null ? null : filter.buyerId().value())
            .param("raisedAt", after == null ? null : after.raisedAt().atOffset(ZoneOffset.UTC))
            .param("id", after == null ? null : after.id())
            // One Break more than the page holds tells whether another page follows.
            .param("limit", limit + 1)
            .query(this::raised)
            .list();
    var page = rows.subList(0, Math.min(limit, rows.size()));
    var next = rows.size() > limit ? Position.of(page.getLast()) : null;
    return new Page(page, next);
  }

  /**
   * How many Breaks are open, by kind and by how long ago they were raised, as the database's clock
   * tells; a kind and age no open Break has is left out.
   */
  Map<BreakKind, Map<OpenAge, Long>> countOpen() {
    var counts = new EnumMap<BreakKind, Map<OpenAge, Long>>(BreakKind.class);
    jdbc.sql(
            """
            select kind,
                   case when created_at > now() - interval '1 day' then 'UNDER_1_DAY'
                        when created_at >= now() - interval '7 days' then 'ONE_TO_7_DAYS'
                        else 'OVER_7_DAYS' end as age,
                   count(*) as open
              from reconciliation.break
             where status = 'OPEN'
             group by 1, 2
            """)
        .query(
            row -> {
              counts
                  .computeIfAbsent(
                      BreakKind.valueOf(row.getString("kind")),
                      kind -> new EnumMap<>(OpenAge.class))
                  .put(OpenAge.valueOf(row.getString("age")), row.getLong("open"));
            });
    return counts;
  }

  /** How long ago an open Break was raised, and its metric tag, as the dashboard shows it. */
  enum OpenAge {
    /** Raised in the last 24 hours. */
    UNDER_1_DAY("under_1_day"),
    /** Raised 1 to 7 days ago. */
    ONE_TO_7_DAYS("1_to_7_days"),
    /** Raised more than 7 days ago. */
    OVER_7_DAYS("over_7_days");

    final String tag;

    OpenAge(String tag) {
      this.tag = tag;
    }
  }

  private RaisedBreak raised(ResultSet row, int rowNumber) throws SQLException {
    var buyerId = row.getObject("buyer_id", UUID.class);
    var resolutionKind = row.getString("resolution_kind");
    return new RaisedBreak(
        row.getObject("id", UUID.class),
        BreakKind.valueOf(row.getString("kind")),
        row.getString("record_id"),
        RaisedBreak.Status.valueOf(row.getString("status")),
        row.getObject("business_date", LocalDate.class),
        buyerId == null ? null : new BuyerId(buyerId),
        new Money(row.getLong("gap_minor"), Currency.getInstance(row.getString("gap_currency"))),
        json.readValue(row.getString("facts"), FACTS),
        row.getObject("created_at", OffsetDateTime.class).toInstant(),
        resolutionKind == null
            ? null
            : new RaisedBreak.Resolved(
                Resolution.Kind.valueOf(resolutionKind),
                row.getString("resolution_note"),
                row.getString("resolved_by"),
                row.getString("resolved_by_name"),
                row.getObject("resolved_at", OffsetDateTime.class).toInstant(),
                row.getObject("correcting_entry_id", UUID.class)));
  }

  /** Which Breaks a list shows: those that match every field given; a null field matches any. */
  record Filter(RaisedBreak.Status status, BreakKind kind, BuyerId buyerId) {}

  /**
   * A page of Breaks.
   *
   * @param next the position of the page's last Break, to ask for the page after it; null on the
   *     last page
   */
  record Page(List<RaisedBreak> breaks, Position next) {

    Page {
      breaks = List.copyOf(breaks);
    }
  }

  /** Where a Break stands in the list: when it was raised, and its ID. */
  record Position(Instant raisedAt, UUID id) {

    static Position of(RaisedBreak raised) {
      return new Position(raised.raisedAt(), raised.id());
    }

    /** The position as a cursor, opaque to callers: its fields, in base64url. */
    String cursor() {
      var fields = raisedAt + " " + id;
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
            Instant.parse(fields.substring(0, separator)),
            UUID.fromString(fields.substring(separator + 1)));
      } catch (IndexOutOfBoundsException | DateTimeParseException e) {
        throw new IllegalArgumentException("Not a Break list cursor", e);
      }
    }
  }
}
