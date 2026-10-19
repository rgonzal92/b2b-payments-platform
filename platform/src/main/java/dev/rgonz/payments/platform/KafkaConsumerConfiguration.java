package dev.rgonz.payments.platform;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

/**
 * The platform's Kafka consumers. What they do when a message fails, as authorization-service's
 * consumers do: a failure that may pass, such as the database being down, is retried with a growing
 * pause and no limit: the partition waits on the message rather than skip a confirmation. A message
 * that can never be read, whose JSON is malformed or lacks a field the platform needs, is logged by
 * partition and offset (never its contents) and skipped. Spring Boot applies this handler to every
 * {@code @KafkaListener}.
 */
@Configuration(proxyBeanMethods = false)
class KafkaConsumerConfiguration {

  // The longest pause stays far below max.poll.interval.ms (5 minutes), past which Kafka would
  // hand the partition to another consumer.
  private static final long FIRST_PAUSE_MS = 1_000;
  private static final long LONGEST_PAUSE_MS = 30_000;

  @Bean
  DefaultErrorHandler kafkaConsumerErrorHandler() {
    var backOff = new ExponentialBackOff(FIRST_PAUSE_MS, 2);
    backOff.setMaxInterval(LONGEST_PAUSE_MS);
    backOff.setMaxElapsedTime(Long.MAX_VALUE);
    var handler = new DefaultErrorHandler(backOff);
    handler.addNotRetryableExceptions(JacksonException.class);
    return handler;
  }

  // authorization-service owns the topic and declares it the same way. The platform declares it
  // too, so whichever starts first creates it with its three partitions before any listener
  // subscribes: a listener subscribing to a missing topic would have the broker create it with one,
  // and stay on that one partition for minutes after the owner adds the other two. Only where the
  // listeners run: test contexts without a broker would otherwise wait at startup to create it.
  @Bean
  @ConditionalOnBooleanProperty(name = "spring.kafka.listener.auto-startup", matchIfMissing = true)
  NewTopic authorizationsTopic() {
    return new NewTopic("authorizations", 3, (short) 1);
  }
}
