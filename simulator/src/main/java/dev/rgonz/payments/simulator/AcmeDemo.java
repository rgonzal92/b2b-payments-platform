package dev.rgonz.payments.simulator;

import dev.rgonz.payments.contracts.client.model.Decision;
import dev.rgonz.payments.contracts.client.model.Invoice;
import dev.rgonz.payments.contracts.client.model.Money;
import dev.rgonz.payments.contracts.client.model.Payment;
import dev.rgonz.payments.simulator.GlobexSupplier.ReceivedCard;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs M1's core path in one call, for demos: Acme's ERP submits an Invoice from Globex, Acme's
 * Approvers approve it, and the demo waits for the platform to pay it, then shows what Globex
 * received. Never shows a PAN or security code.
 */
@RestController
class AcmeDemo {

  static final String DEMO_PATH = "/demo/invoices";

  private static final Logger log = LoggerFactory.getLogger(AcmeDemo.class);
  private static final Duration PAYMENT_WAIT = Duration.ofSeconds(30);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

  private final AcmeErp erp;
  private final AcmeApprovers approvers;
  private final GlobexSupplier globex;

  AcmeDemo(AcmeErp erp, AcmeApprovers approvers, GlobexSupplier globex) {
    this.erp = erp;
    this.approvers = approvers;
    this.globex = globex;
  }

  /**
   * Submits an Invoice for {@code minorUnits} US cents ($12,400 unless given), approved by one of
   * Acme's Approvers, or two when the amount is above Acme's Approval Threshold, and waits for its
   * Payment.
   */
  @PostMapping(DEMO_PATH)
  Summary run(@RequestParam(defaultValue = "1240000") long minorUnits) throws InterruptedException {
    var submitted = erp.submitInvoice(minorUnits);
    // The platform applies Acme's Approval Threshold: an Invoice still RECEIVED needs a second
    // Approver.
    if (approvers.approve(submitted.getId(), 1).getStatus() == Invoice.StatusEnum.RECEIVED) {
      approvers.approve(submitted.getId(), 2);
    }
    var invoice = awaitPayment(submitted.getId());
    var summary = Summary.of(invoice, globex.cardFor(invoice.getId()).orElse(null));
    log.info(
        "The demo Invoice {} ended with its Payment {}", invoice.getId(), summary.paymentState);
    return summary;
  }

  // Reads the Invoice as Acme's ERP until its Payment is COMPLETED or FAILED, for at most
  // PAYMENT_WAIT; returns it as last read.
  private Invoice awaitPayment(UUID invoiceId) throws InterruptedException {
    var deadline = Instant.now().plus(PAYMENT_WAIT);
    var invoice = erp.find(invoiceId);
    while (!settled(invoice.getPayment()) && Instant.now().isBefore(deadline)) {
      Thread.sleep(POLL_INTERVAL);
      invoice = erp.find(invoiceId);
    }
    return invoice;
  }

  private static boolean settled(Payment payment) {
    return payment != null
        && (payment.getState() == Payment.StateEnum.COMPLETED
            || payment.getState() == Payment.StateEnum.FAILED);
  }

  /**
   * What the demo did and what came of it. {@code paymentState} is null when no Payment appeared in
   * time; {@code globexReceived} is null until Globex has opened its card link.
   */
  record Summary(
      UUID invoiceId,
      String invoiceNumber,
      Money amount,
      List<String> approvedBy,
      String paymentState,
      String failureReason,
      ReceivedCard globexReceived) {

    static Summary of(Invoice invoice, ReceivedCard globexReceived) {
      var payment = invoice.getPayment();
      return new Summary(
          invoice.getId(),
          invoice.getInvoiceNumber(),
          invoice.getAmount(),
          invoice.getDecisions().stream()
              .filter(decision -> decision.getDecision() == Decision.DecisionEnum.APPROVED)
              .map(Decision::getApproverName)
              .toList(),
          payment == null ? null : payment.getState().getValue(),
          payment == null ? null : payment.getFailureReason(),
          globexReceived);
    }
  }
}
