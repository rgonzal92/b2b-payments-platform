package dev.rgonz.payments.platform.suppliers;

import dev.rgonz.payments.platform.shared.Money;
import java.util.UUID;

/**
 * The note sent to a Supplier with a Payment, saying which Invoice it pays, how much, and with
 * which Single-Use Card (by Card Token). One per Payment, identified by the Payment's ID.
 */
public record RemittanceAdvice(
    UUID paymentId,
    UUID invoiceId,
    String invoiceNumber,
    UUID supplierId,
    Money amount,
    String cardToken) {}
