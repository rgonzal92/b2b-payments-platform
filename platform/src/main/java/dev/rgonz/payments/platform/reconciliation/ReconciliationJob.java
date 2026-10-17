package dev.rgonz.payments.platform.reconciliation;

import dev.rgonz.payments.platform.authorization.ApprovedAuthorizations;
import dev.rgonz.payments.platform.clearing.RecordedClearing;
import dev.rgonz.payments.platform.clearing.RecordedClearings;
import dev.rgonz.payments.platform.clearing.RecordedSettlements;
import dev.rgonz.payments.platform.payables.InvoicePayments;
import java.time.LocalDate;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.support.ListItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The Reconciliation of one Business Date, a Spring Batch job in the job repository the Clearing
 * File import uses. Each run is a job instance of its own, identified by the Business Date and a
 * new run ID, so a date reconciled already can be reconciled again: a failed run isn't restarted
 * but run again, and the Breaks' natural keys keep the re-run from raising any twice. Each step
 * finds one kind of Break and writes its Breaks through {@link BreakWriter}; the run's summary adds
 * up every step's.
 */
@Configuration(proxyBeanMethods = false)
class ReconciliationJob {

  static final String NAME = "reconciliation";

  /** The Business Date reconciled. */
  static final String BUSINESS_DATE = "businessDate";

  /** A new ID for each run, so each run is a job instance of its own. */
  static final String RUN_ID = "runId";

  private static final int CHUNK_SIZE = 100;

  @Bean
  Job reconciliation(
      JobRepository jobRepository,
      Step reconcileClearings,
      Step reconcileNeverCleared,
      Step reconcileUnpaidInvoices,
      Step reconcileSettlements) {
    return new JobBuilder(NAME, jobRepository)
        .start(reconcileClearings)
        .next(reconcileNeverCleared)
        .next(reconcileUnpaidInvoices)
        .next(reconcileSettlements)
        .build();
  }

  // The Settlements of the day's Clearing Files that didn't match.
  @Bean
  Step reconcileSettlements(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      ListItemReader<Break> mismatchedSettlementsOfTheDay,
      BreakWriter breakWriter) {
    return breaksStep(
        "settlements",
        jobRepository,
        transactionManager,
        mismatchedSettlementsOfTheDay,
        breakWriter);
  }

  @Bean
  @StepScope
  ListItemReader<Break> mismatchedSettlementsOfTheDay(
      @Value("#{jobParameters['" + BUSINESS_DATE + "']}") LocalDate businessDate,
      RecordedSettlements settlements) {
    return new ListItemReader<>(
        settlements.mismatchedOn(businessDate).stream()
            .map(settlement -> RecordBreaks.mismatchedSettlement(businessDate, settlement))
            .toList());
  }

  // The approved Authorizations whose cards expired by the 7-day rule that day.
  @Bean
  Step reconcileNeverCleared(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      ListItemReader<Break> neverClearedOfTheDay,
      BreakWriter breakWriter) {
    return breaksStep(
        "neverCleared", jobRepository, transactionManager, neverClearedOfTheDay, breakWriter);
  }

  @Bean
  @StepScope
  ListItemReader<Break> neverClearedOfTheDay(
      @Value("#{jobParameters['" + BUSINESS_DATE + "']}") LocalDate businessDate,
      ApprovedAuthorizations authorizations,
      InvoicePayments payments) {
    return new ListItemReader<>(
        authorizations.neverClearedOn(businessDate).stream()
            .map(
                authorization ->
                    RecordBreaks.neverCleared(
                        businessDate,
                        authorization,
                        payments.invoiceForCard(authorization.cardToken()).orElse(null)))
            .toList());
  }

  // The Payments that ended unpaid that day, each an unpaid-Invoice Break.
  @Bean
  Step reconcileUnpaidInvoices(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      ListItemReader<Break> unpaidInvoicesOfTheDay,
      BreakWriter breakWriter) {
    return breaksStep(
        "unpaidInvoices", jobRepository, transactionManager, unpaidInvoicesOfTheDay, breakWriter);
  }

  @Bean
  @StepScope
  ListItemReader<Break> unpaidInvoicesOfTheDay(
      @Value("#{jobParameters['" + BUSINESS_DATE + "']}") LocalDate businessDate,
      InvoicePayments payments) {
    return new ListItemReader<>(
        payments.endedUnpaidOn(businessDate).stream()
            .map(payment -> RecordBreaks.unpaidInvoice(businessDate, payment))
            .toList());
  }

  // A step whose reader finds Breaks already, read whole when it starts, as the day's records are
  // few: a chunk of 100 at a time, each chunk's Breaks in one transaction.
  private static Step breaksStep(
      String name,
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      ListItemReader<Break> found,
      BreakWriter breakWriter) {
    return new StepBuilder(name, jobRepository)
        .<Break, Break>chunk(CHUNK_SIZE)
        .transactionManager(transactionManager)
        .reader(found)
        .writer(breakWriter)
        .build();
  }

  // The day's Clearings, a chunk of 100 at a time, each chunk's Breaks in one transaction.
  @Bean
  Step reconcileClearings(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      ListItemReader<RecordedClearing> clearingsOfTheDay,
      BreakWriter breakWriter,
      InvoicePayments payments) {
    return new StepBuilder("clearings", jobRepository)
        .<RecordedClearing, Break>chunk(CHUNK_SIZE)
        .transactionManager(transactionManager)
        .reader(clearingsOfTheDay)
        .processor(new ClearingBreaks(payments::invoiceForCard))
        .writer(breakWriter)
        .build();
  }

  // The day's Clearings, read whole through clearing's API when the step starts: clearing's tables
  // are its own (ADR-0009).
  @Bean
  @StepScope
  ListItemReader<RecordedClearing> clearingsOfTheDay(
      @Value("#{jobParameters['" + BUSINESS_DATE + "']}") LocalDate businessDate,
      RecordedClearings clearings) {
    return new ListItemReader<>(clearings.onBusinessDate(businessDate));
  }

  @Bean
  @StepScope
  BreakWriter breakWriter(Breaks breaks, @Value("#{stepExecution}") StepExecution stepExecution) {
    return new BreakWriter(breaks, stepExecution);
  }
}
