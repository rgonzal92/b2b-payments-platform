package dev.rgonz.payments.platform.clearing;

import dev.rgonz.payments.contracts.server.ClearingFilesApi;
import dev.rgonz.payments.contracts.server.model.ClearingFileDto;
import dev.rgonz.payments.contracts.server.model.ClearingFileImportDto;
import dev.rgonz.payments.contracts.server.model.ClearingOutcomeCountsDto;
import dev.rgonz.payments.platform.clearing.ClearingFileImports.ImportResult;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.RestController;

/** Issuer staff import a Clearing File from the drop folder at once. */
@RestController
class ClearingFilesController implements ClearingFilesApi {

  private final ClearingFileImports imports;

  ClearingFilesController(ClearingFileImports imports) {
    this.imports = imports;
  }

  @Override
  @PreAuthorize("hasRole('issuer-admin')")
  public ResponseEntity<ClearingFileDto> importClearingFile(ClearingFileImportDto request) {
    return switch (imports.importFile(request.getFileName())) {
      case ImportResult.Imported imported ->
          ResponseEntity.status(HttpStatus.CREATED).body(toDto(imported.file()));
      case ImportResult.NotFound _ ->
          throw problem(HttpStatus.NOT_FOUND, "No file has this name in the drop folder.");
      case ImportResult.Refused refused ->
          throw problem(
              HttpStatus.UNPROCESSABLE_CONTENT,
              "This isn't a Clearing File the platform can import: " + refused.reason());
      case ImportResult.AlreadyImported alreadyImported ->
          throw problem(
              HttpStatus.CONFLICT,
              "Clearing File " + alreadyImported.fileId() + " was already imported.");
      case ImportResult.Running running ->
          throw problem(
              HttpStatus.CONFLICT,
              "An import of Clearing File "
                  + running.fileId()
                  + " is running, or stopped without recording it: see"
                  + " docs/runbooks/clearing-import-stopped.md.");
      case ImportResult.Stopped stopped ->
          throw problem(
              HttpStatus.INTERNAL_SERVER_ERROR,
              "The import of Clearing File "
                  + stopped.fileId()
                  + " stopped partway; what it posted stays posted. Import the file again to"
                  + " restart it where it stopped.");
    };
  }

  private static ClearingFileDto toDto(ClearingFileSummary file) {
    return new ClearingFileDto()
        .id(file.id())
        .sequenceNumber(file.sequenceNumber())
        .businessDate(file.businessDate())
        .status(ClearingFileDto.StatusEnum.fromValue(file.status().name()))
        .recordCount(file.recordCount())
        .outcomes(toDto(file.outcomes()));
  }

  private static ClearingOutcomeCountsDto toDto(Map<ClearingOutcome, Long> outcomes) {
    return new ClearingOutcomeCountsDto()
        .matched(outcomes.get(ClearingOutcome.MATCHED))
        .aboveAuthorizedAmount(outcomes.get(ClearingOutcome.ABOVE_AUTHORIZED_AMOUNT))
        .noAuthorization(outcomes.get(ClearingOutcome.NO_AUTHORIZATION))
        .unknownCard(outcomes.get(ClearingOutcome.UNKNOWN_CARD))
        .otherCurrency(outcomes.get(ClearingOutcome.OTHER_CURRENCY))
        .againstReversedAuthorization(outcomes.get(ClearingOutcome.AGAINST_REVERSED_AUTHORIZATION))
        .afterHoldExpired(outcomes.get(ClearingOutcome.AFTER_HOLD_EXPIRED))
        .authorizationAlreadyCleared(outcomes.get(ClearingOutcome.AUTHORIZATION_ALREADY_CLEARED));
  }

  private static ErrorResponseException problem(HttpStatus status, String detail) {
    return new ErrorResponseException(
        status, ProblemDetail.forStatusAndDetail(status, detail), null);
  }
}
