package dev.rgonz.payments.simulator;

import dev.rgonz.payments.contracts.client.model.Decision;
import dev.rgonz.payments.contracts.client.model.Invoice;
import dev.rgonz.payments.contracts.client.model.Money;
import dev.rgonz.payments.contracts.client.model.Payment;
import dev.rgonz.payments.simulator.GlobexAcquirer.Authorization;
import dev.rgonz.payments.simulator.GlobexSupplier.ReceivedCard;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs the core path in one call, for demos: Acme's ERP submits an Invoice from Globex, Acme's
 * Approvers approve it, the demo waits for the platform to pay it, and Globex charges the card it
 * received; then it shows what Globex received and the Issuer's answer to the Charge. Never shows a
 * PAN or security code.
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
   * Acme's Approvers, or two when the amount is above Acme's Approval Threshold, waits for its
   * Payment, and has Globex charge the card once the Payment is complete.
   */
  @PostMapping(DEMO_PATH)
  Summary run(@RequestParam(defaultValue = "1240000") long minorUnits) throws InterruptedException {
    var submitted =
        erp.submitInvoice(new Money().minorUnits(minorUnits).currency(Money.CurrencyEnum.USD));
    // The platform applies Acme's Approval Threshold: an Invoice still RECEIVED needs a second
    // Approver.
    if (approvers.approve(submitted.getId(), 1).getStatus() == Invoice.StatusEnum.RECEIVED) {
      approvers.approve(submitted.getId(), 2);
    }
    var invoice = awaitPayment(submitted.getId());
    // Globex charges the card as soon as the Payment is complete, by which time it has the card.
    var charge =
        completed(invoice.getPayment()) ? globex.charge(invoice.getId()).orElse(null) : null;
    var summary = Summary.of(invoice, globex.cardFor(invoice.getId()).orElse(null), charge);
    log.info(
        "The demo Invoice {} ended with its Payment {} and its Charge {}",
        invoice.getId(),
        summary.paymentState(),
        charge == null ? "not made" : charge.responseCode());
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
    return completed(payment)
        || (payment != null && payment.getState() == Payment.StateEnum.FAILED);
  }

  private static boolean completed(Payment payment) {
    return payment != null && payment.getState() == Payment.StateEnum.COMPLETED;
  }

  /**
   * What the demo did and what came of it. {@code paymentState} is null when no Payment appeared in
   * time; {@code globexReceived} is null until Globex has opened its card link; {@code charge} is
   * null unless the Payment completed and Globex had its card to charge.
   */
  record Summary(
      UUID invoiceId,
      String invoiceNumber,
      Money amount,
      List<String> approvedBy,
      Payment.StateEnum paymentState,
      String failureReason,
      CardShown globexReceived,
      Authorization charge) {

    static Summary of(Invoice invoice, ReceivedCard globexReceived, Authorization charge) {
      var payment = invoice.getPayment();
      return new Summary(
          invoice.getId(),
          invoice.getInvoiceNumber(),
          invoice.getAmount(),
          invoice.getDecisions().stream()
              .filter(decision -> decision.getDecision() == Decision.DecisionEnum.APPROVED)
              .map(Decision::getApproverName)
              .toList(),
          payment == null ? null : payment.getState(),
          payment == null ? null : payment.getFailureReason(),
          globexReceived == null ? null : CardShown.of(globexReceived),
          charge);
    }
  }

  /** What the demo shows of the card Globex received: never its PAN or security code. */
  record CardShown(String invoiceNumber, String lastFour, OffsetDateTime expiresAt, Money amount) {

    static CardShown of(ReceivedCard card) {
      return new CardShown(card.invoiceNumber(), card.lastFour(), card.expiresAt(), card.amount());
    }
  }
}
