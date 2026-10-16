package dev.rgonz.payments.platform.buyers;

import dev.rgonz.payments.platform.shared.ModuleMigrations;
import javax.sql.DataSource;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class BuyersSchema {

  @Bean
  FlywayMigrationInitializer buyersMigrations(DataSource dataSource) {
    return ModuleMigrations.of(dataSource, "buyers");
  }
}
