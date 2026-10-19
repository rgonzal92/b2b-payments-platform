package dev.rgonz.payments.platform;

import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.EventExternalizationConfiguration;

/**
 * Spring Modulith's Kafka externalization (ADR-0008, ADR-0016): every event annotated
 * {@code @Externalized} is sent from the event publication registry, the platform's outbox, with
 * its event type in a header. Externalization runs one event at a time, but each from its own
 * thread after its commit, so one card's events may reach the broker out of the order they were
 * published; consumers must not depend on it. Tests that don't use Kafka keep it off with {@code
 * spring.modulith.events.externalization.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "spring.modulith.events.externalization.enabled",
    matchIfMissing = true)
class KafkaExternalizationConfiguration {

  static final String EVENT_TYPE_HEADER = "event-type";

  @Bean
  EventExternalizationConfiguration eventExternalizationConfiguration() {
    return EventExternalizationConfiguration.externalizing()
        .select(EventExternalizationConfiguration.annotatedAsExternalized())
        .headers(event -> Map.of(EVENT_TYPE_HEADER, event.getClass().getSimpleName()))
        .serializeExternalization(true)
        .build();
  }

  // Three partitions, as the M5 design sets for both topics: one card's events share a partition
  // through their key, and three consumers can share the topic.
  @Bean
  NewTopic cardsTopic() {
    return new NewTopic("cards", 3, (short) 1);
  }
}
