package dev.rgonz.payments.platform.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTests {

  private static final Currency USD = Currency.getInstance("USD");

  @Test
  void subtractingGivesTheDifference() {
    var difference = new Money(10_000_000, USD).subtract(new Money(2_500_000, USD));

    assertThat(difference).isEqualTo(new Money(7_500_000, USD));
  }

  @Test
  void amountsInDifferentCurrenciesDoNotSubtract() {
    var dollars = new Money(10_000, USD);
    var euros = new Money(2_500, Currency.getInstance("EUR"));

    assertThatIllegalArgumentException().isThrownBy(() -> dollars.subtract(euros));
  }

  @Test
  void onlyALargerAmountIsMoreThanAnother() {
    var threshold = new Money(1_000_000, USD);

    assertThat(new Money(1_000_001, USD).isMoreThan(threshold)).isTrue();
    assertThat(new Money(1_000_000, USD).isMoreThan(threshold)).isFalse();
    assertThat(new Money(999_999, USD).isMoreThan(threshold)).isFalse();
  }

  @Test
  void amountsInDifferentCurrenciesDoNotCompare() {
    var dollars = new Money(10_000, USD);
    var euros = new Money(2_500, Currency.getInstance("EUR"));

    assertThatIllegalArgumentException().isThrownBy(() -> dollars.isMoreThan(euros));
  }

  @Test
  void aDifferenceTooLargeForMinorUnitsFailsInsteadOfWrapping() {
    var mostNegative = new Money(Long.MIN_VALUE, USD);
    var oneCent = new Money(1, USD);

    assertThatExceptionOfType(ArithmeticException.class)
        .isThrownBy(() -> mostNegative.subtract(oneCent));
  }
}
