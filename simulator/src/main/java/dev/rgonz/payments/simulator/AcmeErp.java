package dev.rgonz.payments.simulator;

import dev.rgonz.payments.contracts.client.InvoicesApi;
import dev.rgonz.payments.contracts.client.model.Invoice;
import dev.rgonz.payments.contracts.client.model.InvoiceRequest;
import dev.rgonz.payments.contracts.client.model.InvoiceSupplier;
import dev.rgonz.payments.contracts.client.model.Money;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Acme's ERP: submits Invoices to the platform and reads them back, signed in as the acme-erp
 * client.
 */
@Component
class AcmeErp {

  private static final Logger log = LoggerFactory.getLogger(AcmeErp.class);

  private final InvoicesApi invoices;
  private final String globexRemittanceAddress;

  // Where the simulator's Supplier role will receive Globex's Remittance Advice.
  AcmeErp(
      InvoicesApi invoices,
      @Value("http://localhost:${server.port}/globex/remittance-advice")
          String globexRemittanceAddress) {
    this.invoices = invoices;
    this.globexRemittanceAddress = globexRemittanceAddress;
  }

  /** Submits a new Invoice from Globex for the amount, due in 30 days. */
  Invoice submitInvoice(Money amount) {
    var number = "INV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    var request =
        new InvoiceRequest()
            .supplier(
                new InvoiceSupplier()
                    .name("Globex")
                    .taxId("12-3456789")
                    .buyerReference("GLOBEX-01")
                    .remittanceAddress(globexRemittanceAddress))
            .invoiceNumber(number)
            .amount(amount)
            .dueDate(LocalDate.now().plusDays(30));
    // A new key for each new Invoice; retrying this same Invoice would resend the same key.
    var response = invoices.submitInvoice(UUID.randomUUID().toString(), request);
    // The contract's 201 always carries the Invoice; errors arrive as exceptions instead.
    var invoice = Objects.requireNonNull(response.getBody(), "the platform's Invoice");
    log.info("Acme's ERP submitted Invoice {} as {}", number, invoice.getId());
    return invoice;
  }

  /** Reads the Invoice back, with its Payment once it has one. */
  Invoice find(UUID invoiceId) {
    // The contract's 200 always carries the Invoice; errors arrive as exceptions instead.
    return Objects.requireNonNull(
        invoices.getInvoice(invoiceId).getBody(), "the platform's Invoice");
  }
}
