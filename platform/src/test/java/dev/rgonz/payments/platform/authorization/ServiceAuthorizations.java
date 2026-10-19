package dev.rgonz.payments.platform.authorization;

import dev.rgonz.payments.platform.shared.Money;
import java.time.Instant;
import org.springframework.context.ApplicationContext;

/**
 * Plays authorization-service's approved and reversed Authorizations for tests outside {@code
 * authorization} that run no Kafka, as its listener would on reading them. The copy stays
 * package-private: no other module writes it.
 */
public final class ServiceAuthorizations {

  private ServiceAuthorizations() {}

  /** The service approved the Charge: stored, and its card moves to AUTHORIZED. */
  public static void approve(
      ApplicationContext context,
      String requestId,
      String cardToken,
      Money amount,
      String authorizationCode,
      String acceptorId) {
    context
        .getBean(Authorizations.class)
        .approved(
            event(requestId, cardToken, amount, authorizationCode, acceptorId), Instant.now());
  }

  /** The Network reversed the approved Authorization: its card moves back to ACTIVE. */
  public static void reverse(
      ApplicationContext context,
      String requestId,
      String cardToken,
      Money amount,
      String authorizationCode,
      String acceptorId) {
    context
        .getBean(Authorizations.class)
        .reversed(event(requestId, cardToken, amount, authorizationCode, acceptorId));
  }

  private static AuthorizationEvent event(
      String requestId,
      String cardToken,
      Money amount,
      String authorizationCode,
      String acceptorId) {
    return new AuthorizationEvent(
        requestId,
        cardToken,
        amount.minorUnits(),
        amount.currency().getCurrencyCode(),
        authorizationCode,
        acceptorId);
  }
}
