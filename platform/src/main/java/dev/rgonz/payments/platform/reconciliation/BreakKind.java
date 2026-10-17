package dev.rgonz.payments.platform.reconciliation;

/** What kind of mismatch a Break is (M4 design, "Kinds of Break"). */
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
  UNDERCHARGE
}
