package dev.rgonz.payments.authorization.cards;

/**
 * Where the service's copy of a card stands. The platform's events move it to {@code CANCELLED},
 * {@code EXPIRED} or {@code CLEARED}, all final; the service alone moves it between {@code ACTIVE}
 * and {@code AUTHORIZED} when it answers Charges and Reversals.
 */
public enum CardStatus {
  ACTIVE,
  /** A Charge on it was approved: it takes no other until a Reversal makes it ACTIVE again. */
  AUTHORIZED,
  CANCELLED,
  EXPIRED,
  /** A Clearing cleared it: it takes no Charge again, and a Reversal doesn't move it. */
  CLEARED
}
