package dev.rgonz.payments.vault.cards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** A dependency out of reach answers UNAVAILABLE, so the caller waits and retries (design Q23). */
class UnavailableDependenciesTests {

  private final UnavailableDependencies handler = new UnavailableDependencies();

  @Test
  void aDatabaseOutOfReachIsUnavailable() {
    // Port 1 on this machine: nothing listens there, so the connection is refused at once.
    var database =
        JdbcClient.create(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/vault"));
    var failure = catchThrowable(() -> database.sql("select 1").query(Integer.class).single());

    assertThat(handler.handleException(failure).getStatus().getCode())
        .isEqualTo(Status.Code.UNAVAILABLE);
  }

  @Test
  void anyOtherFailureIsLeftToSpringGrpc() {
    assertThat(handler.handleException(new IllegalStateException("a bug"))).isNull();
  }
}
