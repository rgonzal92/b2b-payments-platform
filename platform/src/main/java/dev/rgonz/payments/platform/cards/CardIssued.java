package dev.rgonz.payments.platform.cards;

import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * A Single-Use Card was issued. Published through Spring Modulith's event publication registry in
 * the same transaction as the card (ADR-0008) and externalized to Kafka's {@code cards} topic,
 * keyed by Card Token, for {@code authorization-service} to answer Charges on it without the
 * platform (ADR-0016).
 *
 * <p>It carries everything the service needs to find and check the card, but no PAN or security
 * code: the PAN's keyed hash and the security code's keyed hash, both under {@code vault.hash-key},
 * which the service holds and hashes each request's PAN and code with to compare (ADR-0004, as
 * amended). The Acceptor ID is copied at issuance, so a Supplier that changes Acquirer later is
 * declined.
 *
 * @param expiresAt the card's expiry, in UTC
 * @param amountMinorUnits the amount the card is locked to, in minor units, with {@code currency}
 * @param acceptorId the Supplier's Acceptor ID (ISO 8583 DE 42); null when no Invoice gave it one
 */
@Externalized("cards::#{cardToken}")
public record CardIssued(
    String cardToken,
    String panHash,
    String securityCodeHash,
    Instant expiresAt,
    long amountMinorUnits,
    String currency,
    String acceptorId) {}
