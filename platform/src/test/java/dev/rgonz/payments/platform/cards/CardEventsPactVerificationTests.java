package dev.rgonz.payments.platform.cards;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import au.com.dius.pact.provider.MessageAndMetadata;
import au.com.dius.pact.provider.PactVerifyProvider;
import au.com.dius.pact.provider.junit5.MessageTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import dev.rgonz.payments.platform.TestKafka;
import dev.rgonz.payments.platform.TestcontainersConfiguration;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The platform's side of the card events' contract: each pact {@code authorization-service} wrote
 * into the repository's {@code pacts} folder is checked against a real event, made by issuing,
 * cancelling, expiring or clearing a card and read back from the {@code cards} topic on a real
 * Kafka, with its headers as the message's metadata. Same context as {@link CardEventsTests}, so
 * the two share it.
 */
@Provider("platform")
@PactFolder("../pacts")
@Import({TestcontainersConfiguration.class, TestKafka.class})
@SpringBootTest(
    properties = {
      "cards.expiry-job.enabled=false",
      "payables.payment-worker.enabled=false",
      "spring.modulith.events.externalization.enabled=true"
    })
class CardEventsPactVerificationTests {

  private static final Money AMOUNT = new Money(1_240_000, Currency.getInstance("USD"));

  @Autowired Cards cards;
  @Autowired CardExpiry expiry;
  @Autowired KafkaContainer kafka;

  @BeforeEach
  void verifyMessages(PactVerificationContext context) {
    context.setTarget(new MessageTestTarget(List.of(getClass().getPackageName())));
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void theCardEventsMeetAuthorizationServicesPacts(PactVerificationContext context) {
    context.verifyInteraction();
  }

  @PactVerifyProvider("a CardIssued for a newly issued card")
  MessageAndMetadata cardIssued() {
    return published(issue(Instant.now()).cardToken(), "CardIssued");
  }

  // The saga passes no Acceptor ID when no Invoice gave the Supplier one.
  @PactVerifyProvider("a CardIssued for a card whose Supplier has no Acceptor ID")
  MessageAndMetadata cardIssuedWithoutAcceptorId() {
    return published(issue(Instant.now(), null).cardToken(), "CardIssued");
  }

  @PactVerifyProvider("a CardCancelled for a cancelled card")
  MessageAndMetadata cardCancelled() {
    var card = issue(Instant.now());
    cards.cancel(card.cardToken());
    return published(card.cardToken(), "CardCancelled");
  }

  @PactVerifyProvider("a CardExpired for an expired card")
  MessageAndMetadata cardExpired() {
    var card = issue(Instant.parse("2020-01-01T00:00:00Z"));
    expiry.expireDue(card.expiresAt().plusSeconds(1));
    return published(card.cardToken(), "CardExpired");
  }

  @PactVerifyProvider("a CardCleared for a cleared card")
  MessageAndMetadata cardCleared() {
    var card = issue(Instant.now());
    cards.clear(card.cardToken());
    return published(card.cardToken(), "CardCleared");
  }

  private SingleUseCard issue(Instant now) {
    return issue(now, "ACCEPTOR-1");
  }

  private SingleUseCard issue(Instant now, String acceptorId) {
    return cards.issueSingleUse(
        UUID.randomUUID(),
        new BuyerId(UUID.randomUUID()),
        UUID.randomUUID(),
        acceptorId,
        AMOUNT,
        now);
  }

  // The card's event of that type as the topic holds it: its value, and every header as metadata.
  private MessageAndMetadata published(String cardToken, String eventType) {
    var found = new AtomicReference<ConsumerRecord<String, String>>();
    try (var consumer = newConsumer()) {
      await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(
              () -> {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                  if (cardToken.equals(record.key()) && eventType.equals(eventType(record))) {
                    found.set(record);
                  }
                }
                assertThat(found).doesNotHaveNullValue();
              });
    }
    var metadata = new HashMap<String, Object>();
    found.get().headers().forEach(h -> metadata.put(h.key(), new String(h.value(), UTF_8)));
    return new MessageAndMetadata(found.get().value().getBytes(UTF_8), metadata);
  }

  private static String eventType(ConsumerRecord<String, String> record) {
    var header = record.headers().lastHeader("event-type");
    return header == null ? null : new String(header.value(), UTF_8);
  }

  private KafkaConsumer<String, String> newConsumer() {
    var consumer =
        new KafkaConsumer<String, String>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "card-events-pact-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class));
    consumer.subscribe(List.of("cards"));
    return consumer;
  }
}
