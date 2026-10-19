package dev.rgonz.payments.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.JWTParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;

/**
 * The Gateway between a real Keycloak (the repository's realm file) and a stub platform that
 * records what reaches it and answers as the platform would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayTests {

  // TEST-ONLY client secret for the two clients these tests sign in as, not a secret.
  private static final String CLIENT_SECRET = "test-only-secret";

  // A made-up link token, low in entropy so secret scanners pass it by.
  private static final String LINK_TOKEN = "test-only-link-token";

  // Started once and never stopped: Ryuk, Testcontainers' clean-up container, removes it once the
  // test JVM exits.
  private static final KeycloakContainer keycloak =
      // Same image as compose.yaml, the platform's TestKeycloak and e2e's EndToEndHarness: change
      // all four together.
      new KeycloakContainer("quay.io/keycloak/keycloak:26.8.1")
          .withRealmImportFile("/keycloak/payments-realm.json")
          .withEnv("ACME_ERP_SECRET", CLIENT_SECRET)
          .withEnv("NETWORK_CLIENT_SECRET", CLIENT_SECRET);

  // The rate limiter's store. Same image as compose.yaml and the chart's infra values: change all
  // three together.
  static final GenericContainer<?> redis =
      new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);

  /** The Authorization header of every request that reached the stub platform, null for none. */
  static final List<String> platformSaw = new CopyOnWriteArrayList<>();

  /** Every header of the last request that reached the stub platform. */
  static volatile Headers lastHeaders;

  static final HttpServer platform = startStubPlatform();

  static {
    keycloak.start();
    redis.start();
  }

  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", GatewayTests::issuerUri);
    registry.add(
        "gateway.platform-uri", () -> "http://localhost:" + platform.getAddress().getPort());
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
  }

  @AfterAll
  static void stopStub() {
    platform.stop(0);
  }

  @Autowired WebTestClient http;

  // Every test starts with full buckets.
  @BeforeEach
  void forgetRequests() throws Exception {
    platformSaw.clear();
    redis.execInContainer("redis-cli", "FLUSHALL");
  }

  @Test
  void aValidTokenReachesThePlatformWithTheSameAuthorizationHeader() {
    var authorization = "Bearer " + clientCredentialsToken("acme-erp");

    http.get()
        .uri("/v1/invoices?limit=10")
        .header(HttpHeaders.AUTHORIZATION, authorization)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .json(
            """
            {"items": []}""");

    assertThat(platformSaw).containsExactly(authorization);
  }

  @Test
  void aRequestWithoutATokenGetsTheGatewaysProblemDetails401() {
    http.get()
        .uri("/v1/invoices")
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        .expectHeader()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .json(
            """
            {"type": "about:blank", "title": "Unauthorized", "status": 401}""");

    assertThat(platformSaw).isEmpty();
  }

  @Test
  void anInvalidTokenGetsTheGatewaysProblemDetails401() {
    // A real token with its signature cut off.
    var token = clientCredentialsToken("acme-erp");
    var unsigned = token.substring(0, token.lastIndexOf('.') + 1);

    http.post()
        .uri("/v1/invoices")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unsigned)
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(401);

    assertThat(platformSaw).isEmpty();
  }

  @Test
  void theRemittanceLinkPassesWithoutAToken() {
    http.get()
        .uri("/v1/remittance-links/{token}", LINK_TOKEN)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.invoiceNumber")
        .isEqualTo("INV-42");

    assertThat(platformSaw).containsExactly((String) null);
  }

  @Test
  void aTokenWithThePlatformsRoleGetsThePlatformsAnswer() {
    http.post()
        .uri("/v1/invoices")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientCredentialsToken("acme-erp"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isCreated();
  }

  // The Gateway checks only that the token is valid: the Network's token has no platform role, so
  // the platform's own check refuses it, and its 403 comes back as it sent it.
  @Test
  void aValidTokenWithoutThePlatformsRoleGetsThePlatforms403() {
    var authorization = "Bearer " + clientCredentialsToken("network");

    http.post()
        .uri("/v1/invoices")
        .header(HttpHeaders.AUTHORIZATION, authorization)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .json(
            """
            {"title": "Forbidden", "status": 403, "detail": "Your role doesn't allow this operation."}""");

    assertThat(platformSaw).containsExactly(authorization);
  }

  // With no trusted proxies configured, the Gateway passes on no client-sent forwarding header and
  // adds none, drops X-Application-Context, and the platform sees its own address as Host.
  @Test
  void thePlatformSeesItsOwnHostAndNoForwardingHeaders() {
    http.get()
        .uri("/v1/invoices")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientCredentialsToken("acme-erp"))
        .header("X-Forwarded-For", "203.0.113.9")
        .header("Forwarded", "for=203.0.113.9")
        .header("X-Application-Context", "test")
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(lastHeaders.getFirst(HttpHeaders.HOST))
        .isEqualTo("localhost:" + platform.getAddress().getPort());
    assertThat(lastHeaders.keySet())
        .map(String::toLowerCase)
        .noneMatch(
            name ->
                name.equals("forwarded")
                    || name.startsWith("x-forwarded-")
                    || name.equals("x-application-context"));
  }

  @Test
  void aPathNoRouteServesGetsTheGatewaysProblemDetails404() {
    http.get()
        .uri("/v2/invoices")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientCredentialsToken("acme-erp"))
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectHeader()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .json(
            """
            {"title": "Not Found", "status": 404}""");

    assertThat(platformSaw).isEmpty();
  }

  // M6 design, Rate limiting: each Keycloak client (the token's azp) gets bursts to 40 and 20 a
  // second after that. The bucket refills while the requests go out, so send until one is refused.
  @Test
  void aClientPastItsBurstGetsAProblemDetails429WithRetryAfterAndAnotherClientIsUnaffected() {
    var acme = "Bearer " + clientCredentialsToken("acme-erp");

    var refused = firstRefused(() -> get("/v1/invoices", acme), 200);

    assertThat(refused.passedBefore()).isGreaterThanOrEqualTo(40);
    assertTooManyRequests(refused.result());
    http.get()
        .uri("/v1/invoices")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientCredentialsToken("network"))
        .exchange()
        .expectStatus()
        .isOk();
  }

  // The remittance link has no token, so it is limited by the address the connection came from: 10
  // a minute. A forwarding header names no other client, since anyone can send one.
  @Test
  void theRemittanceLinkGets10AMinutePerClientIp() {
    var link = "/v1/remittance-links/" + LINK_TOKEN;
    for (var i = 0; i < 10; i++) {
      http.get().uri(link).exchange().expectStatus().isOk();
    }

    var eleventh =
        http.get()
            .uri(link)
            .header("X-Forwarded-For", "203.0.113.9")
            .exchange()
            .expectBody()
            .returnResult();

    assertTooManyRequests(eleventh);
    assertThat(platformSaw).hasSize(10);
    // A client's own limit is apart from the link's.
    assertThat(get("/v1/invoices", "Bearer " + clientCredentialsToken("acme-erp")).getStatus())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  void theGatewaysHealthIsOpen() {
    http.get().uri("/actuator/health").exchange().expectStatus().isOk();
  }

  // On Kubernetes Spring Boot would read the client's address from forwarding headers unless told
  // not to, and each forged X-Forwarded-For would get a fresh bucket.
  @Nested
  class OnKubernetes {

    @DynamicPropertySource
    static void kubernetes(DynamicPropertyRegistry registry) {
      registry.add("spring.main.cloud-platform", () -> "kubernetes");
    }

    @Autowired WebTestClient http;

    @Test
    void aForgedForwardingHeaderGetsNoFreshRemittanceLinkBucket() {
      var link = "/v1/remittance-links/" + LINK_TOKEN;
      for (var i = 1; i <= 10; i++) {
        http.get()
            .uri(link)
            .header("X-Forwarded-For", "203.0.113." + i)
            .exchange()
            .expectStatus()
            .isOk();
      }

      http.get()
          .uri(link)
          .header("X-Forwarded-For", "203.0.113.11")
          .exchange()
          .expectStatus()
          .isEqualTo(429);
    }
  }

  // The route points at a port nothing listens on, as when every platform pod is down.
  @Nested
  class WithThePlatformDown {

    @DynamicPropertySource
    static void nothingListening(DynamicPropertyRegistry registry) {
      var port = TestSocketUtils.findAvailableTcpPort();
      registry.add("gateway.platform-uri", () -> "http://localhost:" + port);
    }

    @Autowired WebTestClient http;

    @Test
    void aRequestGetsTheGatewaysProblemDetails502() {
      http.get()
          .uri("/v1/invoices")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientCredentialsToken("acme-erp"))
          .exchange()
          .expectStatus()
          .isEqualTo(502)
          .expectHeader()
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .expectBody()
          .json(
              """
              {"title": "Bad Gateway", "status": 502,
               "detail": "The platform can't be reached."}""");
    }
  }

  private EntityExchangeResult<byte[]> get(String path, String authorization) {
    var request = http.get().uri(path);
    if (authorization != null) {
      request.header(HttpHeaders.AUTHORIZATION, authorization);
    }
    return request.exchange().expectBody().returnResult();
  }

  record Refused(int passedBefore, EntityExchangeResult<byte[]> result) {}

  // Sends the request until the Gateway refuses one, at most max times.
  private static Refused firstRefused(Supplier<EntityExchangeResult<byte[]>> request, int max) {
    for (var passed = 0; passed < max; passed++) {
      var result = request.get();
      if (result.getStatus().value() != 200) {
        return new Refused(passed, result);
      }
    }
    throw new AssertionError("No request of " + max + " was refused");
  }

  private static void assertTooManyRequests(EntityExchangeResult<byte[]> result) {
    assertThat(result.getStatus().value()).isEqualTo(429);
    assertThat(result.getResponseHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER))
        .as("Retry-After in whole seconds")
        .matches("[1-9][0-9]*");
    assertThat(new String(result.getResponseBody(), StandardCharsets.UTF_8))
        .contains("\"title\":\"Too Many Requests\"")
        .contains("\"status\":429");
  }

  // ADR-0017: the limiter fails open. This Redis is the nested context's own, so stopping it leaves
  // the other tests theirs; the Gateway has connected to it before it stops, as in production.
  @Nested
  class WithRedisStopped {

    static final GenericContainer<?> doomedRedis =
        new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void ownRedis(DynamicPropertyRegistry registry) {
      doomedRedis.start();
      registry.add("spring.data.redis.port", () -> doomedRedis.getMappedPort(6379));
    }

    @Autowired WebTestClient http;

    @Autowired MeterRegistry meters;

    @Test
    void requestsStillReachThePlatformAndTheGatewayCountsThem() {
      var acme = "Bearer " + clientCredentialsToken("acme-erp");
      http.get()
          .uri("/v1/invoices")
          .header(HttpHeaders.AUTHORIZATION, acme)
          .exchange()
          .expectStatus()
          .isOk();

      doomedRedis.stop();

      var started = System.nanoTime();
      for (var i = 0; i < 3; i++) {
        http.get()
            .uri("/v1/invoices")
            .header(HttpHeaders.AUTHORIZATION, acme)
            .exchange()
            .expectStatus()
            .isOk();
        http.get().uri("/v1/remittance-links/{token}", LINK_TOKEN).exchange().expectStatus().isOk();
      }
      assertThat(Duration.ofNanos(System.nanoTime() - started))
          .as("six requests without Redis")
          .isLessThan(Duration.ofSeconds(1));
      assertThat(meters.get("gateway.rate.limiter.failed.open").counter().count()).isEqualTo(6);
    }
  }

  private static String issuerUri() {
    return keycloak.getAuthServerUrl() + "/realms/payments";
  }

  private static String clientCredentialsToken(String clientId) {
    var form = new LinkedMultiValueMap<String, String>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", clientId);
    form.add("client_secret", CLIENT_SECRET);
    Map<?, ?> token =
        RestClient.create()
            .post()
            .uri(issuerUri() + "/protocol/openid-connect/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(Map.class);
    return (String) token.get("access_token");
  }

  // Answers like the platform: the remittance link's details, an empty Invoice list, and for a POST
  // to /v1/invoices a 201 to a token with the erp role and the platform's 403 to any other.
  private static HttpServer startStubPlatform() {
    try {
      var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      server.createContext(
          "/v1/",
          exchange -> {
            lastHeaders = exchange.getRequestHeaders();
            platformSaw.add(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
            var path = exchange.getRequestURI().getPath();
            int status;
            String body;
            if (path.equals("/v1/remittance-links/" + LINK_TOKEN)) {
              status = 200;
              body =
                  """
                  {"invoiceNumber": "INV-42"}""";
            } else if (exchange.getRequestMethod().equals("POST") && hasErpRole(exchange)) {
              status = 201;
              body =
                  """
                  {"invoiceNumber": "INV-1"}""";
            } else if (exchange.getRequestMethod().equals("POST")) {
              status = 403;
              body =
                  """
                  {"type": "about:blank", "title": "Forbidden", "status": 403, \
                  "detail": "Your role doesn't allow this operation.", "instance": "/v1/invoices"}""";
            } else {
              status = 200;
              body =
                  """
                  {"items": []}""";
            }
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange
                .getResponseHeaders()
                .add(
                    HttpHeaders.CONTENT_TYPE,
                    status < 400
                        ? MediaType.APPLICATION_JSON_VALUE
                        : MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // The stub reads the token's roles without checking its signature: the Gateway already has.
  private static boolean hasErpRole(HttpExchange exchange) {
    var authorization = exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    try {
      var roles =
          JWTParser.parse(authorization.substring("Bearer ".length()))
              .getJWTClaimsSet()
              .getStringListClaim("roles");
      return roles != null && roles.contains("erp");
    } catch (ParseException e) {
      return false;
    }
  }
}
