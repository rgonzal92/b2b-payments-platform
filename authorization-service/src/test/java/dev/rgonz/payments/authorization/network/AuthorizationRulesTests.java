package dev.rgonz.payments.authorization.network;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.payments.authorization.cards.CardCopy;
import dev.rgonz.payments.authorization.cards.CardStatus;
import dev.rgonz.payments.authorization.network.Authorization.ResponseCode;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Authorization rules (M2 design, Q19), as the platform applies them, on the service's copy of
 * the card: the first rule a Charge fails names the decline's response code.
 */
class AuthorizationRulesTests {

  private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
  // Printed on the card as 11/26.
  private static final Instant EXPIRES_AT = Instant.parse("2026-11-14T12:00:00Z");
  private static final long CARD_AMOUNT = 1_240_000;
  private static final String ACCEPTOR_ID = "GLOBEX000000001";

  @Test
  void aChargeOnAnActiveCardThatPassesEveryRuleIsApproved() {
    assertThat(decide(charge(), card(CardStatus.ACTIVE), true, NOW))
        .isEqualTo(ResponseCode.APPROVED);
  }

  @Test
  void aZeroOrNegativeAmountIsDeclinedAsAnInvalidAmount() {
    var active = card(CardStatus.ACTIVE);

    assertThat(decide(charge(0, "USD"), active, true, NOW)).isEqualTo(ResponseCode.INVALID_AMOUNT);
    assertThat(decide(charge(-1, "USD"), active, true, NOW)).isEqualTo(ResponseCode.INVALID_AMOUNT);
  }

  @Test
  void aChargeWhosePanNoCardHasIsDeclinedAsAnInvalidCard() {
    assertThat(decide(charge(), null, false, NOW)).isEqualTo(ResponseCode.INVALID_CARD);
  }

  @Test
  void aCancelledOrExpiredCardIsDeclinedAsAnExpiredCard() {
    assertThat(decide(charge(), card(CardStatus.CANCELLED), true, NOW))
        .isEqualTo(ResponseCode.EXPIRED_CARD);
    assertThat(decide(charge(), card(CardStatus.EXPIRED), true, NOW))
        .isEqualTo(ResponseCode.EXPIRED_CARD);
  }

  @Test
  void anExpiryDateThatIsntTheCardsExpiryMonthIsDeclinedAsAnExpiredCard() {
    var active = card(CardStatus.ACTIVE);

    for (var expirationDate : List.of("2612", "2610", "2711", "1126", "", "26-11")) {
      assertThat(decide(chargeExpiring(expirationDate), active, true, NOW))
          .as("expiry date %s", expirationDate)
          .isEqualTo(ResponseCode.EXPIRED_CARD);
    }
  }

  @Test
  void aCardWhoseExpiryHasPassedIsDeclinedAsAnExpiredCard() {
    var active = card(CardStatus.ACTIVE);

    assertThat(decide(charge(), active, true, EXPIRES_AT)).isEqualTo(ResponseCode.EXPIRED_CARD);
    assertThat(decide(charge(), active, true, EXPIRES_AT.minusNanos(1_000)))
        .isEqualTo(ResponseCode.APPROVED);
  }

  @Test
  void aWrongSecurityCodeIsDeclinedAsASecurityCodeMismatch() {
    assertThat(decide(charge(), card(CardStatus.ACTIVE), false, NOW))
        .isEqualTo(ResponseCode.SECURITY_CODE_MISMATCH);
  }

  @Test
  void aChargeFromAnotherAcceptorIdOrOnACardCopiedWithNoneIsDeclinedAsNotPermitted() {
    assertThat(decide(charge(), card(CardStatus.ACTIVE, "OTHER0000000002"), true, NOW))
        .isEqualTo(ResponseCode.NOT_PERMITTED);
    assertThat(decide(charge(), card(CardStatus.ACTIVE, null), true, NOW))
        .isEqualTo(ResponseCode.NOT_PERMITTED);
  }

  @Test
  void anAmountAboveTheCardsIsDeclinedAsExceedingTheAmountAndTheCardsAmountIsApproved() {
    var active = card(CardStatus.ACTIVE);

    assertThat(decide(charge(CARD_AMOUNT + 1, "USD"), active, true, NOW))
        .isEqualTo(ResponseCode.EXCEEDS_AMOUNT);
    assertThat(decide(charge(CARD_AMOUNT, "USD"), active, true, NOW))
        .isEqualTo(ResponseCode.APPROVED);
    assertThat(decide(charge(CARD_AMOUNT - 1, "USD"), active, true, NOW))
        .isEqualTo(ResponseCode.APPROVED);
  }

