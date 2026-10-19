package dev.rgonz.payments.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

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
      // Same image as compose.yaml and the platform's TestKeycloak: change them together.
      new KeycloakContainer("quay.io/keycloak/keycloak:26.8.1")
          .withRealmImportFile("/keycloak/payments-realm.json")
          .withEnv("ACME_ERP_SECRET", CLIENT_SECRET)
          .withEnv("NETWORK_CLIENT_SECRET", CLIENT_SECRET);

  /** The Authorization header of every request that reached the stub platform, null for none. */
  static final List<String> platformSaw = new CopyOnWriteArrayList<>();

  static final HttpServer platform = startStubPlatform();

  static {
    keycloak.start();
  }

  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", GatewayTests::issuerUri);
    registry.add(
        "gateway.platform-uri", () -> "http://localhost:" + platform.getAddress().getPort());
  }

  @AfterAll
  static void stopStub() {
    platform.stop(0);
  }

  @Autowired WebTestClient http;

  @BeforeEach
  void forgetRequests() {
    platformSaw.clear();
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

  @Test
  void theGatewaysHealthIsOpen() {
    http.get().uri("/actuator/health").exchange().expectStatus().isOk();
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
  // to /v1/invoices the 403 the platform gives a token without the erp role.
  private static HttpServer startStubPlatform() {
    try {
      var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      server.createContext(
          "/v1/",
          exchange -> {
            platformSaw.add(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
            var path = exchange.getRequestURI().getPath();
            int status;
            String body;
            if (path.equals("/v1/remittance-links/" + LINK_TOKEN)) {
              status = 200;
              body =
                  """
                  {"invoiceNumber": "INV-42"}""";
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
                    status == 200
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
}
