package dev.rgonz.payments.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Acme's demo Approvers approve Invoices, each signed in as its own client, against stubs. */
@SpringBootTest(properties = "simulator.seed.enabled=false")
class AcmeApproversTests {

  record Recorded(String method, String path, String authorization) {}

  static final List<Recorded> platformRequests = new CopyOnWriteArrayList<>();
  static final HttpServer stub = startStub();

  @DynamicPropertySource
  static void pointAtStub(DynamicPropertyRegistry registry) {
    var base = "http://localhost:" + stub.getAddress().getPort();
    registry.add(
        "spring.security.oauth2.client.provider.keycloak.token-uri", () -> base + "/token");
    registry.add(
        "spring.security.oauth2.client.registration.acme-approver-1.client-secret", () -> "test");
    registry.add(
        "spring.security.oauth2.client.registration.acme-approver-2.client-secret", () -> "test");
    registry.add("simulator.platform-url", () -> base);
  }

  @AfterAll
  static void stopStub() {
    stub.stop(0);
  }

  @Autowired AcmeApprovers acmeApprovers;

  @Test
  void eachDemoApproverApprovesSignedInAsItsOwnClient() {
    var invoiceId = UUID.fromString("6f1c2a3e-9a51-4c43-a0f2-0d5e8f3b7c11");

    acmeApprovers.approve(invoiceId, 1);
    acmeApprovers.approve(invoiceId, 2);

    var approval = "/v1/invoices/6f1c2a3e-9a51-4c43-a0f2-0d5e8f3b7c11/approval";
    assertThat(platformRequests)
        .containsExactly(
            new Recorded("POST", approval, "Bearer token-for-acme-approver-1"),
            new Recorded("POST", approval, "Bearer token-for-acme-approver-2"));
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
                """
                {"access_token": "token-for-%s", "token_type": "Bearer", "expires_in": 300}
                """
                    .formatted(clientId));
          });
      server.createContext(
          "/v1/invoices/",
          exchange -> {
            platformRequests.add(
                new Recorded(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization")));
            respond(
                exchange,
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
                  "dueDate": "2026-11-30",
                  "decisions": [
                    {
                      "decision": "APPROVED",
                      "approverId": "5b0c7f0e-2d1a-4c3b-9e8f-7a6b5c4d3e2f",
                      "approverName": "service-account-acme-approver-1",
                      "decidedAt": "2026-10-15T12:00:00Z"
                    }
                  ]
                }
                """);
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void respond(HttpExchange exchange, String json) throws IOException {
    var bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
