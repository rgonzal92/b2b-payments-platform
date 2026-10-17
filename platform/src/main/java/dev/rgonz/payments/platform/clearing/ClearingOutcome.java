package dev.rgonz.payments.platform.clearing;

import dev.rgonz.payments.platform.authorization.ApprovedAuthorization;
import dev.rgonz.payments.platform.shared.Money;
import java.util.Currency;

/**
 * How a Clearing matched its card's Authorization (M3 design, "Matching and outcomes"). Every
 * Clearing is posted, whatever its outcome, because the Issuer pays what clears; M4's
 * Reconciliation turns every outcome but {@link #MATCHED} into a Break.
 */
public enum ClearingOutcome {
  /** At or below its approved Authorization's amount; both amounts are kept. */
  MATCHED,
  /** Above its approved Authorization's amount; the full cleared amount is posted. */
  ABOVE_AUTHORIZED_AMOUNT,
  /** Its card has no Authorization with its authorization code; posted to the card's Buyer. */
  NO_AUTHORIZATION,
  /** The vault knows no card with its PAN; posted to Clearing Suspense. */
  UNKNOWN_CARD,
  /**
   * In another currency than its card's; posted to Clearing Suspense, since the Buyer's credit and
   * Receivable are in the card's currency.
   */
  OTHER_CURRENCY,
  /** Its Authorization was reversed. */
  AGAINST_REVERSED_AUTHORIZATION,
  /** Its card's Hold was already released: the card expired. */
  AFTER_HOLD_EXPIRED,
  /** Another Clearing, with another Network record ID, already cleared its Authorization. */
  AUTHORIZATION_ALREADY_CLEARED;

  /** Whether the Clearing is posted to Clearing Suspense rather than to its card's Buyer. */
  boolean postsToSuspense() {
    return this == UNKNOWN_CARD || this == OTHER_CURRENCY;
  }

  /**
   * What the import found out about one Clearing, from which its outcome follows.
   *
   * @param cleared the Clearing's amount
   * @param cardCurrency the currency of the card with the Clearing's PAN; null when there is none
   * @param authorization the card's Authorization with the Clearing's authorization code; null when
   *     it has none
   * @param authorizationAlreadyCleared whether another Clearing already cleared that Authorization
   * @param holdReleased whether the card's Hold was released before this Clearing came
   */
  record Facts(
      Money cleared,
      Currency cardCurrency,
      ApprovedAuthorization authorization,
      boolean authorizationAlreadyCleared,
      boolean holdReleased) {

    /** The first outcome that applies, in the order the M3 design gives them. */
    ClearingOutcome outcome() {
      if (cardCurrency == null) {
        return UNKNOWN_CARD;
      }
      if (!cleared.currency().equals(cardCurrency)) {
        return OTHER_CURRENCY;
      }
      if (authorization == null) {
        return NO_AUTHORIZATION;
      }
      if (authorizationAlreadyCleared) {
        return AUTHORIZATION_ALREADY_CLEARED;
      }
      if (authorization.reversed()) {
        return AGAINST_REVERSED_AUTHORIZATION;
      }
      if (holdReleased) {
        return AFTER_HOLD_EXPIRED;
      }
      // An approved Authorization is in its card's currency, so the amounts compare.
      if (cleared.isMoreThan(authorization.amount())) {
        return ABOVE_AUTHORIZED_AMOUNT;
      }
      return MATCHED;
    }
  }
}
