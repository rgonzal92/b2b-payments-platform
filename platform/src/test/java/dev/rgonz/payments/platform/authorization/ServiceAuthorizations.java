package dev.rgonz.payments.platform.authorization;

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
  public static void approve(ApplicationContext context, AuthorizationEvent event) {
    context.getBean(Authorizations.class).approved(event, Instant.now());
  }

  /** The Network reversed the approved Authorization: its card moves back to ACTIVE. */
  public static void reverse(ApplicationContext context, AuthorizationEvent event) {
    context.getBean(Authorizations.class).reversed(event);
  }
}
