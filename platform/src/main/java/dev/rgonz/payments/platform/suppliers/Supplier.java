package dev.rgonz.payments.platform.suppliers;

import java.util.UUID;

/**
 * A business that invoices Buyers, held once by the Issuer whichever Buyers it invoices.
 *
 * @param acceptorId the Acceptor ID its Acquirer gave it (ISO 8583 DE 42); null until an Invoice
 *     carries one
 */
public record Supplier(
    UUID id, String taxId, String name, String remittanceAddress, String acceptorId) {}
