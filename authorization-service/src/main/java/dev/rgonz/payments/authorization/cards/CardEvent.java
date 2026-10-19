package dev.rgonz.payments.authorization.cards;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;

/**
 * A card event from the platform's {@code cards} topic, as the service reads it. The service keeps
 * its own records rather than sharing the platform's classes (M5 design): each holds only the
 * fields the service needs, and ignores any others the platform adds. A field the service needs
 * that is missing makes reading fail, so the message is skipped rather than retried forever.
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
      implements CardEvent {

    public CardIssued {
      Objects.requireNonNull(cardToken, "cardToken");
      Objects.requireNonNull(panHash, "panHash");
      Objects.requireNonNull(securityCodeHash, "securityCodeHash");
      Objects.requireNonNull(expiresAt, "expiresAt");
      Objects.requireNonNull(currency, "currency");
      if (amountMinorUnits <= 0) {
        throw new IllegalArgumentException("amountMinorUnits must be positive.");
      }
    }
  }

  /** The platform cancelled the card: it accepts no Charge again. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CardCancelled(String cardToken) implements CardEvent {

    public CardCancelled {
      Objects.requireNonNull(cardToken, "cardToken");
    }
  }

  /** The platform expired the card: it accepts no Charge again. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CardExpired(String cardToken) implements CardEvent {

    public CardExpired {
      Objects.requireNonNull(cardToken, "cardToken");
    }
  }
}
