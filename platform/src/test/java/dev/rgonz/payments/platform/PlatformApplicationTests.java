package dev.rgonz.payments.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PlatformApplicationTests {

  @Autowired JdbcClient jdbc;

  @Test
  void connectsToPostgres() {
    assertThat(jdbc.sql("select 1").query(Integer.class).single()).isEqualTo(1);
  }
}
