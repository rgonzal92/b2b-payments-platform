package dev.rgonz.payments.platform.clearing;

import java.time.LocalDate;

/**
 * A Clearing File the platform received, as its header and its Clearings describe it.
 *
 * @param recordCount the number of Clearings in the file; null when it was refused
 * @param skippedCount how many of them were skipped, their Network record ID already recorded
 * @param refusalReason which check refused the file, by line and field; null unless {@code REFUSED}
 */
record ClearingFileSummary(
    String id,
    long sequenceNumber,
    LocalDate businessDate,
    Status status,
    Long recordCount,
    long skippedCount,
    String refusalReason) {

  enum Status {
    /** Its Clearings are being posted, or an import of it stopped partway. */
    IMPORTING,
    /** Every Clearing in it is recorded. */
    IMPORTED,
    /** It failed a check of the validate step: nothing in it was posted. */
    REFUSED
  }
}
