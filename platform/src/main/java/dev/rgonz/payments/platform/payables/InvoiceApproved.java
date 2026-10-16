package dev.rgonz.payments.platform.payables;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.util.UUID;

/**
 * An Invoice got its final approval, so it can be paid. Published through Spring Modulith's event
 * publication registry in the same transaction as the approval (ADR-0008); the Payment saga starts
 * from it (ADR-0013).
 */
public record InvoiceApproved(UUID invoiceId, BuyerId buyerId, UUID supplierId, Money amount) {}
