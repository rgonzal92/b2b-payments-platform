package dev.rgonz.payments.platform.suppliers;

/** A Buyer's link to a Supplier, with the Buyer's own reference for it. */
public record SupplierRelationship(Supplier supplier, String buyerReference) {}
