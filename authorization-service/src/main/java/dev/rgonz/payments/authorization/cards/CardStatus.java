package dev.rgonz.payments.authorization.cards;

/**
 * Where the service's copy of a card stands. The platform's events move it to {@code CANCELLED} or
 * {@code EXPIRED}, final both; the service alone will move it between {@code ACTIVE} and {@code
 * AUTHORIZED} when it answers Charges (#89).
 */
public enum CardStatus {
  ACTIVE,
  CANCELLED,
  EXPIRED
}
