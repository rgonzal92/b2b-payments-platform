package dev.rgonz.payments.platform.clearing;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.ExecutionContextSerializer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.dao.JacksonExecutionContextStringSerializer;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The Clearing File import, a Spring Batch job run once per file, keyed by the file's ID: Spring
 * Batch refuses to run a file whose import completed again, and restarts one that stopped from the
 * last chunk it committed. Steps run in order: {@code validate}, {@code import}, {@code settle},
 * {@code complete}. Every step's transaction is the platform's own DataSource transaction, which
 * the job repository shares, so a chunk's Journal Entries, Holds, cards and Clearings, and the
 * record of how far the step got, commit together.
 */
@Configuration(proxyBeanMethods = false)
class ClearingFileImportJob {

  static final String NAME = "clearingFileImport";

  /** The file's ID, from its header: the job parameter that identifies a run. */
  static final String FILE_ID = "fileId";

  /** Where the file waits during its import: not identifying, so it names no new run. */
  static final String FILE = "file";

  /** The first step: its failure means the file was refused and nothing posted. */
  static final String VALIDATE = "validate";

  private static final int CHUNK_SIZE = 100;

  @Bean
  Job clearingFileImport(
      JobRepository jobRepository,
      Step validateClearingFile,
      Step importClearings,
      Step settleClearingFile,
      Step completeClearingFile) {
    return new JobBuilder(NAME, jobRepository)
        .start(validateClearingFile)
        .next(importClearings)
        .next(settleClearingFile)
        .next(completeClearingFile)
        .build();
  }

  // The header and trailer are present and parse, and so does every record between them, so the
  // import step never meets a malformed line; the file is recorded, IMPORTING. Anything wrong fails
  // the step, and the job, before a single Clearing is posted.
  @Bean
  Step validateClearingFile(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      ClearingFiles files) {
    return new StepBuilder(VALIDATE, jobRepository)
        .tasklet(
            (contribution, chunkContext) -> {
              var parameters = contribution.getStepExecution().getJobParameters();
              var clearings = new AtomicLong();
              ClearingFileLayout.Envelope envelope;
              try (var lines = ClearingFileLayout.open(Path.of(parameter(parameters, FILE)))) {
                envelope = ClearingFileLayout.read(lines, clearing -> clearings.incrementAndGet());
              }
              if (!envelope.header().fileId().equals(parameter(parameters, FILE_ID))) {
                throw new MalformedClearingFileException(
                    1, "the file ID isn't the one its import started with");
              }
              files.receive(envelope.header(), clearings.get());
              return RepeatStatus.FINISHED;
            },
            transactionManager)
        .build();
  }

  // Chunks of 100 Clearings, each one transaction: a stop rolls back only the chunk it was in.
  @Bean
  Step importClearings(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      ClearingRecordReader clearingRecordReader,
      ClearingRecordWriter clearingRecordWriter) {
    return new StepBuilder("import", jobRepository)
        .<ClearingRecord, ClearingRecord>chunk(CHUNK_SIZE)
        .transactionManager(transactionManager)
        .reader(clearingRecordReader)
        .writer(clearingRecordWriter)
        .build();
  }

  // Once every Clearing is recorded, the file is settled at its trailer's net settlement amount, in
  // one transaction. The file still waits in processing/, so its trailer is read from it again.
  @Bean
  Step settleClearingFile(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      Settlements settlements) {
    return new StepBuilder("settle", jobRepository)
        .tasklet(
            (contribution, chunkContext) -> {
              var parameters = contribution.getStepExecution().getJobParameters();
              FileTrailer trailer;
              try (var lines = ClearingFileLayout.open(Path.of(parameter(parameters, FILE)))) {
                trailer = ClearingFileLayout.read(lines, clearing -> {}).trailer();
              }
              settlements.settle(parameter(parameters, FILE_ID), trailer.netSettlementAmount());
              return RepeatStatus.FINISHED;
            },
            transactionManager)
        .build();
  }

  // The file becomes IMPORTED in the same transaction that records the step complete.
  @Bean
  Step completeClearingFile(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      ClearingFiles files) {
    return new StepBuilder("complete", jobRepository)
        .tasklet(
            (contribution, chunkContext) -> {
              files.markImported(
                  parameter(contribution.getStepExecution().getJobParameters(), FILE_ID));
              return RepeatStatus.FINISHED;
            },
            transactionManager)
        .build();
  }

  // Step-scoped: a new reader and writer for each step execution, built from its job's parameters,
  // so two files importing at once never share one.
  @Bean
  @StepScope
  ClearingRecordReader clearingRecordReader(
      @Value("#{jobParameters['" + FILE + "']}") String file) {
    return new ClearingRecordReader(Path.of(file));
  }

  @Bean
  @StepScope
  ClearingRecordWriter clearingRecordWriter(
      @Value("#{jobParameters['" + FILE_ID + "']}") String fileId, Clearings clearings) {
    return new ClearingRecordWriter(fileId, clearings);
  }

  // What a step saved, such as how many Clearings the reader read, kept as JSON in the job
  // repository instead of Spring Batch's default base64 Java serialization: a person (the runbook)
  // and the "no PAN outside the vault" check can both read it.
  @Bean
  ExecutionContextSerializer executionContextSerializer() {
    return new JacksonExecutionContextStringSerializer();
  }

  private static String parameter(JobParameters parameters, String name) {
    return Objects.requireNonNull(parameters.getString(name), name);
  }
}
