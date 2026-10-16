package dev.rgonz.payments.platform.vault;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** The vault's PANs: 16 digits from the made-up 990000 range, with a valid Luhn check digit. */
class CardNumbersTests {

  @Test
  void theLuhnCheckAcceptsKnownValidNumbersAndRejectsAWrongCheckDigit() {
    assertThat(CardNumbers.isLuhnValid("79927398713")).isTrue();
    assertThat(CardNumbers.isLuhnValid("4111111111111111")).isTrue();
    assertThat(CardNumbers.isLuhnValid("79927398710")).isFalse();
    assertThat(CardNumbers.isLuhnValid("4111111111111112")).isFalse();
  }

  @Test
  void aNewPanHasSixteenDigitsFromThe990000RangeAndPassesTheLuhnCheck() {
    var random = new Random(18);

    for (var i = 0; i < 1_000; i++) {
      var pan = CardNumbers.newPan(random);

      assertThat(pan).hasSize(16).containsOnlyDigits().startsWith("990000");
      assertThat(CardNumbers.isLuhnValid(pan)).as("Luhn check of a new PAN").isTrue();
    }
  }

  @Test
  void aNewSecurityCodeHasThreeDigits() {
    var random = new Random(18);

    for (var i = 0; i < 1_000; i++) {
      assertThat(CardNumbers.newSecurityCode(random)).hasSize(3).containsOnlyDigits();
    }
  }
}
