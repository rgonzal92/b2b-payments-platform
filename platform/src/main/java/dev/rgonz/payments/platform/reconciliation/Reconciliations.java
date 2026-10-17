package dev.rgonz.payments.platform.reconciliation;

import java.time.LocalDate;
import java.util.UUID;
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
import org.springframework.stereotype.Service;

/** Runs the Reconciliation of a Business Date, and answers what it found. */
@Service
class Reconciliations {

  private static final Logger log = LoggerFactory.getLogger(Reconciliations.class);

  private final JobOperator jobOperator;
  private final Job reconciliation;

  Reconciliations(JobOperator jobOperator, Job reconciliation) {
    this.jobOperator = jobOperator;
    this.reconciliation = reconciliation;
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

  /**
   * Runs the Reconciliation of the Business Date and answers when it is done. Not transactional:
   * each of the job's chunks commits on its own.
   */
  RunSummary run(LocalDate businessDate) {
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
