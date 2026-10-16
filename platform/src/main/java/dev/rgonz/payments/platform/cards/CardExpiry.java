package dev.rgonz.payments.platform.cards;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The expiry job: Single-Use Cards nobody charged before their expiry become {@code EXPIRED}, and
 * each one's {@link CardExpired} tells {@code payables} to release its Hold. {@code
 * CardExpirySchedule} runs it; tests switch the schedule off and run it with a later {@code now}.
 */
@Component
class CardExpiry {

  private static final Logger log = LoggerFactory.getLogger(CardExpiry.class);
  private static final int BATCH_SIZE = 100;

  private final SingleUseCards cards;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate transaction;

  CardExpiry(
      SingleUseCards cards, ApplicationEventPublisher events, TransactionTemplate transaction) {
    this.cards = cards;
    this.events = events;
    this.transaction = transaction;
  }

  /**
   * Expires every {@code ACTIVE} card whose expiry is at or before {@code now}, {@value BATCH_SIZE}
   * per transaction. Each batch's status changes and events commit together, so a stop mid-run
   * leaves each card either still {@code ACTIVE}, for the next run, or {@code EXPIRED} with its
   * event recorded, for the registry to deliver. Cards another run has locked are skipped.
   */
  void expireDue(Instant now) {
    boolean batchWasFull;
    do {
      batchWasFull = Boolean.TRUE.equals(transaction.execute(status -> expireBatch(now)));
    } while (batchWasFull);
  }

  // True when the batch was full, so more cards may be due.
  private boolean expireBatch(Instant now) {
    var expired = cards.expireDue(now, BATCH_SIZE);
    for (var card : expired) {
      events.publishEvent(
          new CardExpired(card.cardToken(), card.paymentId(), card.buyerId(), card.amount()));
    }
    if (!expired.isEmpty()) {
      log.info("Expired {} Single-Use Cards nobody charged", expired.size());
    }
    return expired.size() == BATCH_SIZE;
  }
}
