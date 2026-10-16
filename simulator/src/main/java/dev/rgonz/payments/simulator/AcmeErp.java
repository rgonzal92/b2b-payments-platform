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
import org.springframework.stereotype.Component;

/** Acme's ERP: submits Invoices to the platform, signed in as the acme-erp client. */
@Component
class AcmeErp {

  private static final Logger log = LoggerFactory.getLogger(AcmeErp.class);

  private final InvoicesApi invoices;

  AcmeErp(InvoicesApi invoices) {
    this.invoices = invoices;
  }

  /** Submits a new Invoice from Globex for $12,400, due in 30 days. */
  Invoice submitInvoice() {
    var number = "INV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    var request =
        new InvoiceRequest()
            .supplier(
                new InvoiceSupplier()
                    .name("Globex")
                    .taxId("12-3456789")
                    .buyerReference("GLOBEX-01")
                    // Where the simulator's Supplier role will receive Remittance Advice.
                    .remittanceAddress("http://localhost:8081/globex/remittance-advice"))
            .invoiceNumber(number)
            .amount(new Money().minorUnits(1_240_000L).currency(Money.CurrencyEnum.USD))
            .dueDate(LocalDate.now().plusDays(30));
    // A new key for each new Invoice; retrying this same Invoice would resend the same key.
    var response = invoices.submitInvoice(UUID.randomUUID().toString(), request);
    // The contract's 201 always carries the Invoice; errors arrive as exceptions instead.
    var invoice = Objects.requireNonNull(response.getBody(), "the platform's Invoice");
    log.info("Acme's ERP submitted Invoice {} as {}", number, invoice.getId());
    return invoice;
  }
}
