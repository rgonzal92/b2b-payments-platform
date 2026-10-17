package dev.rgonz.payments.platform.payables;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A Payment that ended {@code FAILED} or {@code EXPIRED}, so its Invoice stays unpaid.
 *
 * @param state {@code FAILED} or {@code EXPIRED}
 * @param failureReason why it failed; null when it expired
 * @param endedAt when it ended
 */
public record UnpaidPayment(
    UUID paymentId,
    UUID invoiceId,
    BuyerId buyerId,
    Money amount,
    String state,
    String failureReason,
    Instant endedAt) {}
