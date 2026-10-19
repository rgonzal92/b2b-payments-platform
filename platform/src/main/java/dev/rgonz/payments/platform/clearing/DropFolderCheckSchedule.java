package dev.rgonz.payments.platform.clearing;

import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Checks the drop folder every 30 seconds: restarts each import waiting in {@code processing/},
 * then imports each new Clearing File. Tests that import files by hand turn this off with {@code
 * clearing.drop-folder-check.enabled=false}.
 */
@Component
@ConditionalOnBooleanProperty(name = "clearing.drop-folder-check.enabled", matchIfMissing = true)
class DropFolderCheckSchedule {

  private final ClearingFileImports imports;

  DropFolderCheckSchedule(ClearingFileImports imports) {
    this.imports = imports;
  }

  // A fixed delay never overlaps one instance's checks; the atomic move into processing/ keeps
  // other instances off a file this one took.
  @Scheduled(fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
  void importNewFiles() {
    imports.importNewFiles();
  }
}
