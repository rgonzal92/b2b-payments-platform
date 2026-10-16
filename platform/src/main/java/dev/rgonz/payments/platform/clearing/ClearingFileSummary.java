package dev.rgonz.payments.platform.clearing;

import dev.rgonz.payments.platform.shared.Money;
import java.time.LocalDate;
import java.util.Map;

/**
 * A Clearing File the platform received, as its header and its Clearings describe it.
 *
 * @param recordCount the number of Clearings in the file; null when it was refused
 * @param skippedCount how many of them were skipped, their Network record ID already recorded
 * @param outcomes how many of its recorded Clearings ended with each outcome, zero included
 * @param settlement the file's Settlement; null until the file is settled
 * @param refusalReason which check refused the file, by line and field; null unless {@code REFUSED}
 */
record ClearingFileSummary(
    String id,
    long sequenceNumber,
    LocalDate businessDate,
    Status status,
    Long recordCount,
    long skippedCount,
    Map<ClearingOutcome, Long> outcomes,
    Settlement settlement,
    String refusalReason) {

  ClearingFileSummary {
    outcomes = Map.copyOf(outcomes);
  }

  /** This summary with its recorded Clearings counted by outcome. */
  ClearingFileSummary withOutcomes(Map<ClearingOutcome, Long> outcomes) {
    return new ClearingFileSummary(
        id,
        sequenceNumber,
        businessDate,
        status,
        recordCount,
        skippedCount,
        outcomes,
        settlement,
        refusalReason);
  }

  enum Status {
    /** Its Clearings are being posted, or an import of it stopped partway. */
    IMPORTING,
    /** Every Clearing in it is recorded. */
    IMPORTED,
    /** It failed a check of the validate step: nothing in it was posted. */
    REFUSED
  }

  /**
   * The file's Settlement with the Network.
   *
   * @param amount what the Network settles for the file, its trailer's net settlement amount, and
   *     what the Settlement posted
   * @param importedTotal the total of the file's posted Clearings
   * @param matched whether the two agree
   */
  record Settlement(Money amount, Money importedTotal, boolean matched) {}
}
