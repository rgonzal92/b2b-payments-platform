package dev.rgonz.payments.authorization.cards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.rgonz.payments.authorization.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The service's copy of the cards, fed from a real Kafka into its own PostgreSQL: the test plays
 * the platform, sending each card event as the platform does (keyed by Card Token, its type in the
 * {@code event-type} header, the event as JSON), and reads the copy back through {@link
 * CardCopies}. Kafka delivers at least once, so a repeated event must change nothing.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CardsTopicTests {

  private static final String TOPIC = "cards";
  private static final String GROUP = "authorization-service";

  @Autowired CardCopies copies;
  @Autowired KafkaContainer kafka;

  private KafkaProducer<String, String> producer;

  @BeforeEach
  void startProducer() {
    producer =
        new KafkaProducer<>(
            Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class));
  }

  @AfterEach
  void stopProducer() {
    producer.close();
  }

  @Test
  void cardIssuedStoresTheCardActiveWithItsHashesExpiryAmountAndAcceptorId() {
    var cardToken = newCardToken();

    send(cardToken, "CardIssued", cardIssuedJson(cardToken));

    var copy = awaitCopy(cardToken, CardStatus.ACTIVE);
    assertThat(copy)
        .isEqualTo(
            new CardCopy(
                cardToken,
                "pan-hash-of-" + cardToken,
                "Y29kZS1oYXNoLTAwMDAwMDAwMDAwMDAwMDAwMDAwMDA=",
                Instant.parse("2026-11-17T09:30:00.123456Z"),
                1_240_000L,
                Currency.getInstance("USD"),
                "ACCEPTOR-1",
                CardStatus.ACTIVE));
  }

  @Test
  void cardCancelledMakesTheCardCancelledAndItsRepeatChangesNothing() {
    var cardToken = newCardToken();
    send(cardToken, "CardIssued", cardIssuedJson(cardToken));

    send(cardToken, "CardCancelled", "{\"cardToken\":\"" + cardToken + "\"}");
    awaitCopy(cardToken, CardStatus.CANCELLED);
    send(cardToken, "CardCancelled", "{\"cardToken\":\"" + cardToken + "\"}");
    awaitAllConsumed();

    assertThat(status(cardToken)).isEqualTo(CardStatus.CANCELLED);
  }

  @Test
  void cardExpiredMakesTheCardExpiredAndItsRepeatChangesNothing() {
    var cardToken = newCardToken();
    send(cardToken, "CardIssued", cardIssuedJson(cardToken));

    // The platform's CardExpired carries more than the service reads.
    var expired =
        """
        {"cardToken":"%s","paymentId":"%s","buyerId":{"value":"%s"},
         "amount":{"minorUnits":1240000,"currency":"USD"}}
        """
            .formatted(cardToken, UUID.randomUUID(), UUID.randomUUID());
    send(cardToken, "CardExpired", expired);
    awaitCopy(cardToken, CardStatus.EXPIRED);
    send(cardToken, "CardExpired", expired);
    awaitAllConsumed();

    assertThat(status(cardToken)).isEqualTo(CardStatus.EXPIRED);
  }

  @Test
  void aRepeatedCardIssuedChangesNothingEvenAfterTheCardMoved() {
    var cardToken = newCardToken();
    send(cardToken, "CardIssued", cardIssuedJson(cardToken));
    send(cardToken, "CardCancelled", "{\"cardToken\":\"" + cardToken + "\"}");
    var cancelled = awaitCopy(cardToken, CardStatus.CANCELLED);

    send(cardToken, "CardIssued", cardIssuedJson(cardToken));
    awaitAllConsumed();

    assertThat(copies.find(cardToken).block()).isEqualTo(cancelled);
  }

  private void send(String cardToken, String eventType, String json) {
    var record = new ProducerRecord<>(TOPIC, cardToken, json);
    record.headers().add("event-type", eventType.getBytes(StandardCharsets.UTF_8));
    producer.send(record);
    producer.flush();
  }

  private CardCopy awaitCopy(String cardToken, CardStatus status) {
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(status(cardToken)).isEqualTo(status));
    return copies.find(cardToken).block();
  }

  private CardStatus status(String cardToken) {
    var copy = copies.find(cardToken).block();
    return copy == null ? null : copy.status();
  }

  // The service's consumer group has committed every message the topic holds: whatever was sent
  // has been handled, including a repeat that changed nothing.
  private void awaitAllConsumed() {
    try (var admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
      await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(
              () -> {
                var committed =
                    admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get();
                var ends =
                    admin
                        .listOffsets(
                            committed.keySet().stream()
                                .collect(
                                    Collectors.toMap(
                                        partition -> partition, partition -> OffsetSpec.latest())))
                        .all()
                        .get();
                assertThat(committed).isNotEmpty();
                committed.forEach(
                    (partition, offset) ->
                        assertThat(offset.offset())
                            .as("committed offset of %s", partition)
                            .isEqualTo(ends.get(partition).offset()));
              });
    }
  }

  // Each card's PAN hash is unique, as each card's PAN is.
  private static String cardIssuedJson(String cardToken) {
    return """
        {"cardToken":"%1$s",
         "panHash":"pan-hash-of-%1$s",
         "securityCodeHash":"Y29kZS1oYXNoLTAwMDAwMDAwMDAwMDAwMDAwMDAwMDA=",
         "expiresAt":"2026-11-17T09:30:00.123456Z",
         "amountMinorUnits":1240000,
         "currency":"USD",
         "acceptorId":"ACCEPTOR-1"}
        """
        .formatted(cardToken);
  }

  private static String newCardToken() {
    return UUID.randomUUID().toString();
  }
}
