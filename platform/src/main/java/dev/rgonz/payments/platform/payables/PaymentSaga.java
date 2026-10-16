package dev.rgonz.payments.platform.payables;

import dev.rgonz.payments.platform.cards.CardExpired;
import dev.rgonz.payments.platform.cards.Cards;
import dev.rgonz.payments.platform.ledger.Holds;
import dev.rgonz.payments.platform.suppliers.RemittanceAdvice;
import dev.rgonz.payments.platform.suppliers.RemittanceAdvices;
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
  private final Invoices invoices;
  private final Holds holds;
  private final Cards cards;
  private final RemittanceAdvices remittanceAdvices;
  private final TransactionTemplate transaction;

  PaymentSaga(
      Payments payments,
      Invoices invoices,
      Holds holds,
      Cards cards,
      RemittanceAdvices remittanceAdvices,
      TransactionTemplate transaction) {
    this.payments = payments;
    this.invoices = invoices;
    this.holds = holds;
    this.cards = cards;
    this.remittanceAdvices = remittanceAdvices;
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
   * Ends the Payment of a card that expired uncharged, and releases its Hold, in one transaction.
   * The Payment is locked first, as the worker does, so a step under way finishes before this reads
   * its state. The registry redelivers the event until this completes (ADR-0008); a second delivery
   * finds the Payment already ended and the Hold already released, and changes nothing.
   */
  @ApplicationModuleListener
  void expire(CardExpired expired) {
    payments
        .lock(expired.paymentId())
        .ifPresent(
            payment -> {
              var next = payment.cardExpired();
              if (!next.equals(payment)) {
                payments.update(payment, next);
                log.info(
                    "Payment {} is {}: its card expired uncharged", payment.id(), next.state());
              }
            });
    // The Hold's ID is the Payment's.
    holds.release(expired.paymentId());
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
    var next =
        switch (payment.state()) {
          case STARTED -> placeHold(payment, now);
          case HOLD_PLACED -> issueCard(payment, now);
          case CARD_ISSUED -> deliverRemittanceAdvice(payment, now);
          case COMPLETED, FAILED, EXPIRED ->
              throw new IllegalStateException(
                  "Payment %s is %s: no step is left to run."
                      .formatted(payment.id(), payment.state()));
        };
    payments.update(payment, next);
    log.info("Payment {} is {}", payment.id(), next.state());
    return true;
  }

  // The Payment's ID is the Hold's, so placing it again after a crash finds the same Hold.
  private Payment placeHold(Payment payment, Instant now) {
    return switch (holds.place(payment.id(), payment.buyerId(), payment.amount())) {
      case PLACED -> payment.holdPlaced(now);
      case INSUFFICIENT_CREDIT -> payment.refused("Not enough Available Credit to place the Hold.");
    };
  }

  // Keyed by the Payment's ID too, so issuing again finds the same card.
  private Payment issueCard(Payment payment, Instant now) {
    cards.issueSingleUse(
        payment.id(), payment.buyerId(), payment.supplierId(), payment.amount(), now);
    return payment.cardIssued(now);
  }

  // The advice is the Payment's too, so delivering it again sends the same link, under the same
  // Idempotency-Key for the Supplier to drop the repeat.
  private Payment deliverRemittanceAdvice(Payment payment, Instant now) {
    var card = cards.findForPayment(payment.id()).orElseThrow();
    var invoice = invoices.find(payment.buyerId(), payment.invoiceId()).orElseThrow();
    remittanceAdvices.deliver(
        new RemittanceAdvice(
            payment.id(),
            invoice.id(),
            invoice.invoiceNumber(),
            payment.supplierId(),
            payment.amount(),
            card.cardToken()),
        now);
    return payment.completed();
  }

  // The step's transaction rolled back, undoing what the step did, so a new transaction counts the
  // attempt. If another worker claimed the Payment in between, its record wins. A Payment that
  // fails for good is compensated in the same transaction, so it is never FAILED with its Hold
  // still placed.
  private void recordFailedAttempt(Payment claimed, Instant now, RuntimeException cause) {
    var next = claimed.stepFailed(now);
    log.warn(
        "Payment {} failed its step for technical reasons ({} of {} attempts)",
        claimed.id(),
        next.attempts(),
        Payment.MAX_ATTEMPTS,
        cause);
    transaction.executeWithoutResult(
        status -> {
          if (payments.update(claimed, next) && next.state() == Payment.State.FAILED) {
            compensate(claimed);
          }
        });
  }

  // Undoes what the steps before the failed one did (ADR-0013), last step first. Each undo is
  // harmless to repeat.
  private void compensate(Payment failed) {
    switch (failed.state()) {
      case HOLD_PLACED -> {
        holds.release(failed.id());
        log.info("Payment {} failed for good; released its Hold", failed.id());
      }
      case CARD_ISSUED -> {
        cards.findForPayment(failed.id()).ifPresent(card -> cards.cancel(card.cardToken()));
        holds.release(failed.id());
        log.info(
            "Payment {} failed for good; cancelled its card and released its Hold", failed.id());
      }
      case STARTED, COMPLETED, FAILED, EXPIRED -> {
        // Placing the Hold is the first step: nothing came before it to undo.
      }
    }
  }
}
