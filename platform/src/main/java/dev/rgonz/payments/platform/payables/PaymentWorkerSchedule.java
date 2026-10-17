package dev.rgonz.payments.platform.payables;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the Payment saga's worker every second, as {@code payables.payment-worker.threads} workers
 * at once, each draining due Payments until none is left. Tests that drive the worker by hand turn
 * this off with {@code payables.payment-worker.enabled=false}.
 */
@Component
@ConditionalOnBooleanProperty(name = "payables.payment-worker.enabled", matchIfMissing = true)
class PaymentWorkerSchedule {

  private static final Logger log = LoggerFactory.getLogger(PaymentWorkerSchedule.class);

  private final PaymentSaga saga;
  private final int threads;

  PaymentWorkerSchedule(
      PaymentSaga saga, @Value("${payables.payment-worker.threads}") int threads) {
    if (threads < 1) {
      throw new IllegalArgumentException("payables.payment-worker.threads must be at least 1");
    }
    this.saga = saga;
    this.threads = threads;
  }

  // A fixed delay counts from the end of the previous pass, which waits for all its workers, so one
  // instance's passes never overlap; its workers, and other instances' passes, run at once, and
  // SKIP LOCKED keeps them on different Payments. Each worker holds one database connection at a
  // time.
  @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.SECONDS)
  void runDueSteps() {
    var now = Instant.now();
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var i = 0; i < threads; i++) {
        workers.execute(() -> runDueSteps(now));
      }
    }
  }

  // An executor's thread drops what it throws, so each worker logs its own failure, as the
  // scheduler did when it ran the pass itself; the next pass tries again.
  private void runDueSteps(Instant now) {
    try {
      saga.runDueSteps(now);
    } catch (RuntimeException e) {
      log.error("A Payment worker stopped its pass", e);
    }
  }
}
