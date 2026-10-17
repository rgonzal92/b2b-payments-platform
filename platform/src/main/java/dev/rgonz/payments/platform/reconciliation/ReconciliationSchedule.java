package dev.rgonz.payments.platform.reconciliation;

import java.time.LocalDate;
import java.time.ZoneOffset;
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

  private final Reconciliations reconciliations;

  ReconciliationSchedule(Reconciliations reconciliations) {
    this.reconciliations = reconciliations;
  }

  // Every instance runs it: a second run of the day finds the first's Breaks and raises none.
  @Scheduled(cron = "0 5 0 * * *", zone = "UTC")
  void reconcileYesterday() {
    reconciliations.run(LocalDate.now(ZoneOffset.UTC).minusDays(1));
  }
}
