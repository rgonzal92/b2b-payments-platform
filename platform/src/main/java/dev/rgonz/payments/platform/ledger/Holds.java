package dev.rgonz.payments.platform.ledger;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.util.UUID;

/** How other modules reserve a Buyer's Available Credit, instead of writing ledger's schema. */
public interface Holds {

  /**
   * Places a Hold for the amount on the Buyer's Available Credit. The caller chooses the Hold's ID
   * (a Payment's ID, say), so placing it again with the same ID while it is placed finds the same
   * Hold instead of placing a second one; the first placement's Buyer and amount stand. Placing a
   * released Hold again reserves the amount afresh, after the same check. Joins the caller's
   * transaction, so the Hold commits or rolls back with whatever the caller records about it.
   */
  Outcome place(UUID holdId, BuyerId buyerId, Money amount);

  /**
   * Releases the Hold, so its amount is Available Credit again; its record stays, marked released.
   * Releasing a Hold that is already released, or was never placed, changes nothing; so does
   * releasing a cleared Hold, whose Charge the Buyer Receivable already carries (a card that
   * expired after its Clearing came). Joins the caller's transaction.
   */
  void release(UUID holdId);

  /**
   * Marks the Hold cleared: its Charge cleared, and the Buyer Receivable carries the cleared amount
   * instead. The whole Hold stops counting against Available Credit, so a Clearing below it frees
   * the rest; its record stays, marked cleared. A Hold that isn't placed (released, cleared, or
   * never placed) is left as it is. Joins the caller's transaction.
   */
  void clear(UUID holdId);

  /**
   * Whether the Hold was released, its credit back with the Buyer (its card expired, say): false
   * for a Hold that is placed or cleared, or was never placed. Locks the Hold until the caller's
   * transaction ends, so it can't be released or cleared between this answer and what the caller
   * does about it. Joins the caller's transaction.
   */
  boolean isReleased(UUID holdId);

  enum Outcome {
    PLACED,
    /** The amount is more than the Buyer's Available Credit, so nothing was placed. */
    INSUFFICIENT_CREDIT
  }
}
