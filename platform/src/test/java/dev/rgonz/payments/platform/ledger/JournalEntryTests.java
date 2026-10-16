package dev.rgonz.payments.platform.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.rgonz.payments.platform.ledger.JournalEntry.Line;
import dev.rgonz.payments.platform.ledger.JournalEntry.Side;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A Journal Entry balances by construction (ADR-0015): any other entry can't be built. */
class JournalEntryTests {

  private static final Currency USD = Currency.getInstance("USD");
  private static final Account BUYER_RECEIVABLE =
      Account.buyerReceivable(new BuyerId(UUID.randomUUID()));
  private static final Account NETWORK_SETTLEMENT_PAYABLE = Account.networkSettlementPayable();

  @Test
  void anEntryWhoseDebitsEqualItsCreditsIsBuilt() {
    var entry =
        clearing(
            new Line(BUYER_RECEIVABLE, Side.DEBIT, usd(1_240_000)),
            new Line(NETWORK_SETTLEMENT_PAYABLE, Side.CREDIT, usd(1_000_000)),
            new Line(NETWORK_SETTLEMENT_PAYABLE, Side.CREDIT, usd(240_000)));

    assertThat(entry.lines()).hasSize(3);
  }

  @Test
  void anEntryWhoseDebitsAndCreditsDontBalanceIsRefused() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                clearing(
                    new Line(BUYER_RECEIVABLE, Side.DEBIT, usd(1_240_000)),
                    new Line(NETWORK_SETTLEMENT_PAYABLE, Side.CREDIT, usd(1_239_999))))
        .withMessageContaining("debits (1240000) must equal its credits (1239999)");
  }

  @Test
  void anEntryWithFewerThanTwoLinesOrLinesInTwoCurrenciesIsRefused() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> clearing(new Line(BUYER_RECEIVABLE, Side.DEBIT, usd(1_240_000))));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                clearing(
                    new Line(BUYER_RECEIVABLE, Side.DEBIT, usd(1_240_000)),
                    new Line(
                        NETWORK_SETTLEMENT_PAYABLE,
                        Side.CREDIT,
                        new Money(1_240_000, Currency.getInstance("EUR")))));
  }

  @Test
  void aLineMovesAPositiveAmount() {
    for (var minorUnits : List.of(0L, -1L)) {
      assertThatIllegalArgumentException()
          .as("%d minor units", minorUnits)
          .isThrownBy(() -> new Line(BUYER_RECEIVABLE, Side.DEBIT, usd(minorUnits)));
    }
  }

  @Test
  void onlyABuyerReceivableBelongsToABuyer() {
    var buyerId = new BuyerId(UUID.randomUUID());

    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Account(Account.Kind.NETWORK_SETTLEMENT_PAYABLE, buyerId));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Account(Account.Kind.BUYER_RECEIVABLE, null));
  }

  private static JournalEntry clearing(Line... lines) {
    return new JournalEntry(
        JournalEntry.Kind.CLEARING, UUID.randomUUID().toString(), List.of(lines));
  }

  private static Money usd(long minorUnits) {
    return new Money(minorUnits, USD);
  }
}
