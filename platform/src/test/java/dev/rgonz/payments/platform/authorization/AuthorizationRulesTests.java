package dev.rgonz.payments.platform.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.payments.platform.authorization.Authorization.ResponseCode;
import dev.rgonz.payments.platform.cards.SingleUseCard;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The Authorization rules (Q19), in the design's order: the first rule a Charge fails names the
 * decline's response code.
 */
class AuthorizationRulesTests {

  private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
  // Printed on the card as 11/26.
  private static final Instant EXPIRES_AT = Instant.parse("2026-11-14T12:00:00Z");
  private static final long CARD_AMOUNT = 1_240_000;
  private static final String ACCEPTOR_ID = "GLOBEX000000001";

  @Test
  void aChargeOnAnActiveCardThatPassesEveryRuleIsApproved() {
    assertThat(decide(charge(), card(SingleUseCard.Status.ACTIVE), true, NOW))
        .isEqualTo(ResponseCode.APPROVED);
  }

  @Test
  void aZeroOrNegativeAmountIsDeclinedAsAnInvalidAmount() {
    var active = card(SingleUseCard.Status.ACTIVE);

    assertThat(decide(charge(0, "USD"), active, true, NOW)).isEqualTo(ResponseCode.INVALID_AMOUNT);
    assertThat(decide(charge(-1, "USD"), active, true, NOW)).isEqualTo(ResponseCode.INVALID_AMOUNT);
  }

  @Test
  void aChargeWhosePanNoCardHasIsDeclinedAsAnInvalidCard() {
    assertThat(decide(charge(), null, false, NOW)).isEqualTo(ResponseCode.INVALID_CARD);
  }

  @Test
  void aCancelledOrExpiredCardIsDeclinedAsAnExpiredCard() {
    assertThat(decide(charge(), card(SingleUseCard.Status.CANCELLED), true, NOW))
        .isEqualTo(ResponseCode.EXPIRED_CARD);
    assertThat(decide(charge(), card(SingleUseCard.Status.EXPIRED), true, NOW))
        .isEqualTo(ResponseCode.EXPIRED_CARD);
  }

  @Test
  void anExpiryDateThatIsntTheCardsExpiryMonthIsDeclinedAsAnExpiredCard() {
    var active = card(SingleUseCard.Status.ACTIVE);

    for (var expirationDate : List.of("2612", "2610", "2711", "1126", "", "26-11")) {
      assertThat(decide(chargeExpiring(expirationDate), active, true, NOW))
          .as("expiry date %s", expirationDate)
          .isEqualTo(ResponseCode.EXPIRED_CARD);
    }
  }

  @Test
  void aCardWhoseExpiryHasPassedIsDeclinedAsAnExpiredCard() {
    var active = card(SingleUseCard.Status.ACTIVE);

    assertThat(decide(charge(), active, true, EXPIRES_AT)).isEqualTo(ResponseCode.EXPIRED_CARD);
    assertThat(decide(charge(), active, true, EXPIRES_AT.minusNanos(1_000)))
        .isEqualTo(ResponseCode.APPROVED);
  }

  @Test
  void aWrongSecurityCodeIsDeclinedAsASecurityCodeMismatch() {
    assertThat(decide(charge(), card(SingleUseCard.Status.ACTIVE), false, NOW))
        .isEqualTo(ResponseCode.SECURITY_CODE_MISMATCH);
  }

  @Test
  void aChargeFromAnotherAcceptorIdOrOnACardWhoseSupplierHasNoneIsDeclinedAsNotPermitted() {
    var active = card(SingleUseCard.Status.ACTIVE);

    assertThat(AuthorizationRules.decide(charge(), active, "OTHER0000000002", true, NOW))
        .isEqualTo(ResponseCode.NOT_PERMITTED);
    assertThat(AuthorizationRules.decide(charge(), active, null, true, NOW))
        .isEqualTo(ResponseCode.NOT_PERMITTED);
  }

