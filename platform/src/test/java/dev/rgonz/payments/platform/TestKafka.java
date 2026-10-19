package dev.rgonz.payments.platform;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A single-broker Kafka, started for the tests that read the platform's topic. Tests that don't
 * import it keep externalization off and start no broker.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestKafka {

  @Bean
  @ServiceConnection
  KafkaContainer kafkaContainer() {
    // Same image as compose.yaml: change both together.
    return new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.1"));
  }
}
