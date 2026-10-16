package dev.rgonz.payments.platform.authorization;

import java.util.Optional;

/** How other modules find a card's approved Authorization, by Card Token, never by PAN. */
public interface ApprovedAuthorizations {

  /**
   * The Authorization the platform approved for the card with this authorization code (DE 38),
   * whether or not the Network reversed it since; empty when the card has none with that code. For
   * matching a Clearing to the Authorization it clears.
   */
  Optional<ApprovedAuthorization> find(String cardToken, String authorizationCode);
}
