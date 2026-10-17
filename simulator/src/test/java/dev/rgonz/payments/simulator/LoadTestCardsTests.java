package dev.rgonz.payments.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.client.RestClient;

/**
 * The load test's cards: Initech's ERP sends N $10.00 Invoices to Globex, Initech's Approver
 * approves each, the simulator waits for each Payment, and writes the card Globex received through
 * each link to the cards file, against a stub Keycloak and platform. Like the platform, the stub
 * delivers each Invoice's Remittance Advice to Globex before its Payment shows {@code COMPLETED},
 * and serves a different card behind each link.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "simulator.seed.enabled=false")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class LoadTestCardsTests {

  private static final String HEADER =
      "pan,securityCode,expiry,amountMinorUnits,currency,acceptorId";

  record Recorded(String method, String path, String authorization, String body) {}

  static final List<Recorded> platformRequests = new CopyOnWriteArrayList<>();
  static final StubPlatform platform = new StubPlatform();
  @TempDir static Path varFolder;

  @DynamicPropertySource
  static void pointAtStub(DynamicPropertyRegistry registry) {
    var base = "http://localhost:" + platform.server.getAddress().getPort();
    registry.add(
        "spring.security.oauth2.client.provider.keycloak.token-uri", () -> base + "/token");
    for (var client : List.of("initech-erp", "initech-approver")) {
      registry.add(
          "spring.security.oauth2.client.registration." + client + ".client-secret", () -> "test");
    }
    registry.add("simulator.platform-url", () -> base);
    // A folder that doesn't exist yet: the simulator makes it.
    registry.add(
        "simulator.load-test.cards-file",
        () -> varFolder.resolve("load-test/cards.csv").toString());
  }

  @AfterAll
  static void stopStub() {
    platform.server.stop(0);
  }

  @LocalServerPort int port;
  @Autowired MockMvcTester mvc;

  @BeforeEach
  void resetStub() {
    platformRequests.clear();
    platform.reset(port);
  }

  @Test
  void sendsNInvoicesFromInitechToGlobexApprovesEachAndWritesEveryCardGlobexReceived(
      CapturedOutput output) throws IOException {
    var cardsFile = varFolder.resolve("load-test/cards.csv");

    var run = mvc.post().uri("/demo/load-test-cards?count=3").exchange();

    assertThat(run).hasStatusOk();
    assertThat(run)
        .bodyJson()
        .isStrictlyEqualTo(
            """
            {"requested": 3, "written": 3, "file": "%s"}
            """
                .formatted(cardsFile.toAbsolutePath()));
    var submitted =
        platformRequests.stream().filter(request -> request.path().equals("/v1/invoices")).toList();
    assertThat(submitted)
        .hasSize(3)
        .allSatisfy(
            request -> {
              assertThat(request.method()).isEqualTo("POST");
              assertThat(request.authorization()).isEqualTo("Bearer token-for-initech-erp");
              assertThat(request.body())
                  .contains("\"amount\":{\"minorUnits\":1000,\"currency\":\"USD\"}")
                  .contains("\"name\":\"Globex\"")
                  .contains("\"acceptorId\":\"GLOBEX000000001\"")
                  .contains("/globex/remittance-advice");
            });
    assertThat(submitted)
        .extracting(request -> find(INVOICE_NUMBER, request.body()))
        .doesNotHaveDuplicates();
    assertThat(platformRequests)
        .filteredOn(request -> request.path().endsWith("/approval"))
        .hasSize(3)
        .extracting(Recorded::authorization)
        .containsOnly("Bearer token-for-initech-approver");
    // One line per card, in the format the load test reads.
    assertThat(Files.readAllLines(cardsFile)).first().isEqualTo(HEADER);
    assertThat(Files.readAllLines(cardsFile).subList(1, 4))
        .containsExactlyInAnyOrderElementsOf(platform.cardLines());
    for (var pan : platform.pans()) {
      assertThat(output).doesNotContain(pan);
      assertThat(run).bodyText().doesNotContain(pan);
    }
  }

  @Test
  void aSecondRunOverwritesTheFirstRunsFile() throws IOException {
    assertThat(mvc.post().uri("/demo/load-test-cards?count=2").exchange()).hasStatusOk();
    platform.reset(port);

    assertThat(mvc.post().uri("/demo/load-test-cards?count=1").exchange()).hasStatusOk();

    assertThat(Files.readAllLines(varFolder.resolve("load-test/cards.csv")))
        .containsExactly(HEADER, platform.cardLines().getFirst());
  }

  @Test
  void aCardWhosePaymentFailedIsLeftOutOfTheFile() throws IOException {
    platform.failPaymentOfInvoice(2);

    var run = mvc.post().uri("/demo/load-test-cards?count=3").exchange();

    assertThat(run).hasStatusOk();
    assertThat(run).bodyJson().isLenientlyEqualTo("{\"requested\": 3, \"written\": 2}");
    assertThat(Files.readAllLines(varFolder.resolve("load-test/cards.csv"))).hasSize(3);
  }

  @Test
  void aCountOutsideOneTo10000IsRefusedAndSendsNothing() {
    assertThat(mvc.post().uri("/demo/load-test-cards?count=0").exchange())
        .hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(mvc.post().uri("/demo/load-test-cards?count=10001").exchange())
        .hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(platformRequests).isEmpty();
  }

  private static final Pattern INVOICE_NUMBER = Pattern.compile("\"invoiceNumber\":\"([^\"]+)\"");

  private static String find(Pattern pattern, String body) {
    var matcher = pattern.matcher(body);
    if (!matcher.find()) {
      throw new IllegalStateException("No " + pattern + " in the request");
    }
    return matcher.group(1);
  }

  /**
   * Plays the platform for Initech's Invoices: takes each, shows it {@code APPROVED} once approved,
   * and its Payment {@code STARTED} on the first read and {@code COMPLETED} on the next, after
   * delivering its Remittance Advice to the simulator's Globex; or {@code FAILED}, delivering
   * nothing, for the Invoice it was told to fail. Behind each Invoice's link is its own card, a PAN
   * from the test BIN 990000 numbered in the order the Invoices arrived.
   */
  static final class StubPlatform {

    private static final Pattern MINOR_UNITS = Pattern.compile("\"minorUnits\":(\\d+)");

    record StubInvoice(int number, String invoiceNumber, long minorUnits) {

      String pan() {
        return "99000000%08d".formatted(number);
      }

      // The line the cards file should hold for this Invoice's card: expiry 2026-11-15 is 2611.
      String cardLine() {
        return "%s,321,2611,%d,USD,GLOBEX000000001".formatted(pan(), minorUnits);
      }
    }

    final HttpServer server;
    final Map<UUID, StubInvoice> invoices = new ConcurrentHashMap<>();
    final Set<UUID> approved = ConcurrentHashMap.newKeySet();
    final Map<UUID, AtomicInteger> reads = new ConcurrentHashMap<>();
    final AtomicInteger arrived = new AtomicInteger();
    volatile int simulatorPort;
    volatile int failing;

    StubPlatform() {
      try {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      // Answering a read waits on Globex, which opens the card link here meanwhile.
      server.setExecutor(Executors.newCachedThreadPool());
      server.createContext("/token", this::token);
      server.createContext("/v1/invoices", this::invoices);
      server.createContext("/v1/remittance-links/", this::cardLink);
      server.start();
    }

    void reset(int simulatorPort) {
      this.simulatorPort = simulatorPort;
      invoices.clear();
      approved.clear();
      reads.clear();
      arrived.set(0);
      failing = 0;
    }

    // The Payment of the nth Invoice to arrive (from 1) fails instead of completing.
    void failPaymentOfInvoice(int number) {
      failing = number;
    }

    List<StubInvoice> sorted() {
      return invoices.values().stream()
          .sorted(java.util.Comparator.comparingInt(StubInvoice::number))
          .toList();
    }

    List<String> cardLines() {
      return sorted().stream().map(StubInvoice::cardLine).toList();
    }

    List<String> pans() {
      return sorted().stream().map(StubInvoice::pan).toList();
    }

    // Answers with a token naming the client that asked, so the test sees which one signed in.
    private void token(HttpExchange exchange) throws IOException {
      var basic = exchange.getRequestHeaders().getFirst("Authorization").substring(6);
      var clientId =
          new String(Base64.getDecoder().decode(basic), StandardCharsets.UTF_8).split(":")[0];
      respond(
          exchange,
          200,
          """
          {"access_token": "token-for-%s", "token_type": "Bearer", "expires_in": 300}
          """
              .formatted(clientId));
    }

    private void invoices(HttpExchange exchange) throws IOException {
      var path = exchange.getRequestURI().getPath();
      var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      platformRequests.add(
          new Recorded(
              exchange.getRequestMethod(),
              path,
              exchange.getRequestHeaders().getFirst("Authorization"),
              body));
      if (path.equals("/v1/invoices")) {
        var id = UUID.randomUUID();
        invoices.put(
            id,
            new StubInvoice(
                arrived.incrementAndGet(),
                find(INVOICE_NUMBER, body),
                Long.parseLong(find(MINOR_UNITS, body))));
        respond(exchange, 201, invoice(id, null));
        return;
      }
      var id = UUID.fromString(path.split("/")[3]);
      if (path.endsWith("/approval")) {
        approved.add(id);
        respond(exchange, 200, invoice(id, null));
      } else if (reads.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet() == 1) {
        respond(exchange, 200, invoice(id, "{\"state\": \"STARTED\"}"));
      } else if (invoices.get(id).number() == failing) {
        respond(
            exchange,
            200,
            invoice(id, "{\"state\": \"FAILED\", \"failureReason\": \"Stub failure.\"}"));
      } else {
        deliverRemittanceAdvice(id);
        respond(exchange, 200, invoice(id, "{\"state\": \"COMPLETED\"}"));
      }
    }

    private void deliverRemittanceAdvice(UUID id) {
      var invoice = invoices.get(id);
      RestClient.create()
          .post()
          .uri(URI.create("http://localhost:" + simulatorPort + GlobexSupplier.ADVICE_PATH))
          .header("Idempotency-Key", UUID.randomUUID().toString())
          .contentType(MediaType.APPLICATION_JSON)
          .body(
              """
              {
                "id": "%s",
                "invoiceId": "%s",
                "invoiceNumber": "%s",
                "amount": {"minorUnits": %d, "currency": "USD"},
                "cardLink": "http://localhost:%d/v1/remittance-links/%s",
                "cardLinkExpiresAt": "2026-10-23T12:00:00Z"
              }
              """
                  .formatted(
                      UUID.randomUUID(),
                      id,
                      invoice.invoiceNumber(),
                      invoice.minorUnits(),
                      server.getAddress().getPort(),
                      id))
          .retrieve()
          .toBodilessEntity();
    }

    // The link's token is the Invoice's ID here.
    private void cardLink(HttpExchange exchange) throws IOException {
      var path = exchange.getRequestURI().getPath();
      var invoice = invoices.get(UUID.fromString(path.substring(path.lastIndexOf('/') + 1)));
      respond(
          exchange,
          200,
          """
          {
            "pan": "%s",
            "securityCode": "321",
            "expiresAt": "2026-11-15T12:00:00Z",
            "amount": {"minorUnits": %d, "currency": "USD"},
            "invoiceNumber": "%s"
          }
          """
              .formatted(invoice.pan(), invoice.minorUnits(), invoice.invoiceNumber()));
    }

    private String invoice(UUID id, String payment) {
      var invoice = invoices.get(id);
      return """
          {
            "id": "%s",
            "status": "%s",
            "supplierId": "0b7e7c55-3a9d-4f6e-8b21-5c4d3e2f1a00",
            "supplier": {
              "name": "Globex",
              "taxId": "12-3456789",
              "buyerReference": "GLOBEX-01",
              "remittanceAddress": "http://localhost:8081/globex/remittance-advice"
            },
            "invoiceNumber": "%s",
            "amount": {"minorUnits": %d, "currency": "USD"},
            "dueDate": "2026-11-30",
            "decisions": []%s
          }
          """
          .formatted(
              id,
              approved.contains(id) ? "APPROVED" : "RECEIVED",
              invoice.invoiceNumber(),
              invoice.minorUnits(),
              payment == null ? "" : ", \"payment\": " + payment);
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
      var bytes = json.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    }
  }
}
