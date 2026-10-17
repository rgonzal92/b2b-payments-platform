package dev.rgonz.payments.platform.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import dev.rgonz.payments.platform.TestcontainersConfiguration;
import dev.rgonz.payments.platform.cards.Cards;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * Issuer staff run a Reconciliation for a Business Date over HTTP, on PostgreSQL: every Clearing of
 * that day that didn't match, and every Undercharge, becomes one {@code OPEN} Break, and a re-run
 * finds them instead of adding more. The Clearings are written straight into {@code clearing}'s
 * tables, as an import would have recorded them, so each outcome needs no Network to produce it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ReconciliationRunTests {

  private static final String ISSUER_ADMIN = "ROLE_issuer-admin";
  private static final Currency USD = Currency.getInstance("USD");
  private static final long AUTHORIZED = 1_240_000;
  // Each test reconciles a Business Date of its own, long before any other test's Clearings.
  private static final AtomicInteger DAYS = new AtomicInteger();

  @Autowired MockMvcTester mvc;
  @Autowired JdbcClient jdbc;
  @Autowired Cards cards;
  @Autowired JsonMapper json;

  @Test
  void aRunRaisesOneOpenBreakPerUnmatchedClearingAndPerUndercharge() {
    var date = newBusinessDate();
    var fileId = clearingFile(date);
    var buyerId = new BuyerId(UUID.randomUUID());
    var cardToken = card(buyerId);
    var above =
        clearing(fileId, cardToken, 1_300_000, "USD", AUTHORIZED, "ABOVE_AUTHORIZED_AMOUNT");
    var noAuthorization = clearing(fileId, cardToken, 500_000, "USD", null, "NO_AUTHORIZATION");
    var unknownCard = clearing(fileId, null, 700_000, "USD", null, "UNKNOWN_CARD");
    var otherCurrency = clearing(fileId, cardToken, 800_000, "EUR", null, "OTHER_CURRENCY");
    var reversed =
        clearing(
            fileId, cardToken, AUTHORIZED, "USD", AUTHORIZED, "AGAINST_REVERSED_AUTHORIZATION");
    var expired = clearing(fileId, cardToken, AUTHORIZED, "USD", AUTHORIZED, "AFTER_HOLD_EXPIRED");
    var twice =
        clearing(fileId, cardToken, AUTHORIZED, "USD", AUTHORIZED, "AUTHORIZATION_ALREADY_CLEARED");
    var undercharge = clearing(fileId, cardToken, 1_000_000, "USD", AUTHORIZED, "MATCHED");
    clearing(fileId, cardToken, AUTHORIZED, "USD", AUTHORIZED, "MATCHED");
    // Another day's Clearing is that day's run's to find.
    clearing(clearingFile(date.plusDays(1)), null, 100, "USD", null, "UNKNOWN_CARD");

    var response = run(date, ISSUER_ADMIN);

    assertThat(response).hasStatus(201);
    assertThat(response)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"businessDate": "%s", "breaksFound": 8, "newBreaks": 8, "knownBreaks": 0}
            """
                .formatted(date));
    var buyer = buyerId.value();
    assertThat(breaksOn(date))
        .containsExactlyInAnyOrder(
            tuple("ABOVE_AUTHORIZED_AMOUNT", above, "OPEN", buyer, 60_000L, "USD"),
            tuple("NO_AUTHORIZATION", noAuthorization, "OPEN", buyer, 500_000L, "USD"),
            tuple("UNKNOWN_CARD", unknownCard, "OPEN", null, 700_000L, "USD"),
            tuple("OTHER_CURRENCY", otherCurrency, "OPEN", buyer, 800_000L, "EUR"),
            tuple("AGAINST_REVERSED_AUTHORIZATION", reversed, "OPEN", buyer, AUTHORIZED, "USD"),
            tuple("AFTER_HOLD_EXPIRED", expired, "OPEN", buyer, AUTHORIZED, "USD"),
            tuple("AUTHORIZATION_ALREADY_CLEARED", twice, "OPEN", buyer, AUTHORIZED, "USD"),
            tuple("UNDERCHARGE", undercharge, "OPEN", buyer, 240_000L, "USD"));
    // The Break keeps the Clearing's facts as they were when it was raised.
    String facts =
        jdbc.sql("select facts::text from reconciliation.break where record_id = :id")
            .param("id", undercharge)
            .query(String.class)
            .single();
    assertThat(json.readTree(facts))
        .isEqualTo(
            json.readTree(
                """
            {"clearing": {"networkRecordId": "%s", "fileId": "%s", "businessDate": "%s",
                          "cardToken": "%s", "authorizationRequestId": "A%s",
                          "cleared": {"minorUnits": 1000000, "currency": "USD"},
                          "authorized": {"minorUnits": 1240000, "currency": "USD"},
                          "outcome": "MATCHED"}}
            """
                    .formatted(undercharge, fileId, date, cardToken, undercharge)));
  }

  @Test
  void aRunAgainRaisesNoNewBreakAndCountsTheEarlierOnesAsKnown() {
    var date = newBusinessDate();
    var fileId = clearingFile(date);
    clearing(fileId, null, 700_000, "USD", null, "UNKNOWN_CARD");
    clearing(
        fileId, card(new BuyerId(UUID.randomUUID())), 500_000, "USD", null, "NO_AUTHORIZATION");
    assertThat(run(date, ISSUER_ADMIN)).hasStatus(201);

    var again = run(date, ISSUER_ADMIN);

    assertThat(again).hasStatus(201);
    assertThat(again)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"businessDate": "%s", "breaksFound": 2, "newBreaks": 0, "knownBreaks": 2}
            """
                .formatted(date));
    assertThat(breaksOn(date)).hasSize(2);
  }

  @Test
  void onlyIssuerStaffMayRunAReconciliation() {
    assertProblem(run(newBusinessDate(), "ROLE_approver"), 403);
  }

  @Test
  void aRunWithoutABusinessDateIsABadRequest() {
    var response =
        mvc.post()
            .uri("/v1/reconciliation-runs")
            .with(jwt().authorities(new SimpleGrantedAuthority(ISSUER_ADMIN)))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}")
            .exchange();

    assertProblem(response, 400);
  }

  private MvcTestResult run(LocalDate businessDate, String authority) {
    return mvc.post()
        .uri("/v1/reconciliation-runs")
        .with(jwt().authorities(new SimpleGrantedAuthority(authority)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"businessDate\": \"%s\"}".formatted(businessDate))
        .exchange();
  }

  private static void assertProblem(MvcTestResult response, int status) {
    assertThat(response).hasStatus(status);
    assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(response).bodyJson().extractingPath("$.status").isEqualTo(status);
  }

  private static LocalDate newBusinessDate() {
    return LocalDate.of(2001, 1, 1).plusDays(2L * DAYS.getAndIncrement());
  }

  private List<Tuple> breaksOn(LocalDate date) {
    return jdbc.sql(
            """
            select kind, record_id, status, buyer_id, gap_minor, gap_currency
              from reconciliation.break where business_date = :date
            """)
        .param("date", Date.valueOf(date))
        .query(
            (row, n) ->
                tuple(
                    row.getString("kind"),
                    row.getString("record_id"),
                    row.getString("status"),
                    row.getObject("buyer_id", UUID.class),
                    row.getLong("gap_minor"),
                    row.getString("gap_currency")))
        .list();
  }

  // An imported Clearing File covering the date; returns its ID.
  private String clearingFile(LocalDate businessDate) {
    var fileId = "F" + UUID.randomUUID().toString().substring(0, 15);
    jdbc.sql(
            """
            insert into clearing.clearing_file
                   (id, sequence_number, business_date, status, record_count, imported_at)
            values (:id, 1, :date, 'IMPORTED', 0, now())
            """)
        .param("id", fileId)
        .param("date", Date.valueOf(businessDate))
        .update();
    return fileId;
  }

  private String card(BuyerId buyerId) {
    return cards
        .issueSingleUse(
            UUID.randomUUID(),
            buyerId,
            UUID.randomUUID(),
            new Money(AUTHORIZED, USD),
            Instant.now())
        .cardToken();
  }

  // A Clearing of the file, recorded with this outcome; its Authorization's request ID is "A" and
  // its Network record ID when it has one. Returns its Network record ID.
  private String clearing(
      String fileId,
      String cardToken,
      long clearedMinor,
      String clearedCurrency,
      Long authorizedMinor,
      String outcome) {
    var networkRecordId = "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 19);
    jdbc.sql(
            """
            insert into clearing.clearing
                   (network_record_id, file_id, card_token, authorization_request_id,
                    cleared_amount_minor, cleared_currency, authorized_amount_minor,
                    authorized_currency, outcome, journal_entry_id)
            values (:id, :fileId, :cardToken, :authorizationRequestId, :cleared, :clearedCurrency,
                    :authorized, :authorizedCurrency, :outcome, :journalEntryId)
            """)
        .param("id", networkRecordId)
        .param("fileId", fileId)
        .param("cardToken", cardToken)
        .param("authorizationRequestId", authorizedMinor == null ? null : "A" + networkRecordId)
        .param("cleared", clearedMinor)
        .param("clearedCurrency", clearedCurrency)
        .param("authorized", authorizedMinor)
        .param("authorizedCurrency", authorizedMinor == null ? null : "USD")
        .param("outcome", outcome)
        .param("journalEntryId", UUID.randomUUID())
        .update();
    return networkRecordId;
  }
}
