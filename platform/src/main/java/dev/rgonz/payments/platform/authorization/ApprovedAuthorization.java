package dev.rgonz.payments.platform.authorization;

import dev.rgonz.payments.platform.shared.Money;

/**
 * An Authorization the platform approved.
 *
 * @param requestId its retrieval reference number (DE 37)
 * @param amount the Charge's amount as authorized (DE 4, DE 49)
 * @param reversed whether the Network cancelled it with a Reversal since
 */
public record ApprovedAuthorization(String requestId, Money amount, boolean reversed) {}
