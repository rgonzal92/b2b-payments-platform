package dev.rgonz.payments.platform;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;

/**
 * Runs one module's migrations in the module's own schema, with its own history table (ADR-0009).
 */
public final class ModuleMigrations {

  private ModuleMigrations() {}

  public static FlywayMigrationInitializer of(DataSource dataSource, String module) {
    Flyway flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(module)
            .locations("classpath:db/migration/" + module)
            .load();
    return new FlywayMigrationInitializer(flyway);
  }
}
