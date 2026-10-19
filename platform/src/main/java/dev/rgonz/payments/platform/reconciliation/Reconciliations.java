package dev.rgonz.payments.platform.reconciliation;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;

/** Runs the Reconciliation of a Business Date, and answers what it found. */
@Service
class Reconciliations {

  private static final Logger log = LoggerFactory.getLogger(Reconciliations.class);

  /** The advisory lock a run holds, keyed by {@code hashtext} of this name. */
  static final String LOCK = "reconciliation";

  private final JobOperator jobOperator;
  private final Job reconciliation;
  private final DataSource dataSource;

  Reconciliations(JobOperator jobOperator, Job reconciliation, DataSource dataSource) {
    this.jobOperator = jobOperator;
    this.reconciliation = reconciliation;
    this.dataSource = dataSource;
  }

  /** What a run found: every Break, and of those, the ones it raised. */
  record RunSummary(UUID runId, LocalDate businessDate, long breaksFound, long newBreaks) {

    long knownBreaks() {
      return breaksFound - newBreaks;
    }
  }

  /** A run that didn't complete; the Breaks it raised before it stopped stay raised. */
  static class RunFailedException extends RuntimeException {
    RunFailedException(UUID runId, LocalDate businessDate) {
      super("The Reconciliation run " + runId + " of " + businessDate + " didn't complete");
    }
  }

  /** Another run, on this pod or another, holds the lock, so this one didn't start. */
  static class RunInProgressException extends RuntimeException {
    RunInProgressException(LocalDate businessDate) {
      super(
          "Another Reconciliation is running, so the Reconciliation of "
              + businessDate
              + " didn't start");
    }
  }

  /**
   * Runs the Reconciliation of the Business Date and answers when it is done, unless another run
   * holds the lock. Not transactional: each of the job's chunks commits on its own.
   */
  RunSummary run(LocalDate businessDate) {
    var connection = lock(businessDate);
    try {
      return runLocked(businessDate);
    } finally {
      release(connection, businessDate);
    }
  }

  // A session-level advisory lock on a connection of its own, outside the job's transactions and
  // held for the whole run: one run at a time across pods. PostgreSQL drops it with the connection.
  private Connection lock(LocalDate businessDate) {
    Connection connection;
    try {
      connection = dataSource.getConnection();
    } catch (SQLException e) {
      throw new IllegalStateException("Couldn't lock the Reconciliation of " + businessDate, e);
    }
    var locked = false;
    try {
      locked = tryLock(connection);
    } catch (DataAccessException e) {
      throw new IllegalStateException("Couldn't lock the Reconciliation of " + businessDate, e);
    } finally {
      if (!locked) {
        close(connection, businessDate);
      }
    }
    if (!locked) {
      throw new RunInProgressException(businessDate);
    }
    return connection;
  }

  // The pool keeps the connection open, so without the unlock the lock would outlive the run. When
  // the unlock fails, the connection is aborted, which ends its session and so the lock. Either way
  // the run's own outcome stands: failures here are only logged.
  private static void release(Connection connection, LocalDate businessDate) {
    try {
      unlock(connection);
    } catch (DataAccessException e) {
      log.warn(
          "Couldn't release the Reconciliation lock after the run of {}; aborting its connection",
          businessDate,
          e);
      try {
        connection.abort(Runnable::run);
      } catch (SQLException abortFailed) {
        log.warn("Couldn't abort the Reconciliation lock's connection", abortFailed);
      }
    }
    close(connection, businessDate);
  }

  private static void close(Connection connection, LocalDate businessDate) {
    try {
      connection.close();
    } catch (SQLException e) {
      log.warn(
          "Couldn't close the Reconciliation lock's connection after the run of {}",
          businessDate,
          e);
    }
  }

  private static boolean tryLock(Connection connection) {
    return Boolean.TRUE.equals(
        on(connection)
            .sql("select pg_try_advisory_lock(hashtext(:lock))")
            .param("lock", LOCK)
            .query(Boolean.class)
            .single());
  }

  private static void unlock(Connection connection) {
    on(connection)
        .sql("select pg_advisory_unlock(hashtext(:lock))")
        .param("lock", LOCK)
        .query(Boolean.class)
        .single();
  }

  // The lock's own connection, which closing the JdbcClient's statements leaves open.
  private static JdbcClient on(Connection connection) {
    return JdbcClient.create(new SingleConnectionDataSource(connection, true));
  }

  private RunSummary runLocked(LocalDate businessDate) {
    var runId = UUID.randomUUID();
    var parameters =
        new JobParametersBuilder()
            .addLocalDate(ReconciliationJob.BUSINESS_DATE, businessDate)
            .addString(ReconciliationJob.RUN_ID, runId.toString())
            .toJobParameters();
    JobExecution execution;
    try {
      execution = jobOperator.start(reconciliation, parameters);
    } catch (JobInstanceAlreadyCompleteException
        | JobExecutionAlreadyRunningException
        | JobRestartException
        | InvalidJobParametersException e) {
      // A new run ID makes a new job instance: none is complete, running or restarted.
      throw new IllegalStateException("Couldn't start the Reconciliation of " + businessDate, e);
    }
    if (execution.getStatus() != BatchStatus.COMPLETED) {
      log.error(
          "The Reconciliation run {} of {} didn't complete: see"
              + " docs/runbooks/reconciliation-run-failed.md",
          runId,
          businessDate);
      throw new RunFailedException(runId, businessDate);
    }
    var steps = execution.getStepExecutions();
    var summary =
        new RunSummary(
            runId,
            businessDate,
            steps.stream().mapToLong(StepExecution::getWriteCount).sum(),
            steps.stream()
                .mapToLong(step -> step.getExecutionContext().getLong(BreakWriter.NEW_BREAKS, 0))
                .sum());
    log.info(
        "Reconciliation run {} of {}: {} Breaks found, {} new",
        runId,
        businessDate,
        summary.breaksFound(),
        summary.newBreaks());
    return summary;
  }
}
