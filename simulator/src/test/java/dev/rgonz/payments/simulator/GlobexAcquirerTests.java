package dev.rgonz.payments.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.rgonz.payments.contracts.client.model.Money;
import dev.rgonz.payments.simulator.GlobexAcquirer.Authorization;
import dev.rgonz.payments.simulator.GlobexSupplier.ReceivedCard;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Globex's Acquirer turns Globex's Charge into an Authorization request, and the Network sends it
 * to the platform over gRPC, signed in as the network client: against a stub Keycloak and a stub
 * {@code IssuerAuthorization} server.
 */
@SpringBootTest(properties = "simulator.seed.enabled=false")
@ExtendWith(OutputCaptureExtension.class)
class GlobexAcquirerTests {

  private static final String PAN = "9900001234567899";
  private static final String SECURITY_CODE = "321";

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

  @Autowired GlobexAcquirer acquirer;

  @BeforeEach
  void resetIssuer() {
    issuer.reset();
  }

  @Test
  void chargesTheCardWithGlobexsAcceptorIdSignedInAsTheNetworkAndReturnsTheIssuersAnswer() {
    issuer.answerWith("00", "AB12CD");
    // 21:00 on 30 November in New York is 1 December in UTC, the card's expiry month.
    var card = card(OffsetDateTime.parse("2026-11-30T21:00:00-05:00"));

    var authorization = acquirer.charge(card);

    assertThat(authorization).isEqualTo(new Authorization(true, "00", "AB12CD"));
    assertThat(issuer.received).hasSize(1);
    var received = issuer.received.getFirst();
    assertThat(received.authorization()).isEqualTo("Bearer token-for-network");
    var request = received.request();
    assertThat(request.getCardAcceptorId()).isEqualTo("GLOBEX000000001");
    assertThat(request.getCardAcceptorName()).isEqualTo("Globex");
    assertThat(request.getPrimaryAccountNumber()).isEqualTo(PAN);
    assertThat(request.getSecurityCode()).isEqualTo(SECURITY_CODE);
    assertThat(request.getExpirationDate()).isEqualTo("2612");
    assertThat(request.getAmountMinor()).isEqualTo(1_240_000);
    assertThat(request.getCurrencyCode()).isEqualTo("USD");
    assertThat(request.getRetrievalReferenceNumber()).matches("[0-9A-Z]{12}");
    assertThat(request.getSystemTraceAuditNumber()).matches("\\d{6}");
    assertThat(request.getMerchantCategoryCode()).matches("\\d{4}");
    assertThat(request.hasTransmittedAt()).isTrue();
  }

  @Test
  void aDeclineComesBackWithItsResponseCodeAndNoAuthorizationCode() {
    // proto3 has no null: a decline's authorization code arrives as "".
    issuer.answerWith("57", "");

    var authorization = acquirer.charge(card(OffsetDateTime.parse("2026-11-15T12:00:00Z")));

    assertThat(authorization).isEqualTo(new Authorization(false, "57", null));
  }

  @Test
  void eachChargeGetsAFreshRetrievalReferenceNumberAndTraceNumber() {
    var card = card(OffsetDateTime.parse("2026-11-15T12:00:00Z"));

    acquirer.charge(card);
    acquirer.charge(card);

    assertThat(issuer.received)
        .extracting(received -> received.request().getRetrievalReferenceNumber())
        .doesNotHaveDuplicates()
        .allMatch(number -> number.matches("[0-9A-Z]{12}"));
    assertThat(issuer.received)
        .extracting(received -> received.request().getSystemTraceAuditNumber())
        .doesNotHaveDuplicates()
        .allMatch(number -> number.matches("\\d{6}"));
  }

  @Test
  void neitherTheCardRecordNorAChargesLogsShowThePanOrSecurityCode(CapturedOutput output) {
    var card = card(OffsetDateTime.parse("2026-11-15T12:00:00Z"));

    acquirer.charge(card);

    var requestId = issuer.received.getFirst().request().getRetrievalReferenceNumber();
    assertThat(card.toString())
        .contains("7899")
        .doesNotContain(PAN)
        .doesNotContainPattern(securityCodeAfterItsName());
    assertThat(output)
        .contains(requestId)
        .contains("7899")
        .doesNotContain(PAN)
        .doesNotContainPattern(securityCodeAfterItsName());
  }

  // Three digits alone turn up in any log (timestamps, ports), so this looks for the security code
  // where a logged request or record would put it: after its name.
  private static String securityCodeAfterItsName() {
    return "(?i)security.?code\\W{0,5}" + SECURITY_CODE;
  }

  private static ReceivedCard card(OffsetDateTime expiresAt) {
    return new ReceivedCard(
        "INV-42",
        PAN,
        SECURITY_CODE,
        expiresAt,
        new Money().minorUnits(1_240_000L).currency(Money.CurrencyEnum.USD));
  }

  // Answers with a token naming the client that asked, so the test sees which one signed in.
  private static HttpServer startStubKeycloak() {
    try {
      var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      server.createContext(
          "/token",
          exchange -> {
            var basic = exchange.getRequestHeaders().getFirst("Authorization").substring(6);
            var clientId =
                new String(Base64.getDecoder().decode(basic), StandardCharsets.UTF_8).split(":")[0];
            var bytes =
                """
                {"access_token": "token-for-%s", "token_type": "Bearer", "expires_in": 300}
                """
                    .formatted(clientId)
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
