package dev.rgonz.payments.platform.clearing;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 */
@Component
class ClearingFileImports {

  private static final Logger log = LoggerFactory.getLogger(ClearingFileImports.class);

  private final Path dropFolder;
  private final Path processingFolder;
  private final Path failedFolder;
  private final JobOperator jobOperator;
  private final Job clearingFileImport;
  private final ClearingFiles files;

  // A String, not a Path: Spring converts a Path setting through its resource loader, which
  // resolves a relative path against the classpath, and fails once the platform runs from a jar.
  ClearingFileImports(
      @Value("${clearing.drop-folder}") String dropFolder,
      JobOperator jobOperator,
      Job clearingFileImport,
      ClearingFiles files) {
    this.dropFolder = Path.of(dropFolder).toAbsolutePath().normalize();
    this.processingFolder = this.dropFolder.resolve("processing");
    this.failedFolder = this.dropFolder.resolve("failed");
    this.jobOperator = jobOperator;
    this.clearingFileImport = clearingFileImport;
    this.files = files;
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
   * imports.
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
    var contentHash = ClearingFileLayout.contentHash(file);
    var earlier = files.repeatedBy(fileId, contentHash);
    if (earlier.isPresent()) {
      return repeated(file, earlier.get());
    }
    var parameters =
        new JobParametersBuilder()
            .addString(ClearingFileImportJob.FILE_ID, fileId)
            .addString(ClearingFileImportJob.FILE, file.toString(), false)
            .addString(ClearingFileImportJob.CONTENT_HASH, contentHash, false)
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

  private ImportResult refused(Path file, String reason) throws IOException {
    moveToFailed(file);
    log.warn("Refused Clearing File {}: {}", file.getFileName(), reason);
    return new ImportResult.Refused(reason);
  }

  private ImportResult repeated(Path file, String earlierFileId) throws IOException {
    moveToFailed(file);
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
     * The file repeats one the platform already received, so nothing was posted, and it moved to
     * {@code failed/}.
     */
    record Repeated(String earlierFileId) implements ImportResult {}

    /** An import of the file is running, or stopped without its end being recorded. */
    record Running(String fileId) implements ImportResult {}

    /** The import stopped partway; what it committed stays, and importing again restarts it. */
    record Stopped(String fileId) implements ImportResult {}
  }
}
