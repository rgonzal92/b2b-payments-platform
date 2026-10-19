package dev.rgonz.payments.authorization.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The {@code authorizations} topic, and scheduling for {@link OutboxRelaySchedule}, the service's
 * only scheduled task.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class OutboxConfiguration {

  // Three partitions, as the M5 design sets for both topics, like the platform's cards topic: one
  // card's events share a partition through their key, and three consumers can share the topic.
  @Bean
  NewTopic authorizationsTopic() {
    return new NewTopic(OutboxRelay.TOPIC, 3, (short) 1);
  }
}
