package dev.rgonz.payments.platform.reconciliation;

import dev.rgonz.payments.platform.ledger.Journal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

/** A Reconciler resolves a Break once, moving money only by a new Correcting Entry. */
@Service
class BreakResolutions {

  private final Breaks breaks;
  private final Journal journal;

  BreakResolutions(Breaks breaks, Journal journal) {
    this.breaks = breaks;
    this.journal = journal;
  }

  /**
   * Resolves the {@code OPEN} Break and posts its Correcting Entry, if any, in one transaction, and
   * returns it resolved. Answers 404 for an unknown Break and 409 for one already resolved, so a
   * repeat can't move money twice.
   */
  @Transactional
  RaisedBreak resolve(UUID breakId, Resolution resolution, Reconciler reconciler) {
    if (!breaks.resolve(breakId, resolution.kind(), resolution.note(), reconciler)) {
      throw breaks.find(breakId).isPresent()
          ? problem(HttpStatus.CONFLICT, "This Break is already resolved.")
          : problem(HttpStatus.NOT_FOUND, "No Break has this ID.");
    }
    if (resolution.movesMoney()) {
      var entryId =
          journal.postCorrection(
              breakId.toString(), resolution.debited(), resolution.credited(), resolution.amount());
      breaks.recordCorrectingEntry(breakId, entryId);
    }
    return breaks.find(breakId).orElseThrow();
  }

  private static ErrorResponseException problem(HttpStatus status, String detail) {
    return new ErrorResponseException(
        status, ProblemDetail.forStatusAndDetail(status, detail), null);
  }
}
