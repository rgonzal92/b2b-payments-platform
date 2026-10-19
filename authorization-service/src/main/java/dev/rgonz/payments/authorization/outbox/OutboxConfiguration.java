package dev.rgonz.payments.authorization.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The {@code authorizations} topic, and the schedule that runs {@link OutboxRelay}. Tests that run
 * relay passes themselves turn the schedule off with {@code outbox.relay.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
class OutboxConfiguration {

  // Three partitions, as the M5 design sets for both topics, like the platform's cards topic: one
  // card's events share a partition through their key, and three consumers can share the topic.
  @Bean
  NewTopic authorizationsTopic() {
    return new NewTopic(OutboxRelay.TOPIC, 3, (short) 1);
  }

  // The relay is the service's only scheduled task, so the flag turns scheduling as a whole on.
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnBooleanProperty(name = "outbox.relay.enabled", matchIfMissing = true)
  static class RelaySchedule {}
}
