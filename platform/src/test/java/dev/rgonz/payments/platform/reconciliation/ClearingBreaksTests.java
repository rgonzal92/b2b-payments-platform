package dev.rgonz.payments.platform.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.payments.platform.clearing.ClearingOutcome;
import dev.rgonz.payments.platform.clearing.RecordedClearing;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Which Break a Clearing raises, and its gap (M4 design, "Kinds of Break"). */
class ClearingBreaksTests {

  private static final Currency USD = Currency.getInstance("USD");
  private static final BuyerId BUYER = new BuyerId(UUID.randomUUID());

  private final ClearingBreaks breaks = new ClearingBreaks(cardToken -> Optional.empty());

  @Test
  void aMatchedClearingAtItsAuthorizedAmountRaisesNothing() {
    assertThat(breaks.process(clearing(ClearingOutcome.MATCHED, 1_000, 1_000L))).isNull();
  }

  @Test
  void aMatchedClearingBelowItsAuthorizedAmountIsAnUnderchargeOfTheDifference() {
    var found = breaks.process(clearing(ClearingOutcome.MATCHED, 800, 1_000L));

    assertThat(found.kind()).isEqualTo(BreakKind.UNDERCHARGE);
    assertThat(found.gap()).isEqualTo(new Money(200, USD));
  }

  @Test
  void aClearingAboveItsAuthorizedAmountIsABreakOfTheDifference() {
    var found = breaks.process(clearing(ClearingOutcome.ABOVE_AUTHORIZED_AMOUNT, 1_300, 1_000L));

    assertThat(found.kind()).isEqualTo(BreakKind.ABOVE_AUTHORIZED_AMOUNT);
    assertThat(found.gap()).isEqualTo(new Money(300, USD));
  }

  // Every other outcome puts the whole Clearing in question, with or without an Authorization.
  @ParameterizedTest
  @EnumSource(
      value = ClearingOutcome.class,
      names = {"MATCHED", "ABOVE_AUTHORIZED_AMOUNT"},
      mode = EnumSource.Mode.EXCLUDE)
  void everyOtherOutcomeIsABreakOfItsOwnKindForTheWholeClearing(ClearingOutcome outcome) {
    var found = breaks.process(clearing(outcome, 1_000, null));

    assertThat(found.kind().name()).isEqualTo(outcome.name());
    assertThat(found.gap()).isEqualTo(new Money(1_000, USD));
  }

  @Test
  void theBreakNamesItsClearingAndKeepsItsFactsWithoutAnAuthorization() {
    var clearing = clearing(ClearingOutcome.NO_AUTHORIZATION, 1_000, null);

    var found = breaks.process(clearing);

    assertThat(found.recordId()).isEqualTo(clearing.networkRecordId());
    assertThat(found.businessDate()).isEqualTo(clearing.businessDate());
    assertThat(found.buyerId()).isEqualTo(BUYER);
    @SuppressWarnings("unchecked")
    var facts = (Map<String, Object>) found.facts().get("clearing");
    assertThat(facts)
        .containsEntry("networkRecordId", clearing.networkRecordId())
        .containsEntry("buyerId", BUYER.value().toString())
        .containsEntry("authorizationRequestId", null)
        .containsEntry("authorized", null)
        .containsEntry("outcome", "NO_AUTHORIZATION");
  }

  private static RecordedClearing clearing(
      ClearingOutcome outcome, long clearedMinor, Long authorizedMinor) {
    return new RecordedClearing(
        "R" + UUID.randomUUID(),
        "F1",
        LocalDate.of(2026, 10, 15),
        "card-token",
        BUYER,
        authorizedMinor == null ? null : "A1",
        new Money(clearedMinor, USD),
        authorizedMinor == null ? null : new Money(authorizedMinor, USD),
        outcome);
  }
}
