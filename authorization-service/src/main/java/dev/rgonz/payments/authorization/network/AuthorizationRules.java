package dev.rgonz.payments.authorization.network;

import dev.rgonz.payments.authorization.cards.CardCopy;
import dev.rgonz.payments.authorization.cards.CardStatus;
import dev.rgonz.payments.authorization.network.Authorization.ResponseCode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * The Authorization rules (M2 design, Q19), in the order of its table, as the platform applies
 * them: the first rule a Charge fails names the decline's response code. A pure decision on the
 * service's copy of the card, so it reads nothing and changes nothing; the caller compares the
 * security code's hash, and the conditional move to {@code AUTHORIZED} settles two approvals at
 * once.
 */
final class AuthorizationRules {

  // An AUTHORIZED or CLEARED card is still chargeable here: a second Charge on it is a 57, not a
  // 54. Any other status, and any status added later, declines as an expired card.
  private static final Set<CardStatus> CHARGEABLE =
      Set.of(CardStatus.ACTIVE, CardStatus.AUTHORIZED, CardStatus.CLEARED);

  // DE 14 as printed on the card: the expiry's year and month, in UTC like every stored time.
  private static final DateTimeFormatter EXPIRATION_DATE =
      DateTimeFormatter.ofPattern("yyMM").withZone(ZoneOffset.UTC);

  private AuthorizationRules() {}

  /**
   * The response code for the Charge: {@code 00} when it passes every rule.
   *
   * @param card the copy of the card whose PAN the Charge carries; null when no card has it
   * @param securityCodeMatches whether the Charge's security code hashes to the card's
   */
  static ResponseCode decide(
      Charge charge, CardCopy card, boolean securityCodeMatches, Instant now) {
    // A malformed amount says nothing about the card, so it's checked first.
    if (charge.amountMinor() <= 0) {
      return ResponseCode.INVALID_AMOUNT;
    }
    if (card == null) {
      return ResponseCode.INVALID_CARD;
    }
    if (!chargeable(card, charge.expirationDate(), now)) {
      return ResponseCode.EXPIRED_CARD;
    }
    if (!securityCodeMatches) {
      return ResponseCode.SECURITY_CODE_MISMATCH;
    }
    // An exact match with the Acceptor ID copied at issuance: a card copied with none can't be
    // charged by anyone.
    if (card.acceptorId() == null || !card.acceptorId().equals(charge.acceptorId())) {
      return ResponseCode.NOT_PERMITTED;
    }
    if (!withinAmount(card, charge)) {
      return ResponseCode.EXCEEDS_AMOUNT;
    }
    // A Single-Use Card takes one Charge: once approved, and once cleared, it takes no other.
    if (card.status() == CardStatus.AUTHORIZED || card.status() == CardStatus.CLEARED) {
      return ResponseCode.NOT_PERMITTED;
    }
    return ResponseCode.APPROVED;
  }

  // A CLEARED card found by its PAN's hash has every detail: its CardIssued filled them in.
  private static boolean chargeable(CardCopy card, String expirationDate, Instant now) {
    return CHARGEABLE.contains(card.status())
        && EXPIRATION_DATE.format(card.expiresAt()).equals(expirationDate)
        && now.isBefore(card.expiresAt());
  }

  // The currency is compared as sent, so a code that isn't ISO 4217 declines here too.
  private static boolean withinAmount(CardCopy card, Charge charge) {
    return card.currency().getCurrencyCode().equals(charge.currencyCode())
        && charge.amountMinor() <= card.amountMinorUnits();
  }
}
