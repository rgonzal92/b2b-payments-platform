package dev.rgonz.payments.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Acme's ERP submits Invoices signed in as acme-erp, against a stub Keycloak and platform. */
@SpringBootTest(properties = "simulator.seed.enabled=false")
class AcmeErpTests {

  record Recorded(
      String method, String path, String authorization, String idempotencyKey, String body) {}

  static final List<Recorded> platformRequests = new CopyOnWriteArrayList<>();
  static final HttpServer stub = startStub();

  @DynamicPropertySource
  static void pointAtStub(DynamicPropertyRegistry registry) {
    var base = "http://localhost:" + stub.getAddress().getPort();
    registry.add(
        "spring.security.oauth2.client.provider.keycloak.token-uri", () -> base + "/token");
    registry.add("spring.security.oauth2.client.registration.acme-erp.client-secret", () -> "test");
    registry.add("simulator.platform-url", () -> base);
  }

  @AfterAll
  static void stopStub() {
    stub.stop(0);
  }

  @Autowired AcmeErp acmeErp;

  @Test
  void submitsInvoicesAsAcmesErpEachUnderANewIdempotencyKey() {
    acmeErp.submitInvoice();
    acmeErp.submitInvoice();

    assertThat(platformRequests).hasSize(2);
    var first = platformRequests.getFirst();
    assertThat(first.method()).isEqualTo("POST");
    assertThat(first.path()).isEqualTo("/v1/invoices");
    assertThat(first.authorization()).isEqualTo("Bearer token-for-acme-erp");
    assertThat(first.idempotencyKey()).isNotBlank();
    assertThat(first.idempotencyKey()).isNotEqualTo(platformRequests.getLast().idempotencyKey());
    assertThat(first.body())
        .contains("\"taxId\":\"12-3456789\"")
        .contains("\"remittanceAddress\":\"http://localhost:8081/globex/remittance-advice\"")
        .contains("\"minorUnits\":1240000")
        .contains("\"currency\":\"USD\"")
        .contains("\"invoiceNumber\":\"INV-");
  }

  private static HttpServer startStub() {
    try {
      var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      // Answers with a token naming the client that asked, so the test sees which one signed in.
      server.createContext(
          "/token",
          exchange -> {
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
          });
      server.createContext(
          "/v1/invoices",
          exchange -> {
            var headers = exchange.getRequestHeaders();
            platformRequests.add(
                new Recorded(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    headers.getFirst("Authorization"),
                    headers.getFirst("Idempotency-Key"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            respond(
                exchange,
                201,
                """
                {
                  "id": "6f1c2a3e-9a51-4c43-a0f2-0d5e8f3b7c11",
                  "status": "RECEIVED",
                  "supplierId": "0b7e7c55-3a9d-4f6e-8b21-5c4d3e2f1a00",
                  "supplier": {
                    "name": "Globex",
                    "taxId": "12-3456789",
                    "buyerReference": "GLOBEX-01",
                    "remittanceAddress": "http://localhost:8081/globex/remittance-advice"
                  },
                  "invoiceNumber": "INV-1",
                  "amount": {"minorUnits": 1240000, "currency": "USD"},
                  "dueDate": "2026-11-30"
                }
                """);
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
