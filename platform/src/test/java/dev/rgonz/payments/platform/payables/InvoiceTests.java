package dev.rgonz.payments.platform.payables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The Approval Threshold rule. */
class InvoiceTests {

  private static final Currency USD = Currency.getInstance("USD");
  private static final Money THRESHOLD = new Money(1_000_000, USD);

  @Test
  void oneApproverApprovesAnInvoiceAtOrUnderTheApprovalThreshold() {
    assertThat(invoiceFor(new Money(1_000_000, USD)).isApprovedBy(List.of("ann"), THRESHOLD))
        .isTrue();
    assertThat(invoiceFor(new Money(999_999, USD)).isApprovedBy(List.of("ann"), THRESHOLD))
        .isTrue();
  }

  @Test
  void anInvoiceAboveTheApprovalThresholdNeedsTwoDifferentApprovers() {
    var invoice = invoiceFor(new Money(1_000_001, USD));

    assertThat(invoice.isApprovedBy(List.of("ann"), THRESHOLD)).isFalse();
    assertThat(invoice.isApprovedBy(List.of("ann", "ann"), THRESHOLD)).isFalse();
    assertThat(invoice.isApprovedBy(List.of("ann", "bob"), THRESHOLD)).isTrue();
  }

  @Test
  void noApproversApproveNothing() {
    assertThat(invoiceFor(new Money(1, USD)).isApprovedBy(List.of(), THRESHOLD)).isFalse();
  }

  @Test
  void aThresholdInAnotherCurrencyIsAnError() {
    var invoice = invoiceFor(new Money(1, Currency.getInstance("EUR")));

    assertThatIllegalArgumentException()
        .isThrownBy(() -> invoice.isApprovedBy(List.of("ann"), THRESHOLD));
  }

  private static Invoice invoiceFor(Money amount) {
    return new Invoice(
        UUID.randomUUID(),
        new BuyerId(UUID.randomUUID()),
        UUID.randomUUID(),
        "INV-1",
        amount,
        LocalDate.of(2026, 11, 30),
        Invoice.Status.RECEIVED);
  }
}
