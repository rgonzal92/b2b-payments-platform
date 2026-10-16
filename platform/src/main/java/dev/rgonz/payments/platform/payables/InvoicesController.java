package dev.rgonz.payments.platform.payables;

import dev.rgonz.payments.contracts.server.InvoicesApi;
import dev.rgonz.payments.contracts.server.model.InvoiceDto;
import dev.rgonz.payments.contracts.server.model.InvoiceRequestDto;
import dev.rgonz.payments.contracts.server.model.InvoiceSupplierDto;
import dev.rgonz.payments.contracts.server.model.MoneyDto;
import dev.rgonz.payments.platform.buyers.BuyerDirectory;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import dev.rgonz.payments.platform.suppliers.SupplierOnInvoice;
import dev.rgonz.payments.platform.suppliers.SupplierRelationship;
import dev.rgonz.payments.platform.suppliers.Suppliers;
import java.util.Currency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.RestController;

@RestController
class InvoicesController implements InvoicesApi {

  private final Invoices invoices;
  private final Suppliers suppliers;
  private final IdempotentRequests idempotentRequests;
  private final BuyerDirectory buyers;

  InvoicesController(
      Invoices invoices,
      Suppliers suppliers,
      IdempotentRequests idempotentRequests,
      BuyerDirectory buyers) {
    this.invoices = invoices;
    this.suppliers = suppliers;
    this.idempotentRequests = idempotentRequests;
    this.buyers = buyers;
  }

  @Override
  @PreAuthorize("hasRole('erp')")
  public ResponseEntity<InvoiceDto> submitInvoice(
      String idempotencyKey, InvoiceRequestDto request) {
    var buyerId = currentBuyer();
    // The shared Money schema allows zero (an Approval Threshold may be zero), so this rule is
    // here.
    if (request.getAmount().getMinorUnits() <= 0) {
      throw problem(HttpStatus.BAD_REQUEST, "An Invoice's amount must be more than zero.");
    }
    return idempotentRequests.once(
        buyerId, idempotencyKey, request, InvoiceDto.class, () -> receive(buyerId, request));
  }

  private ResponseEntity<InvoiceDto> receive(BuyerId buyerId, InvoiceRequestDto request) {
    var supplier = request.getSupplier();
    var relationship =
        suppliers.relate(
            buyerId,
            new SupplierOnInvoice(
                supplier.getTaxId(),
                supplier.getName(),
                supplier.getRemittanceAddress(),
                supplier.getBuyerReference()));
    var invoice =
        new Invoice(
            UUID.randomUUID(),
            buyerId,
            relationship.supplier().id(),
            request.getInvoiceNumber(),
            toMoney(request.getAmount()),
            request.getDueDate(),
            Invoice.Status.RECEIVED);
    if (!invoices.insertUnlessDuplicate(invoice)) {
      // Thrown inside the idempotent transaction, so the key isn't kept and a resend is refused
      // too.
      throw problem(
          HttpStatus.CONFLICT, "This Buyer already has an Invoice with this Supplier and number.");
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(toDto(invoice, relationship));
  }

  @Override
  @PreAuthorize("hasAnyRole('erp', 'approver')")
  public ResponseEntity<InvoiceDto> getInvoice(UUID invoiceId) {
    var buyerId = currentBuyer();
    var invoice =
        invoices
            .find(buyerId, invoiceId)
            .orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "No such Invoice."));
    var relationship = suppliers.find(buyerId, invoice.supplierId()).orElseThrow();
    return ResponseEntity.ok(toDto(invoice, relationship));
  }

  // The Buyer always comes from the token, never from the request, so one Buyer can't act as
  // another.
  private BuyerId currentBuyer() {
    if (SecurityContextHolder.getContext().getAuthentication()
            instanceof JwtAuthenticationToken token
        && token.getToken().getClaimAsString("buyer_id") instanceof String claim) {
      try {
        var buyerId = new BuyerId(UUID.fromString(claim));
        if (buyers.exists(buyerId)) {
          return buyerId;
        }
      } catch (IllegalArgumentException notAnId) {
        // Falls through to the refusal below.
      }
    }
    throw problem(HttpStatus.FORBIDDEN, "Your token isn't linked to a known Buyer.");
  }

  private static ErrorResponseException problem(HttpStatus status, String detail) {
    return new ErrorResponseException(
        status, ProblemDetail.forStatusAndDetail(status, detail), null);
  }

  private static Money toMoney(MoneyDto money) {
    return new Money(money.getMinorUnits(), Currency.getInstance(money.getCurrency().getValue()));
  }

  private static InvoiceDto toDto(Invoice invoice, SupplierRelationship relationship) {
    var supplier = relationship.supplier();
    return new InvoiceDto()
        .id(invoice.id())
        .status(InvoiceDto.StatusEnum.fromValue(invoice.status().name()))
        .supplierId(supplier.id())
        .supplier(
            new InvoiceSupplierDto()
                .name(supplier.name())
                .taxId(supplier.taxId())
                .buyerReference(relationship.buyerReference())
                .remittanceAddress(supplier.remittanceAddress()))
        .invoiceNumber(invoice.invoiceNumber())
        .amount(
            new MoneyDto()
                .minorUnits(invoice.amount().minorUnits())
                .currency(
                    MoneyDto.CurrencyEnum.fromValue(invoice.amount().currency().getCurrencyCode())))
        .dueDate(invoice.dueDate());
  }
}
