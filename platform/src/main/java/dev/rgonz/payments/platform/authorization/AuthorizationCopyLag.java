package dev.rgonz.payments.platform.authorization;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * Compares, through Kafka's admin client, the offsets {@code AuthorizationEventsListener}'s group
 * committed with the end of each partition of the {@code authorizations} topic. Each call gives up
 * after 5 seconds, so a Kafka outage answers "not caught up" without holding the drop-folder check.
 */
@Component
class AuthorizationCopyLag implements AuthorizationCopyFreshness, DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(AuthorizationCopyLag.class);
  private static final String TOPIC = AuthorizationEventsListener.TOPIC;
  private static final int TIMEOUT_MS = 5_000;

  private final Admin admin;

  // One client for the platform's life: it connects only when first asked.
  AuthorizationCopyLag(KafkaAdmin kafkaAdmin) {
    var config = new HashMap<>(kafkaAdmin.getConfigurationProperties());
    config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, TIMEOUT_MS);
    config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, TIMEOUT_MS);
    this.admin = Admin.create(config);
  }

  @Override
  public boolean caughtUp() {
    try {
      var lags = lags();
      if (!lags.isEmpty()) {
        log.info(
            "The Authorization Copy lags behind the {} topic (group {}), by partition: {}",
            TOPIC,
            AuthorizationEventsListener.GROUP_ID,
            lags);
      }
      return lags.isEmpty();
    } catch (ExecutionException e) {
      log.warn(
          "Couldn't read the Authorization Copy's lag from Kafka: {}", e.getCause().toString());
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * Each partition the group hasn't read to its end, with how many events it is behind: from the
   * offset the group committed, or, on a partition it never committed on, from the partition's
   * first retained offset, where the group would start (auto-offset-reset earliest). Partition by
   * partition, so one partition can't offset another's lag.
   */
  static Map<TopicPartition, Long> lags(
      Map<TopicPartition, Long> ends,
      Map<TopicPartition, Long> committed,
      Map<TopicPartition, Long> firsts) {
    var lags =
        new TreeMap<TopicPartition, Long>(Comparator.comparingInt(TopicPartition::partition));
    ends.forEach(
        (partition, end) -> {
          var read =
              committed.containsKey(partition) ? committed.get(partition) : firsts.get(partition);
          if (read < end) {
            lags.put(partition, end - read);
          }
        });
    return lags;
  }

  private Map<TopicPartition, Long> lags() throws ExecutionException, InterruptedException {
    var partitions =
        admin.describeTopics(List.of(TOPIC)).allTopicNames().get().get(TOPIC).partitions().stream()
            .map(info -> new TopicPartition(TOPIC, info.partition()))
            .toList();
    var ends = offsets(partitions, OffsetSpec.latest());
    var committed = new HashMap<TopicPartition, Long>();
    admin
        .listConsumerGroupOffsets(AuthorizationEventsListener.GROUP_ID)
        .partitionsToOffsetAndMetadata()
        .get()
        .forEach(
            (partition, offset) -> {
              if (offset != null) {
                committed.put(partition, offset.offset());
              }
            });
    var uncommitted = partitions.stream().filter(p -> !committed.containsKey(p)).toList();
    var firsts =
        uncommitted.isEmpty()
            ? Map.<TopicPartition, Long>of()
            : offsets(uncommitted, OffsetSpec.earliest());
    return lags(ends, committed, firsts);
  }

  private Map<TopicPartition, Long> offsets(List<TopicPartition> partitions, OffsetSpec spec)
      throws ExecutionException, InterruptedException {
    return admin
        .listOffsets(partitions.stream().collect(Collectors.toMap(p -> p, _ -> spec)))
        .all()
        .get()
        .entrySet()
        .stream()
        .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().offset()));
  }

  @Override
  public void destroy() {
    admin.close();
  }
}
