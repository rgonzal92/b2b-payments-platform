package dev.rgonz.payments.authorization.network;

/**
 * An event the service publishes on the {@code authorizations} topic about an Authorization it
 * answered, through the outbox: the platform stores each approved or reversed Authorization so its
 * Clearing can match them (M5 design, "Events and contracts"). A declined Authorization publishes
 * nothing. Both carry the same fields and share the request ID, so the platform keys its write on
 * the request ID and event type, and a repeat changes nothing. The platform keeps its own records:
 * no class is shared.
 *
 * <p>Never a PAN or security code (ADR-0004): the card is named by its Card Token.
 */
sealed interface AuthorizationEvent {

  /**
   * The service approved a Charge and moved its card to {@code AUTHORIZED}.
   *
   * @param requestId the Network's retrieval reference number (DE 37)
   * @param amountMinorUnits the Charge's amount (DE 4), in minor units of {@code currency}
   * @param currency the Charge's ISO 4217 currency code (DE 49)
   * @param authorizationCode the approved Authorization's code (DE 38)
   * @param acceptorId the Card Acceptor ID (DE 42)
   */
  record AuthorizationApproved(
      String requestId,
      String cardToken,
      long amountMinorUnits,
      String currency,
      String authorizationCode,
      String acceptorId)
      implements AuthorizationEvent {}

  /**
   * The Network reversed an approved Authorization, and its card is {@code ACTIVE} again; the
   * fields are the approved Authorization's.
   */
  record AuthorizationReversed(
      String requestId,
      String cardToken,
      long amountMinorUnits,
      String currency,
      String authorizationCode,
      String acceptorId)
      implements AuthorizationEvent {}
}
