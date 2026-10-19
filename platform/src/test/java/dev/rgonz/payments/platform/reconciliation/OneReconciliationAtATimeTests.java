package dev.rgonz.payments.platform.reconciliation;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.zaxxer.hikari.HikariDataSource;
import dev.rgonz.payments.platform.TestcontainersConfiguration;
import dev.rgonz.payments.platform.clearing.RecordedClearings;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Two platform pods never run two Reconciliations at once: each run holds a PostgreSQL advisory
 * lock on a connection of its own. Two runs here are two callers on one PostgreSQL, each on its own
 * connection, as two pods' would be. A run is held at its first step, reading the day's Clearings,
 * until the test lets it go, so the other caller comes while it runs.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "reconciliation.daily-run.enabled=true")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class OneReconciliationAtATimeTests {

  // Each test reconciles a Business Date of its own, apart from ReconciliationRunTests' dates.
  private static final AtomicInteger DAYS = new AtomicInteger();

  @MockitoSpyBean RecordedClearings clearings;
  @Autowired ReconciliationSchedule schedule;
  @Autowired MockMvcTester mvc;
  @Autowired DataSource dataSource;
  @Autowired JdbcClient jdbc;

  @Test
  void aScheduledRunWhileAnotherRunsLogsThatItSkippedAndOnlyOneRunHappens(CapturedOutput output)
      throws Exception {
    var held = holdRunsAtTheirFirstStep();

    var first = CompletableFuture.runAsync(schedule::reconcileYesterday);
    assertThat(held.started().await(30, SECONDS)).isTrue();
    schedule.reconcileYesterday();
    held.release().countDown();
    first.get(30, SECONDS);

    verify(clearings, times(1)).onBusinessDate(yesterday());
    assertThat(output).contains("skipped the Reconciliation of " + yesterday());
  }

  @Test
  void aRunAskedForWhileAnotherRunsIsAConflict() throws Exception {
    var held = holdRunsAtTheirFirstStep();
    var date = newBusinessDate();

    var first = CompletableFuture.supplyAsync(() -> run(date));
    assertThat(held.started().await(30, SECONDS)).isTrue();
    var second = run(date);
    held.release().countDown();

    assertThat(second).hasStatus(409);
    assertThat(second).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(second).bodyJson().extractingPath("$.status").isEqualTo(409);
    assertThat(first.get(30, SECONDS)).hasStatus(201);
  }

  @Test
  void aRunThatFailedLetsTheNextOneRun() {
    var date = newBusinessDate();
    doThrow(new IllegalStateException("clearing is unreachable"))
        .doCallRealMethod()
        .when(clearings)
        .onBusinessDate(date);

    assertThat(run(date)).hasStatus(500);
    assertThat(run(date)).hasStatus(201);
  }

  // Another pod's run holds the lock, on a connection outside this pod's pool, and its connection
  // drops: PostgreSQL lets the lock go.
  @Test
  void aRunWhoseConnectionDroppedLetsTheNextOneRun() throws SQLException {
    var date = newBusinessDate();
    var pool = dataSource.unwrap(HikariDataSource.class);
    try (var otherPod =
        DriverManager.getConnection(pool.getJdbcUrl(), pool.getUsername(), pool.getPassword())) {
      int pid;
      try (var statement = otherPod.createStatement();
          var result =
              statement.executeQuery(
                  "select pg_backend_pid(), pg_try_advisory_lock(hashtext('"
                      + Reconciliations.LOCK
                      + "'))")) {
        result.next();
        pid = result.getInt(1);
        assertThat(result.getBoolean(2)).isTrue();
      }
      assertThat(run(date)).hasStatus(409);

      jdbc.sql("select pg_terminate_backend(:pid)").param("pid", pid).query().singleValue();

      assertThat(run(date)).hasStatus(201);
    }
  }

  private record Held(CountDownLatch started, CountDownLatch release) {}

  // Every run waits at its first step, after it took the lock, until the test releases it.
  private Held holdRunsAtTheirFirstStep() {
    var held = new Held(new CountDownLatch(1), new CountDownLatch(1));
    doAnswer(
            invocation -> {
              held.started().countDown();
              held.release().await(30, SECONDS);
              return invocation.callRealMethod();
            })
        .when(clearings)
        .onBusinessDate(any());
    return held;
  }

  private MvcTestResult run(LocalDate businessDate) {
    return mvc.post()
        .uri("/v1/reconciliation-runs")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_issuer-admin")))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"businessDate\": \"%s\"}".formatted(businessDate))
        .exchange();
  }

  private static LocalDate yesterday() {
    return LocalDate.now(ZoneOffset.UTC).minusDays(1);
  }

  private static LocalDate newBusinessDate() {
    return LocalDate.of(2003, 1, 1).plusDays(DAYS.getAndIncrement());
  }
}
