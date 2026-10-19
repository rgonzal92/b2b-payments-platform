package dev.rgonz.payments.platform.authorization;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * An Authorization Copy that has always caught up, for tests that import Clearing Files without a
 * Kafka broker: the real check would find none and hold every file. Tests of the wait run a broker
 * and leave the real check in place.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CaughtUpAuthorizationCopy {

  @Bean
  @Primary
  AuthorizationCopyFreshness caughtUpAuthorizationCopy() {
    return () -> true;
  }
}
