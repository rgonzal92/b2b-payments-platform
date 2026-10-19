package dev.rgonz.payments.platform.authorization;

import java.util.HashMap;
import java.util.List;
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
class AuthorizationCopyLag implements AuthorizationCopy, DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(AuthorizationCopyLag.class);
  private static final String TOPIC = "authorizations";
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
      var lag = lag();
      if (lag > 0) {
        log.info(
            "The Authorization Copy lags {} events behind the {} topic (group {})",
            lag,
            TOPIC,
            AuthorizationEventsListener.GROUP_ID);
      }
      return lag == 0;
    } catch (ExecutionException e) {
      log.warn(
          "Couldn't read the Authorization Copy's lag from Kafka: {}", e.getCause().toString());
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private long lag() throws ExecutionException, InterruptedException {
    var partitions =
        admin.describeTopics(List.of(TOPIC)).allTopicNames().get().get(TOPIC).partitions().stream()
            .map(info -> new TopicPartition(TOPIC, info.partition()))
            .toList();
    var ends =
        admin
            .listOffsets(
                partitions.stream()
                    .collect(Collectors.toMap(partition -> partition, _ -> OffsetSpec.latest())))
            .all()
            .get();
    var committed =
        admin
            .listConsumerGroupOffsets(AuthorizationEventsListener.GROUP_ID)
            .partitionsToOffsetAndMetadata()
            .get();
    var lag = 0L;
    for (var partition : partitions) {
      // A partition the group never committed on lags by all it holds.
      var read = committed.get(partition);
      lag += ends.get(partition).offset() - (read == null ? 0 : read.offset());
    }
    return lag;
  }

  @Override
  public void destroy() {
    admin.close();
  }
}
