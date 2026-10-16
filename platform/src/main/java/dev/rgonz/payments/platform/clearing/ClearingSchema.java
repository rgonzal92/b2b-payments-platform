package dev.rgonz.payments.platform.clearing;

import dev.rgonz.payments.platform.shared.ModuleMigrations;
import javax.sql.DataSource;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ClearingSchema {

  @Bean
  FlywayMigrationInitializer clearingMigrations(DataSource dataSource) {
    return ModuleMigrations.of(dataSource, "clearing");
  }

  // Spring Batch's job repository, which only the Clearing File import uses, in a schema of its own
  // (spring.batch.jdbc.table-prefix=batch.BATCH_). Spring Boot creates the job repository only
  // after every Flyway migration has run.
  @Bean
  FlywayMigrationInitializer batchMigrations(DataSource dataSource) {
    return ModuleMigrations.of(dataSource, "batch");
  }
}
