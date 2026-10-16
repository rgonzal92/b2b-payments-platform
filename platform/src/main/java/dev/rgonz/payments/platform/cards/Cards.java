package dev.rgonz.payments.platform.cards;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** How other modules issue and look up Virtual Cards, by Card Token, never by PAN (ADR-0004). */
public interface Cards {

  /**
   * Issues the Payment's Single-Use Card for the Buyer, locked to the Supplier and amount and valid
   * for 30 days from {@code now}; its PAN goes into the vault. Issuing again for the same Payment
   * returns the card it already has, and the first issuance's details stand. Joins the caller's
   * transaction, so the card commits or rolls back with whatever the caller records about it.
   */
  SingleUseCard issueSingleUse(
      UUID paymentId, BuyerId buyerId, UUID supplierId, Money amount, Instant now);

  /** The Payment's Single-Use Card; empty until one is issued. */
  Optional<SingleUseCard> findForPayment(UUID paymentId);
}
