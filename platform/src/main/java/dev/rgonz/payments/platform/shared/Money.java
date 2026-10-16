package dev.rgonz.payments.platform.shared;

import java.util.Currency;
import java.util.Objects;

/** An amount in integer minor units (cents for USD), always paired with its currency. */
public record Money(long minorUnits, Currency currency) {

  public Money {
    Objects.requireNonNull(currency, "currency");
  }

  public Money subtract(Money other) {
    if (!currency.equals(other.currency)) {
      throw new IllegalArgumentException(
          "Can't subtract " + other.currency + " from " + currency + ".");
    }
    return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
  }
}
