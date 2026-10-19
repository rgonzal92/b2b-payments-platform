package dev.rgonz.payments.authorization;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** The service's own PostgreSQL and a single-broker Kafka, as compose.yaml runs them. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgresContainer() {
    // Same tag as compose.yaml's authorization-postgres: change both together.
    return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));
  }

  @Bean
  @ServiceConnection
  KafkaContainer kafkaContainer() {
    // Same image as compose.yaml: change both together.
    return new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.1"));
  }
}
