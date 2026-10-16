package dev.rgonz.payments.platform.payables;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the Payment saga's worker every second. Tests that drive the worker by hand turn this off
 * with {@code payables.payment-worker.enabled=false}.
 */
@Component
@ConditionalOnBooleanProperty(name = "payables.payment-worker.enabled", matchIfMissing = true)
class PaymentWorkerSchedule {

  private final PaymentSaga saga;

  PaymentWorkerSchedule(PaymentSaga saga) {
    this.saga = saga;
  }

  // A fixed delay counts from the end of the previous pass, so one instance's passes never
  // overlap; other instances' passes may, and SKIP LOCKED keeps them on different Payments.
  @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.SECONDS)
  void runDueSteps() {
    saga.runDueSteps(Instant.now());
  }
}
