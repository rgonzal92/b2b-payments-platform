package dev.rgonz.payments.platform.vault;

/**
 * A card's PAN and security code, decrypted. Shown to a Supplier once and never stored or logged
 * outside the vault, so toString leaves them out.
 */
public record CardDetails(String pan, String securityCode) {

  @Override
  public String toString() {
    return "CardDetails[pan=…" + pan.substring(pan.length() - 4) + "]";
  }
}
