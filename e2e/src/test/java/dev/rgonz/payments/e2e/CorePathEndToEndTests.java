package dev.rgonz.payments.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import dev.rgonz.payments.platform.PlatformApplication;
import dev.rgonz.payments.simulator.SimulatorApplication;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.json.JsonContent;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The core path the way the demo runs it: the platform and the simulator both running in this JVM,
 * against real PostgreSQL, Kafka and Keycloak (the repository's realm file), with
 * authorization-service running beside them from its executable jar, against its own PostgreSQL.
 * The service copies each card the platform issues from the {@code cards} topic and confirms it on
 * {@code authorizations}, which the platform's Payment waits for before sending the Remittance
 * Advice. The simulator's demo endpoint has Acme's ERP submit an Invoice from Globex, two of Acme's
 * Approvers approve it, the platform's worker pays it with a Single-Use Card, Globex opens the card
 * through its Remittance Advice link, and Globex charges it: the simulator's Acquirer and Network
 * ask authorization-service over gRPC, signed in as the network client, and the service approves
 * the Charge and tells the platform on {@code authorizations}. Then, once the platform's copy has
 * the approved Authorization, the demo's second call has the Network write its Clearing File into
 * the platform's drop folder and Issuer staff import it, and its third has Issuer staff run today's
 * Reconciliation, which finds no Break. Last, the simulator seeds a few load-test cards through the
 * same path.
 */
@Testcontainers
class CorePathEndToEndTests {

  // TEST-ONLY client secret for every client in the realm file, not a secret.
  private static final String CLIENT_SECRET = "test-only-secret";

  @Container
  static final PostgreSQLContainer postgres =
      // Same tag as compose.yaml and the platform's TestcontainersConfiguration: change all three.
      new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));

  @Container
  static final PostgreSQLContainer authorizationPostgres =
      new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));

  @Container
  static final KafkaContainer kafka =
      // Same image as compose.yaml and the platform's TestKafka: change all three together.
      new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.1"));

  @Container
  static final KeycloakContainer keycloak =
      // Same image as compose.yaml and the platform's TestKeycloak: change all three together.
      new KeycloakContainer("quay.io/keycloak/keycloak:26.8.1")
          .withRealmImportFile("/keycloak/payments-realm.json")
          .withEnv("SIMULATOR_ISSUER_ADMIN_SECRET", CLIENT_SECRET)
          .withEnv("SIMULATOR_RECONCILER_SECRET", CLIENT_SECRET)
          .withEnv("ACME_ERP_SECRET", CLIENT_SECRET)
          .withEnv("ACME_APPROVER_1_SECRET", CLIENT_SECRET)
          .withEnv("ACME_APPROVER_2_SECRET", CLIENT_SECRET)
          .withEnv("INITECH_ERP_SECRET", CLIENT_SECRET)
          .withEnv("INITECH_APPROVER_SECRET", CLIENT_SECRET)
          .withEnv("NETWORK_CLIENT_SECRET", CLIENT_SECRET);

  // The Issuer's drop folder: the simulator's Network writes its Clearing Files here and the
  // platform imports them from here.
  @TempDir static Path dropFolder;

  // The service's working directory: away from the repository, so it reads no developer's .env.
  @TempDir static Path serviceDirectory;

  // Where the load-test seeding writes its cards: not the repository's var/load-test.
  @TempDir static Path loadTestFolder;

  // TEST-ONLY hash key, not a secret: the same all-0x02 bytes as the platform's own tests. The
  // platform and the service must share it (ADR-0016).
  private static final String HASH_KEY = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=";

  private static Process authorizationService;
  private static ConfigurableApplicationContext platform;
  private static ConfigurableApplicationContext simulator;
  private static int platformPort;
  private static int simulatorPort;
  private static int servicePort;
  // The Network link's gRPC server, off the fixed 9090 another build may be using.
  private static int serviceGrpcPort;

  @BeforeAll
  static void startBothApps() {
    platformPort = TestSocketUtils.findAvailableTcpPort();
    simulatorPort = TestSocketUtils.findAvailableTcpPort();
    var issuerUri = keycloak.getAuthServerUrl() + "/realms/payments";
    // Before the platform: either may start first, as each declares both topics.
    authorizationService = startAuthorizationService(issuerUri);

    platform =
        start(
            PlatformApplication.class,
            "server.port=" + platformPort,
            "platform.public-url=http://localhost:" + platformPort,
            "spring.datasource.url=" + postgres.getJdbcUrl(),
            "spring.datasource.username=" + postgres.getUsername(),
            "spring.datasource.password=" + postgres.getPassword(),
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + issuerUri,
            // TEST-ONLY keys, not secrets: the same all-0x01, 0x02 and 0x03 bytes as the
            // platform's own tests.
            "vault.encryption-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
            "vault.hash-key=" + HASH_KEY,
            "remittance.link-key=AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM=",
            // Not the repository's var/clearing, where a developer's own Clearing Files may wait.
            "clearing.drop-folder=" + dropFolder,
            // The demo has Issuer staff import its file at once.
            "clearing.drop-folder-check.enabled=false",
            // The cards topic out to the service, and its authorizations topic back.
            "spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers());
    // Returns once BuyerSeeder has created Acme and Initech and their Credit Lines through the
    // platform.
    simulator =
        start(
            SimulatorApplication.class,
            // Globex's remittance address is built from this port.
            "server.port=" + simulatorPort,
            "simulator.platform-url=http://localhost:" + platformPort,
            // The Network charges Globex's card through the service's gRPC server.
            "simulator.platform-grpc-address=localhost:" + serviceGrpcPort,
            "spring.security.oauth2.client.provider.keycloak.token-uri="
                + issuerUri
                + "/protocol/openid-connect/token",
            "spring.security.oauth2.client.registration.issuer-admin.client-secret="
                + CLIENT_SECRET,
            "spring.security.oauth2.client.registration.acme-erp.client-secret=" + CLIENT_SECRET,
            "spring.security.oauth2.client.registration.acme-approver-1.client-secret="
                + CLIENT_SECRET,
            "spring.security.oauth2.client.registration.acme-approver-2.client-secret="
                + CLIENT_SECRET,
            "spring.security.oauth2.client.registration.initech-erp.client-secret=" + CLIENT_SECRET,
            "spring.security.oauth2.client.registration.initech-approver.client-secret="
                + CLIENT_SECRET,
            "spring.security.oauth2.client.registration.network.client-secret=" + CLIENT_SECRET,
            // The Network writes its Clearing File into the platform's drop folder, only when the
            // demo asks: not at midnight.
            "simulator.network.drop-folder=" + dropFolder,
            "simulator.network.clearing-file-cron=-",
            "simulator.load-test.cards-file=" + loadTestFolder.resolve("cards.csv"),
            // The platform's dependencies are on this classpath too: the simulator has no database.
            "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration,"
                + "org.springframework.modulith.events.config.EventPublicationAutoConfiguration");
  }

  // Starts the app in this JVM with its own application.properties and the given properties on
  // top, as command-line arguments, which outrank the file. Docker Compose support is an optional
  // dependency of both apps, so it isn't on this classpath and starts nothing.
  private static ConfigurableApplicationContext start(Class<?> application, String... properties) {
    var args =
        Stream.concat(
                Stream.of(
                    "spring.config.location=" + ownApplicationProperties(application),
                    // No OTLP receiver runs here: export nothing. Metrics would otherwise go to
                    // localhost:4318 by default, and any OTEL_EXPORTER_OTLP_* variable set in the
                    // environment would turn traces and logs on.
                    "management.otlp.metrics.export.enabled=false",
                    "management.tracing.export.enabled=false",
                    "management.logging.export.otlp.enabled=false",
                    // Logback is set up once per JVM, by the first app to start, so every line
                    // would carry the platform's name; the logger's package tells the apps apart.
                    "logging.include-application-name=false"),
                Arrays.stream(properties))
            .map(property -> "--" + property)
            .toArray(String[]::new);
    // The main class names the app in its log lines; Boot would guess Surefire's.
    return new SpringApplicationBuilder(application).main(application).run(args);
  }

  // The service's executable jar, as built before this module (authorizationServiceJar in the pom),
  // in its own JVM: its reactive stack and Flyway setup can't share this classpath with the
  // platform's. Returns once its health answers; its log goes to target/authorization-service.log.
  private static Process startAuthorizationService(String issuerUri) {
    servicePort = TestSocketUtils.findAvailableTcpPort();
    serviceGrpcPort = TestSocketUtils.findAvailableTcpPort();
    var jdbcUrl = authorizationPostgres.getJdbcUrl();
    var command =
        Stream.concat(
                Stream.of(
                    ProcessHandle.current().info().command().orElse("java"),
                    "-jar",
                    Path.of(System.getProperty("authorizationServiceJar"))
                        .toAbsolutePath()
                        .toString()),
                Stream.of(
                        "server.port=" + servicePort,
                        "spring.grpc.server.port=" + serviceGrpcPort,
                        "spring.r2dbc.url=" + jdbcUrl.replaceFirst("^jdbc:", "r2dbc:"),
                        "spring.r2dbc.username=" + authorizationPostgres.getUsername(),
                        "spring.r2dbc.password=" + authorizationPostgres.getPassword(),
                        "spring.flyway.url=" + jdbcUrl,
                        "spring.flyway.user=" + authorizationPostgres.getUsername(),
                        "spring.flyway.password=" + authorizationPostgres.getPassword(),
                        "spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + issuerUri,
                        "vault.hash-key=" + HASH_KEY)
                    .map(property -> "--" + property))
            .toList();
    try {
      var log = Path.of("target", "authorization-service.log").toAbsolutePath();
      var process =
          new ProcessBuilder(command)
              .directory(serviceDirectory.toFile())
              .redirectErrorStream(true)
              .redirectOutput(log.toFile())
              .start();
      awaitHealthy(process, log);
      return process;
    } catch (IOException e) {
      throw new IllegalStateException("authorization-service didn't start", e);
    }
  }

  private static void awaitHealthy(Process process, Path log) throws IOException {
    var health =
        HttpRequest.newBuilder(URI.create("http://localhost:" + servicePort + "/actuator/health"))
            .build();
    var deadline = Instant.now().plus(Duration.ofSeconds(60));
    try (var http = HttpClient.newHttpClient()) {
      while (Instant.now().isBefore(deadline)) {
        if (!process.isAlive()) {
          throw new IllegalStateException("authorization-service stopped; see " + log);
        }
        try {
          if (http.send(health, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
            return;
          }
        } catch (IOException notListeningYet) {
          // Not listening yet: try again.
        }
        Thread.sleep(500);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    throw new IllegalStateException("authorization-service wasn't healthy in time; see " + log);
  }

  // Both apps' jars hold an application.properties, and classpath:application.properties finds
  // only the first one on the classpath, for both apps: each gets the one beside its own classes.
  private static String ownApplicationProperties(Class<?> application) {
    var location = application.getProtectionDomain().getCodeSource().getLocation().toString();
    return location.endsWith(".jar")
        ? "jar:" + location + "!/application.properties"
        : location + "application.properties";
  }

  @AfterAll
  static void stopBothApps() {
    if (simulator != null) {
      simulator.close();
    }
    if (platform != null) {
      platform.close();
    }
    if (authorizationService != null) {
      authorizationService.destroy();
    }
  }

  @Test
  // The demo itself waits up to 30 seconds for the worker to pay the Invoice, and the seeding up
  // to two minutes for each of its Payments, though they take seconds.
  @Timeout(value = 90, unit = TimeUnit.SECONDS)
  void theDemoInvoiceIsPaidItsChargeApprovedClearedFromTheNetworksFileAndReconciledWithoutABreak()
      throws IOException {
    var summary =
        RestClient.create()
            .post()
            .uri("http://localhost:{port}/demo/invoices", simulatorPort)
            .retrieve()
            .body(String.class);

    var json = new JsonContent(summary);
    assertThat(json)
        .isLenientlyEqualTo(
            """
            {
              "amount": {"minorUnits": 1240000, "currency": "USD"},
              "approvedBy": ["service-account-acme-approver-1", "service-account-acme-approver-2"],
              "paymentState": "COMPLETED",
              "globexReceived": {"amount": {"minorUnits": 1240000, "currency": "USD"}},
              "authorization": {"approved": true, "responseCode": "00"}
            }
            """);
    String invoiceNumber = JsonPath.read(summary, "$.invoiceNumber");
    assertThat(json).extractingPath("$.globexReceived.invoiceNumber").isEqualTo(invoiceNumber);
    assertThat(json).extractingPath("$.globexReceived.lastFour").asString().matches("\\d{4}");
    assertThat(json).extractingPath("$.authorization.authorizationCode").asString().hasSize(6);
    // The service's approved Authorization reaches the platform's copy through its outbox and
    // Kafka a moment after the Network's answer; the Clearing File must not come before it (#94
    // makes the import wait on its own).
    String authorizationCode = JsonPath.read(summary, "$.authorization.authorizationCode");
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> platformHasApprovedAuthorization(authorizationCode));
    // Until the Charge clears, its Hold reserves Acme's credit.
    assertThat(acmesCredit())
        .isLenientlyEqualTo(
            """
            {
              "openHolds": {"minorUnits": 1240000, "currency": "USD"},
              "buyerReceivable": {"minorUnits": 0, "currency": "USD"},
              "availableCredit": {"minorUnits": 8760000, "currency": "USD"}
            }
            """);

    var cleared =
        RestClient.create()
            .post()
            .uri("http://localhost:{port}/demo/clearing-file", simulatorPort)
            .retrieve()
            .body(String.class);

    // One import, the platform's 201 and its summary: the Charge's Clearing matched its
    // Authorization, which moves the card to CLEARED, and the file is settled at the Network's
    // figure, exactly what was posted.
    var clearedJson = new JsonContent(cleared);
    assertThat(clearedJson).extractingPath("$.imports").asArray().hasSize(1);
    assertThat(clearedJson)
        .isLenientlyEqualTo(
            """
            {"imports": [{
              "status": 201,
              "summary": {
                "status": "IMPORTED",
                "recordCount": 1,
                "skippedCount": 0,
                "outcomes": {"matched": 1, "aboveAuthorizedAmount": 0, "noAuthorization": 0,
                             "unknownCard": 0, "otherCurrency": 0,
                             "againstReversedAuthorization": 0, "afterHoldExpired": 0,
                             "authorizationAlreadyCleared": 0},
                "settlement": {
                  "amount": {"minorUnits": 1240000, "currency": "USD"},
                  "importedTotal": {"minorUnits": 1240000, "currency": "USD"},
                  "matched": true
                }
              }
            }]}
            """);
    String fileId = JsonPath.read(cleared, "$.imports[0].summary.id");
    assertThat(clearedJson).extractingPath("$.fileName").isEqualTo(fileId + ".txt");
    // The books, which no API shows until M9: the Clearing debited Acme's Buyer Receivable and
    // credited the Network Settlement Payable, and the Settlement was posted, paying that Payable
    // out of Issuer Cash.
    assertThat(balancesByAccountKind())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "BUYER_RECEIVABLE", 1_240_000L,
                "NETWORK_SETTLEMENT_PAYABLE", 0L,
                "ISSUER_CASH", -1_240_000L));
    // The Hold became posted spend: Acme owes the cleared amount, and its Available Credit counts
    // that instead of the Hold.
    assertThat(acmesCredit())
        .isLenientlyEqualTo(
            """
            {
              "openHolds": {"minorUnits": 0, "currency": "USD"},
              "buyerReceivable": {"minorUnits": 1240000, "currency": "USD"},
              "availableCredit": {"minorUnits": 8760000, "currency": "USD"}
            }
            """);
    // The platform deletes a file once it has imported it: the shared drop folder is empty again.
    try (var left = Files.walk(dropFolder)) {
      assertThat(left.filter(Files::isRegularFile)).isEmpty();
    }

    var reconciled =
        RestClient.create()
            .post()
            .uri("http://localhost:{port}/demo/reconciliation", simulatorPort)
            .retrieve()
            .body(String.class);

    // Today's Reconciliation finds nothing to raise: the Charge's Clearing matched its
    // Authorization at the full amount, the Settlement matched the file, and the books balance.
    // So no Break names the Charge, and none is open for today.
    assertThat(new JsonContent(reconciled))
        .isStrictlyEqualTo(
            """
            {
              "run": {
                "runId": "%s",
                "businessDate": "%s",
                "breaksFound": 0,
                "newBreaks": 0,
                "knownBreaks": 0
              },
              "openBreaks": []
            }
            """
                .formatted(
                    JsonPath.<String>read(reconciled, "$.run.runId"),
                    LocalDate.now(ZoneOffset.UTC)));

    // Last, after the Reconciliation it would change: the load test's cards, Initech's Invoices
    // paid through the same path, a card written for each.
    var seeded =
        RestClient.create()
            .post()
            .uri("http://localhost:{port}/demo/load-test-cards?count=5", simulatorPort)
            .retrieve()
            .body(String.class);

    assertThat(new JsonContent(seeded))
        .isLenientlyEqualTo(
            """
            {"requested": 5, "written": 5}
            """);
    // The header, then one line per card.
    assertThat(Files.readAllLines(loadTestFolder.resolve("cards.csv"))).hasSize(6);
  }

  // The platform's Authorization Copy holds the service's approved Authorization with this code,
  // and its card moved to AUTHORIZED in the same transaction. One module's schema per query
  // (ADR-0009): the copy's, then cards'.
  private static boolean platformHasApprovedAuthorization(String authorizationCode) {
    var jdbc = platform.getBean(JdbcClient.class);
    var cardToken =
        jdbc.sql(
                """
                select card_token from "authorization".authorizations
                 where authorization_code = :code and status = 'APPROVED'
                """)
            .param("code", authorizationCode)
            .query(String.class)
            .optional();
    return cardToken
        .map(
            token ->
                jdbc.sql("select status from cards.card where card_token = :cardToken")
                    .param("cardToken", token)
                    .query(String.class)
                    .optional()
                    .filter("AUTHORIZED"::equals)
                    .isPresent())
        .orElse(false);
  }

  // Each kind of Account's balance in the platform's journal, its debits less its credits.
  private static Map<String, Long> balancesByAccountKind() {
    return platform
        .getBean(JdbcClient.class)
        .sql(
            """
            select a.kind,
                   sum(case l.side when 'DEBIT' then l.amount_minor else -l.amount_minor end)
              from ledger.journal_line l
              join ledger.account a on a.id = l.account_id
             group by a.kind
            """)
        .query((row, rowNumber) -> Map.entry(row.getString(1), row.getLong(2)))
        .list()
        .stream()
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  // Acme's credit, as Issuer staff read it: signed in through Keycloak as the simulator's
  // issuer-admin client.
  private static JsonContent acmesCredit() {
    String tokenResponse =
        RestClient.create()
            .post()
            .uri(keycloak.getAuthServerUrl() + "/realms/payments/protocol/openid-connect/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(
                "grant_type=client_credentials&client_id=simulator-issuer-admin&client_secret="
                    + CLIENT_SECRET)
            .retrieve()
            .body(String.class);
    String accessToken = JsonPath.read(tokenResponse, "$.access_token");
    var acme = simulator.getEnvironment().getRequiredProperty("simulator.acme.buyer-id");
    return new JsonContent(
        RestClient.create()
            .get()
            .uri("http://localhost:{port}/v1/buyers/{buyerId}/credit", platformPort, acme)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .retrieve()
            .body(String.class));
  }
}
