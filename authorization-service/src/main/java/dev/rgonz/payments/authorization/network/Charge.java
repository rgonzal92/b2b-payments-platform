package dev.rgonz.payments.authorization.network;

/**
 * A Charge as the Network's Authorization request describes it, as sent: the amount and currency
 * aren't checked yet, so a currency code that isn't ISO 4217 can still be recorded and declined.
 * Holds the PAN and security code in memory only, to hash and compare them (ADR-0004, as amended by
 * ADR-0016), so toString leaves them out.
 *
 * @param requestId the retrieval reference number (DE 37)
 * @param pan the card's PAN (DE 2)
 * @param securityCode the card's security code
 * @param expirationDate the card's expiry as printed on it, YYMM (DE 14)
 * @param amountMinor the amount in minor units of the currency (DE 4)
 * @param currencyCode the currency's ISO 4217 alphabetic code, as sent (DE 49)
 * @param acceptorId the Supplier's Acceptor ID (DE 42)
 */
record Charge(
    String requestId,
    String pan,
    String securityCode,
    String expirationDate,
    long amountMinor,
    String currencyCode,
    String acceptorId) {

  @Override
  public String toString() {
    return "Charge[requestId=" + requestId + "]";
  }
}
