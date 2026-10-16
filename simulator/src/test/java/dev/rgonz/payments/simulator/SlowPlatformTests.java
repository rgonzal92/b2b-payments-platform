package dev.rgonz.payments.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import dev.rgonz.payments.contracts.client.model.Money;
import dev.rgonz.payments.simulator.GlobexAcquirer.Authorization;
import dev.rgonz.payments.simulator.GlobexSupplier.ReceivedCard;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The Network doesn't wait on a slow Issuer: when the platform doesn't answer an Authorization
 * within the Network's deadline, Globex's Charge is declined {@code 91} (issuer unavailable) and
 * the Network sends a Reversal for it in the background, against a stub Keycloak and a stub {@code
 * IssuerAuthorization} server that answers late.
 */
@SpringBootTest(
    properties = {
      "simulator.seed.enabled=false",
      // Short, so the test runs fast; the stub answers well after it.
      "simulator.network.authorization-deadline=200ms"
    })
class SlowPlatformTests {

  static final StubIssuer issuer = new StubIssuer();
  static final HttpServer keycloak = startStubKeycloak();

  @DynamicPropertySource
  static void pointAtStubs(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.security.oauth2.client.provider.keycloak.token-uri",
        () -> "http://localhost:" + keycloak.getAddress().getPort() + "/token");
    registry.add("spring.security.oauth2.client.registration.network.client-secret", () -> "test");
    registry.add("simulator.platform-grpc-address", issuer::address);
  }

  @AfterAll
  static void stopStubs() {
    issuer.stop();
    keycloak.stop(0);
  }

  // A deadline counts from when it's set, so the first call's connection and the Network's sign-in
  // count against it: make that call first, so the test's deadline measures only the stub's answer.
  @BeforeAll
  static void connectAndSignIn(@Autowired GlobexAcquirer acquirer) {
    acquirer.charge(card());
  }

  @Autowired GlobexAcquirer acquirer;

  @BeforeEach
  void resetIssuer() {
    issuer.reset();
  }

  @Test
  void aChargeAnsweredAfterTheDeadlineIsDeclined91AndReversedUnderItsRetrievalReferenceNumber() {
    issuer.answerAfter(Duration.ofSeconds(2));

    var authorization = acquirer.charge(card());

    assertThat(authorization).isEqualTo(new Authorization(false, "91", null));
    var requestId = issuer.received.getFirst().request().getRetrievalReferenceNumber();
    // The Network sends the Reversal in the background, after Globex has its decline.
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(issuer.reversals)
                    .singleElement()
                    .satisfies(
                        reversal -> {
                          assertThat(reversal.request().getRetrievalReferenceNumber())
                              .isEqualTo(requestId);
                          assertThat(reversal.request().getSystemTraceAuditNumber())
                              .matches("\\d{6}");
                          assertThat(reversal.request().hasTransmittedAt()).isTrue();
                          assertThat(reversal.authorization())
                              .isEqualTo("Bearer token-for-network");
                        }));
  }

  private static ReceivedCard card() {
    return new ReceivedCard(
        "INV-42",
        "9900001234567899",
        "321",
        OffsetDateTime.parse("2026-11-15T12:00:00Z"),
        new Money().minorUnits(1_240_000L).currency(Money.CurrencyEnum.USD));
  }

  // Answers every client with a token for the network client.
  private static HttpServer startStubKeycloak() {
    try {
      var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      server.createContext(
          "/token",
          exchange -> {
            var bytes =
                """
                {"access_token": "token-for-network", "token_type": "Bearer", "expires_in": 300}
                """
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
