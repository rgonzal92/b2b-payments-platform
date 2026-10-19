package dev.rgonz.payments.platform.clearing;

import dev.rgonz.payments.platform.authorization.AuthorizationCopyFreshness;
import dev.rgonz.payments.platform.shared.Money;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Imports Clearing Files from the drop folder, where the Network delivers them. A file is moved
 * into the folder's {@code processing/} folder before its import starts, in one atomic rename, so
 * of two importers only one gets it; it waits there until its import completes, then is deleted. A
 * refused file moves on to the folder's {@code failed/} folder, where it waits for a person. Logs
 * name a file by its name or ID, never its contents.
 *
 * <p>Before an import starts, it waits for the platform's Authorization Copy to catch up with
 * authorization-service, so no Clearing is matched against a copy missing its approved
 * Authorization: while the copy lags, or Kafka can't be reached, the file stays where it is,
 * untouched. The next check of the drop folder tries a new file again; a restart of a file in
 * {@code processing/} must be requested again once the copy has caught up (M5 design, "The gaps").
 */
@Component
class ClearingFileImports {

  private static final Logger log = LoggerFactory.getLogger(ClearingFileImports.class);
  // The clearing.files counter's results.
  private static final String IMPORTED = "imported";
  private static final String REFUSED = "refused";
  private static final String REPEATED = "repeated";

  private final Path dropFolder;
  private final Path processingFolder;
  private final Path failedFolder;
  private final JobOperator jobOperator;
  private final Job clearingFileImport;
  private final ClearingFiles files;
  private final MeterRegistry meters;
  private final AuthorizationCopyFreshness authorizationCopy;

  // A String, not a Path: Spring converts a Path setting through its resource loader, which
  // resolves a relative path against the classpath, and fails once the platform runs from a jar.
  ClearingFileImports(
      @Value("${clearing.drop-folder}") String dropFolder,
      JobOperator jobOperator,
      Job clearingFileImport,
      ClearingFiles files,
      MeterRegistry meters,
      AuthorizationCopyFreshness authorizationCopy) {
    this.dropFolder = Path.of(dropFolder).toAbsolutePath().normalize();
    this.processingFolder = this.dropFolder.resolve("processing");
    this.failedFolder = this.dropFolder.resolve("failed");
    this.jobOperator = jobOperator;
    this.clearingFileImport = clearingFileImport;
    this.files = files;
    this.meters = meters;
    this.authorizationCopy = authorizationCopy;
    // Each series exists from startup at zero: Prometheus's rate() and increase() need a sample
    // before the first increment, or they miss it.
    List.of(IMPORTED, REFUSED, REPEATED).forEach(this::files);
    for (var outcome : ClearingOutcome.values()) {
      clearings(outcome);
    }
    settlements(true);
    settlements(false);
  }

