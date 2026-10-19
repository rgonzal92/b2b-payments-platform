package dev.rgonz.payments.platform.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.rgonz.payments.platform.TestAuthorizationsTopic;
import dev.rgonz.payments.platform.TestKafka;
import dev.rgonz.payments.platform.TestcontainersConfiguration;
import dev.rgonz.payments.platform.cards.CardExpiryJob;
import dev.rgonz.payments.platform.cards.Cards;
import dev.rgonz.payments.platform.cards.SingleUseCard;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The platform's copy of the Authorizations authorization-service answered, fed by its approved and
 * reversed Authorizations on a real Kafka's {@code authorizations} topic: each is stored for
 * Clearing to match, and the platform's card moves with it.
 */
@Import({TestcontainersConfiguration.class, TestKafka.class})
@SpringBootTest(
    properties = {
      "payables.payment-worker.enabled=false",
      "cards.expiry-job.enabled=false",
      "spring.kafka.listener.auto-startup=true"
    })
class AuthorizationCopyTests {

  private static final Money AMOUNT = new Money(1_240_000, Currency.getInstance("USD"));

  @Autowired ApprovedAuthorizations approvedAuthorizations;
  @Autowired Cards cards;
  @Autowired KafkaContainer kafka;
  @Autowired ApplicationContext context;

  private TestAuthorizationsTopic topic;

  @BeforeEach
  void connect() {
    topic = new TestAuthorizationsTopic(kafka);
  }

