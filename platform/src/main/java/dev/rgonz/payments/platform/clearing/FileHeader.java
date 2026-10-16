package dev.rgonz.payments.platform.clearing;

import java.time.LocalDate;

/**
 * A Clearing File's header record.
 *
 * @param fileId the Network's ID for the file, unique across its files: the import job's key
 * @param sequenceNumber the Network's count of files sent to the Issuer
 * @param businessDate the day the file covers
 */
record FileHeader(String fileId, long sequenceNumber, LocalDate businessDate)
    implements ClearingFileLayout.Record {}
