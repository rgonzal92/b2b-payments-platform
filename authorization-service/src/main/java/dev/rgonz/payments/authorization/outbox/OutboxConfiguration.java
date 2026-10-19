package dev.rgonz.payments.authorization.outbox;

import io.micrometer.observation.ObservationPredicate;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

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

  // A relay pass every 100 ms would otherwise be a trace of its own, ten a second, burying the
  // Charges' traces in Tempo. Each row's send still runs in the trace the row was written in.
  @Bean
  ObservationPredicate noRelayPassObservations() {
    return (name, context) ->
        !(context instanceof ScheduledTaskObservationContext task
            && task.getTargetClass() == OutboxRelaySchedule.class);
  }
}
