package dev.rgonz.payments.platform.vault;

/**
 * The only place PANs and security codes are stored (ADR-0004). Other modules refer to a card by
 * its Card Token; only {@link #cardDetails} hands out a PAN, for showing to a Supplier once.
 */
public interface Vault {

  /**
   * Generates a PAN and security code for the card with this Card Token and stores both encrypted,
   * then returns the PAN's last four digits, the only part of it that leaves the vault. Joins the
   * caller's transaction, so the card data commits or rolls back with the card that uses it.
   */
  String generatePan(String cardToken);

  /**
   * The PAN and security code of the card with this Card Token, decrypted, for showing to the
   * Supplier the card pays, once. Callers never store or log them.
   */
  CardDetails cardDetails(String cardToken);
}
