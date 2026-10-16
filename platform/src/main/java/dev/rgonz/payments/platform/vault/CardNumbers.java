package dev.rgonz.payments.platform.vault;

import java.util.random.RandomGenerator;

/**
 * Generates PANs and security codes. Every PAN is synthetic: 16 digits from the made-up {@value
 * #RANGE} range, with a valid Luhn check digit (ADR-0004).
 */
final class CardNumbers {

  private static final String RANGE = "990000";
  private static final int PAN_LENGTH = 16;

  private CardNumbers() {}

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

  /** Whether the last digit is the Luhn check digit of the others. */
  static boolean isLuhnValid(String digits) {
    return luhnSum(digits, false) % 10 == 0;
  }

  private static int luhnCheckDigit(CharSequence digitsWithoutCheckDigit) {
    // The check digit will be rightmost, so the digit now rightmost is the first one doubled.
    return (10 - luhnSum(digitsWithoutCheckDigit, true) % 10) % 10;
  }

  // From the right, every second digit is doubled, and a doubled digit above 9 counts as its
  // digits' sum (the same as subtracting 9).
  private static int luhnSum(CharSequence digits, boolean doubleRightmost) {
    var sum = 0;
    var doubled = doubleRightmost;
    for (var i = digits.length() - 1; i >= 0; i--) {
      var digit = Character.digit(digits.charAt(i), 10);
      if (doubled) {
        digit *= 2;
        if (digit > 9) {
          digit -= 9;
        }
      }
      sum += digit;
      doubled = !doubled;
    }
    return sum;
  }
}
