package dev.rgonz.payments.platform.reconciliation;

import dev.rgonz.payments.platform.clearing.RecordedClearing;
import dev.rgonz.payments.platform.shared.Money;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.batch.infrastructure.item.ItemProcessor;

/**
 * The Break a Clearing raises, if any: one of its outcome's kind when it didn't match, an
 * Undercharge when it matched below its Authorization's amount, and none when it matched in full.
 */
class ClearingBreaks implements ItemProcessor<RecordedClearing, Break> {

  @Override
  public Break process(RecordedClearing clearing) {
    // A switch expression: an outcome added without its Break here doesn't compile.
    var kind =
        switch (clearing.outcome()) {
          // A matched Clearing always has its Authorization, in its own currency.
          case MATCHED ->
              clearing.authorized().isMoreThan(clearing.cleared()) ? BreakKind.UNDERCHARGE : null;
          case ABOVE_AUTHORIZED_AMOUNT -> BreakKind.ABOVE_AUTHORIZED_AMOUNT;
          case NO_AUTHORIZATION -> BreakKind.NO_AUTHORIZATION;
          case UNKNOWN_CARD -> BreakKind.UNKNOWN_CARD;
          case OTHER_CURRENCY -> BreakKind.OTHER_CURRENCY;
          case AGAINST_REVERSED_AUTHORIZATION -> BreakKind.AGAINST_REVERSED_AUTHORIZATION;
          case AFTER_HOLD_EXPIRED -> BreakKind.AFTER_HOLD_EXPIRED;
          case AUTHORIZATION_ALREADY_CLEARED -> BreakKind.AUTHORIZATION_ALREADY_CLEARED;
        };
    if (kind == null) {
      return null;
    }
    return new Break(
        kind,
        clearing.networkRecordId(),
        clearing.businessDate(),
        clearing.buyerId(),
        gap(kind, clearing),
        Map.of("clearing", facts(clearing)));
  }

  // What cleared above or below the Authorization; for every other kind, the whole Clearing is in
  // question. Every kind listed, so a new one doesn't compile until its gap is decided.
  private static Money gap(BreakKind kind, RecordedClearing clearing) {
    return switch (kind) {
      case ABOVE_AUTHORIZED_AMOUNT -> clearing.cleared().subtract(clearing.authorized());
      case UNDERCHARGE -> clearing.authorized().subtract(clearing.cleared());
      case NO_AUTHORIZATION,
          UNKNOWN_CARD,
          OTHER_CURRENCY,
          AGAINST_REVERSED_AUTHORIZATION,
          AFTER_HOLD_EXPIRED,
          AUTHORIZATION_ALREADY_CLEARED ->
          clearing.cleared();
    };
  }

  // A LinkedHashMap, which keeps the nulls Map.of refuses: a missing card or Authorization shows.
  private static Map<String, Object> facts(RecordedClearing clearing) {
    var facts = new LinkedHashMap<String, Object>();
    facts.put("networkRecordId", clearing.networkRecordId());
    facts.put("fileId", clearing.fileId());
    facts.put("businessDate", clearing.businessDate().toString());
    facts.put("cardToken", clearing.cardToken());
    facts.put("buyerId", clearing.buyerId() == null ? null : clearing.buyerId().value().toString());
    facts.put("authorizationRequestId", clearing.authorizationRequestId());
    facts.put("cleared", money(clearing.cleared()));
    facts.put("authorized", clearing.authorized() == null ? null : money(clearing.authorized()));
    facts.put("outcome", clearing.outcome().name());
    return facts;
  }

  private static Map<String, Object> money(Money money) {
    return Map.of("minorUnits", money.minorUnits(), "currency", money.currency().getCurrencyCode());
  }
}
