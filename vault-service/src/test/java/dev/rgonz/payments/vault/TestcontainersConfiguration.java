package dev.rgonz.payments.vault;

import java.nio.file.Path;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The service's own PostgreSQL and a dev-mode OpenBao set up by the repository's {@code
 * openbao/dev-init.hcl}, as compose.yaml runs them.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  /** TEST-ONLY dev root token, not a secret: it opens only the throwaway container. */
  public static final String ROOT_TOKEN = "test-root-token";

  /**
   * TEST-ONLY token for the service, under the vault-service policy; the same as in tests' config.
   */
  public static final String SERVICE_TOKEN = "test-openbao-token";

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgresContainer() {
    // Same tag as compose.yaml's vault-postgres: change both together.
    return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));
  }

  @Bean
  GenericContainer<?> openBaoContainer() {
    // Same image as compose.yaml's openbao: change both together.
    return new GenericContainer<>(DockerImageName.parse("openbao/openbao:2.7.1"))
        .withEnv("BAO_DEV_ROOT_TOKEN_ID", ROOT_TOKEN)
        .withEnv("OPENBAO_TOKEN", SERVICE_TOKEN)
        .withEnv("SKIP_CHOWN", "true")
        .withFileSystemBind(
            Path.of("../openbao").toAbsolutePath().toString(),
            "/openbao/config",
            BindMode.READ_ONLY)
        .withExposedPorts(8200)
        // Self-initialization has run once the dev server says it has started.
        .waitingFor(Wait.forLogMessage(".*OpenBao server started!.*", 1));
  }

  @Bean
  DynamicPropertyRegistrar openBaoProperties(GenericContainer<?> openBaoContainer) {
    return properties ->
        properties.add(
            "openbao.address",
            () ->
                "http://"
                    + openBaoContainer.getHost()
                    + ":"
                    + openBaoContainer.getMappedPort(8200));
  }
}
