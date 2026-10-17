package dev.rgonz.payments.platform.clearing;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.time.LocalDate;

/**
 * A Clearing as its import recorded it, for other modules to read. Never holds the PAN: the card is
 * named by its Card Token (ADR-0004).
 *
 * @param networkRecordId the Network's ID for the Clearing, which is also the Clearing's own
 * @param fileId the Clearing File that brought it
 * @param businessDate the day its file covers
 * @param cardToken its card; null when the vault knows no card with its PAN
 * @param buyerId its card's Buyer; null when there is no card
 * @param authorizationRequestId DE 37 of the card's Authorization it cleared; null when there is
 *     none
 * @param cleared its amount
 * @param authorized its Authorization's amount; null when there is no Authorization
 * @param outcome how it matched its card's Authorization
 */
public record RecordedClearing(
    String networkRecordId,
    String fileId,
    LocalDate businessDate,
    String cardToken,
    BuyerId buyerId,
    String authorizationRequestId,
    Money cleared,
    Money authorized,
    ClearingOutcome outcome) {}
