package dev.rgonz.payments.platform.clearing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.payments.platform.authorization.ApprovedAuthorization;
import dev.rgonz.payments.platform.clearing.ClearingOutcome.Facts;
import dev.rgonz.payments.platform.shared.Money;
import java.util.Currency;
import org.junit.jupiter.api.Test;

/**
 * How a Clearing's outcome follows from what the import found (M3 design, "Matching and outcomes"):
 * the first case that applies wins.
 */
class ClearingOutcomeTests {

  private static final Currency USD = Currency.getInstance("USD");
  private static final ApprovedAuthorization APPROVED =
      new ApprovedAuthorization("Q00000000001", usd(1_240_000), false);
  private static final boolean NOT_YET_CLEARED = false;
  private static final boolean HOLD_OPEN = false;

  @Test
  void aClearingWhoseCardTheVaultDoesntKnowIsUnknownCard() {
    var facts = new Facts(usd(1_240_000), null, null, NOT_YET_CLEARED, HOLD_OPEN);

    assertThat(facts.outcome()).isEqualTo(ClearingOutcome.UNKNOWN_CARD);
  }

  @Test
  void aClearingInAnotherCurrencyThanItsCardsIsOtherCurrency() {
    var euros = new Money(1_240_000, Currency.getInstance("EUR"));

    var facts = new Facts(euros, USD, APPROVED, NOT_YET_CLEARED, HOLD_OPEN);

    assertThat(facts.outcome()).isEqualTo(ClearingOutcome.OTHER_CURRENCY);
  }

  @Test
  void aClearingWhoseCardHasNoAuthorizationWithItsCodeIsNoAuthorization() {
    var facts = new Facts(usd(1_240_000), USD, null, NOT_YET_CLEARED, HOLD_OPEN);

    assertThat(facts.outcome()).isEqualTo(ClearingOutcome.NO_AUTHORIZATION);
  }

  @Test
  void aSecondClearingOfAnAuthorizationIsAuthorizationAlreadyClearedNotMatched() {
    var facts = new Facts(usd(1_240_000), USD, APPROVED, true, HOLD_OPEN);

    assertThat(facts.outcome()).isEqualTo(ClearingOutcome.AUTHORIZATION_ALREADY_CLEARED);
  }

  @Test
  void aReversedAuthorizationClearedAboveItsAmountIsAgainstAReversedAuthorization() {
    var reversed = new ApprovedAuthorization("Q00000000001", usd(1_000_000), true);

    var facts = new Facts(usd(1_240_000), USD, reversed, NOT_YET_CLEARED, HOLD_OPEN);

    assertThat(facts.outcome()).isEqualTo(ClearingOutcome.AGAINST_REVERSED_AUTHORIZATION);
  }

  @Test
  void aClearingAfterItsCardsHoldWasReleasedAboveItsAmountIsAfterHoldExpired() {
    var facts = new Facts(usd(1_300_000), USD, APPROVED, NOT_YET_CLEARED, true);

    assertThat(facts.outcome()).isEqualTo(ClearingOutcome.AFTER_HOLD_EXPIRED);
  }

  @Test
  void aClearingAboveItsApprovedAuthorizationIsAboveAuthorizedAmount() {
    var facts = new Facts(usd(1_240_001), USD, APPROVED, NOT_YET_CLEARED, HOLD_OPEN);

    assertThat(facts.outcome()).isEqualTo(ClearingOutcome.ABOVE_AUTHORIZED_AMOUNT);
  }

  @Test
  void aClearingAtOrBelowItsApprovedAuthorizationIsMatched() {
    for (var cleared : new long[] {1_240_000, 1_000_000}) {
      var facts = new Facts(usd(cleared), USD, APPROVED, NOT_YET_CLEARED, HOLD_OPEN);

      assertThat(facts.outcome()).isEqualTo(ClearingOutcome.MATCHED);
    }
  }

  @Test
  void anUnknownCardComesFirst() {
    var euros = new Money(1_300_000, Currency.getInstance("EUR"));

    var facts = new Facts(euros, null, null, true, true);

    assertThat(facts.outcome()).isEqualTo(ClearingOutcome.UNKNOWN_CARD);
  }

  @Test
  void aSecondClearingOfAReversedAuthorizationIsAuthorizationAlreadyCleared() {
    var reversed = new ApprovedAuthorization("Q00000000001", usd(1_240_000), true);

    var facts = new Facts(usd(1_300_000), USD, reversed, true, true);

    assertThat(facts.outcome()).isEqualTo(ClearingOutcome.AUTHORIZATION_ALREADY_CLEARED);
  }

  @Test
  void aReversedAuthorizationWhoseHoldWasReleasedIsAgainstAReversedAuthorization() {
    var reversed = new ApprovedAuthorization("Q00000000001", usd(1_240_000), true);

    var facts = new Facts(usd(1_240_000), USD, reversed, NOT_YET_CLEARED, true);

    assertThat(facts.outcome()).isEqualTo(ClearingOutcome.AGAINST_REVERSED_AUTHORIZATION);
  }

  private static Money usd(long minorUnits) {
    return new Money(minorUnits, USD);
  }
}
