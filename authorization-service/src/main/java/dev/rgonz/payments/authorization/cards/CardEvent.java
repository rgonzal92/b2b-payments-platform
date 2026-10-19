package dev.rgonz.payments.authorization.cards;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Currency;

/**
 * A card event from the platform's {@code cards} topic, as the service reads it. The service keeps
 * its own records rather than sharing the platform's classes (M5 design): each holds only the
 * fields the service needs, and ignores any others the platform adds.
 */
public sealed interface CardEvent {

  String cardToken();

  /**
   * A Single-Use Card was issued: everything the service needs to find and check it, with the PAN
   * and security code only as keyed hashes under {@code vault.hash-key} (ADR-0004).
   *
   * @param amountMinorUnits the amount the card is locked to, in minor units of {@code currency}
   * @param acceptorId the Supplier's Acceptor ID (ISO 8583 DE 42)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CardIssued(
      String cardToken,
      String panHash,
      String securityCodeHash,
      Instant expiresAt,
      long amountMinorUnits,
      Currency currency,
      String acceptorId)
      implements CardEvent {}

  /** The platform cancelled the card: it accepts no Charge again. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CardCancelled(String cardToken) implements CardEvent {}

  /** The platform expired the card: it accepts no Charge again. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CardExpired(String cardToken) implements CardEvent {}
}
