package dev.rgonz.payments.platform.reconciliation;

import dev.rgonz.payments.platform.authorization.NeverClearedAuthorization;
import dev.rgonz.payments.platform.clearing.RecordedSettlement;
import dev.rgonz.payments.platform.payables.UnpaidPayment;
import dev.rgonz.payments.platform.shared.Money;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Break each record found outside a Clearing raises (M4 design, "Kinds of Break"), its gap and
 * its facts; {@link ClearingBreaks} does the same for Clearings.
 */
final class RecordBreaks {

  private RecordBreaks() {}

  /**
   * An Authorization that never cleared leaves its Charge unpaid to the Supplier: the gap is the
   * authorized amount. Its Invoice is the one its card's Payment pays; null when none is known.
   */
  static Break neverCleared(
      LocalDate businessDate, NeverClearedAuthorization authorization, UUID invoiceId) {
    var facts = new LinkedHashMap<String, Object>();
    facts.put("requestId", authorization.requestId());
    facts.put("cardToken", authorization.cardToken());
    facts.put("buyerId", authorization.buyerId().value().toString());
    facts.put("amount", money(authorization.amount()));
    return new Break(
        BreakKind.NEVER_CLEARED,
        authorization.requestId(),
        businessDate,
        authorization.buyerId(),
        authorization.amount(),
        Map.of("authorization", facts, "invoice", invoice(invoiceId)));
  }

  /** A Payment that ended unpaid leaves its whole Invoice unpaid: the gap is its amount. */
  static Break unpaidInvoice(LocalDate businessDate, UnpaidPayment payment) {
    var facts = new LinkedHashMap<String, Object>();
    facts.put("paymentId", payment.paymentId().toString());
    facts.put("buyerId", payment.buyerId().value().toString());
    facts.put("amount", money(payment.amount()));
    facts.put("state", payment.state());
    facts.put("failureReason", payment.failureReason());
    facts.put("endedAt", payment.endedAt().toString());
    return new Break(
        BreakKind.UNPAID_INVOICE,
        payment.paymentId().toString(),
        businessDate,
        payment.buyerId(),
        payment.amount(),
        Map.of("payment", facts, "invoice", invoice(payment.invoiceId())));
  }

  /**
   * A Settlement whose Network figure isn't the platform's imported total: the gap is how far apart
   * they are, either way (the facts keep both, so the direction shows). It names its Clearing File,
   * whose ID is the Settlement's own, and has no Buyer: a file carries many.
   */
  static Break mismatchedSettlement(LocalDate businessDate, RecordedSettlement settlement) {
    var difference = settlement.networkFigure().subtract(settlement.importedTotal());
    var facts = new LinkedHashMap<String, Object>();
    facts.put("networkFigure", money(settlement.networkFigure()));
    facts.put("importedTotal", money(settlement.importedTotal()));
    facts.put("settledAt", settlement.settledAt().toString());
    return new Break(
        BreakKind.MISMATCHED_SETTLEMENT,
        settlement.fileId(),
        businessDate,
        null,
        new Money(Math.absExact(difference.minorUnits()), difference.currency()),
        Map.of(
            "settlement",
            facts,
            "clearingFile",
            Map.of(
                "id", settlement.fileId(),
                "businessDate", settlement.businessDate().toString())));
  }

  // A LinkedHashMap, which keeps the null Map.of refuses: an unknown Invoice shows.
  static Map<String, Object> invoice(UUID invoiceId) {
    var facts = new LinkedHashMap<String, Object>();
    facts.put("id", invoiceId == null ? null : invoiceId.toString());
    return facts;
  }

  static Map<String, Object> money(Money money) {
    return Map.of("minorUnits", money.minorUnits(), "currency", money.currency().getCurrencyCode());
  }
}
