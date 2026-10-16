package dev.rgonz.payments.platform.payables;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The Payment saga's state transitions: a step that succeeds, is refused, or fails technically. */
class PaymentTests {

  private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

  @Test
  void aPaymentStartsDueForItsFirstStepAtOnce() {
    var payment = started();

    assertThat(payment.state()).isEqualTo(Payment.State.STARTED);
    assertThat(payment.attempts()).isZero();
    assertThat(payment.nextAttemptAt()).isEqualTo(NOW);
  }

  @Test
  void placingTheHoldMakesIssuingTheCardDueAtOnce() {
    var placed = started().stepFailed(NOW).holdPlaced(NOW);

    assertThat(placed.state()).isEqualTo(Payment.State.HOLD_PLACED);
    assertThat(placed.attempts()).isZero();
    assertThat(placed.nextAttemptAt()).isEqualTo(NOW);
  }

  @Test
  void issuingTheCardMakesDeliveringTheRemittanceAdviceDueAtOnce() {
    var issued = started().holdPlaced(NOW).stepFailed(NOW).cardIssued(NOW);

    assertThat(issued.state()).isEqualTo(Payment.State.CARD_ISSUED);
    assertThat(issued.attempts()).isZero();
    assertThat(issued.nextAttemptAt()).isEqualTo(NOW);
  }

  @Test
  void aDeliveredRemittanceAdviceCompletesThePaymentWithNoStepLeft() {
    var completed = started().holdPlaced(NOW).cardIssued(NOW).stepFailed(NOW).completed();

    assertThat(completed.state()).isEqualTo(Payment.State.COMPLETED);
    assertThat(completed.attempts()).isZero();
    assertThat(completed.failureReason()).isNull();
    assertThat(completed.nextAttemptAt()).isNull();
  }

  @Test
  void theFifthFailedDeliveryFailsThePaymentNamingTheStep() {
    var payment = started().holdPlaced(NOW).cardIssued(NOW);
    for (var attempt = 1; attempt <= 5; attempt++) {
      payment = payment.stepFailed(NOW);
    }

    assertThat(payment.state()).isEqualTo(Payment.State.FAILED);
    assertThat(payment.failureReason())
        .isEqualTo("Delivering the Remittance Advice failed 5 times for technical reasons.");
  }

  @Test
  void aRefusalFailsThePaymentAtOnce() {
    var refused = started().refused("Not enough Available Credit to place the Hold.");

    assertThat(refused.state()).isEqualTo(Payment.State.FAILED);
    assertThat(refused.failureReason()).isEqualTo("Not enough Available Credit to place the Hold.");
    assertThat(refused.nextAttemptAt()).isNull();
  }

  @Test
  void aTechnicalFailureRetriesTheStepAfterABackoffThatDoublesEachTime() {
    var once = started().stepFailed(NOW);
    var twice = once.stepFailed(NOW);
    var thrice = twice.stepFailed(NOW);
    var fourTimes = thrice.stepFailed(NOW);

    assertThat(fourTimes.state()).isEqualTo(Payment.State.STARTED);
    assertThat(fourTimes.attempts()).isEqualTo(4);
    assertThat(fourTimes.failureReason()).isNull();
    assertThat(List.of(once, twice, thrice, fourTimes))
        .extracting(Payment::nextAttemptAt)
        .containsExactly(
            Instant.parse("2026-10-15T12:00:10Z"),
            Instant.parse("2026-10-15T12:00:20Z"),
            Instant.parse("2026-10-15T12:00:40Z"),
            Instant.parse("2026-10-15T12:01:20Z"));
  }

  @Test
  void theFifthTechnicalFailureFailsThePaymentForGood() {
    var payment = started();
    for (var attempt = 1; attempt <= 5; attempt++) {
      payment = payment.stepFailed(NOW);
    }

    assertThat(payment.state()).isEqualTo(Payment.State.FAILED);
    assertThat(payment.attempts()).isEqualTo(5);
    assertThat(payment.failureReason())
        .isEqualTo("Placing the Hold failed 5 times for technical reasons.");
    assertThat(payment.nextAttemptAt()).isNull();
  }

  private static Payment started() {
    return Payment.startFor(
        new InvoiceApproved(
            UUID.randomUUID(),
            new BuyerId(UUID.randomUUID()),
            UUID.randomUUID(),
            new Money(1_240_000, Currency.getInstance("USD"))),
        NOW);
  }
}
