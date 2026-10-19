package dev.rgonz.payments.platform.payables;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Payments waiting for authorization-service to have their card on file, as two gauges for Grafana:
 * {@code payments.waiting.card.on.file}, how many, and {@code
 * payments.waiting.card.on.file.oldest}, how long the oldest has waited, in seconds (0 when none
 * waits), which the alert on a wait over 5 minutes reads (#96). Read from the table, as {@code
 * OpenBreaksMetric} reads Breaks: one indexed query every 30 seconds, not one per export.
 */
@Component
class WaitingPaymentsMetric {

  private final Payments payments;
  private final AtomicLong waiting = new AtomicLong();
  private final AtomicLong oldestWaitSeconds = new AtomicLong();

  WaitingPaymentsMetric(Payments payments, MeterRegistry meters) {
    this.payments = payments;
    // Both exist from startup, at zero until the first refresh.
    Gauge.builder("payments.waiting.card.on.file", waiting, AtomicLong::get)
        .description("Payments whose card is issued, waiting for it to be on file")
        .register(meters);
    Gauge.builder("payments.waiting.card.on.file.oldest", oldestWaitSeconds, AtomicLong::get)
        .description("How long the Payment waiting longest for its card to be on file has waited")
        .baseUnit("seconds")
        .register(meters);
  }

  @Scheduled(fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
  void refresh() {
    refreshAt(Instant.now());
  }

  /** Reads the waiting Payments into the gauges, their waits measured up to {@code now}. */
  void refreshAt(Instant now) {
    var current = payments.waitingForCardOnFile();
    waiting.set(current.count());
    oldestWaitSeconds.set(
        current.oldestSince() == null
            ? 0
            : Math.max(0, Duration.between(current.oldestSince(), now).toSeconds()));
  }
}
