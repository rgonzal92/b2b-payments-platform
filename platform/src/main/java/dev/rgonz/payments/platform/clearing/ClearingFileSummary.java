package dev.rgonz.payments.platform.clearing;

import java.time.LocalDate;
import java.util.Map;

/**
 * A Clearing File the platform received, as its header and its Clearings describe it.
 *
 * @param recordCount the number of Clearings in the file
 * @param outcomes how many of its recorded Clearings ended with each outcome, zero included
 */
record ClearingFileSummary(
    String id,
    long sequenceNumber,
    LocalDate businessDate,
    Status status,
    long recordCount,
    Map<ClearingOutcome, Long> outcomes) {

  ClearingFileSummary {
    outcomes = Map.copyOf(outcomes);
  }

  enum Status {
    /** Its Clearings are being posted, or an import of it stopped partway. */
    IMPORTING,
    /** Every Clearing in it is recorded. */
    IMPORTED
  }
}
