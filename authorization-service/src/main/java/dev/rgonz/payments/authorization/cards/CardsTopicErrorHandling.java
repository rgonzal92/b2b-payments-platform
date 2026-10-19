package dev.rgonz.payments.authorization.cards;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

/**
 * What the {@code cards} consumer does when a message fails. A failure that may pass, such as the
 * database being down, is retried with a growing pause and no limit: the partition waits on the
 * message rather than skip a card. A message that can never be read, whose JSON is malformed or
 * lacks a field the service needs, is logged by partition and offset (never its contents) and
 * skipped. Spring Boot applies this handler to every {@code @KafkaListener}.
 */
@Configuration(proxyBeanMethods = false)
class CardsTopicErrorHandling {

  // The longest pause stays far below max.poll.interval.ms (5 minutes), past which Kafka would
  // hand the partition to another consumer.
  private static final long FIRST_PAUSE_MS = 1_000;
  private static final long LONGEST_PAUSE_MS = 30_000;

  @Bean
  DefaultErrorHandler cardsTopicErrorHandler() {
    var backOff = new ExponentialBackOff(FIRST_PAUSE_MS, 2);
    backOff.setMaxInterval(LONGEST_PAUSE_MS);
    backOff.setMaxElapsedTime(Long.MAX_VALUE);
    var handler = new DefaultErrorHandler(backOff);
    handler.addNotRetryableExceptions(JacksonException.class);
    return handler;
  }
}
