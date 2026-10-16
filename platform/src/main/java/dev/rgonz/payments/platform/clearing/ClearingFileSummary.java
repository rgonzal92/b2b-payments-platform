package dev.rgonz.payments.platform.clearing;

import dev.rgonz.payments.platform.shared.Money;
import java.time.LocalDate;

/**
 * A Clearing File the platform received, as its header and its Clearings describe it.
 *
 * @param recordCount the number of Clearings in the file
 * @param settlement the file's Settlement; null until the file is settled
 */
record ClearingFileSummary(
    String id,
    long sequenceNumber,
    LocalDate businessDate,
    Status status,
    long recordCount,
    Settlement settlement) {

  enum Status {
    /** Its Clearings are being posted, or an import of it stopped partway. */
    IMPORTING,
    /** Every Clearing in it is recorded. */
    IMPORTED
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
