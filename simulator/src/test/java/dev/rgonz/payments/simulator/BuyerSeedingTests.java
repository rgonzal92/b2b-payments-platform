package dev.rgonz.payments.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The simulator creates Acme, then Initech, and gives each a Credit Line at startup, signed in as
 * Issuer staff, against a stub Keycloak and platform.
 */
@SpringBootTest(properties = "simulator.seed.enabled=true")
class BuyerSeedingTests {

  record Recorded(String method, String path, String authorization, String body) {}

  static final List<Recorded> platformRequests = new CopyOnWriteArrayList<>();
  static final HttpServer stub = startStub();

  @DynamicPropertySource
  static void pointAtStub(DynamicPropertyRegistry registry) {
    var base = "http://localhost:" + stub.getAddress().getPort();
    registry.add(
        "spring.security.oauth2.client.provider.keycloak.token-uri", () -> base + "/token");
    registry.add(
        "spring.security.oauth2.client.registration.issuer-admin.client-secret", () -> "test");
    registry.add("simulator.platform-url", () -> base);
  }

  @AfterAll
  static void stopStub() {
    stub.stop(0);
  }

  @Test
  void createsAcmeAsIssuerStaff() {
    var put = platformRequests.getFirst();
    assertThat(put.method()).isEqualTo("PUT");
    assertThat(put.path()).isEqualTo("/v1/buyers/dc9a9857-e40f-4d50-9ee3-879f0c7472c5");
    assertThat(put.authorization()).isEqualTo("Bearer stub-token");
    assertThat(put.body()).contains("\"name\":\"Acme\"").contains("\"currency\":\"USD\"");
  }

  @Test
  void thenGivesAcmeACreditLine() {
    var put = platformRequests.get(1);
    assertThat(put.method()).isEqualTo("PUT");
    assertThat(put.path()).isEqualTo("/v1/buyers/dc9a9857-e40f-4d50-9ee3-879f0c7472c5/credit-line");
    assertThat(put.authorization()).isEqualTo("Bearer stub-token");
    assertThat(put.body()).contains("\"minorUnits\":10000000").contains("\"currency\":\"USD\"");
  }

  @Test
  void thenCreatesInitechWithAnApprovalThresholdOf100Dollars() {
    assertThat(platformRequests).hasSize(4);
    var put = platformRequests.get(2);
    assertThat(put.method()).isEqualTo("PUT");
    assertThat(put.path()).isEqualTo("/v1/buyers/13542409-3211-4b3d-8ba0-3762801cffcd");
    assertThat(put.authorization()).isEqualTo("Bearer stub-token");
    assertThat(put.body())
        .contains("\"name\":\"Initech\"")
        .contains("\"approvalThreshold\":{\"minorUnits\":10000,\"currency\":\"USD\"}");
  }

  @Test
  void andGivesInitechAMillionDollarCreditLine() {
    var put = platformRequests.get(3);
    assertThat(put.method()).isEqualTo("PUT");
    assertThat(put.path()).isEqualTo("/v1/buyers/13542409-3211-4b3d-8ba0-3762801cffcd/credit-line");
    assertThat(put.authorization()).isEqualTo("Bearer stub-token");
    assertThat(put.body()).contains("\"minorUnits\":100000000").contains("\"currency\":\"USD\"");
  }

  private static HttpServer startStub() {
    try {
      var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      server.createContext(
          "/token",
          exchange ->
              respond(
                  exchange,
                  200,
                  """
                  {"access_token": "stub-token", "token_type": "Bearer", "expires_in": 300}
                  """));
      server.createContext(
          "/v1/buyers/",
          exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var path = exchange.getRequestURI().getPath();
            platformRequests.add(
                new Recorded(
                    exchange.getRequestMethod(),
                    path,
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    body));
            // A Credit Line comes back as sent; a Buyer comes back with its ID.
            respond(
                exchange,
                201,
                path.endsWith("/credit-line")
                    ? body
                    : body.replaceFirst(
                        "\\{", "{\"id\": \"" + path.substring("/v1/buyers/".length()) + "\", "));
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void respond(HttpExchange exchange, int status, String json) throws IOException {
    var bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
