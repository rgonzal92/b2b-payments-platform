package dev.rgonz.payments.platform.ledger;

import dev.rgonz.payments.platform.shared.Money;

/**
 * The books in one currency as the journal stands (ADR-0015), and their balance checks.
 *
 * @param debits the total of every debit posted
 * @param credits the total of every credit posted
 * @param networkSettlementPayable the Network Settlement Payable's balance, its credits less its
 *     debits: what the Issuer owes the Network for Clearings not yet settled; negative when the
 *     Network settled more than was cleared
 */
public record BooksBalance(Money debits, Money credits, Money networkSettlementPayable) {

  /** Every Journal Entry balances, so the whole journal's debits equal its credits. */
  public boolean debitsEqualCredits() {
    return debits.minorUnits() == credits.minorUnits();
  }

  /**
   * Once every Clearing File is settled, at the platform's own figure, nothing is owed the Network.
   */
  public boolean networkSettlementPayableIsZero() {
    return networkSettlementPayable.minorUnits() == 0;
  }
}
