package dev.rgonz.payments.authorization.cards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.rgonz.payments.authorization.AuthorizationsTopic;
import dev.rgonz.payments.authorization.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

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
  @Autowired DatabaseClient db;
  @Autowired KafkaContainer kafka;
  @Autowired PostgreSQLContainer postgres;

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

  // The platform's saga waits for it before sending the Supplier the card (M5 design, "The gaps").
  @Test
  void aNewlyStoredCardIsPublishedAsOnFileOnce() {
    var cardToken = newCardToken();

    send(cardToken, "CardIssued", cardIssuedJson(cardToken));
    send(cardToken, "CardIssued", cardIssuedJson(cardToken));
    awaitAllConsumed();

    var record = new AuthorizationsTopic(kafka).awaitRecords(cardToken, 1).getFirst();
    assertThat(AuthorizationsTopic.eventType(record)).isEqualTo("CardOnFile");
    assertThat(record.value()).isEqualTo("{\"cardToken\":\"" + cardToken + "\"}");
    assertThat(outboxRows(cardToken)).isOne();
  }

  // The Supplier never gets a card that ended first, so the platform doesn't wait for it.
  @Test
  void aCardThatEndedBeforeItsCardIssuedIsNotOnFile() {
    var cardToken = newCardToken();
    send(cardToken, "CardCancelled", "{\"cardToken\":\"" + cardToken + "\"}");
    awaitCopy(cardToken, CardStatus.CANCELLED);

    send(cardToken, "CardIssued", cardIssuedJson(cardToken));
    awaitAllConsumed();

    assertThat(outboxRows(cardToken)).isZero();
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

  // The platform may send one card's events out of order (see docs/learning/kafka.md), so a card
  // that ended before its CardIssued arrived must stay ended.
  @Test
  void aCardCancelledBeforeItsCardIssuedStaysCancelledWithItsDetails() {
    var cardToken = newCardToken();
    send(cardToken, "CardCancelled", "{\"cardToken\":\"" + cardToken + "\"}");
    awaitCopy(cardToken, CardStatus.CANCELLED);

    send(cardToken, "CardIssued", cardIssuedJson(cardToken));
    send(cardToken, "CardIssued", cardIssuedJson(cardToken));
    awaitAllConsumed();

    assertThat(copies.find(cardToken).block())
        .isEqualTo(
            new CardCopy(
                cardToken,
                "pan-hash-of-" + cardToken,
                "Y29kZS1oYXNoLTAwMDAwMDAwMDAwMDAwMDAwMDAwMDA=",
                Instant.parse("2026-11-17T09:30:00.123456Z"),
                1_240_000L,
                Currency.getInstance("USD"),
                "ACCEPTOR-1",
                CardStatus.CANCELLED));
  }

  @Test
  void aCardExpiredBeforeItsCardIssuedNeverBecomesActive() {
    var cardToken = newCardToken();
    send(cardToken, "CardExpired", "{\"cardToken\":\"" + cardToken + "\"}");
    send(cardToken, "CardExpired", "{\"cardToken\":\"" + cardToken + "\"}");
    awaitCopy(cardToken, CardStatus.EXPIRED);

    send(cardToken, "CardIssued", cardIssuedJson(cardToken));
    awaitAllConsumed();

    var copy = copies.find(cardToken).block();
    assertThat(copy.status()).isEqualTo(CardStatus.EXPIRED);
    assertThat(copy.panHash()).isEqualTo("pan-hash-of-" + cardToken);
  }

  @Test
  void aCardIssuedThatArrivesWhileTheDatabaseIsDownIsStoredOnceItIsBack() throws SQLException {
    // The consumer is up and reading before the database goes down.
    var earlierCard = newCardToken();
    send(earlierCard, "CardIssued", cardIssuedJson(earlierCard));
    awaitCopy(earlierCard, CardStatus.ACTIVE);
    var cardToken = newCardToken();
    // Connected to the server's postgres database: PostgreSQL won't refuse connections to the
    // database the statement runs in.
    try (var admin =
        DriverManager.getConnection(
            postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/postgres"),
            postgres.getUsername(),
            postgres.getPassword())) {
      refuseConnections(admin, postgres.getDatabaseName(), true);

      send(cardToken, "CardIssued", cardIssuedJson(cardToken));

      // The partition waits on the event, retrying, instead of skipping it: longer than ten tries
      // take, each failing to connect in about half a second.
      await()
          .during(Duration.ofSeconds(10))
          .atMost(Duration.ofSeconds(12))
          .until(() -> !allConsumed());
      refuseConnections(admin, postgres.getDatabaseName(), false);
    }

    awaitCopy(cardToken, CardStatus.ACTIVE);
  }

  @Test
  void messagesThatCanNeverBeReadAreSkippedAndTheEventsAfterThemStillArrive() {
    var cardToken = newCardToken();
    var otherCard = newCardToken();
    var tokenOnly = "{\"cardToken\":\"" + cardToken + "\"}";
    send(cardToken, "CardIssued", cardIssuedJson(cardToken));
    awaitCopy(cardToken, CardStatus.ACTIVE);

    send(cardToken, null, tokenOnly);
    send(cardToken, "CardRevoked", tokenOnly);
    send(cardToken, "CardCancelled", "{not json");
    send(otherCard, "CardIssued", "{\"cardToken\":\"" + otherCard + "\"}");
    send(cardToken, "CardExpired", tokenOnly);

    awaitCopy(cardToken, CardStatus.EXPIRED);
    awaitAllConsumed();
    assertThat(copies.find(otherCard).blockOptional()).isEmpty();
  }

  private void send(String cardToken, String eventType, String json) {
    var record = new ProducerRecord<>(TOPIC, cardToken, json);
    if (eventType != null) {
      record.headers().add("event-type", eventType.getBytes(StandardCharsets.UTF_8));
    }
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

  // The events the copy wrote to the outbox for the card, in the same transaction as its write.
  private long outboxRows(String cardToken) {
    return db.sql("select count(*) as n from outbox where card_token = :cardToken")
        .bind("cardToken", cardToken)
        .map(row -> row.get("n", Long.class))
        .one()
        .block();
  }

  // The service's consumer group has committed every message the topic holds: whatever was sent
  // has been handled, including a repeat that changed nothing.
  private void awaitAllConsumed() {
    await().atMost(Duration.ofSeconds(30)).until(this::allConsumed);
  }

  private boolean allConsumed() throws Exception {
    try (var admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
      var partitions =
          admin
              .describeTopics(List.of(TOPIC))
              .allTopicNames()
              .get()
              .get(TOPIC)
              .partitions()
              .stream()
              .map(info -> new TopicPartition(TOPIC, info.partition()))
              .toList();
      var ends =
          admin
              .listOffsets(
                  partitions.stream()
                      .collect(Collectors.toMap(partition -> partition, p -> OffsetSpec.latest())))
              .all()
              .get();
      Map<TopicPartition, OffsetAndMetadata> committed =
          admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get();
      return partitions.stream()
          .allMatch(
              partition -> {
                var offset = committed.get(partition);
                return (offset == null ? 0 : offset.offset()) == ends.get(partition).offset();
              });
    }
  }

  // Stands in for the service's database going down: it ends every other connection and accepts
  // no new one until allowed again.
  private static void refuseConnections(Connection admin, String database, boolean refuse)
      throws SQLException {
    try (var statement = admin.createStatement()) {
      statement.execute("alter database " + database + " allow_connections " + !refuse);
      if (refuse) {
        statement.execute(
            "select pg_terminate_backend(pid) from pg_stat_activity where datname = '"
                + database
                + "'");
      }
    }
  }

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
