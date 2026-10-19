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
   * anything; empty when no card has it. The PAN itself never comes back. For matching a Clearing
   * File record to its card, whose caller passes the PAN straight through and never stores or logs
   * it.
   */
  Optional<String> cardFor(String pan);

  /**
   * The PAN's and security code's keyed hashes for the card with this Card Token, both under {@code
   * vault.hash-key}: the PAN's is the one the vault stores and finds the card by, and the security
   * code's is hashed now. Nothing either protects leaves the vault; a holder of the same hash key
   * can hash a request's PAN and code and compare. For publishing the card to the Authorization
   * service (ADR-0016).
   */
  CardHashes hashes(String cardToken);
}
