package dev.rgonz.payments.platform.authorization;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;

/**
 * An approved Authorization whose card expired by the 7-day rule: no Clearing came for it in time.
 *
 * @param requestId its retrieval reference number (DE 37)
 * @param cardToken its card, never the PAN (ADR-0004)
 * @param buyerId its card's Buyer
 * @param amount the Charge's amount as authorized
 */
public record NeverClearedAuthorization(
    String requestId, String cardToken, BuyerId buyerId, Money amount) {}
