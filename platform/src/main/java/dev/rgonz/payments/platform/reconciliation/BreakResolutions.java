package dev.rgonz.payments.platform.reconciliation;

import dev.rgonz.payments.platform.buyers.BuyerDirectory;
import dev.rgonz.payments.platform.ledger.Account;
import dev.rgonz.payments.platform.ledger.Journal;
import dev.rgonz.payments.platform.shared.Money;
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
  private final BuyerDirectory buyers;

  BreakResolutions(Breaks breaks, Journal journal, BuyerDirectory buyers) {
    this.breaks = breaks;
    this.journal = journal;
    this.buyers = buyers;
  }

  /**
   * Resolves the {@code OPEN} Break and posts its Correcting Entry, if any, in one transaction, and
   * returns it resolved. Answers 422 for a Buyer Receivable of an unknown Buyer or in another
   * currency than the Buyer's, 404 for an unknown Break and 409 for one already resolved, so a
   * repeat can't move money twice.
   */
  @Transactional
  RaisedBreak resolve(UUID breakId, Resolution resolution, Reconciler reconciler) {
    checkBuyerReceivable(resolution.debited(), resolution);
    checkBuyerReceivable(resolution.credited(), resolution);
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

  // A Buyer Receivable belongs to a known Buyer, in the Buyer's currency (its Approval Threshold's,
  // as its Credit Line's): one of no Buyer, or in another currency, would carry debt that no
  // Available Credit counts and no Repayment can clear.
  private void checkBuyerReceivable(Account account, Resolution resolution) {
    if (account == null || account.buyerId() == null) {
      return;
    }
    var currency =
        buyers
            .approvalThreshold(account.buyerId())
            .map(Money::currency)
            .orElseThrow(() -> problem(HttpStatus.UNPROCESSABLE_CONTENT, "No Buyer has this ID."));
    if (!currency.equals(resolution.amount().currency())) {
      throw problem(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "A Buyer Receivable is in its Buyer's currency, " + currency.getCurrencyCode() + ".");
    }
  }

  private static ErrorResponseException problem(HttpStatus status, String detail) {
    return new ErrorResponseException(
        status, ProblemDetail.forStatusAndDetail(status, detail), null);
  }
}
