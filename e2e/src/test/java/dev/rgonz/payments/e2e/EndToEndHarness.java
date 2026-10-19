package dev.rgonz.payments.e2e;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import dev.rgonz.payments.platform.PlatformApplication;
import dev.rgonz.payments.simulator.SimulatorApplication;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.TestSocketUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * What every end-to-end test runs against: real PostgreSQL, Kafka and Keycloak (the repository's
 * realm file), authorization-service from its executable jar in its own JVM against its own
 * PostgreSQL, and the platform and the simulator in this JVM. Each test class gets its own
 * containers and apps. The platform can be stopped and started again against the same databases and
 * Kafka.
 */
@Testcontainers
abstract class EndToEndHarness {

  // TEST-ONLY client secret for every client in the realm file, not a secret.
  static final String CLIENT_SECRET = "test-only-secret";

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
  static ConfigurableApplicationContext platform;
  static ConfigurableApplicationContext simulator;
  static int platformPort;
  static int simulatorPort;
  private static int servicePort;
  // The Network link's gRPC server, off the fixed 9090 another build may be using.
  private static int serviceGrpcPort;

  @BeforeAll
  static void startTheApps() {
    platformPort = TestSocketUtils.findAvailableTcpPort();
    simulatorPort = TestSocketUtils.findAvailableTcpPort();
    // Before the platform: either may start first, as each declares both topics.
    authorizationService = startAuthorizationService(issuerUri());
    startPlatform();
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
                + issuerUri()
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

  /** Starts the platform on its port, against the same database, Kafka and Keycloak each time. */
  static void startPlatform() {
    platform =
        start(
            PlatformApplication.class,
            "server.port=" + platformPort,
            "platform.public-url=http://localhost:" + platformPort,
            "spring.datasource.url=" + postgres.getJdbcUrl(),
            "spring.datasource.username=" + postgres.getUsername(),
            "spring.datasource.password=" + postgres.getPassword(),
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + issuerUri(),
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
  }

  private static String issuerUri() {
    return keycloak.getAuthServerUrl() + "/realms/payments";
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
              // Appended: each test class starts its own service.
              .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
              .start();
      awaitHealthy(process, log);
      return process;
    } catch (IOException e) {
      throw new IllegalStateException("authorization-service didn't start", e);
    }
  }

  private static void awaitHealthy(Process process, Path log) {
    var deadline = Instant.now().plus(Duration.ofSeconds(60));
    try {
      while (Instant.now().isBefore(deadline)) {
        if (!process.isAlive()) {
          throw new IllegalStateException("authorization-service stopped; see " + log);
        }
        try {
          if (healthStatus(servicePort) == 200) {
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

  /**
   * The HTTP status of the app's health on the port; throws {@link java.net.ConnectException} while
   * nothing listens there.
   */
  static int healthStatus(int port) throws IOException, InterruptedException {
    try (var http = HttpClient.newHttpClient()) {
      return http.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                  .build(),
              HttpResponse.BodyHandlers.discarding())
          .statusCode();
    }
  }

  // Both apps' jars hold an application.properties, and classpath:application.properties finds
  // only the first one on the classpath, for both apps: each gets the one beside its own classes.
  private static String ownApplicationProperties(Class<?> application) {
    var location = application.getProtectionDomain().getCodeSource().getLocation().toString();
    return location.endsWith(".jar")
        ? "jar:" + location + "!/application.properties"
        : location + "application.properties";
  }

  /**
   * The platform's database, read straight from its container: it answers whether the platform is
   * running or not.
   */
  static JdbcClient platformDatabase() {
    return JdbcClient.create(
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
  }

  @AfterAll
  static void stopTheApps() {
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

  // The platform's Authorization Copy holds the service's approved Authorization with this code,
  // and its card moved to AUTHORIZED in the same transaction. One module's schema per query
  // (ADR-0009): the copy's, then cards'.
  static boolean platformHasApprovedAuthorization(String authorizationCode) {
    var jdbc = platformDatabase();
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
}
