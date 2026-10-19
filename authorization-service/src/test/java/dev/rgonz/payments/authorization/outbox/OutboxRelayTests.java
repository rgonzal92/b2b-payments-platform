package dev.rgonz.payments.authorization.outbox;

import static dev.rgonz.payments.authorization.AuthorizationsTopic.eventType;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.payments.authorization.AuthorizationsTopic;
import dev.rgonz.payments.authorization.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The outbox and its relay, on the service's PostgreSQL and a real Kafka: events appended to the
 * outbox reach the {@code authorizations} topic in the order they were appended, keyed by Card
 * Token and named in the {@code event-type} header. The scheduled relay is off here, so each test
 * runs a relay pass itself, as the schedule would.
 */
@Import({TestcontainersConfiguration.class, OutboxRelayTests.FlakyKafka.class})
@SpringBootTest(properties = "outbox.relay.enabled=false")
class OutboxRelayTests {

  /** Stand-in events, as the service's own events are records serialized to JSON. */
  record SomethingHappened(String cardToken, long amountMinorUnits) {}

  record First(String cardToken) {}

  record Second(String cardToken) {}

  record Third(String cardToken) {}

  /**
   * The real template, except for the sends a test arms to fail: Kafka is the boundary the relay
   * can't control, so the test stands in for its failures. A send armed to fail before Kafka never
   * reaches the topic; one armed to fail after Kafka reaches it, but its acknowledgement is lost,
   * as when the service crashes between the send and the commit that marks it sent.
   */
  static class FlakyKafka {

    @Bean
    FlakyKafkaTemplate kafkaTemplate(ProducerFactory<String, String> producers) {
      return new FlakyKafkaTemplate(producers);
    }
  }

  static class FlakyKafkaTemplate extends KafkaTemplate<String, String> {

    final Set<String> failBeforeKafka = ConcurrentHashMap.newKeySet();
    final Set<String> failAfterKafka = ConcurrentHashMap.newKeySet();

    FlakyKafkaTemplate(ProducerFactory<String, String> producers) {
      super(producers);
    }

    @Override
    public CompletableFuture<SendResult<String, String>> send(
        ProducerRecord<String, String> record) {
      var send = record.key() + "/" + eventType(record);
      if (failBeforeKafka.contains(send)) {
        return CompletableFuture.failedFuture(new TimeoutException("Stand-in send failure."));
      }
      var sent = super.send(record);
      return failAfterKafka.contains(send)
          ? sent.thenCompose(
              result ->
                  CompletableFuture.failedFuture(
                      new TimeoutException("Stand-in lost acknowledgement.")))
          : sent;
    }

    private static String eventType(ProducerRecord<String, String> record) {
      return new String(record.headers().lastHeader("event-type").value(), StandardCharsets.UTF_8);
    }
  }

  @Autowired Outbox outbox;
  @Autowired OutboxRelay relay;
  @Autowired FlakyKafkaTemplate flakyKafka;
  @Autowired KafkaContainer kafka;
  @Autowired PostgreSQLContainer postgres;

  @AfterEach
  void healKafka() {
    flakyKafka.failBeforeKafka.clear();
    flakyKafka.failAfterKafka.clear();
  }

  @Test
  void theAuthorizationsTopicHasThreePartitions() {
    assertThat(new AuthorizationsTopic(kafka).partitions()).isEqualTo(3);
  }

  @Test
  void aRelayPassSendsAnAppendedEventKeyedByCardTokenWithItsTypeInAHeader() {
    var cardToken = newCardToken();
    outbox.append(cardToken, new SomethingHappened(cardToken, 1_240_000)).block();

    relay.relay().block();

    var record = awaitRecords(cardToken, 1).getFirst();
    assertThat(record.key()).isEqualTo(cardToken);
    assertThat(eventType(record)).isEqualTo("SomethingHappened");
    assertThat(record.value())
        .isEqualTo("{\"cardToken\":\"" + cardToken + "\",\"amountMinorUnits\":1240000}");
  }

  // Rows a crashed service committed but never sent wait for any later pass, and the events of a
  // pass that stopped at a failed send follow in order, each once.
  @Test
  void aPassThatStopsAtAFailedSendLeavesTheRestForTheNextPassWhichSendsThemInOrder() {
    var cardToken = newCardToken();
    append(cardToken, new First(cardToken), new Second(cardToken), new Third(cardToken));
    flakyKafka.failBeforeKafka.add(cardToken + "/Second");

    assertThat(relay.relay().block()).isOne();
    flakyKafka.failBeforeKafka.clear();
    assertThat(relay.relay().block()).isEqualTo(2);

    var records = awaitRecords(cardToken, 3);
    assertThat(records)
        .extracting(AuthorizationsTopic::eventType)
        .containsExactly("First", "Second", "Third");
  }

  @Test
  void anEventWhoseAcknowledgementWasLostIsSentAgainUnchanged() {
    var cardToken = newCardToken();
    append(cardToken, new First(cardToken), new Second(cardToken));
    flakyKafka.failAfterKafka.add(cardToken + "/First");

    assertThat(relay.relay().block()).isZero();
    flakyKafka.failAfterKafka.clear();
    assertThat(relay.relay().block()).isEqualTo(2);

    var records = awaitRecords(cardToken, 3);
    assertThat(records)
        .extracting(AuthorizationsTopic::eventType)
        .containsExactly("First", "First", "Second");
    assertThat(records.get(1).value()).isEqualTo(records.get(0).value());
  }

  // Another instance's relay, or this one's still running: whichever holds the lock sends, and
  // a pass that can't take it sends nothing rather than race it.
  @Test
  void aPassWhileAnotherRelayHoldsTheLockSendsNothing() throws Exception {
    var cardToken = newCardToken();
    append(cardToken, new First(cardToken));

    try (var otherRelay =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      try (var statement = otherRelay.createStatement()) {
        statement.execute("select pg_advisory_lock(hashtext('outbox-relay'))");
      }
      assertThat(relay.relay().block()).isZero();
    }

    assertThat(relay.relay().block()).isOne();
    assertThat(awaitRecords(cardToken, 1)).hasSize(1);
  }

  private void append(String cardToken, Object... events) {
    for (var event : events) {
      outbox.append(cardToken, event).block();
    }
  }

  private List<ConsumerRecord<String, String>> awaitRecords(String cardToken, int count) {
    return new AuthorizationsTopic(kafka).awaitRecords(cardToken, count);
  }

  private static String newCardToken() {
    return "card-token-" + UUID.randomUUID();
  }
}
