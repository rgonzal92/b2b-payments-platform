package dev.rgonz.payments.simulator;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.rgonz.payments.contracts.client.ClearingFilesApi;
import dev.rgonz.payments.contracts.client.model.ClearingFile;
import dev.rgonz.payments.contracts.client.model.ClearingFileImport;
import dev.rgonz.payments.contracts.client.model.Problem;
import dev.rgonz.payments.simulator.NetworkClearingFiles.EdgeCase;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

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
   * Issuer staff import it; answers with the file's name and the platform's answer to the import.
   * With nothing to clear it writes no file and answers {@code 204}, whatever the switches.
   *
   * <p>Each switch changes this one file, to show the platform's rule for a Clearing edge case: the
   * first Clearing repeated under its Network record ID; the first Clearing above its authorized
   * amount; an extra Clearing on the first one's card that no Authorization matches; an extra
   * Clearing on a PAN the Issuer never issued; the trailer's amount total off by one cent. And
   * {@code sameFileTwice} has the Network send the file again once it is imported, and Issuer staff
   * import it a second time.
   */
  @PostMapping(DEMO_PATH)
  ResponseEntity<Answer> run(
      @RequestParam(defaultValue = "false") boolean duplicateRecord,
      @RequestParam(defaultValue = "false") boolean aboveAuthorizedAmount,
      @RequestParam(defaultValue = "false") boolean noAuthorization,
      @RequestParam(defaultValue = "false") boolean unknownPan,
      @RequestParam(defaultValue = "false") boolean wrongTrailerTotal,
      @RequestParam(defaultValue = "false") boolean sameFileTwice) {
    var edgeCases = EnumSet.noneOf(EdgeCase.class);
    if (duplicateRecord) {
      edgeCases.add(EdgeCase.DUPLICATE_RECORD);
    }
    if (aboveAuthorizedAmount) {
      edgeCases.add(EdgeCase.ABOVE_AUTHORIZED_AMOUNT);
    }
    if (noAuthorization) {
      edgeCases.add(EdgeCase.NO_AUTHORIZATION);
    }
    if (unknownPan) {
      edgeCases.add(EdgeCase.UNKNOWN_PAN);
    }
    if (wrongTrailerTotal) {
      edgeCases.add(EdgeCase.WRONG_TRAILER_TOTAL);
    }
    var written = clearingFiles.write(LocalDate.now(ZoneOffset.UTC), edgeCases);
    if (written.isEmpty()) {
      log.info("The demo found no Clearings to send");
      return ResponseEntity.noContent().build();
    }
    var file = written.get();
    var imports = new ArrayList<PlatformAnswer>();
    imports.add(importFile(file.name()));
    if (sameFileTwice) {
      // The platform took the file out of its drop folder when it imported it.
      clearingFiles.sendAgain(file);
      imports.add(importFile(file.name()));
    }
    return ResponseEntity.ok(new Answer(file.name(), imports));
  }

  // The platform's answer, whatever it is: a refusal is an answer worth showing, not a failure.
  private PlatformAnswer importFile(String fileName) {
    PlatformAnswer answer;
    try {
      var response = issuerStaff.importClearingFile(new ClearingFileImport().fileName(fileName));
      answer = new PlatformAnswer(response.getStatusCode().value(), response.getBody(), null);
    } catch (RestClientResponseException refused) {
      answer =
          new PlatformAnswer(
              refused.getStatusCode().value(), null, refused.getResponseBodyAs(Problem.class));
    }
    log.info("The platform answered the import of Clearing File {}: {}", fileName, answer.status());
    return answer;
  }

  /**
   * What the demo did: the name of the file the Network wrote into the drop folder, and the
   * platform's answer to each import of it.
   */
  record Answer(String fileName, List<PlatformAnswer> imports) {}

  /**
   * The platform's answer to one import: its HTTP status, and the file's summary or, when the
   * platform refused the import, its Problem Details. Neither ever holds a PAN.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record PlatformAnswer(int status, ClearingFile summary, Problem problem) {}
}
