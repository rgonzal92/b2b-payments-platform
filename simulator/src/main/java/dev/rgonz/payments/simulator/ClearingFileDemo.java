package dev.rgonz.payments.simulator;

import dev.rgonz.payments.contracts.client.ClearingFilesApi;
import dev.rgonz.payments.contracts.client.model.ClearingFile;
import dev.rgonz.payments.contracts.client.model.ClearingFileImport;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The demo's Clearing step, after {@link AcmeDemo}'s Charge: the Network writes its Clearing File,
 * and Issuer staff (the issuer-admin client) import it at once rather than wait for the platform's
 * next check of its drop folder. Never shows a PAN.
 */
@RestController
class ClearingFileDemo {

  static final String DEMO_PATH = "/demo/clearing-file";

  private static final Logger log = LoggerFactory.getLogger(ClearingFileDemo.class);

  private final NetworkClearingFiles clearingFiles;
  private final ClearingFilesApi issuerStaff;

  ClearingFileDemo(NetworkClearingFiles clearingFiles, ClearingFilesApi issuerStaff) {
    this.clearingFiles = clearingFiles;
    this.issuerStaff = issuerStaff;
  }

  /**
   * Has the Network write today's Clearing File (UTC) with every Clearing it hasn't sent yet, and
   * Issuer staff import it; answers with the platform's summary of the import. With nothing to
   * clear it writes no file and answers {@code 204}.
   */
  @PostMapping(DEMO_PATH)
  ResponseEntity<ClearingFile> run() {
    var written = clearingFiles.write(LocalDate.now(ZoneOffset.UTC));
    if (written.isEmpty()) {
      log.info("The demo found no Clearings to send");
      return ResponseEntity.noContent().build();
    }
    var fileName = written.get();
    var response = issuerStaff.importClearingFile(new ClearingFileImport().fileName(fileName));
    // The contract's 201 always carries the summary; errors arrive as exceptions instead.
    var imported = Objects.requireNonNull(response.getBody(), "the platform's summary");
    log.info("The platform imported Clearing File {}: {}", fileName, imported.getStatus());
    return ResponseEntity.ok(imported);
  }
}
