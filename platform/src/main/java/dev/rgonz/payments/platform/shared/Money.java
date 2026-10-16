package dev.rgonz.payments.platform.shared;

import java.util.Currency;
import java.util.Objects;

/** An amount in integer minor units (cents for USD), always paired with its currency. */
public record Money(long minorUnits, Currency currency) {

  public Money {
    Objects.requireNonNull(currency, "currency");
  }
}
