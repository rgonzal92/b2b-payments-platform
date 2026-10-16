package dev.rgonz.payments.platform.payables;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The Issuer's attempt to pay one approved Invoice, and where its saga stands (ADR-0013, ADR-0014).
 * {@code nextAttemptAt} is when the worker should run the next step; null while no step is left.
 */
record Payment(
    UUID id,
    UUID invoiceId,
    BuyerId buyerId,
    Money amount,
    State state,
    String failureReason,
    int attempts,
    Instant nextAttemptAt) {

  static final int MAX_ATTEMPTS = 5;
  private static final Duration FIRST_BACKOFF = Duration.ofSeconds(10);

  /** A new Payment for the approved Invoice, due for its first step now. */
  static Payment startFor(InvoiceApproved invoice, Instant now) {
    return new Payment(
        UUID.randomUUID(),
        invoice.invoiceId(),
        invoice.buyerId(),
        invoice.amount(),
        State.STARTED,
        null,
        0,
        now);
  }

  /** The Hold is placed; card issuance (#18) will be the next step, so nothing is due yet. */
  Payment holdPlaced() {
    return new Payment(id, invoiceId, buyerId, amount, State.HOLD_PLACED, null, 0, null);
  }

  /**
   * A business refusal, such as not enough credit: retrying wouldn't change it, so it fails now.
   */
  Payment refused(String reason) {
    return new Payment(id, invoiceId, buyerId, amount, State.FAILED, reason, attempts, null);
  }

  /**
   * A technical error, such as a lost connection: the step runs again after a backoff that doubles
   * each time (10, 20, 40, 80 seconds), and the fifth failure fails the Payment for good.
   */
  Payment stepFailed(Instant now) {
    var failed = attempts + 1;
    if (failed >= MAX_ATTEMPTS) {
      return new Payment(
          id,
          invoiceId,
          buyerId,
          amount,
          State.FAILED,
          "Placing the Hold failed " + failed + " times for technical reasons.",
          failed,
          null);
    }
    var backoff = FIRST_BACKOFF.multipliedBy(1L << (failed - 1));
    return new Payment(
        id, invoiceId, buyerId, amount, state, failureReason, failed, now.plus(backoff));
  }

  enum State {
    STARTED,
    HOLD_PLACED,
    FAILED
  }
}
