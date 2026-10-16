package dev.rgonz.payments.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import dev.rgonz.payments.platform.PlatformApplication;
import dev.rgonz.payments.simulator.SimulatorApplication;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.json.JsonContent;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The core path the way the demo runs it: the platform and the simulator both running in this JVM,
 * against real PostgreSQL and Keycloak (the repository's realm file). The simulator's demo endpoint
 * has Acme's ERP submit an Invoice from Globex, two of Acme's Approvers approve it, the platform's
 * worker pays it with a Single-Use Card, Globex opens the card through its Remittance Advice link,
 * and Globex charges it: the simulator's Acquirer and Network ask the platform over gRPC, signed in
 * as the network client, and the platform approves the Charge. Then the demo's second call has the
 * Network write its Clearing File into the platform's drop folder and Issuer staff import it.
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
  static final KeycloakContainer keycloak =
      // Same image as compose.yaml and the platform's TestKeycloak: change all three together.
      new KeycloakContainer("quay.io/keycloak/keycloak:26.8.1")
          .withRealmImportFile("/keycloak/payments-realm.json")
          .withEnv("SIMULATOR_ISSUER_ADMIN_SECRET", CLIENT_SECRET)
          .withEnv("ACME_ERP_SECRET", CLIENT_SECRET)
          .withEnv("ACME_APPROVER_1_SECRET", CLIENT_SECRET)
          .withEnv("ACME_APPROVER_2_SECRET", CLIENT_SECRET)
          .withEnv("NETWORK_CLIENT_SECRET", CLIENT_SECRET);

  // The Issuer's drop folder: the simulator's Network writes its Clearing Files here and the
  // platform imports them from here.
  @TempDir static Path dropFolder;

  private static ConfigurableApplicationContext platform;
  private static ConfigurableApplicationContext simulator;
  private static int platformPort;
  private static int simulatorPort;

  @BeforeAll
  static void startBothApps() {
    platformPort = TestSocketUtils.findAvailableTcpPort();
    // The Network link's gRPC server, off the fixed 9090 another build may be using.
    var platformGrpcPort = TestSocketUtils.findAvailableTcpPort();
    simulatorPort = TestSocketUtils.findAvailableTcpPort();
    var issuerUri = keycloak.getAuthServerUrl() + "/realms/payments";

    platform =
        start(
            PlatformApplication.class,
            "server.port=" + platformPort,
            "spring.grpc.server.port=" + platformGrpcPort,
            "platform.public-url=http://localhost:" + platformPort,
            "spring.datasource.url=" + postgres.getJdbcUrl(),
            "spring.datasource.username=" + postgres.getUsername(),
            "spring.datasource.password=" + postgres.getPassword(),
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + issuerUri,
            // TEST-ONLY keys, not secrets: the same all-0x01, 0x02 and 0x03 bytes as the
            // platform's own tests.
            "vault.encryption-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
            "vault.hash-key=AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=",
            "remittance.link-key=AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM=",
            // Not the repository's var/clearing, where a developer's own Clearing Files may wait.
            "clearing.drop-folder=" + dropFolder,
            // The demo has Issuer staff import its file at once.
            "clearing.drop-folder-check.enabled=false");
    // Returns once AcmeSeeder has created Acme and its Credit Line through the platform.
    simulator =
        start(
            SimulatorApplication.class,
            // Globex's remittance address is built from this port.
            "server.port=" + simulatorPort,
            "simulator.platform-url=http://localhost:" + platformPort,
            // The Network charges Globex's card through the platform's gRPC server.
            "simulator.platform-grpc-address=localhost:" + platformGrpcPort,
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
            "spring.security.oauth2.client.registration.network.client-secret=" + CLIENT_SECRET,
            // The Network writes its Clearing File into the platform's drop folder, only when the
            // demo asks: not at midnight.
            "simulator.network.drop-folder=" + dropFolder,
            "simulator.network.clearing-file-cron=-",
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
  }

  @Test
  // The demo itself waits up to 30 seconds for the worker to pay the Invoice.
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void theDemoInvoiceIsPaidGlobexsChargeApprovedAndItsClearingImportedFromTheNetworksFile()
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

    var imported =
        RestClient.create()
            .post()
            .uri("http://localhost:{port}/demo/clearing-file", simulatorPort)
            .retrieve()
            .body(String.class);

    // The Charge's Clearing posted (importedTotal), and the Network's trailer settles exactly that.
    assertThat(new JsonContent(imported))
        .isLenientlyEqualTo(
            """
            {
              "status": "IMPORTED",
              "recordCount": 1,
              "settlement": {
                "amount": {"minorUnits": 1240000, "currency": "USD"},
                "importedTotal": {"minorUnits": 1240000, "currency": "USD"},
                "matched": true
              }
            }
            """);
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
