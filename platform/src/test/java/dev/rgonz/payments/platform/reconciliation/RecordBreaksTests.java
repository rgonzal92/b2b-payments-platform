package dev.rgonz.payments.platform.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.payments.platform.clearing.RecordedSettlement;
import dev.rgonz.payments.platform.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.Test;

/** The gaps of the Breaks found outside a Clearing (M4 design, "Kinds of Break"). */
class RecordBreaksTests {

  private static final Currency USD = Currency.getInstance("USD");
  private static final LocalDate DATE = LocalDate.of(2026, 10, 15);

  @Test
  void aSettlementAboveTheImportedTotalIsABreakOfTheDifference() {
    var found = RecordBreaks.mismatchedSettlement(DATE, settlement(1_000, 940));

    assertThat(found.kind()).isEqualTo(BreakKind.MISMATCHED_SETTLEMENT);
    assertThat(found.recordId()).isEqualTo("F1");
    assertThat(found.gap()).isEqualTo(new Money(60, USD));
  }

  // The gap is never negative; the facts keep both figures, so which way it went shows.
  @Test
  void aSettlementBelowTheImportedTotalIsABreakOfTheDifferenceToo() {
    var found = RecordBreaks.mismatchedSettlement(DATE, settlement(500, 525));

    assertThat(found.gap()).isEqualTo(new Money(25, USD));
  }

  private static RecordedSettlement settlement(long networkFigure, long importedTotal) {
    return new RecordedSettlement(
        "F1",
        DATE,
        new Money(networkFigure, USD),
        new Money(importedTotal, USD),
        Instant.parse("2026-10-16T00:01:00Z"));
  }
}
