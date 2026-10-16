package dev.rgonz.payments.platform.cards;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the card expiry job every minute. Tests that run the job by hand turn this off with {@code
 * cards.expiry-job.enabled=false}.
 */
@Component
@ConditionalOnBooleanProperty(name = "cards.expiry-job.enabled", matchIfMissing = true)
class CardExpirySchedule {

  private final CardExpiry expiry;

  CardExpirySchedule(CardExpiry expiry) {
    this.expiry = expiry;
  }

  // Every minute: cards expire at any time of day, and an expired card's credit returns soon after.
  // A fixed delay never overlaps one instance's passes; SKIP LOCKED keeps others on other cards.
  @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
  void expireDue() {
    expiry.expireDue(Instant.now());
  }
}
