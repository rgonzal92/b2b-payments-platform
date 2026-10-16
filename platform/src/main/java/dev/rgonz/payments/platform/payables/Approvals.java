package dev.rgonz.payments.platform.payables;

import dev.rgonz.payments.platform.buyers.BuyerDirectory;
import dev.rgonz.payments.platform.shared.BuyerId;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

/** Approvers' approvals and rejections of Invoices. */
@Service
class Approvals {

  private final Invoices invoices;
  private final Decisions decisions;
  private final BuyerDirectory buyers;
  private final ApplicationEventPublisher events;

  Approvals(
      Invoices invoices,
      Decisions decisions,
      BuyerDirectory buyers,
      ApplicationEventPublisher events) {
    this.invoices = invoices;
    this.decisions = decisions;
    this.buyers = buyers;
    this.events = events;
  }

  @Transactional
  void approve(BuyerId buyerId, UUID invoiceId, Approver approver) {
    var invoice = locked(buyerId, invoiceId);
    if (invoice.status() == Invoice.Status.REJECTED) {
      throw rejectionIsFinal();
    }
    var approverIds = approverIds(invoiceId, Decision.Outcome.APPROVED);
    if (approverIds.contains(approver.id())) {
      // The same Approver twice counts once, so a retried approval is safe.
      return;
    }
    if (invoice.status() == Invoice.Status.APPROVED) {
      throw alreadyApproved();
    }
    decisions.record(invoiceId, approver, Decision.Outcome.APPROVED);
    var threshold = buyers.approvalThreshold(buyerId).orElseThrow();
    if (invoice.isApprovedBy(
        Stream.concat(approverIds.stream(), Stream.of(approver.id())).toList(), threshold)) {
      invoices.setStatus(invoiceId, Invoice.Status.APPROVED);
      // Stored by the event publication registry in this transaction, so the status change and
      // the event commit or roll back together (ADR-0008).
      events.publishEvent(
          new InvoiceApproved(invoiceId, buyerId, invoice.supplierId(), invoice.amount()));
    }
  }

  @Transactional
  void reject(BuyerId buyerId, UUID invoiceId, Approver approver) {
    var invoice = locked(buyerId, invoiceId);
    if (invoice.status() == Invoice.Status.REJECTED) {
      if (approverIds(invoiceId, Decision.Outcome.REJECTED).contains(approver.id())) {
        // The same Approver's rejection again, such as a retry: nothing changes.
        return;
      }
      throw rejectionIsFinal();
    }
    if (invoice.status() == Invoice.Status.APPROVED) {
      throw alreadyApproved();
    }
    decisions.record(invoiceId, approver, Decision.Outcome.REJECTED);
    invoices.setStatus(invoiceId, Invoice.Status.REJECTED);
  }

  // Locking the Invoice's row makes concurrent decisions on it take turns.
  private Invoice locked(BuyerId buyerId, UUID invoiceId) {
    return invoices
        .findForUpdate(buyerId, invoiceId)
        .orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "No such Invoice."));
  }

  private List<String> approverIds(UUID invoiceId, Decision.Outcome outcome) {
    return decisions.of(invoiceId).stream()
        .filter(decision -> decision.outcome() == outcome)
        .map(decision -> decision.approver().id())
        .toList();
  }

  private static ErrorResponseException rejectionIsFinal() {
    return problem(HttpStatus.CONFLICT, "This Invoice was rejected, and a rejection is final.");
  }

  // Its Payment may already be under way, so an approved Invoice takes no new decisions.
  private static ErrorResponseException alreadyApproved() {
    return problem(HttpStatus.CONFLICT, "This Invoice is already approved.");
  }

  private static ErrorResponseException problem(HttpStatus status, String detail) {
    return new ErrorResponseException(
        status, ProblemDetail.forStatusAndDetail(status, detail), null);
  }
}
