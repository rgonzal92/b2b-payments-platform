package dev.rgonz.payments.platform.authorization;

import dev.rgonz.payments.platform.authorization.Authorization.ResponseCode;
import dev.rgonz.payments.platform.cards.SingleUseCard;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The Authorization rules (Q19), in the order of the M2 design's table: the first rule a Charge
 * fails names the decline's response code. A pure decision on facts the caller gathered, so it
 * reads nothing and changes nothing; the vault checks the security code, and the conditional move
 * to {@code AUTHORIZED} settles two approvals at once.
 */
final class AuthorizationRules {

  // DE 14 as printed on the card: the expiry's year and month, in UTC like every stored time.
  private static final DateTimeFormatter EXPIRATION_DATE =
      DateTimeFormatter.ofPattern("yyMM").withZone(ZoneOffset.UTC);

  private AuthorizationRules() {}

  /**
   * The response code for the Charge: {@code 00} when it passes every rule.
   *
   * @param card the card whose PAN the Charge carries; null when no card has it
   * @param suppliersAcceptorId the Acceptor ID of the card's Supplier; null when it has none
   * @param securityCodeMatches the vault's verdict on the Charge's security code
   */
  static ResponseCode decide(
      Charge charge,
      SingleUseCard card,
      String suppliersAcceptorId,
      boolean securityCodeMatches,
      Instant now) {
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
    // An exact match: the Network names the Supplier by the Acceptor ID its Acquirer gave it, and a
    // card whose Supplier has none can't be charged by anyone.
    if (suppliersAcceptorId == null || !suppliersAcceptorId.equals(charge.acceptorId())) {
      return ResponseCode.NOT_PERMITTED;
    }
    if (!withinAmount(card, charge)) {
      return ResponseCode.EXCEEDS_AMOUNT;
    }
    if (card.status() == SingleUseCard.Status.AUTHORIZED) {
      return ResponseCode.NOT_PERMITTED;
    }
    return ResponseCode.APPROVED;
  }

  // An AUTHORIZED card is still chargeable here: a second Charge on it is a 57, not a 54.
  private static boolean chargeable(SingleUseCard card, String expirationDate, Instant now) {
    return card.status() != SingleUseCard.Status.CANCELLED
        && card.status() != SingleUseCard.Status.EXPIRED
        && EXPIRATION_DATE.format(card.expiresAt()).equals(expirationDate)
        && now.isBefore(card.expiresAt());
  }

  // The currency is compared as sent, so a code that isn't ISO 4217 declines here too.
  private static boolean withinAmount(SingleUseCard card, Charge charge) {
    return card.amount().currency().getCurrencyCode().equals(charge.currencyCode())
        && charge.amountMinor() <= card.amount().minorUnits();
  }
}
