package dev.rgonz.payments.platform.suppliers;

/** The Supplier as an Invoice names it, with the invoiced Buyer's own reference for it. */
public record SupplierOnInvoice(
    String taxId, String name, String remittanceAddress, String buyerReference) {}
