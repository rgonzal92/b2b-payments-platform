package dev.rgonz.payments.platform.reconciliation;

import dev.rgonz.payments.platform.shared.ModuleMigrations;
import javax.sql.DataSource;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ReconciliationSchema {

  @Bean
  FlywayMigrationInitializer reconciliationMigrations(DataSource dataSource) {
    return ModuleMigrations.of(dataSource, "reconciliation");
  }
}
