package dev.rgonz.payments.platform.authorization;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** How other modules find a card's approved Authorization, by Card Token, never by PAN. */
public interface ApprovedAuthorizations {

  /**
   * Every approved Authorization whose card expired on this day, by the UTC calendar, because the
   * Charge didn't clear within 7 days, in the order the cards expired. One the Network reversed
   * since is left out: its Charge isn't coming.
   */
  List<NeverClearedAuthorization> neverClearedOn(LocalDate day);

  /**
   * The Authorization the platform approved for the card with this authorization code (DE 38),
   * whether or not the Network reversed it since; empty when the card has none with that code. For
   * matching a Clearing to the Authorization it clears.
   */
  Optional<ApprovedAuthorization> find(String cardToken, String authorizationCode);
}
