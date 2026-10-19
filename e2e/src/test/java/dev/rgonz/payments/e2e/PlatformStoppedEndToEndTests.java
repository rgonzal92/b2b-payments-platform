package dev.rgonz.payments.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

import dev.rgonz.payments.contracts.network.AuthorizationRequest;
import dev.rgonz.payments.contracts.network.AuthorizationResponse;
import dev.rgonz.payments.contracts.network.IssuerAuthorizationGrpc.IssuerAuthorizationBlockingStub;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.client.RestClient;

/**
 * The force M5 exists for (ADR-0016): with the platform stopped, authorization-service still
 * approves Charges on the cards it already has on file, and once the platform starts again its
 * Authorization Copy catches up from Kafka. The platform issues a few cards through the simulator's
 * load-test seeding, which waits until each Payment completes, so each card is on file with the
 * service. Then the platform's application context is closed, its port refusing connections, and
 * the Network charges every card over gRPC, signed in as the network client. Last, a new platform
 * starts against the same database and Kafka, and its copy picks up every approved Authorization
 * the service published while it was down.
 */
class PlatformStoppedEndToEndTests extends EndToEndHarness {

  private static final int CARDS = 3;

  @Test
  // Seeding takes seconds, as does each start of the platform.
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  void theServiceApprovesChargesWhileThePlatformIsStoppedAndThePlatformCatchesUpOnRestart()
      throws IOException, InterruptedException {
    RestClient.create()
        .post()
        .uri("http://localhost:{port}/demo/load-test-cards?count={count}", simulatorPort, CARDS)
        .retrieve()
        .toBodilessEntity();
    // The header, then pan,securityCode,expiry,amountMinorUnits,currency,acceptorId per card.
    var cards = Files.readAllLines(loadTestFolder.resolve("cards.csv")).stream().skip(1).toList();
    assertThat(cards).hasSize(CARDS);

    platform.close();
    assertThatExceptionOfType(ConnectException.class)
        .isThrownBy(PlatformStoppedEndToEndTests::askThePlatformForItsHealth);

    var network = simulator.getBean(IssuerAuthorizationBlockingStub.class);
    var answers =
        IntStream.range(0, cards.size())
            .mapToObj(
                n ->
                    network
                        .withDeadlineAfter(Duration.ofSeconds(5))
                        .authorize(charge("STOPPED-%05d".formatted(n), cards.get(n))))
            .toList();
    assertThat(answers).extracting(AuthorizationResponse::getResponseCode).containsOnly("00");
    List<String> authorizationCodes =
        answers.stream().map(AuthorizationResponse::getAuthorizationCode).toList();
    assertThat(authorizationCodes).allSatisfy(code -> assertThat(code).hasSize(6));

    startPlatform();

    // Each approved Authorization reaches the copy from the authorizations topic, and its card
    // moves to AUTHORIZED.
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () ->
                authorizationCodes.stream()
                    .allMatch(EndToEndHarness::platformHasApprovedAuthorization));
  }

  // A Charge for the card's whole amount under the request ID, with only the fields the service
  // reads.
  private static AuthorizationRequest charge(String requestId, String card) {
    var field = card.split(",");
    return AuthorizationRequest.newBuilder()
        .setRetrievalReferenceNumber(requestId)
        .setPrimaryAccountNumber(field[0])
        .setSecurityCode(field[1])
        .setExpirationDate(field[2])
        .setAmountMinor(Long.parseLong(field[3]))
        .setCurrencyCode(field[4])
        .setCardAcceptorId(field[5])
        .build();
  }

  // Throws ConnectException while nothing listens on the platform's port.
  private static void askThePlatformForItsHealth() throws IOException, InterruptedException {
    try (var http = HttpClient.newHttpClient()) {
      http.send(
          HttpRequest.newBuilder(
                  URI.create("http://localhost:" + platformPort + "/actuator/health"))
              .build(),
          HttpResponse.BodyHandlers.discarding());
    }
  }
}
