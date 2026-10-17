package dev.rgonz.payments.platform.reconciliation;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A Break as stored, with its status and, once resolved, how.
 *
 * @param buyerId the Buyer it concerns; null when none is known
 * @param resolved how it was resolved; null while {@code OPEN}
 */
record RaisedBreak(
    UUID id,
    BreakKind kind,
    String recordId,
    Status status,
    LocalDate businessDate,
    BuyerId buyerId,
    Money gap,
    Resolved resolved) {

  enum Status {
    OPEN,
    RESOLVED
  }

  /**
   * A Break's resolution as recorded.
   *
   * @param resolvedBy the Reconciler's user ID (the token's subject)
   * @param resolvedByName the Reconciler's user name when they resolved it
   * @param correctingEntryId the Correcting Entry's Journal Entry ID; null when accepted
   */
  record Resolved(
      Resolution.Kind kind,
      String note,
      String resolvedBy,
      String resolvedByName,
      Instant resolvedAt,
      UUID correctingEntryId) {}
}
