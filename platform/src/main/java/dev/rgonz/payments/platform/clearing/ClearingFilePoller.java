package dev.rgonz.payments.platform.clearing;

import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Checks the drop folder for new Clearing Files every 30 seconds. Tests that import files by hand
 * turn this off with {@code clearing.poller.enabled=false}.
 */
@Component
@ConditionalOnBooleanProperty(name = "clearing.poller.enabled", matchIfMissing = true)
class ClearingFilePoller {

  private final ClearingFileImports imports;

  ClearingFilePoller(ClearingFileImports imports) {
    this.imports = imports;
  }

  // A fixed delay never overlaps one instance's checks; the atomic move into processing/ keeps
  // other instances off a file this one took.
  @Scheduled(fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
  void importNewFiles() {
    imports.importNewFiles();
  }
}
