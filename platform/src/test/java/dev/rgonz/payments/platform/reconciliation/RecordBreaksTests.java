package dev.rgonz.payments.platform.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.payments.platform.clearing.RecordedSettlement;
import dev.rgonz.payments.platform.ledger.BooksBalance;
import dev.rgonz.payments.platform.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Breaks found outside a Clearing and their gaps (M4 design, "Kinds of Break"), from records as
 * their modules' read APIs answer them.
 */
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

  @Test
  void balancedBooksRaiseNothing() {
    assertThat(RecordBreaks.booksOutOfBalance(DATE, List.of(books(1_000, 1_000, 0)), true))
        .isEmpty();
  }

  @Test
  void debitsAboveCreditsAreABreakOfTheDifferenceNamedByDateCheckAndCurrency() {
    var found = RecordBreaks.booksOutOfBalance(DATE, List.of(books(1_500, 1_000, 0)), true);

    assertThat(found)
        .singleElement()
        .satisfies(
            books -> {
              assertThat(books.kind()).isEqualTo(BreakKind.BOOKS_OUT_OF_BALANCE);
              assertThat(books.recordId()).isEqualTo("2026-10-15/DEBITS_AGAINST_CREDITS/USD");
              assertThat(books.gap()).isEqualTo(new Money(500, USD));
              assertThat(books.buyerId()).isNull();
            });
  }

  // The Network Settlement Payable holds what is owed for Clearings not yet settled, so it is only
  // checked once every file is settled; a negative balance is a gap like a positive one.
  @Test
  void aNetworkSettlementPayableNotZeroOnceEveryFileIsSettledIsABreakOfItsBalance() {
    var found = RecordBreaks.booksOutOfBalance(DATE, List.of(books(1_000, 1_000, -60)), true);

    assertThat(found)
        .singleElement()
        .satisfies(
            books -> {
              assertThat(books.recordId()).isEqualTo("2026-10-15/NETWORK_SETTLEMENT_PAYABLE/USD");
              assertThat(books.gap()).isEqualTo(new Money(60, USD));
            });
  }

  @Test
  void aNetworkSettlementPayableNotZeroWhileAFileIsUnsettledRaisesNothing() {
    assertThat(RecordBreaks.booksOutOfBalance(DATE, List.of(books(1_000, 1_000, 400)), false))
        .isEmpty();
  }

  private static BooksBalance books(long debits, long credits, long networkSettlementPayable) {
    return new BooksBalance(
        new Money(debits, USD), new Money(credits, USD), new Money(networkSettlementPayable, USD));
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