  @Test
  void aChargeInAnotherCurrencyOrOneThatIsntIso4217IsDeclinedAsExceedingTheAmount() {
    var active = card(CardStatus.ACTIVE);

    for (var currencyCode : List.of("EUR", "usd", "XXQ", "DOLLARS", "")) {
      assertThat(decide(charge(CARD_AMOUNT, currencyCode), active, true, NOW))
          .as("currency %s", currencyCode)
          .isEqualTo(ResponseCode.EXCEEDS_AMOUNT);
    }
  }

  // The service sends no event for a cleared card: it stays AUTHORIZED here, which already
  // declines a second Charge as the platform declines one on a CLEARED card.
  @Test
  void aChargeOnACardWithAnApprovedAuthorizationIsDeclinedAsNotPermitted() {
    assertThat(decide(charge(), card(CardStatus.AUTHORIZED), true, NOW))
        .isEqualTo(ResponseCode.NOT_PERMITTED);
  }

  @Test
  void theFirstRuleAChargeFailsNamesTheDecline() {
    var wrongCode = false;
    var active = card(CardStatus.ACTIVE);
    var otherSuppliers = card(CardStatus.ACTIVE, "OTHER0000000002");

    assertThat(decide(charge(0, "USD"), null, wrongCode, NOW))
        .as("an invalid amount on an unknown PAN")
        .isEqualTo(ResponseCode.INVALID_AMOUNT);
    assertThat(decide(charge(), card(CardStatus.EXPIRED), wrongCode, NOW))
        .as("an expired card with a wrong security code")
        .isEqualTo(ResponseCode.EXPIRED_CARD);
    assertThat(decide(chargeExpiring("2612"), active, wrongCode, NOW))
        .as("the wrong expiry month and a wrong security code")
        .isEqualTo(ResponseCode.EXPIRED_CARD);
    assertThat(decide(charge(CARD_AMOUNT + 1, "EUR"), active, wrongCode, NOW))
        .as("a wrong security code and an amount above the card's")
        .isEqualTo(ResponseCode.SECURITY_CODE_MISMATCH);
    assertThat(decide(charge(), otherSuppliers, wrongCode, NOW))
        .as("a wrong security code from another Acceptor ID")
        .isEqualTo(ResponseCode.SECURITY_CODE_MISMATCH);
    assertThat(decide(charge(CARD_AMOUNT + 1, "EUR"), otherSuppliers, true, NOW))
        .as("another Acceptor ID and an amount above the card's")
        .isEqualTo(ResponseCode.NOT_PERMITTED);
    assertThat(decide(charge(CARD_AMOUNT + 1, "USD"), card(CardStatus.AUTHORIZED), true, NOW))
        .as("an amount above the card's on an AUTHORIZED card")
        .isEqualTo(ResponseCode.EXCEEDS_AMOUNT);
  }

  private static ResponseCode decide(
      Charge charge, CardCopy card, boolean securityCodeMatches, Instant now) {
    return AuthorizationRules.decide(charge, card, securityCodeMatches, now);
  }

  private static Charge charge() {
    return charge(CARD_AMOUNT, "USD");
  }

  private static Charge charge(long amountMinor, String currencyCode) {
    return new Charge("request-1", "pan", "123", "2611", amountMinor, currencyCode, ACCEPTOR_ID);
  }

  private static Charge chargeExpiring(String expirationDate) {
    return new Charge("request-1", "pan", "123", expirationDate, CARD_AMOUNT, "USD", ACCEPTOR_ID);
  }

  // Copied with the Charge's Acceptor ID unless a test says otherwise.
  private static CardCopy card(CardStatus status) {
    return card(status, ACCEPTOR_ID);
  }

  private static CardCopy card(CardStatus status, String acceptorId) {
    return new CardCopy(
        "card-token-1",
        "pan-hash-1",
        "security-code-hash-1",
        EXPIRES_AT,
        CARD_AMOUNT,
        Currency.getInstance("USD"),
        acceptorId,
        status);
  }
}
