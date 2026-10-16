package dev.rgonz.payments.platform.clearing;

import java.time.LocalDate;

/**
 * A Clearing File the platform received, as its header and its Clearings describe it.
 *
 * @param recordCount the number of Clearings in the file
 */
record ClearingFileSummary(
    String id, long sequenceNumber, LocalDate businessDate, Status status, long recordCount) {

  enum Status {
    /** Its Clearings are being posted, or an import of it stopped partway. */
    IMPORTING,
    /** Every Clearing in it is recorded. */
    IMPORTED
  }
}
