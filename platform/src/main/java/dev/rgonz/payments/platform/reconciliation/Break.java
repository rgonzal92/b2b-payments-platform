package dev.rgonz.payments.platform.reconciliation;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.time.LocalDate;
import java.util.Map;

/**
 * What a run could not match, before it is raised. Its kind and record ID are its natural key.
 *
 * @param recordId the ID of the record it names, of the type its kind says
 * @param businessDate the Business Date of the run that found it
 * @param buyerId the Buyer it concerns; null when none is known
 * @param gap how much doesn't match, never negative
 * @param facts the named records' facts, keyed by record type, stored as JSON
 */
record Break(
    BreakKind kind,
    String recordId,
    LocalDate businessDate,
    BuyerId buyerId,
    Money gap,
    Map<String, Object> facts) {}
