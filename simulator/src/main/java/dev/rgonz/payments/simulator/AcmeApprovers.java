package dev.rgonz.payments.simulator;

import dev.rgonz.payments.contracts.client.InvoicesApi;
import dev.rgonz.payments.contracts.client.model.Invoice;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Acme's two demo Approvers: each approves Invoices on request, signed in as its own client. */
class AcmeApprovers {

  private static final Logger log = LoggerFactory.getLogger(AcmeApprovers.class);

  private final List<InvoicesApi> approvers;

  AcmeApprovers(InvoicesApi approver1, InvoicesApi approver2) {
    this.approvers = List.of(approver1, approver2);
  }

  /** Approves the Invoice as Acme's Approver 1 (acme-approver-1) or 2 (acme-approver-2). */
  Invoice approve(UUID invoiceId, int approver) {
    var response = approvers.get(approver - 1).approveInvoice(invoiceId);
    // The contract's 200 always carries the Invoice; errors arrive as exceptions instead.
    var invoice = Objects.requireNonNull(response.getBody(), "the platform's Invoice");
    log.info(
        "Acme's Approver {} approved Invoice {}, now {}", approver, invoiceId, invoice.getStatus());
    return invoice;
  }
}
