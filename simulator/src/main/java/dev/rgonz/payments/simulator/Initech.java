package dev.rgonz.payments.simulator;

import dev.rgonz.payments.contracts.client.InvoicesApi;
import dev.rgonz.payments.contracts.client.model.Invoice;
import dev.rgonz.payments.contracts.client.model.InvoiceRequest;
import dev.rgonz.payments.contracts.client.model.InvoiceSupplier;
import dev.rgonz.payments.contracts.client.model.Money;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Initech, the load test's Buyer: its ERP submits Invoices from Globex and reads them back, signed
 * in as initech-erp, and its one Approver approves them, signed in as initech-approver. Logs
 * nothing: a load-test run sends thousands of Invoices.
 */
class Initech {

  private final InvoicesApi erp;
  private final InvoicesApi approver;
  private final InvoiceSupplier globex;

  Initech(InvoicesApi erp, InvoicesApi approver, InvoiceSupplier globex) {
    this.erp = erp;
    this.approver = approver;
    this.globex = globex;
  }

  /** Submits a new Invoice from Globex under the number, for the amount, due in 30 days. */
  Invoice submitInvoice(String invoiceNumber, Money amount) {
    var request =
        new InvoiceRequest()
            .supplier(globex)
            .invoiceNumber(invoiceNumber)
            .amount(amount)
            .dueDate(LocalDate.now().plusDays(30));
    // A new key for each new Invoice.
    var response = erp.submitInvoice(UUID.randomUUID().toString(), request);
    // The contract's 201 always carries the Invoice; errors arrive as exceptions instead.
    return Objects.requireNonNull(response.getBody(), "the platform's Invoice");
  }

  /** Approves the Invoice as Initech's Approver. */
  void approve(UUID invoiceId) {
    approver.approveInvoice(invoiceId);
  }

  /** Reads the Invoice back as Initech's ERP, with its Payment once it has one. */
  Invoice find(UUID invoiceId) {
    // The contract's 200 always carries the Invoice; errors arrive as exceptions instead.
    return Objects.requireNonNull(erp.getInvoice(invoiceId).getBody(), "the platform's Invoice");
  }
}
