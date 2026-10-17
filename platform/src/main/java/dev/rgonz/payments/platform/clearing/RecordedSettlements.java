package dev.rgonz.payments.platform.clearing;

import java.time.LocalDate;
import java.util.List;

/** How other modules read the Settlements the platform recorded. */
public interface RecordedSettlements {

  /**
   * Every Settlement of this Business Date's Clearing Files that didn't match: the Network's figure
   * isn't the imported total, or a posted Clearing is in another currency. In file ID order.
   */
  List<RecordedSettlement> mismatchedOn(LocalDate businessDate);

  /**
   * Whether every Clearing File received has its Settlement, refused files aside: false while a
   * file is still being imported, or stopped partway. True when no file was received. Joins the
   * caller's transaction.
   */
  boolean everyFileSettled();
}