  /**
   * Imports the file with this name from the drop folder, or restarts the import of the file with
   * this name in {@code processing/}, where an import that stopped left it; returns when the import
   * is done. Not transactional: each of the job's steps and chunks commits on its own.
   */
  ImportResult importFile(String fileName) {
    try {
      // A file's name only: no path out of the drop folder, and not a folder in it (processing/).
      var source = dropFolder.resolve(fileName).normalize();
      if (!dropFolder.equals(source.getParent()) || Files.isDirectory(source)) {
        return new ImportResult.NotFound();
      }
      var file = processingFolder.resolve(fileName);
      if (Files.notExists(file) && Files.notExists(source)) {
        return new ImportResult.NotFound();
      }
      if (!authorizationCopy.caughtUp()) {
        log.warn(
            "Clearing File {} waits: the platform's Authorization Copy hasn't caught up, or Kafka"
                + " can't be reached; see docs/runbooks/kafka-down.md",
            fileName);
        return new ImportResult.Waiting();
      }
      if (Files.notExists(file) && !claim(source, file)) {
        return new ImportResult.NotFound();
      }
      FileHeader header;
      try (var lines = ClearingFileLayout.open(file)) {
        header = ClearingFileLayout.readHeader(lines);
      } catch (MalformedClearingFileException malformed) {
        // Without a header there is no file ID to record it under.
        return refused(file, malformed.getMessage());
      }
      return run(header, file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Imports each new file in the drop folder, one after another, in name order. A name starting
   * with {@code .} is a file the Network is still writing, and waits for its rename (the layout's
   * delivery rule). A file whose import fails is logged and left where it is; the next one still
   * imports. A file in {@code processing/} isn't touched: only a request restarts a stopped import.
   */
  void importNewFiles() {
    List<String> names;
    try (var entries = Files.list(dropFolder)) {
      names =
          entries
              .filter(Files::isRegularFile)
              .map(file -> file.getFileName().toString())
              .filter(name -> !name.startsWith("."))
              .sorted()
              .toList();
    } catch (NoSuchFileException noFolderYet) {
      return;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    for (var name : names) {
      try {
        importFile(name);
      } catch (RuntimeException e) {
        log.error("Couldn't import Clearing File {}", name, e);
      }
    }
  }

  // Moves the file into processing/, unless another import got there first.
  private boolean claim(Path source, Path file) throws IOException {
    Files.createDirectories(processingFolder);
    try {
      Files.move(source, file, StandardCopyOption.ATOMIC_MOVE);
      return true;
    } catch (NoSuchFileException gone) {
      // Gone from the drop folder: it never was there, or another import moved it a moment ago.
      return Files.exists(file);
    }
  }

  private ImportResult run(FileHeader header, Path file) throws IOException {
    var fileId = header.fileId();
    var earlier = files.repeatedBy(fileId, ClearingFileLayout.contentHash(file));
    if (earlier.isPresent()) {
      return repeated(file, earlier.get());
    }
    var parameters =
        new JobParametersBuilder()
            .addString(ClearingFileImportJob.FILE_ID, fileId)
            .addString(ClearingFileImportJob.FILE, file.toString(), false)
            .toJobParameters();
    JobExecution execution;
    try {
      execution = jobOperator.start(clearingFileImport, parameters);
    } catch (JobInstanceAlreadyCompleteException alreadyImported) {
      return repeated(file, fileId);
    } catch (JobExecutionAlreadyRunningException running) {
      log.warn("Clearing File {} is being imported, or its import stopped abruptly", fileId);
      return new ImportResult.Running(fileId);
    } catch (JobRestartException | InvalidJobParametersException e) {
      throw new IllegalStateException("Couldn't start the import of Clearing File " + fileId, e);
    }
    if (execution.getStatus() == BatchStatus.COMPLETED) {
      // Already gone when an import of the same name started meanwhile found it repeated.
      Files.deleteIfExists(file);
      var imported = files.find(fileId).orElseThrow();
      log.info(
          "Clearing File {} (sequence {}, Business Date {}) imported: {} Clearings",
          imported.id(),
          imported.sequenceNumber(),
          imported.businessDate(),
          imported.recordCount());
      countImported(imported);
      var settlement = imported.settlement();
      if (!settlement.matched()) {
        log.warn(
            "Clearing File {} settled at the Network's {}, but its posted Clearings total {}: see"
                + " docs/runbooks/settlement-mismatched.md",
            fileId,
            readable(settlement.amount()),
            readable(settlement.importedTotal()));
      }
      return new ImportResult.Imported(imported);
    }
    var refusal = refusal(execution);
    if (refusal.isPresent()) {
      files.refuse(header, refusal.get());
      return refused(file, refusal.get());
    }
    log.error(
        "The import of Clearing File {} stopped partway; importing it again restarts it", fileId);
    return new ImportResult.Stopped(fileId);
  }

  // In major units for a person reading the log: 9850.00 USD.
  private static String readable(Money money) {
    return BigDecimal.valueOf(money.minorUnits(), money.currency().getDefaultFractionDigits())
        + " "
        + money.currency().getCurrencyCode();
  }

  // A check of the validate step that the file failed: nothing in it was posted. Any other failure,
  // such as the database being unreachable, stopped the import instead, and importing the file
  // again runs the validate step again.
  private static Optional<String> refusal(JobExecution execution) {
    return execution.getStepExecutions().stream()
        .filter(step -> ClearingFileImportJob.VALIDATE.equals(step.getStepName()))
        .flatMap(step -> step.getFailureExceptions().stream())
        .filter(MalformedClearingFileException.class::isInstance)
        .map(Throwable::getMessage)
        .findFirst();
  }

  // Counted once the import completes, from what it committed: a chunk that rolled back and ran
  // again counts once. Names and tags, never a PAN or Card Token, are what Grafana reads.
  private void countImported(ClearingFileSummary imported) {
    files(IMPORTED).increment();
    imported.outcomes().forEach((outcome, count) -> clearings(outcome).increment(count));
    settlements(imported.settlement().matched()).increment();
  }

  private Counter files(String result) {
    return Counter.builder("clearing.files")
        .description("Clearing Files the platform imported, refused, or refused as a repeat")
        .tag("result", result)
        .register(meters);
  }

  private Counter clearings(ClearingOutcome outcome) {
    return Counter.builder("clearing.clearings")
        .description("Clearings recorded by imported Clearing Files, by outcome")
        .tag("outcome", outcome.name())
        .register(meters);
  }

  private Counter settlements(boolean matched) {
    return Counter.builder("clearing.settlements")
        .description("Clearing File Settlements, by whether they matched the posted Clearings")
        .tag("matched", String.valueOf(matched))
        .register(meters);
  }

  private ImportResult refused(Path file, String reason) throws IOException {
    moveToFailed(file);
    files(REFUSED).increment();
    log.warn("Refused Clearing File {}: {}", file.getFileName(), reason);
    return new ImportResult.Refused(reason);
  }

  private ImportResult repeated(Path file, String earlierFileId) throws IOException {
    moveToFailed(file);
    files(REPEATED).increment();
    log.warn(
        "Refused Clearing File {}: it repeats Clearing File {}", file.getFileName(), earlierFileId);
    return new ImportResult.Repeated(earlierFileId);
  }

  // A refused file waits in failed/ until a person resolves it.
  private void moveToFailed(Path file) throws IOException {
    Files.createDirectories(failedFolder);
    Files.move(file, failedFolder.resolve(file.getFileName()), StandardCopyOption.ATOMIC_MOVE);
  }

  /** How an import ended. */
  sealed interface ImportResult {

    record Imported(ClearingFileSummary file) implements ImportResult {}

    /** No file has the name in the drop folder or in {@code processing/}. */
    record NotFound() implements ImportResult {}

    /**
     * The file failed a check of the validate step, so nothing was posted, and it moved to {@code
     * failed/}; the reason names the line and the field.
     */
    record Refused(String reason) implements ImportResult {}

    /**
     * The file repeats one the platform imported or is importing, so nothing was posted, and it
     * moved to {@code failed/}.
     */
    record Repeated(String earlierFileId) implements ImportResult {}

    /**
     * The platform's Authorization Copy hasn't caught up, or Kafka can't be reached: the file stays
     * where it is, untouched: in the drop folder for its next check, or in {@code processing/} for
     * the restart to be requested again.
     */
    record Waiting() implements ImportResult {}

    /** An import of the file is running, or stopped without its end being recorded. */
    record Running(String fileId) implements ImportResult {}

    /** The import stopped partway; what it committed stays, and importing again restarts it. */
    record Stopped(String fileId) implements ImportResult {}
  }
}
