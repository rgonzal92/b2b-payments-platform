package dev.rgonz.payments.simulator;

import dev.rgonz.payments.contracts.client.BreaksApi;
import dev.rgonz.payments.contracts.client.ReconciliationRunsApi;
import dev.rgonz.payments.contracts.client.model.Amount;
import dev.rgonz.payments.contracts.client.model.BreakKind;
import dev.rgonz.payments.contracts.client.model.BreakStatus;
import dev.rgonz.payments.contracts.client.model.ReconciliationRun;
import dev.rgonz.payments.contracts.client.model.ReconciliationRunRequest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The demo's Reconciliation step, after {@link ClearingFileDemo}'s import: Issuer staff (the
 * issuer-admin client) run Reconciliation for today at once rather than wait for the platform's
 * daily run, then list today's open Breaks. Never shows a PAN, nor the facts of the records a Break
 * names.
 */
@RestController
class ReconciliationDemo {

  static final String DEMO_PATH = "/demo/reconciliation";

  // The most Breaks the platform shows on a page.
  private static final int PAGE_SIZE = 100;

  private static final Logger log = LoggerFactory.getLogger(ReconciliationDemo.class);

  private final ReconciliationRunsApi reconciliationRuns;
  private final BreaksApi breaks;

  ReconciliationDemo(ReconciliationRunsApi reconciliationRuns, BreaksApi breaks) {
    this.reconciliationRuns = reconciliationRuns;
    this.breaks = breaks;
  }

  /**
   * Runs Reconciliation for today (UTC) and answers the run's summary and today's open Breaks,
   * newest first, each with its ID, kind, gap and Buyer: a Break to resolve is picked from here.
   */
  @PostMapping(DEMO_PATH)
  Answer run() {
    var today = LocalDate.now(ZoneOffset.UTC);
    var run =
        reconciliationRuns
            .runReconciliation(new ReconciliationRunRequest().businessDate(today))
            .getBody();
    log.info(
        "Reconciliation of {} found {} Breaks",
        today,
        Objects.requireNonNull(run, "the run's summary").getBreaksFound());
    return new Answer(run, openBreaksOf(today));
  }

  // The list is newest first by when each Break was raised, and a Break of today's Business Date
  // can't have been raised before today began: stop at the first page that reaches back past it.
  private List<OpenBreak> openBreaksOf(LocalDate businessDate) {
    var startOfDay = businessDate.atStartOfDay().atOffset(ZoneOffset.UTC);
    var found = new ArrayList<OpenBreak>();
    String cursor = null;
    do {
      var page =
          Objects.requireNonNull(
              breaks.listBreaks(BreakStatus.OPEN, null, null, cursor, PAGE_SIZE).getBody(),
              "a page of Breaks");
      for (var item : page.getItems()) {
        if (item.getRaisedAt().isBefore(startOfDay)) {
          return found;
        }
        if (item.getBusinessDate().equals(businessDate)) {
          found.add(new OpenBreak(item.getId(), item.getKind(), item.getGap(), item.getBuyerId()));
        }
      }
      cursor = page.getNextCursor();
    } while (cursor != null);
    return found;
  }

  /** What the demo did: the platform's summary of the run, and today's open Breaks. */
  record Answer(ReconciliationRun run, List<OpenBreak> openBreaks) {}

  /**
   * An open Break: its ID, kind, gap and the Buyer it concerns, {@code null} when none is known (an
   * unknown card's). {@code GET /v1/breaks/{id}} shows the records it names.
   */
  record OpenBreak(UUID id, BreakKind kind, Amount gap, UUID buyerId) {}
}
