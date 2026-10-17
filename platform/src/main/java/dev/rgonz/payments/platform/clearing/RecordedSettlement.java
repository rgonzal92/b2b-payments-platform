package dev.rgonz.payments.platform.clearing;

import dev.rgonz.payments.platform.shared.Money;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A Clearing File's Settlement as the settle step recorded it, for other modules to read. A
 * Settlement is its file's own, so the file's ID names both.
 *
 * @param fileId its Clearing File
 * @param businessDate the day its file covers
 * @param networkFigure what the Network settled, the trailer's net settlement amount
 * @param importedTotal the platform's own total of the file's posted Clearings, in the same
 *     currency
 * @param settledAt when it was recorded
 */
public record RecordedSettlement(
    String fileId,
    LocalDate businessDate,
    Money networkFigure,
    Money importedTotal,
    Instant settledAt) {}
