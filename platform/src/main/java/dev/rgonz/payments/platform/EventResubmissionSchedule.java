package dev.rgonz.payments.platform;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every minute, sends again each event publication still incomplete after {@code
 * platform.event-resubmission.older-than} (a minute): an event Kafka refused while it was down, or
 * a listener that failed. Without it they wait for the next restart ({@code
 * republish-outstanding-events-on-restart}), and a Payment whose {@code CardIssued} never reached
 * authorization-service waits for its card to be on file until the card expires. A resent event may
 * arrive twice, and out of order with the card's other events; every consumer and listener already
 * tolerates both. Tests that run a pass by hand, or none, turn this off with {@code
 * platform.event-resubmission.enabled=false}.
 */
@Component
@ConditionalOnBooleanProperty(name = "platform.event-resubmission.enabled", matchIfMissing = true)
class EventResubmissionSchedule {

  private final IncompleteEventPublications publications;
  private final Duration olderThan;

  EventResubmissionSchedule(
      IncompleteEventPublications publications,
      @Value("${platform.event-resubmission.older-than:PT1M}") Duration olderThan) {
    this.publications = publications;
    this.olderThan = olderThan;
  }

  // Older than a minute, so an event whose first send or listener is still under way isn't sent
  // again beside it.
  @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
  void resubmitIncomplete() {
    publications.resubmitIncompletePublicationsOlderThan(olderThan);
  }
}
