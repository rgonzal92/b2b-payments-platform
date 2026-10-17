package dev.rgonz.payments.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The demo's Reconciliation step, after the Clearing File's: Issuer staff run Reconciliation for
 * today, then list today's open Breaks, against a stub Keycloak and platform. The answer shows the
 * run's summary and each Break's ID, kind, gap and Buyer, never the facts of the records it names.
 */
@SpringBootTest(properties = "simulator.seed.enabled=false")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ReconciliationDemoTests {

  // In the stub's facts only: the answer and the log never show the records' facts.
  private static final String CARD_TOKEN = "test-only-card-token";

  record Recorded(String method, String uri, String authorization, String body) {}

  static final List<Recorded> platformRequests = new CopyOnWriteArrayList<>();
  static final HttpServer platform = startStubPlatform();

  @DynamicPropertySource
  static void pointAtStub(DynamicPropertyRegistry registry) {
    var base = "http://localhost:" + platform.getAddress().getPort();
    registry.add(
        "spring.security.oauth2.client.provider.keycloak.token-uri", () -> base + "/token");
    registry.add(
        "spring.security.oauth2.client.registration.issuer-admin.client-secret", () -> "test");
    registry.add("simulator.platform-url", () -> base);
  }

  @AfterAll
  static void stopStub() {
    platform.stop(0);
  }

  @Autowired MockMvcTester mvc;

  @BeforeEach
  void forgetRequests() {
    platformRequests.clear();
  }

  @Test
  void issuerStaffRunTodaysReconciliationAndTheAnswerShowsTheRunAndTodaysOpenBreaks(
      CapturedOutput output) {
    var today = LocalDate.now(ZoneOffset.UTC);

    var demo = mvc.post().uri("/demo/reconciliation").exchange();

    assertThat(demo).hasStatusOk();
    // Strictly: the run's summary as the platform answered it, and only today's Breaks, from both
    // pages, without yesterday's on the second.
    assertThat(demo)
        .bodyJson()
        .isStrictlyEqualTo(
            """
            {
              "run": {
                "runId": "7d5b3c2a-1f0e-4d9c-8b7a-6e5f4d3c2b1a",
                "businessDate": "%1$s",
                "breaksFound": 2,
                "newBreaks": 2,
                "knownBreaks": 0
              },
              "openBreaks": [
                {
                  "id": "11111111-1111-4111-8111-111111111111",
                  "kind": "UNKNOWN_CARD",
                  "gap": {"minorUnits": 1240000, "currency": "USD"},
                  "buyerId": null
                },
                {
                  "id": "22222222-2222-4222-8222-222222222222",
                  "kind": "UNDERCHARGE",
                  "gap": {"minorUnits": 500, "currency": "USD"},
                  "buyerId": "dc9a9857-e40f-4d50-9ee3-879f0c7472c5"
                }
              ]
            }
            """
                .formatted(today));
    assertThat(platformRequests)
        .extracting(Recorded::method, Recorded::authorization)
        .containsExactly(
            tuple("POST", "Bearer token-for-simulator-issuer-admin"),
            tuple("GET", "Bearer token-for-simulator-issuer-admin"),
            tuple("GET", "Bearer token-for-simulator-issuer-admin"));
    assertThat(platformRequests.getFirst().uri()).isEqualTo("/v1/reconciliation-runs");
    assertThat(platformRequests.getFirst().body())
        .isEqualToIgnoringWhitespace("{\"businessDate\":\"%s\"}".formatted(today));
    assertThat(platformRequests.get(1).uri()).startsWith("/v1/breaks?").contains("status=OPEN");
    assertThat(platformRequests.get(2).uri()).contains("status=OPEN", "cursor=page-2");
    assertThat(output).doesNotContain(CARD_TOKEN);
  }

  /**
   * Plays Keycloak and the platform: answers the run with its summary, and the list of open Breaks
   * in two pages, newest first: two Breaks raised today, then one a run raised yesterday.
   */
  private static HttpServer startStubPlatform() {
    HttpServer server;
    try {
      server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    server.createContext(
        "/token",
        exchange ->
            respond(
                exchange,
                """
                {"access_token": "token-for-simulator-issuer-admin", "token_type": "Bearer",
                 "expires_in": 300}
                """));
    server.createContext(
        "/v1/reconciliation-runs",
        exchange -> {
          record(exchange);
          var today = LocalDate.now(ZoneOffset.UTC);
          respond(
              exchange,
              201,
              """
              {"runId": "7d5b3c2a-1f0e-4d9c-8b7a-6e5f4d3c2b1a", "businessDate": "%s",
               "breaksFound": 2, "newBreaks": 2, "knownBreaks": 0}
              """
                  .formatted(today));
        });
    server.createContext(
        "/v1/breaks",
        exchange -> {
          record(exchange);
          var today = LocalDate.now(ZoneOffset.UTC);
          var secondPage = exchange.getRequestURI().getQuery().contains("cursor=page-2");
          respond(
              exchange,
              secondPage
                  ? """
                    {"items": [%s]}
                    """
                      .formatted(
                          aBreak(
                              "33333333-3333-4333-8333-333333333333",
                              "NEVER_CLEARED",
                              today.minusDays(1),
                              "\"dc9a9857-e40f-4d50-9ee3-879f0c7472c5\"",
                              1000))
                  : """
                    {"items": [%s, %s], "nextCursor": "page-2"}
                    """
                      .formatted(
                          aBreak(
                              "11111111-1111-4111-8111-111111111111",
                              "UNKNOWN_CARD",
                              today,
                              null,
                              1240000),
                          aBreak(
                              "22222222-2222-4222-8222-222222222222",
                              "UNDERCHARGE",
                              today,
                              "\"dc9a9857-e40f-4d50-9ee3-879f0c7472c5\"",
                              500)));
        });
    server.start();
    return server;
  }

  // An open Break as the platform shows it, raised on its Business Date's day, with facts.
  private static String aBreak(
      String id, String kind, LocalDate businessDate, String buyerId, long gap) {
    return """
        {"id": "%s", "kind": "%s", "status": "OPEN", "recordId": "NET-STUB-0001-000001",
         "businessDate": "%s", %s"gap": {"minorUnits": %d, "currency": "USD"},
         "facts": {"clearing": {"cardToken": "%s", "networkRecordId": "NET-STUB-0001-000001"}},
         "raisedAt": "%sT00:10:00Z"}
        """
        .formatted(
            id,
            kind,
            businessDate,
            buyerId == null ? "" : "\"buyerId\": " + buyerId + ", ",
            gap,
            CARD_TOKEN,
            businessDate);
  }

  private static void record(HttpExchange exchange) throws IOException {
    platformRequests.add(
        new Recorded(
            exchange.getRequestMethod(),
            exchange.getRequestURI().toString(),
            exchange.getRequestHeaders().getFirst("Authorization"),
            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
  }

  private static void respond(HttpExchange exchange, String json) throws IOException {
    respond(exchange, 200, json);
  }

  private static void respond(HttpExchange exchange, int status, String json) throws IOException {
    var bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
