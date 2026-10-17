package dev.rgonz.payments.platform.reconciliation;

/** What Reconciliation could not match, by kind (M4 design, "Kinds of Break"). */
enum BreakKind {
  // One per Clearing outcome other than matched; each names the Clearing.
  ABOVE_AUTHORIZED_AMOUNT,
  NO_AUTHORIZATION,
  UNKNOWN_CARD,
  OTHER_CURRENCY,
  AGAINST_REVERSED_AUTHORIZATION,
  AFTER_HOLD_EXPIRED,
  AUTHORIZATION_ALREADY_CLEARED,
  /** A matched Clearing below its Authorization's amount; names the Clearing. */
  UNDERCHARGE,
  /** An approved Authorization whose card expired by the 7-day rule; names the Authorization. */
  NEVER_CLEARED,
  /** A Payment that ended {@code FAILED} or {@code EXPIRED}; names the Payment. */
  UNPAID_INVOICE,
  /** A Settlement whose Network figure isn't the imported total; names its Clearing File. */
  MISMATCHED_SETTLEMENT,
  /** A failed balance check of the books; names the Business Date, check and currency. */
  BOOKS_OUT_OF_BALANCE
}
