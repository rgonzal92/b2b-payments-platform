package dev.rgonz.payments.platform.reconciliation;

import dev.rgonz.payments.platform.authorization.NeverClearedAuthorization;
import dev.rgonz.payments.platform.clearing.RecordedSettlement;
import dev.rgonz.payments.platform.ledger.BooksBalance;
import dev.rgonz.payments.platform.payables.UnpaidPayment;
import dev.rgonz.payments.platform.shared.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

  /**
   * A Break for each balance check the books fail, in each currency: total debits against total
   * credits always, and the Network Settlement Payable only once every Clearing File is settled, as
   * until then it rightly holds what is owed for the rest. The gap is how far each is off, either
   * way. An alarm, never routine: it names the Business Date, check and currency, so each day's run
   * raises its own while the books stay out, and a re-run of the day none.
   */
  static List<Break> booksOutOfBalance(
      LocalDate businessDate, List<BooksBalance> balances, boolean everyFileSettled) {
    var found = new ArrayList<Break>();
    for (var books : balances) {
      if (!books.debitsEqualCredits()) {
        found.add(
            booksBreak(
                businessDate,
                "DEBITS_AGAINST_CREDITS",
                books.debits().subtract(books.credits()),
                books,
                everyFileSettled));
      }
      if (everyFileSettled && !books.networkSettlementPayableIsZero()) {
        found.add(
            booksBreak(
                businessDate,
                "NETWORK_SETTLEMENT_PAYABLE",
                books.networkSettlementPayable(),
                books,
                everyFileSettled));
      }
    }
    return found;
  }

  private static Break booksBreak(
      LocalDate businessDate,
      String check,
      Money off,
      BooksBalance books,
      boolean everyFileSettled) {
    var currency = off.currency().getCurrencyCode();
    var facts = new LinkedHashMap<String, Object>();
    facts.put("check", check);
    facts.put("debits", money(books.debits()));
    facts.put("credits", money(books.credits()));
    facts.put("networkSettlementPayable", money(books.networkSettlementPayable()));
    facts.put("everyFileSettled", everyFileSettled);
    return new Break(
        BreakKind.BOOKS_OUT_OF_BALANCE,
        businessDate + "/" + check + "/" + currency,
        businessDate,
        null,
        new Money(Math.absExact(off.minorUnits()), off.currency()),
        Map.of("journal", facts));
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
