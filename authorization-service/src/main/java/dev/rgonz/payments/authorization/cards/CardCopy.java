package dev.rgonz.payments.authorization.cards;

import java.time.Instant;
import java.util.Currency;

/**
 * The service's copy of one card: what it needs to answer a Charge on it without the platform. The
 * PAN and security code are held only as their keyed hashes (ADR-0004).
 *
 * @param amountMinorUnits the amount the card is locked to, in minor units of {@code currency}
 * @param acceptorId the Supplier's Acceptor ID, copied at issuance; null if the platform sent none
 */
public record CardCopy(
    String cardToken,
    String panHash,
    String securityCodeHash,
    Instant expiresAt,
    long amountMinorUnits,
    Currency currency,
    String acceptorId,
    CardStatus status) {}
