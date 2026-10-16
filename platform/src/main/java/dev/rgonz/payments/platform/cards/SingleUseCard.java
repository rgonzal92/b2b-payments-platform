package dev.rgonz.payments.platform.cards;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A Virtual Card issued to pay one Invoice, valid only for its Supplier and amount. Holds the PAN's
 * last four digits, which identify the card to people, and never the PAN itself.
 */
public record SingleUseCard(
    String cardToken,
    UUID paymentId,
    BuyerId buyerId,
    UUID supplierId,
    Money amount,
    String lastFour,
    Instant issuedAt,
    Instant expiresAt,
    Status status) {

  public enum Status {
    ACTIVE,
    /** A Charge on it was approved; Clearing (M3) or a Reversal comes next. */
    AUTHORIZED,
    CANCELLED
  }
}
