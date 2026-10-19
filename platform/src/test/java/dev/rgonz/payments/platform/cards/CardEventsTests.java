package dev.rgonz.payments.platform.cards;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import dev.rgonz.payments.platform.TestHashes;
import dev.rgonz.payments.platform.TestKafka;
import dev.rgonz.payments.platform.TestcontainersConfiguration;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import dev.rgonz.payments.platform.vault.Vault;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The card events on the {@code cards} topic, read back from a real Kafka: each issued, cancelled
 * or expired card is published, keyed by its Card Token, names its event type in a header, and
 * carries no PAN or security code. The platform doesn't promise to send one card's events in the
 * order they were published (docs/learning/kafka.md), so the tests don't check it. Externalization
 * is off in every other test context.
 */
@Import({TestcontainersConfiguration.class, TestKafka.class})
@SpringBootTest(
    properties = {
      "cards.expiry-job.enabled=false",
      "payables.payment-worker.enabled=false",
      "spring.modulith.events.externalization.enabled=true"
    })
class CardEventsTests {

  private static final String TOPIC = "cards";
  private static final String EVENT_TYPE = "event-type";
  private static final Money AMOUNT = new Money(1_240_000, Currency.getInstance("USD"));

  @Autowired Cards cards;
  @Autowired CardExpiry expiry;
  @Autowired Vault vault;
  @Autowired JdbcClient jdbc;
  @Autowired KafkaContainer kafka;

  @Test
  void theCardsTopicHasThreePartitions() {
    try (var consumer =
        new KafkaConsumer<String, String>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class))) {
      assertThat(consumer.partitionsFor(TOPIC)).hasSize(3);
    }
  }

  @Test
  void issuingACardPublishesCardIssuedWithWhatTheServiceNeedsButNoPanOrSecurityCode() {
    var card =
        cards.issueSingleUse(
            UUID.randomUUID(),
            new BuyerId(UUID.randomUUID()),
            UUID.randomUUID(),
            "ACCEPTOR-1",
            AMOUNT,
            Instant.now());
    var details = vault.cardDetails(card.cardToken());

    var record = awaitRecords(card.cardToken(), 1).getFirst();

    assertThat(record.key()).isEqualTo(card.cardToken());
    assertThat(header(record, EVENT_TYPE)).isEqualTo("CardIssued");
    var payload = record.value();
    assertThat(JsonPath.<String>read(payload, "$.cardToken")).isEqualTo(card.cardToken());
    assertThat(Instant.parse(JsonPath.read(payload, "$.expiresAt"))).isEqualTo(card.expiresAt());
    assertThat(JsonPath.<Number>read(payload, "$.amountMinorUnits").longValue())
        .isEqualTo(AMOUNT.minorUnits());
    assertThat(JsonPath.<String>read(payload, "$.currency")).isEqualTo("USD");
    assertThat(JsonPath.<String>read(payload, "$.acceptorId")).isEqualTo("ACCEPTOR-1");
    assertThat(JsonPath.<String>read(payload, "$.panHash"))
        .isEqualTo(TestHashes.keyedHash(details.pan()));
    assertThat(JsonPath.<String>read(payload, "$.securityCodeHash"))
        .isEqualTo(TestHashes.keyedHash(details.securityCode()));
    assertThat(payload)
        .doesNotContain("\"" + details.pan() + "\"")
        .doesNotContain("\"" + details.securityCode() + "\"");

    // The stored event is the same, so the registry's outbox never holds a secret either; the send
    // completes it.
    var stored = awaitCompletedEvent("CardIssued", card.cardToken());
    assertThat(stored)
        .contains(card.cardToken())
        .doesNotContain("\"" + details.pan() + "\"")
        .doesNotContain("\"" + details.securityCode() + "\"");
  }

  @Test
  void cancellingACardPublishesCardCancelledOnce() {
    var card = issue();
    cards.cancel(card.cardToken());
    // A card already CANCELLED changes nothing, so it publishes nothing.
    cards.cancel(card.cardToken());

    var records = awaitRecords(card.cardToken(), 2);

    assertThat(records)
        .extracting(record -> header(record, EVENT_TYPE))
        .containsExactlyInAnyOrder("CardIssued", "CardCancelled");
    assertThat(records).allMatch(record -> record.key().equals(card.cardToken()));
    assertThat(JsonPath.<String>read(ofType(records, "CardCancelled").value(), "$.cardToken"))
        .isEqualTo(card.cardToken());
  }

  @Test
  void expiringACardPublishesCardExpired() {
    var issuedAt = Instant.parse("2020-01-01T00:00:00Z");
    var card = issue(issuedAt);
    expiry.expireDue(card.expiresAt().plusSeconds(1));

    var records = awaitRecords(card.cardToken(), 2);

    assertThat(records)
        .extracting(record -> header(record, EVENT_TYPE))
        .containsExactlyInAnyOrder("CardIssued", "CardExpired");
    assertThat(JsonPath.<String>read(ofType(records, "CardExpired").value(), "$.cardToken"))
        .isEqualTo(card.cardToken());
  }

  private SingleUseCard issue() {
    return issue(Instant.now());
  }

  private SingleUseCard issue(Instant now) {
    return cards.issueSingleUse(
        UUID.randomUUID(),
        new BuyerId(UUID.randomUUID()),
        UUID.randomUUID(),
        "ACCEPTOR-1",
        AMOUNT,
        now);
  }

  // Polls one consumer from the topic's start until it has seen the card's expected events.
  private List<ConsumerRecord<String, String>> awaitRecords(String cardToken, int count) {
    var records = new ArrayList<ConsumerRecord<String, String>>();
    try (var consumer = newConsumer()) {
      await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(
              () -> {
                consumer
                    .poll(Duration.ofMillis(500))
                    .forEach(
                        record -> {
                          if (cardToken.equals(record.key())) {
                            records.add(record);
                          }
                        });
                assertThat(records).hasSizeGreaterThanOrEqualTo(count);
              });
    }
    return records;
  }

  private KafkaConsumer<String, String> newConsumer() {
    var consumer =
        new KafkaConsumer<String, String>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "card-events-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class));
    consumer.subscribe(List.of(TOPIC));
    return consumer;
  }

  private static ConsumerRecord<String, String> ofType(
      List<ConsumerRecord<String, String>> records, String eventType) {
    return records.stream()
        .filter(record -> eventType.equals(header(record, EVENT_TYPE)))
        .findFirst()
        .orElseThrow();
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    var header = record.headers().lastHeader(name);
    assertThat(header).as("header %s", name).isNotNull();
    return new String(header.value(), UTF_8);
  }

  // The event as Spring Modulith stored it in its publication registry, the platform's outbox,
  // completed only once Kafka had it.
  private String awaitCompletedEvent(String type, String cardToken) {
    var stored = new AtomicReference<String>();
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                stored.set(
                    jdbc.sql(
                            """
                            select serialized_event
                              from modulith.event_publication
                             where event_type = :eventType and serialized_event like :cardToken
                               and completion_date is not null
                            """)
                        .param("eventType", "dev.rgonz.payments.platform.cards." + type)
                        .param("cardToken", "%" + cardToken + "%")
                        .query(String.class)
                        .single()));
    return stored.get();
  }
}
