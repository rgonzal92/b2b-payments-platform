package dev.rgonz.payments.platform.suppliers;

import java.util.UUID;

/** A business that invoices Buyers, held once by the Issuer whichever Buyers it invoices. */
public record Supplier(UUID id, String taxId, String name, String remittanceAddress) {}
