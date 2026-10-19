package dev.rgonz.payments.authorization.network;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import au.com.dius.pact.provider.MessageAndMetadata;
import au.com.dius.pact.provider.PactVerifyProvider;
import au.com.dius.pact.provider.junit5.MessageTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import dev.rgonz.payments.authorization.AuthorizationsTopic;
import dev.rgonz.payments.authorization.TestcontainersConfiguration;
import dev.rgonz.payments.authorization.cards.CardHashing;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The service's side of the {@code authorizations} topic's contract: each pact the platform wrote
 * into the repository's {@code pacts} folder is checked against a real event, made by issuing a
 * card on the {@code cards} topic, then approving and reversing a Charge on it, and read back from
 * the topic on a real Kafka with its headers as the message's metadata. The service builds before
 * the platform, so it verifies the pact file as committed; CI fails when the platform's consumer
 * tests write a different one (docs/learning/pact.md).
 */
@Provider("authorization-service")
@PactFolder("../pacts")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AuthorizationEventsPactVerificationTests {

  private static final long AMOUNT = 1_240_000;
  private static final String ACCEPTOR_ID = "ACCEPTOR-1";
  private static final String SECURITY_CODE = "123";
  // Low-entropy fake PANs, one per card, off NetworkLinkTests' range: the service finds a card by
  // its PAN's hash.
  private static final AtomicLong PANS = new AtomicLong(7_000_000_000L);
  private static final DateTimeFormatter EXPIRATION_DATE =
      DateTimeFormatter.ofPattern("yyMM").withZone(ZoneOffset.UTC);

  @Autowired Authorizations authorizations;
  @Autowired CardHashing hashing;
  @Autowired KafkaContainer kafka;

  @BeforeEach
  void verifyMessages(PactVerificationContext context) {
    context.setTarget(new MessageTestTarget(List.of(getClass().getPackageName())));
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void theAuthorizationsEventsMeetThePlatformsPacts(PactVerificationContext context) {
    context.verifyInteraction();
  }

  @PactVerifyProvider("a CardOnFile for a card the service holds")
  MessageAndMetadata cardOnFile() {
    var card = issuedCard();
    return published(card.cardToken(), "CardOnFile", 1);
  }

  @PactVerifyProvider("an AuthorizationApproved for an approved Charge")
  MessageAndMetadata authorizationApproved() {
    var card = issuedCard();
    approve(card);
    return published(card.cardToken(), "AuthorizationApproved", 2);
  }

  @PactVerifyProvider("an AuthorizationReversed for a reversed approved Authorization")
  MessageAndMetadata authorizationReversed() {
    var card = issuedCard();
    authorizations.reverse(approve(card)).block();
    return published(card.cardToken(), "AuthorizationReversed", 3);
  }

  private record Card(String cardToken, String pan, Instant expiresAt) {}

  // Issued as the platform issues it, on the cards topic: the service puts it on file.
  private Card issuedCard() {
    var card =
        new Card(
            UUID.randomUUID().toString(),
            "990000%010d".formatted(PANS.incrementAndGet()),
            Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS));
    var record =
        new ProducerRecord<>(
            "cards",
            card.cardToken(),
            """
            {"cardToken":"%s","panHash":"%s","securityCodeHash":"%s","expiresAt":"%s",
             "amountMinorUnits":%d,"currency":"USD","acceptorId":"%s"}
            """
                .formatted(
                    card.cardToken(),
                    hashing.hash(card.pan()),
                    hashing.hash(SECURITY_CODE),
                    card.expiresAt(),
                    AMOUNT,
                    ACCEPTOR_ID));
    record.headers().add("event-type", "CardIssued".getBytes(UTF_8));
    try (var producer =
        new KafkaProducer<String, String>(
            Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class))) {
      producer.send(record);
    }
    // On file once its CardOnFile is on the topic.
    new AuthorizationsTopic(kafka).awaitRecords(card.cardToken(), 1);
    return card;
  }

  // The Network's Charge of the card's whole amount, approved; returns its request ID.
  private String approve(Card card) {
    var requestId = UUID.randomUUID().toString();
    var answer =
        authorizations
            .authorize(
                new Charge(
                    requestId,
                    card.pan(),
                    SECURITY_CODE,
                    EXPIRATION_DATE.format(card.expiresAt()),
                    AMOUNT,
                    "USD",
                    ACCEPTOR_ID),
                Instant.now())
            .block();
    assertThat(answer).isNotNull();
    assertThat(answer.authorizationCode()).isNotNull();
    return requestId;
  }

  // The card's event of that type as the topic holds it, once its first {@code count} events have
  // arrived: its value, and every header as metadata.
  private MessageAndMetadata published(String cardToken, String eventType, int count) {
    ConsumerRecord<String, String> found =
        new AuthorizationsTopic(kafka)
            .awaitRecords(cardToken, count).stream()
                .filter(record -> eventType.equals(AuthorizationsTopic.eventType(record)))
                .findFirst()
                .orElseThrow();
    var metadata = new HashMap<String, Object>();
    found.headers().forEach(h -> metadata.put(h.key(), new String(h.value(), UTF_8)));
    return new MessageAndMetadata(found.value().getBytes(UTF_8), metadata);
  }
}
