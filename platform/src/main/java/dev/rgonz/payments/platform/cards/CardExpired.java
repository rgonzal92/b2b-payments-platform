package dev.rgonz.payments.platform.cards;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.util.UUID;

/**
 * A Single-Use Card reached its expiry before anyone charged it, and is now {@code EXPIRED}.
 * Published through Spring Modulith's event publication registry in the same transaction as the
 * status change (ADR-0008), so it is delivered at least once; {@code payables} releases the card's
 * Hold and ends its Payment.
 */
public record CardExpired(String cardToken, UUID paymentId, BuyerId buyerId, Money amount) {}
