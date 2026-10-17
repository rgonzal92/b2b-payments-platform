package dev.rgonz.payments.platform.clearing;

import java.time.LocalDate;
import java.util.List;

/** How other modules read the Clearings the platform recorded, never by PAN (ADR-0004). */
public interface RecordedClearings {

  /**
   * Every Clearing recorded from the Clearing Files of this Business Date, whatever its outcome, in
   * the order of their Network record IDs; none when no file of that day was imported.
   */
  List<RecordedClearing> onBusinessDate(LocalDate businessDate);
}
