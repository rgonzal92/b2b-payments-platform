package dev.rgonz.payments.platform.payables;

import dev.rgonz.payments.platform.ledger.Holds;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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
  private final TransactionTemplate savepoint;

  PaymentSaga(Payments payments, Holds holds, PlatformTransactionManager transactionManager) {
    this.payments = payments;
    this.holds = holds;
    this.transaction = new TransactionTemplate(transactionManager);
    this.savepoint = new TransactionTemplate(transactionManager);
    this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
  }

  /**
   * Starts the approved Invoice's Payment. The registry redelivers the event until this completes
   * (ADR-0008), so it may arrive more than once; the Invoice's existing Payment wins.
   */
  @ApplicationModuleListener
  void start(InvoiceApproved invoice) {
    if (payments.insertUnlessInvoiceHasOne(Payment.startFor(invoice, Instant.now()))) {
      log.info("Invoice {} started a Payment", invoice.invoiceId());
    }
  }

  /**
   * Runs the next step of up to {@value BATCH_SIZE} due Payments, one transaction each, and returns
   * how many it ran. The scheduler calls it; tests call it with a later {@code now} instead of
   * waiting out a backoff.
   */
  int runDueSteps(Instant now) {
    var ran = 0;
    while (ran < BATCH_SIZE && Boolean.TRUE.equals(transaction.execute(status -> runNext(now)))) {
      ran++;
    }
    return ran;
  }

  private boolean runNext(Instant now) {
    var claimed = payments.claimNextDue(now);
    if (claimed.isEmpty()) {
      return false;
    }
    var payment = claimed.get();
    Payment next;
    try {
      // A technical error rolls back to the savepoint, undoing the step but keeping the claim, so
      // the failed attempt is saved in this same transaction.
      next = savepoint.execute(status -> placeHold(payment));
    } catch (RuntimeException e) {
      next = payment.stepFailed(now);
      log.warn(
          "Payment {} failed its step for technical reasons ({} of {} attempts)",
          payment.id(),
          next.attempts(),
          Payment.MAX_ATTEMPTS,
          e);
    }
    payments.save(next);
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
}