  @Test
  void anApprovedAuthorizationIsStoredAndMovesItsCardToAuthorized() throws Exception {
    var card = issuedCard();
    var requestId = newRequestId();

    send("AuthorizationApproved", requestId, card.cardToken());

    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> approvedAuthorizations.find(card.cardToken(), "ABC123").isPresent());
    assertThat(approvedAuthorizations.find(card.cardToken(), "ABC123"))
        .contains(new ApprovedAuthorization(requestId, AMOUNT, false));
    assertThat(statusOf(card)).isEqualTo(SingleUseCard.Status.AUTHORIZED);
  }

  @Test
  void aReversedAuthorizationIsMarkedReversedAndMovesItsCardBackToActive() throws Exception {
    var card = issuedCard();
    var requestId = newRequestId();

    send("AuthorizationApproved", requestId, card.cardToken());
    send("AuthorizationReversed", requestId, card.cardToken());

    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> statusOf(card) == SingleUseCard.Status.ACTIVE && isReversed(card));
    assertThat(approvedAuthorizations.find(card.cardToken(), "ABC123"))
        .contains(new ApprovedAuthorization(requestId, AMOUNT, true));
  }

  // Kafka redelivers events whose offsets weren't committed: the approved Authorization again
  // after its Reversal must not move the card back to AUTHORIZED.
  @Test
  void theSameEventsAgainChangeNothing() throws Exception {
    var card = issuedCard();
    var requestId = newRequestId();
    send("AuthorizationApproved", requestId, card.cardToken());
    send("AuthorizationReversed", requestId, card.cardToken());

    send("AuthorizationApproved", requestId, card.cardToken());
    send("AuthorizationReversed", requestId, card.cardToken());
    topic.awaitConsumed(AuthorizationEventsListener.GROUP_ID);

    assertThat(approvedAuthorizations.find(card.cardToken(), "ABC123"))
        .contains(new ApprovedAuthorization(requestId, AMOUNT, true));
    assertThat(statusOf(card)).isEqualTo(SingleUseCard.Status.ACTIVE);
  }

  // The platform expired the card on day 30 while the service approved a Charge on it: the card
  // stays EXPIRED, and its Clearing still finds the Authorization.
  @Test
  void anApprovedAuthorizationOnAnExpiredCardIsStoredAndTheCardStaysExpired() throws Exception {
    var card = issuedCard(Instant.parse("2020-01-01T00:00:00Z"));
    CardExpiryJob.runAt(context, card.expiresAt().plusSeconds(1));
    var requestId = newRequestId();

    send("AuthorizationApproved", requestId, card.cardToken());
    topic.awaitConsumed(AuthorizationEventsListener.GROUP_ID);

    assertThat(approvedAuthorizations.find(card.cardToken(), "ABC123"))
        .contains(new ApprovedAuthorization(requestId, AMOUNT, false));
    assertThat(statusOf(card)).isEqualTo(SingleUseCard.Status.EXPIRED);
  }

  @Test
  void aLateReversalOfAClearedCardLeavesItCleared() throws Exception {
    var card = issuedCard();
    var requestId = newRequestId();
    send("AuthorizationApproved", requestId, card.cardToken());
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> statusOf(card) == SingleUseCard.Status.AUTHORIZED);
    cards.clear(card.cardToken());

    send("AuthorizationReversed", requestId, card.cardToken());

    await().atMost(Duration.ofSeconds(30)).until(() -> isReversed(card));
    assertThat(statusOf(card)).isEqualTo(SingleUseCard.Status.CLEARED);
  }

  // The service sends both on the Card Token's partition, approved first, so this can't happen;
  // the copy still ends as if it had read them in order.
  @Test
  void aReversalReadBeforeItsApprovedAuthorizationLeavesTheCardActive() throws Exception {
    var card = issuedCard();
    var requestId = newRequestId();

    send("AuthorizationReversed", requestId, card.cardToken());
    send("AuthorizationApproved", requestId, card.cardToken());
    topic.awaitConsumed(AuthorizationEventsListener.GROUP_ID);

    assertThat(approvedAuthorizations.find(card.cardToken(), "ABC123"))
        .contains(new ApprovedAuthorization(requestId, AMOUNT, true));
    assertThat(statusOf(card)).isEqualTo(SingleUseCard.Status.ACTIVE);
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void anUnreadableMessageIsSkippedAndTheNextEventStillArrives(CapturedOutput output)
      throws Exception {
    var card = issuedCard();
    var cardToken = card.cardToken();

    // Same key, so the same partition: a stuck message would hold back the good one behind it.
    topic.send(cardToken, "AuthorizationApproved", "{not json");
    topic.send(cardToken, "AuthorizationApproved", "{\"requestId\": \"request-1\"}");
    topic.send(cardToken, "AuthorizationApproved", null);
    topic.send(cardToken, null, "{}");
    topic.send(cardToken, "CardOnFile", "{\"cardToken\": \"%s\"}".formatted(cardToken));
    send("AuthorizationApproved", newRequestId(), cardToken);

    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> statusOf(card) == SingleUseCard.Status.AUTHORIZED);
    assertThat(output)
        .contains("Skipped an AuthorizationApproved message with no value")
        .contains("Skipped a message with no event-type header");
  }

  private boolean isReversed(SingleUseCard card) {
    return approvedAuthorizations
        .find(card.cardToken(), "ABC123")
        .map(ApprovedAuthorization::reversed)
        .orElse(false);
  }

  private SingleUseCard issuedCard() {
    return issuedCard(Instant.now());
  }

  private SingleUseCard issuedCard(Instant now) {
    return cards.issueSingleUse(
        UUID.randomUUID(),
        new BuyerId(UUID.randomUUID()),
        UUID.randomUUID(),
        "ACCEPTOR-1",
        AMOUNT,
        now);
  }

  private SingleUseCard.Status statusOf(SingleUseCard card) {
    return cards.find(card.cardToken()).orElseThrow().status();
  }

  // The service's event as its outbox relay sends it.
  private void send(String eventType, String requestId, String cardToken) throws Exception {
    topic.send(
        cardToken,
        eventType,
        """
        {"requestId": "%s", "cardToken": "%s", "amountMinorUnits": 1240000, "currency": "USD",
         "authorizationCode": "ABC123", "acceptorId": "ACCEPTOR-1"}
        """
            .formatted(requestId, cardToken));
  }

  private static String newRequestId() {
    return "Q" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
  }
}
