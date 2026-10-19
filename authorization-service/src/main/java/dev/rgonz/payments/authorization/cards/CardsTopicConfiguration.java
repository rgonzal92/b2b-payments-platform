package dev.rgonz.payments.authorization.cards;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The platform's {@code cards} topic, declared here too, as the platform declares the service's
 * {@code authorizations}: whichever app starts first creates it with its three partitions before
 * {@link CardsTopicListener} subscribes. A consumer subscribing to a missing topic has the broker
 * create it with one partition, and stays on that one for minutes after the other two are added.
 */
@Configuration(proxyBeanMethods = false)
class CardsTopicConfiguration {

  static final String NAME = "cards";

  @Bean
  NewTopic cardsTopic() {
    return new NewTopic(NAME, 3, (short) 1);
  }
}
