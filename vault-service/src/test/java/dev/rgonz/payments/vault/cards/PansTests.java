package dev.rgonz.payments.vault.cards;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** The Card Vault's PANs: 16 digits from the made-up 990000 range, with a valid Luhn check digit. */
class PansTests {

  @Test
  void theLuhnCheckAcceptsKnownValidNumbersAndRejectsAWrongCheckDigit() {
    assertThat(Pans.isLuhnValid("79927398713")).isTrue();
    assertThat(Pans.isLuhnValid("4111111111111111")).isTrue();
    assertThat(Pans.isLuhnValid("79927398710")).isFalse();
    assertThat(Pans.isLuhnValid("4111111111111112")).isFalse();
  }

  @Test
  void aNewPanHasSixteenDigitsFromThe990000RangeAndPassesTheLuhnCheck() {
    var random = new Random(18);

    for (var i = 0; i < 1_000; i++) {
      var pan = Pans.newPan(random);

      assertThat(pan).hasSize(16).containsOnlyDigits().startsWith("990000");
      assertThat(Pans.isLuhnValid(pan)).as("Luhn check of a new PAN").isTrue();
    }
  }

  @Test
  void aNewSecurityCodeHasThreeDigits() {
    var random = new Random(18);

    for (var i = 0; i < 1_000; i++) {
      assertThat(Pans.newSecurityCode(random)).hasSize(3).containsOnlyDigits();
    }
  }
}
