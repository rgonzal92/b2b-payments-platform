package dev.rgonz.payments.platform.payables;

import dev.rgonz.payments.platform.ledger.Holds;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs each Payment's saga (ADR-0013, ADR-0014): InvoiceApproved starts it, and the worker runs its
 * steps, each in one transaction with the Payment's new state.
 */
@Component
class PaymentSaga {

  private static final Logger log = LoggerFactory.getLogger(PaymentSaga.class);
  private static final int BATCH_SIZE = 10;

  private final Payments payments;
  private final Holds holds;
  private final TransactionTemplate transaction;

  PaymentSaga(Payments payments, Holds holds, TransactionTemplate transaction) {
    this.payments = payments;
    this.holds = holds;
    this.transaction = transaction;
  }

  /**
   * Starts the approved Invoice's Payment. The registry redelivers the event until this completes
   * (ADR-0008), so it may arrive more than once; the Invoice's existing Payment wins.
   */
  @ApplicationModuleListener
  void start(InvoiceApproved approved) {
    if (payments.insertUnlessInvoiceHasOne(Payment.startFor(approved, Instant.now()))) {
      log.info("Invoice {} started a Payment", approved.invoiceId());
    }
  }

  /**
   * Runs the next step of up to {@value BATCH_SIZE} due Payments, one transaction each. The
   * scheduler calls it; tests call it with a later {@code now} instead of waiting out a backoff.
   */
  void runDueSteps(Instant now) {
    for (var ran = 0; ran < BATCH_SIZE; ran++) {
      var claimed = new AtomicReference<Payment>();
      try {
        if (!Boolean.TRUE.equals(transaction.execute(status -> runNextStep(now, claimed)))) {
          return;
        }
      } catch (RuntimeException e) {
        if (claimed.get() == null) {
          // Nothing was claimed, so there is no attempt to count: the next pass tries again.
          throw e;
        }
        recordFailedAttempt(claimed.get(), now, e);
      }
    }
  }

  // Claims the Payment due longest, runs its next step and saves its new state, all in the
  // caller's transaction.
  private boolean runNextStep(Instant now, AtomicReference<Payment> claimed) {
    var due = payments.claimNextDue(now);
    if (due.isEmpty()) {
      return false;
    }
    var payment = due.get();
    claimed.set(payment);
    var next = placeHold(payment);
    payments.update(payment, next);
    log.info("Payment {} is {}", payment.id(), next.state());
    return true;
  }

  // The Payment's ID is the Hold's, so placing it again after a crash finds the same Hold.
  private Payment placeHold(Payment payment) {
    return switch (holds.place(payment.id(), payment.buyerId(), payment.amount())) {
      case PLACED -> payment.holdPlaced();
      case INSUFFICIENT_CREDIT -> payment.refused("Not enough Available Credit to place the Hold.");
    };
  }

  // The step's transaction rolled back, undoing what the step did, so a new transaction counts the
  // attempt. If another worker claimed the Payment in between, its record wins.
  private void recordFailedAttempt(Payment claimed, Instant now, RuntimeException cause) {
    var next = claimed.stepFailed(now);
    log.warn(
        "Payment {} failed its step for technical reasons ({} of {} attempts)",
        claimed.id(),
        next.attempts(),
        Payment.MAX_ATTEMPTS,
        cause);
    transaction.executeWithoutResult(status -> payments.update(claimed, next));
  }
}
