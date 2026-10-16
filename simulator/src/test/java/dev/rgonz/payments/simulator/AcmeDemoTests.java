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
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
 * The demo runs the core path in one call: Acme's ERP submits an Invoice from Globex, Acme's
 * Approvers approve it, the demo waits for its Payment, and Globex charges the card it received,
 * against a stub Keycloak, platform and {@code IssuerAuthorization} gRPC server. Like the platform,
 * the stub delivers the Remittance Advice to Globex before the Payment shows {@code COMPLETED}, and
 * serves the card link. A second call has the Network write its Clearing File and Issuer staff
 * import it.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "simulator.seed.enabled=false")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AcmeDemoTests {

  // Acme's Approval Threshold, as the simulator seeds it.
  private static final long APPROVAL_THRESHOLD = 1_000_000;
  // A made-up link token, low in entropy so secret scanners pass it by.
  private static final String LINK_TOKEN = "test-only-demo-link";
  private static final String PAN = "9900001234567899";

  record Recorded(String method, String path, String authorization) {}

  static final List<Recorded> platformRequests = new CopyOnWriteArrayList<>();
  static final StubPlatform platform = new StubPlatform();
  static final StubIssuer issuer = new StubIssuer();
  @TempDir static Path dropFolder;

  @DynamicPropertySource
  static void pointAtStub(DynamicPropertyRegistry registry) {
    var base = "http://localhost:" + platform.server.getAddress().getPort();
    registry.add(
        "spring.security.oauth2.client.provider.keycloak.token-uri", () -> base + "/token");
    for (var client :
        List.of("issuer-admin", "acme-erp", "acme-approver-1", "acme-approver-2", "network")) {
      registry.add(
          "spring.security.oauth2.client.registration." + client + ".client-secret", () -> "test");
    }
    registry.add("simulator.platform-url", () -> base);
    registry.add("simulator.platform-grpc-address", issuer::address);
    registry.add("simulator.network.drop-folder", () -> dropFolder.toString());
  }

  @AfterAll
  static void stopStub() {
    platform.server.stop(0);
    issuer.stop();
  }

  @LocalServerPort int port;
  @Autowired MockMvcTester mvc;

  @Autowired NetworkClearingFiles clearingFiles;

  // Each test starts with the Network holding no Clearings from an earlier test's Charges.
  @BeforeEach
  void resetStub() {
    platformRequests.clear();
    platform.reset(port);
    issuer.reset();
    clearingFiles.write(LocalDate.of(9999, 12, 31));
  }

  @Test
  void anInvoiceAboveAcmesThresholdIsApprovedTwicePaidAndGlobexsChargeOnItsCardApproved(
      CapturedOutput output) {
    issuer.answerWith("00", "AB12CD");

    var demo = mvc.post().uri("/demo/invoices").exchange();

    assertThat(demo).hasStatusOk();
    assertThat(demo)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {
              "invoiceId": "%s",
              "invoiceNumber": "%s",
              "amount": {"minorUnits": 1240000, "currency": "USD"},
              "approvedBy": ["service-account-acme-approver-1", "service-account-acme-approver-2"],
              "paymentState": "COMPLETED",
              "globexReceived": {
                "lastFour": "7899",
                "expiresAt": "2026-11-15T12:00:00Z",
                "amount": {"minorUnits": 1240000, "currency": "USD"}
              },
              "authorization": {"approved": true, "responseCode": "00", "authorizationCode": "AB12CD"}
            }
            """
                .formatted(platform.invoiceId, platform.invoiceNumber));
    assertThat(demo).bodyText().doesNotContain(PAN).doesNotContain("securityCode");
    assertThat(issuer.received)
        .singleElement()
        .satisfies(charge -> assertThat(charge.request().getAmountMinor()).isEqualTo(1_240_000));
    var invoice = "/v1/invoices/" + platform.invoiceId;
    assertThat(platformRequests)
        .startsWith(
            new Recorded("POST", "/v1/invoices", "Bearer token-for-acme-erp"),
            new Recorded("POST", invoice + "/approval", "Bearer token-for-acme-approver-1"),
            new Recorded("POST", invoice + "/approval", "Bearer token-for-acme-approver-2"),
            new Recorded("GET", invoice, "Bearer token-for-acme-erp"));
    assertThat(output).doesNotContain(PAN).doesNotContain(LINK_TOKEN);
  }

  @Test
  void anInvoiceAtAcmesThresholdTakesOneApproval() {
    var demo = mvc.post().uri("/demo/invoices?minorUnits={amount}", APPROVAL_THRESHOLD).exchange();

    assertThat(demo).hasStatusOk();
    assertThat(demo)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {
              "amount": {"minorUnits": 1000000, "currency": "USD"},
              "approvedBy": ["service-account-acme-approver-1"],
              "paymentState": "COMPLETED"
            }
            """);
    assertThat(platformRequests)
        .filteredOn(request -> request.path().endsWith("/approval"))
        .hasSize(1);
  }

  @Test
  void aFailedPaymentEndsTheDemoWithItsReasonAndNoCard() {
    platform.failPaymentWith("Not enough Available Credit to place the Hold.");

    var demo = mvc.post().uri("/demo/invoices").exchange();

    assertThat(demo).hasStatusOk();
    assertThat(demo)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {
              "paymentState": "FAILED",
              "failureReason": "Not enough Available Credit to place the Hold.",
              "globexReceived": null,
              "authorization": null
            }
            """);
    assertThat(issuer.received).isEmpty();
    // The demo stops reading the Invoice once its Payment has failed.
    assertThat(platformRequests).filteredOn(request -> request.method().equals("GET")).hasSize(2);
  }

  @Test
  void theClearingFileDemoHasTheNetworkWriteItsFileAndIssuerStaffImportIt(CapturedOutput output) {
    issuer.answerWith("00", "CL34EF");
    assertThat(mvc.post().uri("/demo/invoices").exchange()).hasStatusOk();
    var requestId = issuer.received.getLast().request().getRetrievalReferenceNumber();

    var demo = mvc.post().uri("/demo/clearing-file").exchange();

    assertThat(demo).hasStatusOk();
    assertThat(platformRequests)
        .last()
        .isEqualTo(
            new Recorded("POST", "/v1/clearing-files", "Bearer token-for-simulator-issuer-admin"));
    // The file the import named was the Network's, in the drop folder, with the demo's Charge in
    // it.
    var imported = platform.imports.getLast();
    assertThat(imported.content()).startsWith("90");
    assertThat(imported.content().lines())
        .anySatisfy(line -> assertThat(line).startsWith("05").contains(PAN, requestId, "CL34EF"));
    // The file's name, and the platform's summary as the stub answered it.
    assertThat(demo)
        .bodyJson()
        .isStrictlyEqualTo(
            """
            {"fileName": "%s", "imports": [%s]}
            """
                .formatted(imported.fileName(), IMPORTED.shown()));
    assertThat(demo).bodyText().doesNotContain(PAN);
    assertThat(output).doesNotContain(PAN);
  }

  /**
   * A switch of the clearing-file demo that changes the file the Network writes, how the stub
   * platform answers its import, and what the file shows of the change (NetworkClearingFileTests
   * reads each change by the layout).
   */
  record FileSwitch(String name, ImportAnswer platformAnswer, Consumer<List<String>> fileShows) {

    @Override
    public String toString() {
      return name;
    }
  }

  // The file below carries one Charge: $12,400.00 on the stub's card, authorization code CL34EF.
  static Stream<FileSwitch> fileSwitches() {
    return Stream.of(
        new FileSwitch(
            "duplicateRecord",
            IMPORTED,
            lines -> assertThat(details(lines)).hasSize(2).containsOnly(details(lines).getFirst())),
        new FileSwitch(
            "aboveAuthorizedAmount",
            IMPORTED,
            lines ->
                assertThat(details(lines))
                    .extracting(line -> field(line, 42, 53))
                    .containsExactly("000001250000")),
        new FileSwitch(
            "noAuthorization",
            IMPORTED,
            lines ->
                assertThat(details(lines))
                    .extracting(line -> field(line, 69, 74))
                    .containsExactly("CL34EF", "NONE  ")),
        new FileSwitch(
            "unknownPan",
            IMPORTED,
            lines ->
                assertThat(details(lines))
                    .extracting(line -> field(line, 23, 41).strip())
                    .hasSize(2)
                    .startsWith(PAN)
                    .doesNotHaveDuplicates()),
        new FileSwitch(
            "wrongTrailerTotal",
            REFUSED,
            lines -> assertThat(field(lines.getLast(), 11, 25)).isEqualTo("000000001240001")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fileSwitches")
  void eachSwitchChangesTheFileIssuerStaffImportOnceAndTheAnswerShowsThePlatformsResponse(
      FileSwitch fileSwitch, CapturedOutput output) {
    issuer.answerWith("00", "CL34EF");
    assertThat(mvc.post().uri("/demo/invoices").exchange()).hasStatusOk();
    platform.answerImportsWith(fileSwitch.platformAnswer());

    var demo = mvc.post().uri("/demo/clearing-file?" + fileSwitch.name() + "=true").exchange();

    assertThat(demo).hasStatusOk();
    assertThat(platform.imports).hasSize(1);
    var imported = platform.imports.getFirst();
    var lines = imported.content().lines().toList();
    fileSwitch.fileShows().accept(lines);
    assertThat(demo)
        .bodyJson()
        .isStrictlyEqualTo(
            """
            {"fileName": "%s", "imports": [%s]}
            """
                .formatted(imported.fileName(), fileSwitch.platformAnswer().shown()));
    for (var pan : details(lines).stream().map(line -> field(line, 23, 41).strip()).toList()) {
      assertThat(demo).bodyText().doesNotContain(pan);
      assertThat(output).doesNotContain(pan);
    }
  }

  // The platform takes a file out of its drop folder when it imports it, so the Network sends the
  // file again for the second import.
  @Test
  void theSameFileTwiceIsSentAndImportedTwiceAndTheAnswerShowsBothResponses(CapturedOutput output) {
    issuer.answerWith("00", "CL34EF");
    assertThat(mvc.post().uri("/demo/invoices").exchange()).hasStatusOk();
    platform.answerImportsWith(IMPORTED, REPEATED);

    var demo = mvc.post().uri("/demo/clearing-file?sameFileTwice=true").exchange();

    assertThat(demo).hasStatusOk();
    assertThat(platform.imports).hasSize(2);
    var first = platform.imports.getFirst();
    assertThat(first.content()).isNotNull().contains("CL34EF");
    assertThat(platform.imports.getLast()).isEqualTo(first);
    assertThat(demo)
        .bodyJson()
        .isStrictlyEqualTo(
            """
            {"fileName": "%s", "imports": [%s, %s]}
            """
                .formatted(first.fileName(), IMPORTED.shown(), REPEATED.shown()));
    assertThat(demo).bodyText().doesNotContain(PAN);
    assertThat(output).doesNotContain(PAN);
  }

  @Test
  void withNothingToClearTheDemoWritesNoFileAndImportsNothing() throws IOException {
    long filesBefore;
    try (var files = Files.list(dropFolder)) {
      filesBefore = files.count();
    }

    var demo = mvc.post().uri("/demo/clearing-file").exchange();
    // The switches change the file the Network writes; they never make one.
    var switched =
        mvc.post()
            .uri(
                "/demo/clearing-file?duplicateRecord=true&aboveAuthorizedAmount=true"
                    + "&noAuthorization=true&unknownPan=true&wrongTrailerTotal=true"
                    + "&sameFileTwice=true")
            .exchange();

    assertThat(demo).hasStatus(HttpStatus.NO_CONTENT);
    assertThat(switched).hasStatus(HttpStatus.NO_CONTENT);
    assertThat(platformRequests).isEmpty();
    try (var files = Files.list(dropFolder)) {
      assertThat(files.count()).isEqualTo(filesBefore);
    }
  }

  // A Clearing File's detail records (05).
  private static List<String> details(List<String> lines) {
    return lines.stream().filter(line -> line.startsWith("05")).toList();
  }

  // A field of a Clearing File's record, by the layout's 1-based positions.
  private static String field(String record, int from, int to) {
    return record.substring(from - 1, to);
  }

  /**
   * A Clearing File import as the stub received it: the file named, and its content then. {@code
   * toString} leaves out the content, which holds PANs, so a failed assertion doesn't print them.
   */
  record Import(String fileName, String content) {

    @Override
    public String toString() {
      return "Import[fileName=%s, content %s]"
          .formatted(fileName, content == null ? "absent" : "of " + content.length() + " chars");
    }
  }

  /** How the stub answers an import: the status and body the platform would send. */
  record ImportAnswer(int status, String body) {

    boolean refused() {
      return status >= 400;
    }

    // As the demo's answer shows it: the status, and the body as the summary or the problem.
    String shown() {
      return """
          {"status": %d, "%s": %s}"""
          .formatted(status, refused() ? "problem" : "summary", body);
    }
  }

  static final ImportAnswer IMPORTED =
      new ImportAnswer(
          201,
          """
          {"id": "NET-STUB-0001", "sequenceNumber": 7, "businessDate": "2026-10-16",
           "status": "IMPORTED", "recordCount": 1, "skippedCount": 0,
           "outcomes": {"matched": 1, "aboveAuthorizedAmount": 0, "noAuthorization": 0,
                        "unknownCard": 0, "otherCurrency": 0, "againstReversedAuthorization": 0,
                        "afterHoldExpired": 0, "authorizationAlreadyCleared": 0},
           "settlement": {"amount": {"minorUnits": 1240000, "currency": "USD"},
                          "importedTotal": {"minorUnits": 1240000, "currency": "USD"},
                          "matched": true}}
          """);
  static final ImportAnswer REFUSED =
      new ImportAnswer(
          422,
          """
          {"type": "about:blank", "title": "Unprocessable Content", "status": 422,
           "detail": "This isn't a Clearing File the platform can import: Line 3: the amount \
          total (positions 11–25) isn't the sum of the detail records' amounts",
           "instance": "/v1/clearing-files"}
          """);
  static final ImportAnswer REPEATED =
      new ImportAnswer(
          409,
          """
          {"type": "about:blank", "title": "Conflict", "status": 409,
           "detail": "This file repeats Clearing File NET-STUB-0001, imported or importing, so \
          nothing in it was posted",
           "instance": "/v1/clearing-files"}
          """);

  /**
   * Plays the platform for one demo Invoice at a time: takes the Invoice, applies Acme's approval
   * rule, and shows its Payment {@code STARTED} on the first read and {@code COMPLETED} on the
   * next, after delivering the Remittance Advice to the simulator's Globex; or {@code FAILED},
   * delivering nothing, when told to fail it. Answers each Clearing File import as it was told to,
   * or with {@link #IMPORTED}.
   */
  static final class StubPlatform {

    private static final Pattern INVOICE_NUMBER = Pattern.compile("\"invoiceNumber\":\"([^\"]+)\"");
    private static final Pattern MINOR_UNITS = Pattern.compile("\"minorUnits\":(\\d+)");
    private static final Pattern FILE_NAME = Pattern.compile("\"fileName\":\"([^\"]+)\"");

    final HttpServer server;
    volatile int simulatorPort;
    volatile UUID invoiceId;
    volatile String invoiceNumber;
    volatile long minorUnits;
    volatile String failureReason;
    final List<Import> imports = new CopyOnWriteArrayList<>();
    final Queue<ImportAnswer> importAnswers = new ConcurrentLinkedQueue<>();
    final List<String> approvers = new CopyOnWriteArrayList<>();
    final AtomicInteger reads = new AtomicInteger();

    StubPlatform() {
      try {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      // Answering a read can wait on Globex, which opens the card link here meanwhile.
      server.setExecutor(Executors.newCachedThreadPool());
      server.createContext("/token", this::token);
      server.createContext("/v1/invoices", this::invoices);
      server.createContext("/v1/remittance-links/" + LINK_TOKEN, this::cardLink);
      server.createContext("/v1/clearing-files", this::importClearingFile);
      server.start();
    }

    // The next Invoice's Payment fails for this reason instead of completing.
    void failPaymentWith(String reason) {
      failureReason = reason;
    }

    // The next imports are answered with these, in order.
    void answerImportsWith(ImportAnswer... answers) {
      importAnswers.addAll(List.of(answers));
    }

    void reset(int simulatorPort) {
      this.simulatorPort = simulatorPort;
      invoiceId = null;
      failureReason = null;
      imports.clear();
      importAnswers.clear();
      approvers.clear();
      reads.set(0);
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
      var authorization = exchange.getRequestHeaders().getFirst("Authorization");
      var path = exchange.getRequestURI().getPath();
      platformRequests.add(new Recorded(exchange.getRequestMethod(), path, authorization));
      if (path.equals("/v1/invoices")) {
        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        invoiceId = UUID.randomUUID();
        invoiceNumber = find(INVOICE_NUMBER, body);
        minorUnits = Long.parseLong(find(MINOR_UNITS, body));
        respond(exchange, 201, invoice(status(), null));
      } else if (path.endsWith("/approval")) {
        approvers.add("service-account-" + authorization.substring("Bearer token-for-".length()));
        respond(exchange, 200, invoice(status(), null));
      } else if (reads.incrementAndGet() == 1) {
        respond(exchange, 200, invoice(status(), "{\"state\": \"STARTED\"}"));
      } else if (failureReason != null) {
        respond(
            exchange,
            200,
            invoice(
                status(),
                "{\"state\": \"FAILED\", \"failureReason\": \"%s\"}".formatted(failureReason)));
      } else {
        deliverRemittanceAdvice();
        respond(
            exchange,
            200,
            invoice(
                status(),
                """
                {"state": "COMPLETED",
                 "card": {"cardToken": "test-only-card-token", "lastFour": "7899",
                          "expiresAt": "2026-11-15T12:00:00Z"}}
                """));
      }
    }

    // One approval at or under the threshold, two different Approvers above it.
    private String status() {
      var needed = minorUnits > APPROVAL_THRESHOLD ? 2 : 1;
      return approvers.size() >= needed ? "APPROVED" : "RECEIVED";
    }

    private void deliverRemittanceAdvice() {
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
                      invoiceId,
                      invoiceNumber,
                      minorUnits,
                      server.getAddress().getPort(),
                      LINK_TOKEN))
          .retrieve()
          .toBodilessEntity();
    }

    // Takes the named file out of the drop folder, whatever it answers, as the platform does.
    private void importClearingFile(HttpExchange exchange) throws IOException {
      platformRequests.add(
          new Recorded(
              exchange.getRequestMethod(),
              exchange.getRequestURI().getPath(),
              exchange.getRequestHeaders().getFirst("Authorization")));
      var fileName =
          find(
              FILE_NAME,
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      var file = dropFolder.resolve(fileName);
      imports.add(
          new Import(
              fileName,
              Files.exists(file) ? Files.readString(file, StandardCharsets.US_ASCII) : null));
      Files.deleteIfExists(file);
      var answer = Objects.requireNonNullElse(importAnswers.poll(), IMPORTED);
      respond(
          exchange,
          answer.status(),
          answer.refused() ? "application/problem+json" : "application/json",
          answer.body());
    }

    private void cardLink(HttpExchange exchange) throws IOException {
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
              .formatted(PAN, minorUnits, invoiceNumber));
    }

    private String invoice(String status, String payment) {
      var decisions =
          approvers.stream()
              .map(
                  approver ->
                      """
                      {"decision": "APPROVED", "approverId": "%s", "approverName": "%s",
                       "decidedAt": "2026-10-16T12:00:00Z"}
                      """
                          .formatted(UUID.nameUUIDFromBytes(approver.getBytes()), approver))
              .collect(Collectors.joining(","));
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
            "decisions": [%s]%s
          }
          """
          .formatted(
              invoiceId,
              status,
              invoiceNumber,
              minorUnits,
              decisions,
              payment == null ? "" : ", \"payment\": " + payment);
    }

    private static String find(Pattern pattern, String body) {
      var matcher = pattern.matcher(body);
      if (!matcher.find()) {
        throw new IllegalStateException("No " + pattern + " in the request");
      }
      return matcher.group(1);
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
      respond(exchange, status, "application/json", json);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String json)
        throws IOException {
      var bytes = json.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", contentType);
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    }
  }
}
