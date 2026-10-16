package dev.rgonz.payments.platform.vault;

import dev.rgonz.payments.platform.shared.ModuleMigrations;
import javax.sql.DataSource;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class VaultSchema {

  @Bean
  FlywayMigrationInitializer vaultMigrations(DataSource dataSource) {
    return ModuleMigrations.of(dataSource, "vault");
  }
}
