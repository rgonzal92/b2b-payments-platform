package dev.rgonz.payments.platform.ledger;

import dev.rgonz.payments.platform.shared.Money;

/**
 * A Buyer's Credit Line, the open Holds against it, and its Buyer Receivable: what it owes for
 * cleared Charges, read from the journal (ADR-0015).
 */
record Credit(Money creditLine, Money openHolds, Money buyerReceivable) {

  /** The Credit Line minus the Buyer Receivable and minus open Holds. */
  Money availableCredit() {
    return creditLine.subtract(buyerReceivable).subtract(openHolds);
  }
}
