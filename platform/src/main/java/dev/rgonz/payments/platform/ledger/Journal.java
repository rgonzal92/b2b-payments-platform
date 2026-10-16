package dev.rgonz.payments.platform.ledger;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.util.UUID;

/** How other modules post to the Issuer's double-entry journal (ADR-0015). */
public interface Journal {

  /**
   * Posts a Clearing's Journal Entry: debits the Buyer's Receivable and credits the Network
   * Settlement Payable by the cleared amount, and returns the entry's ID. The Network record ID is
   * its idempotency key: posting it again returns the first entry's ID and posts nothing, whatever
   * the Buyer and amount. Joins the caller's transaction.
   */
  UUID postClearing(String networkRecordId, BuyerId buyerId, Money amount);
}
