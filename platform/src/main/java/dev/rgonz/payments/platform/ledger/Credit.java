package dev.rgonz.payments.platform.ledger;

import dev.rgonz.payments.platform.shared.Money;

/** A Buyer's Credit Line and the open Holds against it. */
record Credit(Money creditLine, Money openHolds) {

  /** The Credit Line minus open Holds. Posted spend is zero until Clearing arrives in M3. */
  Money availableCredit() {
    return creditLine.subtract(openHolds);
  }
}
