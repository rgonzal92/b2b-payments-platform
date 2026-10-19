package dev.rgonz.payments.authorization.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs an {@link OutboxRelay} pass 100 ms after the last one ended. It calls the relay bean, so
 * each pass goes through its transactional proxy: the pass's advisory lock then holds until it
 * commits. Tests that run passes by hand turn this off with {@code outbox.relay.enabled=false}.
 */
@Component
@ConditionalOnBooleanProperty(name = "outbox.relay.enabled", matchIfMissing = true)
class OutboxRelaySchedule {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelaySchedule.class);

  private final OutboxRelay relay;

  OutboxRelaySchedule(OutboxRelay relay) {
    this.relay = relay;
  }

  // On the scheduler's own thread, which may block: it isn't on the hot path. A fixed delay never
  // starts a pass before the last one ended. A pass that fails, as every pass does while the
  // database is down, logs one line, not a stack trace each time; the next pass tries again.
  @Scheduled(fixedDelay = 100)
  void relay() {
    try {
      relay.relay().block();
    } catch (RuntimeException e) {
      log.warn("Outbox relay pass failed: {}", e.toString());
    }
  }
}
