package dev.rgonz.payments.platform.reconciliation;

import dev.rgonz.payments.contracts.server.ReconciliationRunsApi;
import dev.rgonz.payments.contracts.server.model.ReconciliationRunDto;
import dev.rgonz.payments.contracts.server.model.ReconciliationRunRequestDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.RestController;

/** Issuer staff run a Reconciliation at once, instead of waiting for the daily run. */
@RestController
class ReconciliationRunsController implements ReconciliationRunsApi {

  private final Reconciliations reconciliations;

  ReconciliationRunsController(Reconciliations reconciliations) {
    this.reconciliations = reconciliations;
  }

  @Override
  @PreAuthorize("hasRole('issuer-admin')")
  public ResponseEntity<ReconciliationRunDto> runReconciliation(
      ReconciliationRunRequestDto request) {
    Reconciliations.RunSummary summary;
    try {
      summary = reconciliations.run(request.getBusinessDate());
    } catch (Reconciliations.RunFailedException e) {
      throw new ErrorResponseException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          ProblemDetail.forStatusAndDetail(
              HttpStatus.INTERNAL_SERVER_ERROR,
              e.getMessage()
                  + "; the Breaks it raised stay raised. Run it again to find the rest: see"
                  + " docs/runbooks/reconciliation-run-failed.md."),
          e);
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new ReconciliationRunDto()
                .runId(summary.runId())
                .businessDate(summary.businessDate())
                .breaksFound(summary.breaksFound())
                .newBreaks(summary.newBreaks())
                .knownBreaks(summary.knownBreaks()));
  }
}
