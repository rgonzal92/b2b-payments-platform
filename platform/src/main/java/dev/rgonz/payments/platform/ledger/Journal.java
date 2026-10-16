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

  /**
   * Posts the Journal Entry of a Clearing no Buyer can be charged for yet: debits Clearing Suspense
   * and credits the Network Settlement Payable by the cleared amount, and returns the entry's ID,
   * so the Issuer's books still show what it owes the Network until a person resolves it. Keyed by
   * the Network record ID like {@link #postClearing}, and joins the caller's transaction.
   */
  UUID postClearingToSuspense(String networkRecordId, Money amount);

  /**
   * Posts a Clearing File's Settlement: debits the Network Settlement Payable and credits Issuer
   * Cash by the Network's figure, and returns the entry's ID. The file ID is its idempotency key:
   * posting it again returns the first entry's ID and posts nothing, whatever the amount. Joins the
   * caller's transaction.
   */
  UUID postSettlement(String fileId, Money amount);
}
