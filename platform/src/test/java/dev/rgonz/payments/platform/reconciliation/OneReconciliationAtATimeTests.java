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
import java.sql.Connection;
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
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Two platform pods never run two Reconciliations at once: each run holds a PostgreSQL advisory
 * lock on a connection of its own. Two runs here are two callers on one PostgreSQL, each on its own
 * connection, as two pods' would be. A run is held at its first step, reading the day's Clearings,
 * until the test lets it go, so the other caller comes while it runs. Whether the lock is free is
 * asked on a connection outside the pool, as another pod would: a session's own advisory lock is
 * re-entrant, so a pooled connection that kept it would find it free.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class OneReconciliationAtATimeTests {

  // Each test reconciles a Business Date of its own, apart from ReconciliationRunTests' dates.
  private static final AtomicInteger DAYS = new AtomicInteger();

  @MockitoSpyBean RecordedClearings clearings;
  @Autowired Reconciliations reconciliations;
  @Autowired MockMvcTester mvc;
  @Autowired DataSource dataSource;
  @Autowired JdbcClient jdbc;

  // Tests turn the schedule off, so its 00:05 cron never fires here: each pod's schedule is called
  // by hand.
  @Test
  void aScheduledRunWhileAnotherRunsLogsThatItSkippedAndOnlyOneRunHappens(CapturedOutput output)
      throws Exception {
    var held = holdRunsAtTheirFirstStep();
    var onePod = new ReconciliationSchedule(reconciliations);
    var otherPod = new ReconciliationSchedule(reconciliations);

    var first = CompletableFuture.runAsync(onePod::reconcileYesterday);
    assertThat(held.started().await(30, SECONDS)).isTrue();
    otherPod.reconcileYesterday();
    held.release().countDown();
    first.get(30, SECONDS);

    verify(clearings, times(1)).onBusinessDate(yesterday());
    assertThat(output)
        .contains(
            "This pod skipped the daily run: Another Reconciliation is running, so the"
                + " Reconciliation of "
                + yesterday()
                + " didn't start");
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
  void aRunThatEndedOrFailedLetsTheLockGo() throws SQLException {
    var date = newBusinessDate();
    doThrow(new IllegalStateException("clearing is unreachable"))
        .doCallRealMethod()
        .when(clearings)
        .onBusinessDate(date);

    assertThat(run(date)).hasStatus(500);
    assertThat(lockIsFree()).isTrue();
    assertThat(run(date)).hasStatus(201);
    assertThat(lockIsFree()).isTrue();
  }

  // The lock's connection drops mid-run, so the unlock fails: the run still answers what it found,
  // and the lock went with the connection.
  @Test
  void aRunWhoseUnlockFailedStillAnswersWhatItFound(CapturedOutput output) throws Exception {
    var held = holdRunsAtTheirFirstStep();
    var date = newBusinessDate();

    var first = CompletableFuture.supplyAsync(() -> run(date));
    assertThat(held.started().await(30, SECONDS)).isTrue();
    jdbc.sql(
            """
            select pg_terminate_backend(pid) from pg_locks
             where locktype = 'advisory' and granted
            """)
        .query()
        .singleValue();
    held.release().countDown();

    assertThat(first.get(30, SECONDS)).hasStatus(201);
    assertThat(output)
        .contains("Couldn't release the Reconciliation lock after the run of " + date);
    assertThat(lockIsFree()).isTrue();
  }

  // Another pod's run holds the lock, and its connection drops: PostgreSQL lets the lock go.
  @Test
  void aRunWhoseConnectionDroppedLetsTheNextOneRun() throws SQLException {
    var date = newBusinessDate();
    try (var otherPod = outsideThePool()) {
      var lock = on(otherPod);
      int pid = lock.sql("select pg_backend_pid()").query(Integer.class).single();
      assertThat(tryLock(lock)).isTrue();
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
              assertThat(held.release().await(30, SECONDS)).isTrue();
              return invocation.callRealMethod();
            })
        .when(clearings)
        .onBusinessDate(any());
    return held;
  }

  // Takes the lock and lets it go again, on a connection of another pod's, if no run holds it.
  private boolean lockIsFree() throws SQLException {
    try (var otherPod = outsideThePool()) {
      var lock = on(otherPod);
      var free = tryLock(lock);
      if (free) {
        lock.sql("select pg_advisory_unlock(hashtext(:lock))")
            .param("lock", Reconciliations.LOCK)
            .query(Boolean.class)
            .single();
      }
      return free;
    }
  }

  private static boolean tryLock(JdbcClient lock) {
    return lock.sql("select pg_try_advisory_lock(hashtext(:lock))")
        .param("lock", Reconciliations.LOCK)
        .query(Boolean.class)
        .single();
  }

  private Connection outsideThePool() throws SQLException {
    var pool = dataSource.unwrap(HikariDataSource.class);
    return DriverManager.getConnection(pool.getJdbcUrl(), pool.getUsername(), pool.getPassword());
  }

  private static JdbcClient on(Connection connection) {
    return JdbcClient.create(new SingleConnectionDataSource(connection, true));
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
