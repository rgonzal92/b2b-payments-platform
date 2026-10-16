package dev.rgonz.payments.platform.vault;

/**
 * The only place PANs and security codes exist (ADR-0004). Other modules refer to a card by its
 * Card Token and never receive its PAN.
 */
public interface Vault {

  /**
   * Generates a PAN and security code for the card with this Card Token and stores both encrypted,
   * then returns the PAN's last four digits, the only part of it that leaves the vault. Joins the
   * caller's transaction, so the card data commits or rolls back with the card that uses it.
   */
  String generatePan(String cardToken);
}
