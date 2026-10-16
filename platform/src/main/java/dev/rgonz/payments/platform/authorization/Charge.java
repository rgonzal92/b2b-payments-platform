package dev.rgonz.payments.platform.authorization;

import dev.rgonz.payments.platform.shared.Money;

/**
 * A Charge as the Network's Authorization request describes it. Holds the PAN in memory only, to
 * hand it to the vault (ADR-0004), so toString leaves it out.
 *
 * @param requestId the retrieval reference number (DE 37)
 * @param pan the card's PAN (DE 2)
 * @param amount the amount and currency (DE 4, DE 49)
 * @param acceptorId the Supplier's Acceptor ID (DE 42)
 */
record Charge(String requestId, String pan, Money amount, String acceptorId) {

  @Override
  public String toString() {
    return "Charge[requestId=" + requestId + "]";
  }
}
