package dev.rgonz.payments.platform;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Plays authorization-service on a real Kafka's {@code authorizations} topic: sends messages as its
 * outbox relay does, keyed by Card Token with the event type in a header, and waits until a
 * platform consumer group has read them all.
 */
public final class TestAuthorizationsTopic {

  private static final String TOPIC = "authorizations";

  private final KafkaContainer kafka;

  public TestAuthorizationsTopic(KafkaContainer kafka) {
    this.kafka = kafka;
  }

  /** Sends the message; a null event type sends no header. */
  public void send(String key, String eventType, String json) throws Exception {
    var record = new ProducerRecord<>(TOPIC, key, json);
    if (eventType != null) {
      record.headers().add("event-type", eventType.getBytes(UTF_8));
    }
    try (var producer =
        new KafkaProducer<String, String>(
            Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class))) {
      producer.send(record).get(10, TimeUnit.SECONDS);
    }
  }

  /**
   * Waits until the consumer group has committed every message on the topic, so a test can check
   * that one changed nothing.
   */
  public void awaitConsumed(String groupId) {
    try (var admin =
        Admin.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
      await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(
              () -> {
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
                                .collect(
                                    Collectors.toMap(
                                        partition -> partition, partition -> OffsetSpec.latest())))
                        .all()
                        .get();
                var committed =
                    admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata().get();
                for (var partition : partitions) {
                  var end = ends.get(partition).offset();
                  var read = committed.get(partition);
                  assertThat(read == null ? 0 : read.offset()).as("%s", partition).isEqualTo(end);
                }
              });
    }
  }
}
