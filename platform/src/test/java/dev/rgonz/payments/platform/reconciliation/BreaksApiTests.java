package dev.rgonz.payments.platform.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.jayway.jsonpath.JsonPath;
import dev.rgonz.payments.platform.TestcontainersConfiguration;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/**
 * Reconcilers and Issuer staff read the Breaks over HTTP, on PostgreSQL. Each test raises its
 * Breaks for a Buyer of its own and lists them by that Buyer, so other tests' Breaks stay out of
 * its pages.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BreaksApiTests {

  private static final String RECONCILER = "ROLE_reconciler";
  private static final String ISSUER_ADMIN = "ROLE_issuer-admin";
  private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 14);

  @Autowired MockMvcTester mvc;
  @Autowired Breaks breaks;
  @Autowired JdbcClient jdbc;

  @Test
  void breaksAreListedNewestFirstAPageAtATime() throws Exception {
    var buyerId = new BuyerId(UUID.randomUUID());
    var oldest = raise(BreakKind.UNDERCHARGE, buyerId, Duration.ofHours(3));
    var middle = raise(BreakKind.NO_AUTHORIZATION, buyerId, Duration.ofHours(2));
    var newest = raise(BreakKind.UNKNOWN_CARD, buyerId, Duration.ofHours(1));

    var firstPage = list("buyerId=%s&limit=2".formatted(buyerId.value()), RECONCILER);

    assertThat(firstPage).hasStatus(200);
    assertThat(firstPage)
        .bodyJson()
        .extractingPath("$.items[*].recordId")
        .asArray()
        .containsExactly(newest, middle);
    String cursor = JsonPath.read(firstPage.getResponse().getContentAsString(), "$.nextCursor");
    assertThat(cursor).isNotBlank();

    var lastPage =
        list("buyerId=%s&limit=2&cursor=%s".formatted(buyerId.value(), cursor), ISSUER_ADMIN);

    assertThat(lastPage).hasStatus(200);
    assertThat(lastPage)
        .bodyJson()
        .extractingPath("$.items[*].recordId")
        .asArray()
        .containsExactly(oldest);
    assertThat(lastPage).bodyJson().doesNotHavePath("$.nextCursor");
  }

  @Test
  void eachFilterNarrowsTheList() {
    var buyerId = new BuyerId(UUID.randomUUID());
    var undercharge = raise(BreakKind.UNDERCHARGE, buyerId, Duration.ofHours(2));
    var noAuthorization = raise(BreakKind.NO_AUTHORIZATION, buyerId, Duration.ofHours(1));
    var otherBuyers = raise(BreakKind.UNDERCHARGE, new BuyerId(UUID.randomUUID()), Duration.ZERO);
    var buyer = "buyerId=" + buyerId.value();

    assertThat(recordIds(list(buyer, RECONCILER))).containsExactly(noAuthorization, undercharge);
    assertThat(recordIds(list(buyer + "&kind=UNDERCHARGE", RECONCILER)))
        .containsExactly(undercharge);
    assertThat(recordIds(list(buyer + "&status=OPEN", RECONCILER)))
        .containsExactly(noAuthorization, undercharge);
    assertThat(recordIds(list(buyer + "&status=RESOLVED", RECONCILER))).isEmpty();
    // Without a Buyer, the kind still narrows the list: the other Buyer's Undercharge is in it,
    // and no other kind is.
    var undercharges = list("kind=UNDERCHARGE&limit=100", RECONCILER);
    assertThat(recordIds(undercharges)).contains(otherBuyers, undercharge);
    assertThat(undercharges)
        .bodyJson()
        .extractingPath("$.items[*].kind")
        .asArray()
        .containsOnly("UNDERCHARGE");
  }

  @Test
  void aLimitOutsideOneToAHundredAFilterTheListDoesntKnowOrACursorItDidntGiveIsABadRequest() {
    for (var query :
        List.of(
            "limit=0", "limit=101", "kind=NOT_A_KIND", "status=CLOSED", "cursor=not-a-cursor")) {
      assertProblem(list(query, RECONCILER), 400);
    }
  }

  @Test
  void aBreakShowsItsGapAndTheFactsOfTheRecordsItNames() {
    // An other-currency Clearing of a known card: its gap is in euros, its Authorization unknown.
    var recordId = "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 19);
    var buyerId = UUID.randomUUID();
    var clearing = new LinkedHashMap<String, Object>();
    clearing.put("networkRecordId", recordId);
    clearing.put("fileId", "F20261014");
    clearing.put("businessDate", "2026-10-14");
    clearing.put("cardToken", "ct_123");
    clearing.put("buyerId", buyerId.toString());
    clearing.put("authorizationRequestId", null);
    clearing.put("cleared", Map.of("minorUnits", 800_000, "currency", "EUR"));
    clearing.put("authorized", null);
    clearing.put("outcome", "OTHER_CURRENCY");
    breaks.raise(
        new Break(
            BreakKind.OTHER_CURRENCY,
            recordId,
            BUSINESS_DATE,
            new BuyerId(buyerId),
            new Money(800_000, Currency.getInstance("EUR")),
            Map.of("clearing", clearing)));
    String id =
        JsonPath.read(body(list("buyerId=%s".formatted(buyerId), ISSUER_ADMIN)), "$.items[0].id");

    var response = get(id, RECONCILER);

    assertThat(response).hasStatus(200);
    assertThat(response)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"id": "%s", "kind": "OTHER_CURRENCY", "status": "OPEN", "recordId": "%s",
             "businessDate": "2026-10-14", "buyerId": "%s",
             "gap": {"minorUnits": 800000, "currency": "EUR"},
             "facts": {"clearing": {"networkRecordId": "%s", "fileId": "F20261014",
                                    "businessDate": "2026-10-14", "cardToken": "ct_123",
                                    "buyerId": "%s", "authorizationRequestId": null,
                                    "cleared": {"minorUnits": 800000, "currency": "EUR"},
                                    "authorized": null, "outcome": "OTHER_CURRENCY"}}}
            """
                .formatted(id, recordId, buyerId, recordId, buyerId));
    assertThat(response).bodyJson().extractingPath("$.raisedAt").isNotNull();
  }

  @Test
  void aBreakIdNoBreakHasIsNotFound() {
    assertProblem(get(UUID.randomUUID().toString(), ISSUER_ADMIN), 404);
  }

  @Test
  void onlyReconcilersAndIssuerStaffMayReadTheBreaks() {
    var recordId = raise(BreakKind.UNKNOWN_CARD, new BuyerId(UUID.randomUUID()), Duration.ZERO);
    String id =
        jdbc.sql("select id::text from reconciliation.break where record_id = :recordId")
            .param("recordId", recordId)
            .query(String.class)
            .single();

    for (var role : List.of("ROLE_approver", "ROLE_erp", "ROLE_network")) {
      assertProblem(list("limit=20", role), 403);
      assertProblem(get(id, role), 403);
    }
  }

  private MvcTestResult get(String id, String authority) {
    return mvc.get()
        .uri("/v1/breaks/" + id)
        .with(jwt().authorities(new SimpleGrantedAuthority(authority)))
        .exchange();
  }

  private static String body(MvcTestResult response) {
    assertThat(response).hasStatus(200);
    try {
      return response.getResponse().getContentAsString();
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<Object> recordIds(MvcTestResult response) {
    return JsonPath.read(body(response), "$.items[*].recordId");
  }

  private MvcTestResult list(String query, String authority) {
    return mvc.get()
        .uri("/v1/breaks?" + query)
        .with(jwt().authorities(new SimpleGrantedAuthority(authority)))
        .exchange();
  }

  private static void assertProblem(MvcTestResult response, int status) {
    assertThat(response).hasStatus(status);
    assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(response).bodyJson().extractingPath("$.status").isEqualTo(status);
  }

  // Raises a Break naming a new Clearing, as raised that long ago; returns the record it names.
  private String raise(BreakKind kind, BuyerId buyerId, Duration ago) {
    var recordId = "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 19);
    var clearing = new LinkedHashMap<String, Object>();
    clearing.put("networkRecordId", recordId);
    clearing.put("cleared", Map.of("minorUnits", 1_000_000, "currency", "USD"));
    breaks.raise(
        new Break(
            kind,
            recordId,
            BUSINESS_DATE,
            buyerId,
            new Money(240_000, Currency.getInstance("USD")),
            Map.of("clearing", clearing)));
    jdbc.sql(
            """
            update reconciliation.break set created_at = now() - :seconds * interval '1 second'
             where record_id = :recordId
            """)
        .param("seconds", ago.toSeconds())
        .param("recordId", recordId)
        .update();
    return recordId;
  }
}
