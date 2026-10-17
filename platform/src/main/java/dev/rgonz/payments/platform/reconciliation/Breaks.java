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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Currency;
import java.util.EnumMap;
import java.util.HashMap;
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
             facts::text as facts, created_at
        from reconciliation.break
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

  /** The raised Break with this ID, if any. */
  Optional<Raised> find(UUID id) {
    return jdbc.sql(SELECT + " where id = :id").param("id", id).query(this::raised).optional();
  }

  /**
   * A page of the Breaks that match the filter, newest first by when they were raised, ties broken
   * by ID: the first {@code limit}, or the {@code limit} after an earlier page's last Break.
   *
   * @param after the position of the earlier page's last Break; null for the first page
   */
  Page list(Filter filter, Position after, int limit) {
    var conditions = new ArrayList<String>();
    var params = new HashMap<String, Object>();
    if (filter.status() != null) {
      conditions.add("status = :status");
      params.put("status", filter.status());
    }
    if (filter.kind() != null) {
      conditions.add("kind = :kind");
      params.put("kind", filter.kind().name());
    }
    if (filter.buyerId() != null) {
      conditions.add("buyer_id = :buyerId");
      params.put("buyerId", filter.buyerId().value());
    }
    // Keyset paging: a page starts after the earlier page's last Break, compared on the ordering
    // columns, which break_created_at_id_idx keeps in order, so it never reads and skips the
    // Breaks before it, as OFFSET would.
    if (after != null) {
      conditions.add("(created_at, id) < (:raisedAt, :id)");
      params.put("raisedAt", after.raisedAt().atOffset(ZoneOffset.UTC));
      params.put("id", after.id());
    }
    var where = conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
    // One Break more than the page holds tells whether another page follows.
    params.put("limit", limit + 1);
    var rows =
        jdbc.sql(SELECT + where + " order by created_at desc, id desc limit :limit")
            .params(params)
            .query(this::raised)
            .list();
    var page = rows.subList(0, Math.min(limit, rows.size()));
    var next = rows.size() > limit ? page.getLast().position() : null;
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

  /** How long ago an open Break was raised. */
  enum OpenAge {
    /** Raised in the last 24 hours. */
    UNDER_1_DAY,
    /** Raised 1 to 7 days ago. */
    ONE_TO_7_DAYS,
    /** Raised more than 7 days ago. */
    OVER_7_DAYS
  }

  private Raised raised(ResultSet row, int rowNumber) throws SQLException {
    var buyerId = row.getObject("buyer_id", UUID.class);
    var found =
        new Break(
            BreakKind.valueOf(row.getString("kind")),
            row.getString("record_id"),
            row.getObject("business_date", LocalDate.class),
            buyerId == null ? null : new BuyerId(buyerId),
            new Money(
                row.getLong("gap_minor"), Currency.getInstance(row.getString("gap_currency"))),
            json.readValue(row.getString("facts"), FACTS));
    return new Raised(
        row.getObject("id", UUID.class),
        row.getString("status"),
        row.getObject("created_at", OffsetDateTime.class).toInstant(),
        found);
  }

  /**
   * A Break as raised.
   *
   * @param status {@code OPEN} until a Reconciler resolves it
   * @param raisedAt when a run raised it
   */
  record Raised(UUID id, String status, Instant raisedAt, Break found) {

    Position position() {
      return new Position(raisedAt, id);
    }
  }

  /** Which Breaks a list shows: those that match every field given; a null field matches any. */
  record Filter(String status, BreakKind kind, BuyerId buyerId) {}

  /**
   * A page of Breaks.
   *
   * @param next the position of the page's last Break, to ask for the page after it; null on the
   *     last page
   */
  record Page(List<Raised> breaks, Position next) {

    Page {
      breaks = List.copyOf(breaks);
    }
  }

  /** Where a Break stands in the list: when it was raised, and its ID. */
  record Position(Instant raisedAt, UUID id) {

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
