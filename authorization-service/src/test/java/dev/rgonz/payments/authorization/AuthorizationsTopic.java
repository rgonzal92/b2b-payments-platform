package dev.rgonz.payments.authorization;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Reads the service's {@code authorizations} topic back from a real Kafka, as the platform will:
 * from the topic's start, in a consumer group of its own.
 */
public final class AuthorizationsTopic {

  public static final String TOPIC = "authorizations";

  private final KafkaContainer kafka;

  public AuthorizationsTopic(KafkaContainer kafka) {
    this.kafka = kafka;
  }

  /** The topic's partition count. */
  public int partitions() {
    try (var consumer = newConsumer()) {
      return consumer.partitionsFor(TOPIC).size();
    }
  }

  /**
   * The card's messages, keyed by its Card Token, in the order they reached the topic, once at
   * least {@code count} have arrived.
   */
  public List<ConsumerRecord<String, String>> awaitRecords(String cardToken, int count) {
    var records = new ArrayList<ConsumerRecord<String, String>>();
    try (var consumer = newConsumer()) {
      consumer.subscribe(List.of(TOPIC));
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

  /** The message's {@code event-type} header. */
  public static String eventType(ConsumerRecord<String, String> record) {
    var header = record.headers().lastHeader("event-type");
    assertThat(header).as("event-type header").isNotNull();
    return new String(header.value(), UTF_8);
  }

  private KafkaConsumer<String, String> newConsumer() {
    return new KafkaConsumer<>(
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            kafka.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG,
            "authorizations-test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class));
  }
}