  @Test
  void anAmountAboveTheCardsIsDeclinedAsExceedingTheAmountAndTheCardsAmountIsApproved() {
    var active = card(SingleUseCard.Status.ACTIVE);

    assertThat(decide(charge(CARD_AMOUNT + 1, "USD"), active, true, NOW))
        .isEqualTo(ResponseCode.EXCEEDS_AMOUNT);
    assertThat(decide(charge(CARD_AMOUNT, "USD"), active, true, NOW))
        .isEqualTo(ResponseCode.APPROVED);
    assertThat(decide(charge(CARD_AMOUNT - 1, "USD"), active, true, NOW))
        .isEqualTo(ResponseCode.APPROVED);
  }

  @Test
  void aChargeInAnotherCurrencyOrOneThatIsntIso4217IsDeclinedAsExceedingTheAmount() {
    var active = card(SingleUseCard.Status.ACTIVE);

    for (var currencyCode : List.of("EUR", "usd", "XXQ", "DOLLARS", "")) {
      assertThat(decide(charge(CARD_AMOUNT, currencyCode), active, true, NOW))
          .as("currency %s", currencyCode)
          .isEqualTo(ResponseCode.EXCEEDS_AMOUNT);
    }
  }

  @Test
  void aChargeOnACardWithAnApprovedAuthorizationIsDeclinedAsNotPermitted() {
    assertThat(decide(charge(), card(SingleUseCard.Status.AUTHORIZED), true, NOW))
        .isEqualTo(ResponseCode.NOT_PERMITTED);
  }

  @Test
  void theFirstRuleAChargeFailsNamesTheDecline() {
    var wrongCode = false;
    var active = card(SingleUseCard.Status.ACTIVE);

    assertThat(decide(charge(0, "USD"), null, wrongCode, NOW))
        .as("an invalid amount on an unknown PAN")
        .isEqualTo(ResponseCode.INVALID_AMOUNT);
    assertThat(decide(charge(), card(SingleUseCard.Status.EXPIRED), wrongCode, NOW))
        .as("an expired card with a wrong security code")
        .isEqualTo(ResponseCode.EXPIRED_CARD);
    assertThat(decide(chargeExpiring("2612"), active, wrongCode, NOW))
        .as("the wrong expiry month and a wrong security code")
        .isEqualTo(ResponseCode.EXPIRED_CARD);
    assertThat(decide(charge(CARD_AMOUNT + 1, "EUR"), active, wrongCode, NOW))
        .as("a wrong security code and an amount above the card's")
        .isEqualTo(ResponseCode.SECURITY_CODE_MISMATCH);
    assertThat(AuthorizationRules.decide(charge(), active, "OTHER0000000002", wrongCode, NOW))
        .as("a wrong security code from another Acceptor ID")
        .isEqualTo(ResponseCode.SECURITY_CODE_MISMATCH);
    assertThat(
            AuthorizationRules.decide(
                charge(CARD_AMOUNT + 1, "EUR"), active, "OTHER0000000002", true, NOW))
        .as("another Acceptor ID and an amount above the card's")
        .isEqualTo(ResponseCode.NOT_PERMITTED);
    assertThat(
            decide(
                charge(CARD_AMOUNT + 1, "USD"), card(SingleUseCard.Status.AUTHORIZED), true, NOW))
        .as("an amount above the card's on an AUTHORIZED card")
        .isEqualTo(ResponseCode.EXCEEDS_AMOUNT);
  }

  // The Charge comes from the card's Supplier's Acceptor ID unless a test says otherwise.
  private static ResponseCode decide(
      Charge charge, SingleUseCard card, boolean securityCodeMatches, Instant now) {
    return AuthorizationRules.decide(charge, card, ACCEPTOR_ID, securityCodeMatches, now);
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

  private static SingleUseCard card(SingleUseCard.Status status) {
    return new SingleUseCard(
        "card-token",
        UUID.randomUUID(),
        new BuyerId(UUID.randomUUID()),
        UUID.randomUUID(),
        new Money(CARD_AMOUNT, Currency.getInstance("USD")),
        "1234",
        Instant.parse("2026-10-15T11:00:00Z"),
        EXPIRES_AT,
        status);
  }
}
