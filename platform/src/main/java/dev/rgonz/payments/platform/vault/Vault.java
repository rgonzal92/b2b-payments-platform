package dev.rgonz.payments.platform.vault;

import java.util.Optional;

/**
 * The only place PANs and security codes are stored (ADR-0004). Other modules refer to a card by
 * its Card Token; only {@link #cardDetails} hands out a PAN and security code, for showing to a
 * Supplier once.
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

  /**
   * The Card Token of the card with this PAN, found through the PAN's keyed hash without decrypting
   * anything; empty when no card has it. The PAN itself never comes back. For answering the
   * Network's Authorization request, whose caller passes the PAN straight through and never stores
   * or logs it.
   */
  Optional<String> cardFor(String pan);

  /**
   * Whether this is the security code of the card with this Card Token: the vault decrypts the
   * stored code and compares the two in constant time, so neither the stored code nor how much of
   * it matched ever leaves the vault. False when no card has the Card Token. For answering the
   * Network's Authorization request, whose caller passes the code straight through and never stores
   * or logs it.
   */
  boolean securityCodeMatches(String cardToken, String securityCode);
}
