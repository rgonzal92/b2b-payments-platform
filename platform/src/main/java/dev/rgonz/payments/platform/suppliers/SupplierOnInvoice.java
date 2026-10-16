package dev.rgonz.payments.platform.suppliers;

/**
 * The Supplier as an Invoice names it, with the invoiced Buyer's own reference for it.
 *
 * @param acceptorId the Supplier's Acceptor ID (ISO 8583 DE 42); null when the Invoice has none
 */
public record SupplierOnInvoice(
    String taxId,
    String name,
    String remittanceAddress,
    String buyerReference,
    String acceptorId) {}
