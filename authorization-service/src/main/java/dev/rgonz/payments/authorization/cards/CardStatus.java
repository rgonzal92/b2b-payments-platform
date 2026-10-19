package dev.rgonz.payments.authorization.cards;

/**
 * Where the service's copy of a card stands. The platform's events move it to {@code CANCELLED} or
 * {@code EXPIRED}, final both; the service alone moves it between {@code ACTIVE} and {@code
 * AUTHORIZED} when it answers Charges and Reversals.
 */
public enum CardStatus {
  ACTIVE,
  /** A Charge on it was approved: it takes no other until a Reversal makes it ACTIVE again. */
  AUTHORIZED,
  CANCELLED,
  EXPIRED
}
