package dev.rgonz.payments.platform.clearing;

import dev.rgonz.payments.contracts.server.ClearingFilesApi;
import dev.rgonz.payments.contracts.server.model.ClearingFileDto;
import dev.rgonz.payments.contracts.server.model.ClearingFileImportDto;
import dev.rgonz.payments.contracts.server.model.ClearingFileListDto;
import dev.rgonz.payments.contracts.server.model.ClearingOutcomeCountsDto;
import dev.rgonz.payments.contracts.server.model.MoneyDto;
import dev.rgonz.payments.contracts.server.model.SettlementDto;
import dev.rgonz.payments.platform.clearing.ClearingFileImports.ImportResult;
import dev.rgonz.payments.platform.shared.Money;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issuer staff import a Clearing File from the drop folder at once, and read the files received.
 */
@RestController
class ClearingFilesController implements ClearingFilesApi {

  // The list shows the first 100 gaps in the Network's sequence numbers, as the OpenAPI document
  // says: any more, and the first are the ones to chase.
  private static final int MISSING_SEQUENCE_NUMBERS_SHOWN = 100;

  private final ClearingFileImports imports;
  private final ClearingFiles files;

  ClearingFilesController(ClearingFileImports imports, ClearingFiles files) {
    this.imports = imports;
    this.files = files;
  }

  @Override
  @PreAuthorize("hasRole('issuer-admin')")
  public ResponseEntity<ClearingFileListDto> listClearingFiles(String cursor, Integer limit) {
    var page = files.list(cursor == null ? null : position(cursor), limit);
    return ResponseEntity.ok(
        new ClearingFileListDto()
            .items(page.files().stream().map(ClearingFilesController::toDto).toList())
            .nextCursor(page.next() == null ? null : page.next().cursor())
            .missingSequenceNumbers(files.missingSequenceNumbers(MISSING_SEQUENCE_NUMBERS_SHOWN)));
  }

  private static ClearingFiles.Position position(String cursor) {
    try {
      return ClearingFiles.Position.of(cursor);
    } catch (IllegalArgumentException e) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          "This cursor can't be read: pass a page's nextCursor as it is, or leave it out.");
    }
  }

  @Override
  @PreAuthorize("hasRole('issuer-admin')")
  public ResponseEntity<ClearingFileDto> getClearingFile(String fileId) {
    return files
        .find(fileId)
        .map(file -> ResponseEntity.ok(toDto(file)))
        .orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "No Clearing File has this ID."));
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
      case ImportResult.Repeated repeated ->
          throw problem(
              HttpStatus.CONFLICT,
              "This file repeats Clearing File "
                  + repeated.earlierFileId()
                  + ", imported or importing, so nothing in it was posted: see"
                  + " docs/runbooks/clearing-file-arrived-twice.md.");
      case ImportResult.Waiting _ ->
          throw problem(
              HttpStatus.SERVICE_UNAVAILABLE,
              "The platform's copy of Authorizations hasn't caught up with the authorization"
                  + " service, or Kafka can't be reached, so nothing was imported and the file stays"
                  + " where it is. A new file in the drop folder is imported by the folder's next"
                  + " check; to restart a stopped import, send this request again once the copy has"
                  + " caught up. See docs/runbooks/kafka-down.md.");
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
        .skippedCount(file.skippedCount())
        .outcomes(toDto(file.outcomes()))
        .settlement(file.settlement() == null ? null : toDto(file.settlement()))
        .refusalReason(file.refusalReason());
  }

  private static ClearingOutcomeCountsDto toDto(Map<ClearingOutcome, Long> outcomes) {
    var counts = new ClearingOutcomeCountsDto();
    outcomes.forEach((outcome, count) -> withCount(counts, outcome, count));
    return counts;
  }

  // A switch expression: an outcome added without its count here doesn't compile.
  private static ClearingOutcomeCountsDto withCount(
      ClearingOutcomeCountsDto counts, ClearingOutcome outcome, long count) {
    return switch (outcome) {
      case MATCHED -> counts.matched(count);
      case ABOVE_AUTHORIZED_AMOUNT -> counts.aboveAuthorizedAmount(count);
      case NO_AUTHORIZATION -> counts.noAuthorization(count);
      case UNKNOWN_CARD -> counts.unknownCard(count);
      case OTHER_CURRENCY -> counts.otherCurrency(count);
      case AGAINST_REVERSED_AUTHORIZATION -> counts.againstReversedAuthorization(count);
      case AFTER_HOLD_EXPIRED -> counts.afterHoldExpired(count);
      case AUTHORIZATION_ALREADY_CLEARED -> counts.authorizationAlreadyCleared(count);
    };
  }

  private static SettlementDto toDto(ClearingFileSummary.Settlement settlement) {
    return new SettlementDto()
        .amount(toDto(settlement.amount()))
        .importedTotal(toDto(settlement.importedTotal()))
        .matched(settlement.matched());
  }

  private static MoneyDto toDto(Money money) {
    return new MoneyDto()
        .minorUnits(money.minorUnits())
        .currency(MoneyDto.CurrencyEnum.fromValue(money.currency().getCurrencyCode()));
  }

  private static ErrorResponseException problem(HttpStatus status, String detail) {
    return new ErrorResponseException(
        status, ProblemDetail.forStatusAndDetail(status, detail), null);
  }
}
