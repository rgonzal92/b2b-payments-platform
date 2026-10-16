package dev.rgonz.payments.platform.cards;

import java.time.Instant;
import org.springframework.context.ApplicationContext;

/**
 * Runs the card expiry job by hand, at a chosen time, for tests outside {@code cards} that switch
 * its schedule off. The job stays package-private: no other module calls it.
 */
public final class CardExpiryJob {

  private CardExpiryJob() {}

  public static void runAt(ApplicationContext context, Instant now) {
    context.getBean(CardExpiry.class).expireDue(now);
  }
}
