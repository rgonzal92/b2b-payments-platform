package dev.rgonz.payments.platform.payables;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class Invoices {

  private final JdbcClient jdbc;

  Invoices(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts the Invoice unless the Buyer already has one with its Supplier and Invoice number; true
   * when it was inserted.
   */
  boolean insertUnlessDuplicate(Invoice invoice) {
    return jdbc.sql(
                """
                insert into payables.invoice
                       (id, buyer_id, supplier_id, invoice_number, amount_minor, currency, due_date, status)
                values (:id, :buyerId, :supplierId, :invoiceNumber, :amount, :currency, :dueDate, :status)
                on conflict (buyer_id, supplier_id, invoice_number) do nothing
                """)
            .param("id", invoice.id())
            .param("buyerId", invoice.buyerId().value())
            .param("supplierId", invoice.supplierId())
            .param("invoiceNumber", invoice.invoiceNumber())
            .param("amount", invoice.amount().minorUnits())
            .param("currency", invoice.amount().currency().getCurrencyCode())
            .param("dueDate", invoice.dueDate())
            .param("status", invoice.status().name())
            .update()
        == 1;
  }

  void setStatus(UUID id, Invoice.Status status) {
    jdbc.sql("update payables.invoice set status = :status where id = :id")
        .param("status", status.name())
        .param("id", id)
        .update();
  }

  /** The Buyer's Invoice with this ID; empty when it doesn't exist or is another Buyer's. */
  Optional<Invoice> find(BuyerId buyerId, UUID id) {
    return jdbc.sql(
            """
            select id, buyer_id, supplier_id, invoice_number, amount_minor, currency, due_date, status
              from payables.invoice
             where id = :id
               and buyer_id = :buyerId
            """)
        .param("id", id)
        .param("buyerId", buyerId.value())
        .query(Invoices::toInvoice)
        .optional();
  }

  /** Like {@link #find}, and locks the Invoice's row until the transaction ends. */
  Optional<Invoice> findForUpdate(BuyerId buyerId, UUID id) {
    return jdbc.sql(
            """
            select id, buyer_id, supplier_id, invoice_number, amount_minor, currency, due_date, status
              from payables.invoice
             where id = :id
               and buyer_id = :buyerId
               for update
            """)
        .param("id", id)
        .param("buyerId", buyerId.value())
        .query(Invoices::toInvoice)
        .optional();
  }

  private static Invoice toInvoice(ResultSet row, int rowNumber) throws SQLException {
    return new Invoice(
        row.getObject("id", UUID.class),
        new BuyerId(row.getObject("buyer_id", UUID.class)),
        row.getObject("supplier_id", UUID.class),
        row.getString("invoice_number"),
        new Money(row.getLong("amount_minor"), Currency.getInstance(row.getString("currency"))),
        row.getObject("due_date", LocalDate.class),
        Invoice.Status.valueOf(row.getString("status")));
  }
}
