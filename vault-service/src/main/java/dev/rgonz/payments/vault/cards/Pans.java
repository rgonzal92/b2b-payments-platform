package dev.rgonz.payments.vault.cards;

import java.util.random.RandomGenerator;

/**
 * Generates PANs and security codes, as the platform's vault does. Every PAN is synthetic: 16
 * digits from the made-up {@value #RANGE} range, with a valid Luhn check digit (ADR-0004).
 */
final class Pans {

  private static final String RANGE = "990000";
  private static final int PAN_LENGTH = 16;

  private Pans() {}

  /** The range, then random digits, then the Luhn check digit. */
  static String newPan(RandomGenerator random) {
    var pan = new StringBuilder(PAN_LENGTH).append(RANGE);
    while (pan.length() < PAN_LENGTH - 1) {
      pan.append(random.nextInt(10));
    }
    return pan.append(luhnCheckDigit(pan)).toString();
  }

  static String newSecurityCode(RandomGenerator random) {
    return "%03d".formatted(random.nextInt(1_000));
  }

  // The check digit will be rightmost, so the digit now rightmost is the first one doubled. From
  // the right, every second digit is doubled, and a doubled digit above 9 counts as its digits' sum
  // (the same as subtracting 9).
  private static int luhnCheckDigit(CharSequence digitsWithoutCheckDigit) {
    var sum = 0;
    var doubled = true;
    for (var i = digitsWithoutCheckDigit.length() - 1; i >= 0; i--) {
      var digit = Character.digit(digitsWithoutCheckDigit.charAt(i), 10);
      if (doubled) {
        digit *= 2;
        if (digit > 9) {
          digit -= 9;
        }
      }
      sum += digit;
      doubled = !doubled;
    }
    return (10 - sum % 10) % 10;
  }
}
