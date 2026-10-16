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
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

/**
 * When the Network can't reach the platform at all, Globex's Charge is declined {@code 91} (issuer
 * unavailable) at once, without failing, and the Reversal the Network couldn't send is retried in
 * the background, then logged for follow-up by its retrieval reference number alone: against a stub
 * Keycloak and a gRPC address nothing listens on. The deadline stays at its default, so the call
 * ends {@code UNAVAILABLE} rather than timing out.
 */
@SpringBootTest(properties = "simulator.seed.enabled=false")
@ExtendWith(OutputCaptureExtension.class)
class UnreachablePlatformTests {

  private static final String PAN = "9900001234567899";
  private static final String SECURITY_CODE = "321";
  private static final String GAVE_UP = "couldn't reverse Authorization";

  static final HttpServer keycloak = startStubKeycloak();

  @DynamicPropertySource
  static void pointAtStubs(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.security.oauth2.client.provider.keycloak.token-uri",
        () -> "http://localhost:" + keycloak.getAddress().getPort() + "/token");
    registry.add("spring.security.oauth2.client.registration.network.client-secret", () -> "test");
    // A free port, so nothing answers there.
    var closedPort = TestSocketUtils.findAvailableTcpPort();
    registry.add("simulator.platform-grpc-address", () -> "localhost:" + closedPort);
  }

  @AfterAll
  static void stopStubs() {
    keycloak.stop(0);
  }

  @Autowired GlobexAcquirer acquirer;

  @Test
  void aChargeIsDeclined91AndTheReversalThatNeverArrivedIsLoggedByItsRetrievalReferenceNumber(
      CapturedOutput output) {
    var authorization = acquirer.charge(card());

    assertThat(authorization).isEqualTo(new Authorization(false, "91", null));
    // Globex has its decline while the Network is still trying to send the Reversal.
    assertThat(output.getAll()).doesNotContain(GAVE_UP);
    await().atMost(Duration.ofSeconds(10)).until(() -> output.getAll().contains(GAVE_UP));
    var followUp =
        Pattern.compile(GAVE_UP + " ([0-9A-Z]{12}) after 4 attempts \\(UNAVAILABLE\\)")
            .matcher(output.getAll());
    assertThat(followUp.find()).as("the log line asking for follow-up").isTrue();
    var requestId = followUp.group(1);
    assertThat(output)
        .contains("Globex's Charge " + requestId)
        .contains("no answer to Authorization " + requestId + " (UNAVAILABLE)")
        .doesNotContain(PAN)
        .doesNotContainPattern("(?i)security.?code\\W{0,5}" + SECURITY_CODE);
  }

  private static ReceivedCard card() {
    return new ReceivedCard(
        "INV-42",
        PAN,
        SECURITY_CODE,
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
