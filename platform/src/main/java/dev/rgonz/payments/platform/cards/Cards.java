package dev.rgonz.payments.platform.cards;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** How other modules issue and look up Virtual Cards, by Card Token, never by PAN (ADR-0004). */
public interface Cards {

  /**
   * Every card the expiry job expired on this day, by the UTC calendar, because its approved Charge
   * didn't clear within 7 days, whatever its status since (a late Clearing moves it to {@code
   * CLEARED}), in the order they expired.
   */
  List<SingleUseCard> expiredUnclearedOn(LocalDate day);

  /**
   * Issues the Payment's Single-Use Card for the Buyer, locked to the Supplier, its Acceptor ID and
   * the amount and valid for 30 days from {@code now}; its PAN goes into the vault. The caller
   * passes the Supplier's Acceptor ID because {@code cards} can't call {@code suppliers} (Spring
   * Modulith forbids the cycle), and it is copied now for {@link CardIssued}. Issuing again for the
   * same Payment returns the card it already has, and the first issuance's details stand. Joins the
   * caller's transaction, so the card and its event commit or roll back with whatever the caller
   * records about it.
   */
  SingleUseCard issueSingleUse(
      UUID paymentId,
      BuyerId buyerId,
      UUID supplierId,
      String acceptorId,
      Money amount,
      Instant now);

  /** The Payment's Single-Use Card; empty until one is issued. */
  Optional<SingleUseCard> findForPayment(UUID paymentId);

  /** The card with this Card Token, if there is one. */
  Optional<SingleUseCard> find(String cardToken);

  /**
   * Cancels an {@code ACTIVE} card for good, in one conditional update, so it accepts no Charge and
   * its details are shown to no one. A card in any other status is left as it is, so cancelling it
   * again, or a card that doesn't exist, changes nothing. Joins the caller's transaction.
   */
  void cancel(String cardToken);

  /**
   * Moves the card from {@code ACTIVE} to {@code AUTHORIZED} when a Charge on it is approved at
   * {@code now}, in one conditional update, and says whether it did: false when the card isn't
   * {@code ACTIVE}, or doesn't exist. The card expires if no Clearing comes within 7 days of {@code
   * now}. Joins the caller's transaction.
   */
  boolean authorize(String cardToken, Instant now);

  /**
   * Moves the card from {@code AUTHORIZED} back to {@code ACTIVE} when its approved Authorization
   * is reversed, in one conditional update, so it can be charged again and its 7 days to clear no
   * longer count; a card in any other status is left as it is. Joins the caller's transaction.
   */
  void reverse(String cardToken);

  /**
   * Moves the card to {@code CLEARED} when the Network's Clearing File gives a Charge on it a final
   * amount, whatever its status: the Issuer pays what clears, so even a card that expired, was
   * cancelled or had its Authorization reversed ends {@code CLEARED}, and {@link CardCleared} is
   * published. Clearing it again, or a card that doesn't exist, changes and publishes nothing.
   * Locks the card until the caller's transaction ends, even when it was already {@code CLEARED},
   * so two transactions clearing one card take turns. Joins the caller's transaction.
   */
  void clear(String cardToken);
}
