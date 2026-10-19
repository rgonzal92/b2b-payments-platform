package dev.rgonz.payments.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

import dev.rgonz.payments.contracts.network.AuthorizationRequest;
import dev.rgonz.payments.contracts.network.AuthorizationResponse;
import dev.rgonz.payments.contracts.network.IssuerAuthorizationGrpc.IssuerAuthorizationBlockingStub;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.client.RestClient;

/**
 * The force M5 exists for (ADR-0016): with the platform stopped, authorization-service still
 * approves Charges on the cards it already has on file, and once the platform starts again its
 * Authorization Copy catches up from Kafka. The platform issues a few cards through the simulator's
 * load-test seeding, which waits until each Payment completes, so each card is on file with the
 * service. The Network charges one card first, which opens its gRPC channel and gets its token.
 * Then the platform's application context is closed, its port refusing connections, and the Network
 * charges every other card over gRPC, signed in as the network client, within its own one-second
 * deadline. The platform's database doesn't hold those Authorizations yet. Last, a new platform
 * starts against the same database and Kafka, and its copy picks up every approved Authorization
 * the service published.
 */
class PlatformStoppedEndToEndTests extends EndToEndHarness {

  private static final int CARDS = 4;
  // The Network's own deadline (ADR-0016; the simulator's authorization-deadline): it declines a
  // Charge the Issuer hasn't answered by then with 91.
  private static final Duration NETWORK_DEADLINE = Duration.ofSeconds(1);

  @Test
  // Seeding takes seconds, as does each start of the platform.
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  void theServiceApprovesChargesWhileThePlatformIsStoppedAndThePlatformCatchesUpOnRestart()
      throws IOException {
    RestClient.create()
        .post()
        .uri("http://localhost:{port}/demo/load-test-cards?count={count}", simulatorPort, CARDS)
        .retrieve()
        .toBodilessEntity();
    var cards =
        Files.readAllLines(loadTestFolder.resolve("cards.csv")).stream()
            .skip(1)
            .map(Card::parse)
            .toList();
    assertThat(cards).hasSize(CARDS);
    var network = simulator.getBean(IssuerAuthorizationBlockingStub.class);
    // The first Charge connects the channel and fetches the Network's token, without the deadline.
    var warmUp = network.authorize(charge("WARM-UP-0001", cards.getFirst()));
    assertThat(warmUp.getResponseCode()).isEqualTo("00");

    platform.close();
    assertThatExceptionOfType(ConnectException.class).isThrownBy(() -> healthStatus(platformPort));

    var requestIds = new ArrayList<String>();
    var answers = new ArrayList<AuthorizationResponse>(List.of(warmUp));
    for (var card : cards.subList(1, CARDS)) {
      var requestId = "STOPPED-%04d".formatted(requestIds.size() + 1);
      requestIds.add(requestId);
      answers.add(network.withDeadlineAfter(NETWORK_DEADLINE).authorize(charge(requestId, card)));
    }
    assertThat(answers).extracting(AuthorizationResponse::getResponseCode).containsOnly("00");
    var authorizationCodes =
        answers.stream().map(AuthorizationResponse::getAuthorizationCode).toList();
    assertThat(authorizationCodes).allSatisfy(code -> assertThat(code).hasSize(6));
    // Read straight from the platform's database while it is down: its copy doesn't hold them yet.
    assertThat(copiedAuthorizations(requestIds)).isZero();

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

  // How many of these request IDs the platform's Authorization Copy holds: its schema only
  // (ADR-0009).
  private static long copiedAuthorizations(List<String> requestIds) {
    return platformDatabase()
        .sql("select count(*) from \"authorization\".authorizations where request_id in (:ids)")
        .param("ids", requestIds)
        .query(Long.class)
        .single();
  }

  // A Charge for the card's whole amount under the request ID, with only the fields the service
  // reads.
  private static AuthorizationRequest charge(String requestId, Card card) {
    return AuthorizationRequest.newBuilder()
        .setRetrievalReferenceNumber(requestId)
        .setPrimaryAccountNumber(card.pan())
        .setSecurityCode(card.securityCode())
        .setExpirationDate(card.expiry())
        .setAmountMinor(card.amountMinorUnits())
        .setCurrencyCode(card.currency())
        .setCardAcceptorId(card.acceptorId())
        .build();
  }

  /** One line of the simulator's cards file; its expiry as YYMM. */
  private record Card(
      String pan,
      String securityCode,
      String expiry,
      long amountMinorUnits,
      String currency,
      String acceptorId) {

    // pan,securityCode,expiry,amountMinorUnits,currency,acceptorId: no field holds a comma.
    static Card parse(String line) {
      var field = line.split(",");
      return new Card(field[0], field[1], field[2], Long.parseLong(field[3]), field[4], field[5]);
    }
  }
}
