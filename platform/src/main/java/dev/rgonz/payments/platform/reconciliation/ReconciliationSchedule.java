package dev.rgonz.payments.platform.reconciliation;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reconciles the day before, every day at 00:05 UTC: the Business Date is the Network's calendar
 * day in UTC. Tests that run Reconciliations by hand turn this off with {@code
 * reconciliation.daily-run.enabled=false}.
 */
@Component
@ConditionalOnBooleanProperty(name = "reconciliation.daily-run.enabled", matchIfMissing = true)
class ReconciliationSchedule {

  private static final Logger log = LoggerFactory.getLogger(ReconciliationSchedule.class);

  private final Reconciliations reconciliations;

  ReconciliationSchedule(Reconciliations reconciliations) {
    this.reconciliations = reconciliations;
  }

  // Every instance tries: the one that takes the lock runs it, the others skip it.
  @Scheduled(cron = "0 5 0 * * *", zone = "UTC")
  void reconcileYesterday() {
    var yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
    try {
      reconciliations.run(yesterday);
    } catch (Reconciliations.RunInProgressException e) {
      log.info(
          "Another Reconciliation is running, on this pod or another, so this pod skipped the"
              + " Reconciliation of {}",
          yesterday);
    }
  }
}
