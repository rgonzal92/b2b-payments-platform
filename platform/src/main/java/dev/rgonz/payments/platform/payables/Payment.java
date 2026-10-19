package dev.rgonz.payments.platform.payables;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The Issuer's attempt to pay one approved Invoice, and where its saga stands (ADR-0013, ADR-0014).
 * {@code nextAttemptAt} is when the worker should run the next step; null while no step is due.
 * {@code cardIssuedAt} is when it reached {@code CARD_ISSUED}, to tell how long it has waited for
 * its card to be on file; null before.
 */
record Payment(
    UUID id,
    UUID invoiceId,
    BuyerId buyerId,
    UUID supplierId,
    Money amount,
    State state,
    String failureReason,
    int attempts,
    Instant nextAttemptAt,
    Instant cardIssuedAt) {

  static final int MAX_ATTEMPTS = 5;
  private static final Duration FIRST_BACKOFF = Duration.ofSeconds(10);

  /** A new Payment for the approved Invoice, due for its first step now. */
  static Payment startFor(InvoiceApproved approved, Instant now) {
    return new Payment(
        UUID.randomUUID(),
        approved.invoiceId(),
        approved.buyerId(),
        approved.supplierId(),
        approved.amount(),
        State.STARTED,
        null,
        0,
        now,
        null);
  }

  /** The Hold is placed, so issuing the card is due at once. */
  Payment holdPlaced(Instant now) {
    return moved(State.HOLD_PLACED, null, 0, now);
  }

  /**
   * The card is issued: no step is due until authorization-service confirms the card is on file (M5
   * design, "The gaps"). The wait has no time limit and counts no attempts; the card's expiry ends
   * it if the confirmation never comes.
   */
  Payment cardIssued(Instant now) {
    return new Payment(
        id, invoiceId, buyerId, supplierId, amount, State.CARD_ISSUED, null, 0, null, now);
  }

  /**
   * authorization-service holds the card and can authorize it, so delivering the Remittance Advice
   * is due at once. Only a Payment waiting at {@code CARD_ISSUED} moves: the same confirmation
   * again, or one for a Payment that has ended, leaves it as it is.
   */
  Payment cardOnFile(Instant now) {
    return state == State.CARD_ISSUED ? moved(State.CARD_ON_FILE, null, 0, now) : this;
  }

  /** The Supplier has the Remittance Advice: no step is left. */
  Payment completed() {
    return moved(State.COMPLETED, null, 0, null);
  }

  /**
   * Its Single-Use Card expired, uncharged or uncleared: the Payment ends {@code EXPIRED} with no
   * step left, one still waiting for its card to be on file or still retrying its delivery
   * included. In any other state there is no live card to expire, so the Payment stays as it is.
   */
  Payment cardExpired() {
    return switch (state) {
      case CARD_ISSUED, CARD_ON_FILE, COMPLETED -> moved(State.EXPIRED, null, 0, null);
      case STARTED, HOLD_PLACED, FAILED, EXPIRED -> this;
    };
  }

  /**
   * A business refusal, such as not enough credit: retrying wouldn't change it, so it fails now.
   */
  Payment refused(String reason) {
    return moved(State.FAILED, reason, attempts, null);
  }

  /**
   * A technical error, such as a lost connection: the step runs again after a backoff that doubles
   * each time (10, 20, 40, 80 seconds), and the fifth failure fails the Payment for good.
   */
  Payment stepFailed(Instant now) {
    var failed = attempts + 1;
    if (failed >= MAX_ATTEMPTS) {
      return moved(
          State.FAILED,
          nextStep() + " failed " + failed + " times for technical reasons.",
          failed,
          null);
    }
    var backoff = FIRST_BACKOFF.multipliedBy(1L << (failed - 1));
    return moved(state, failureReason, failed, now.plus(backoff));
  }

  // The step the Payment's state leads to, as a failure reason names it.
  private String nextStep() {
    return switch (state) {
      case STARTED -> "Placing the Hold";
      case HOLD_PLACED -> "Issuing the Single-Use Card";
      case CARD_ON_FILE -> "Delivering the Remittance Advice";
      case CARD_ISSUED, COMPLETED, FAILED, EXPIRED ->
          throw new IllegalStateException("A " + state + " Payment has no step to fail.");
    };
  }

  private Payment moved(State state, String failureReason, int attempts, Instant nextAttemptAt) {
    return new Payment(
        id,
        invoiceId,
        buyerId,
        supplierId,
        amount,
        state,
        failureReason,
        attempts,
        nextAttemptAt,
        cardIssuedAt);
  }

  enum State {
    STARTED,
    HOLD_PLACED,
    CARD_ISSUED,
    CARD_ON_FILE,
    COMPLETED,
    FAILED,
    EXPIRED
  }
}
