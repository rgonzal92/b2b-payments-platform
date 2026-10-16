package dev.rgonz.payments.platform.clearing;

import dev.rgonz.payments.platform.shared.Money;
import java.time.LocalDate;

/**
 * One Clearing as the Clearing File's detail record carries it. Holds the PAN in memory only, to
 * hand it to the vault (ADR-0004), so toString leaves it out.
 *
 * @param networkRecordId the Network's ID for this Clearing, unique across its files
 * @param pan the card's PAN (DE 2)
 * @param amount the Charge's final amount (DE 4, DE 49)
 * @param retrievalReferenceNumber the request ID of the Charge's Authorization (DE 37)
 * @param authorizationCode the code the Issuer gave when it approved the Charge's Authorization (DE
 *     38)
 * @param acceptorId the Supplier's Acceptor ID (DE 42)
 * @param chargeDate the day of the Charge
 */
record ClearingRecord(
    String networkRecordId,
    String pan,
    Money amount,
    String retrievalReferenceNumber,
    String authorizationCode,
    String acceptorId,
    LocalDate chargeDate)
    implements ClearingFileLayout.Record {

  @Override
  public String toString() {
    return "ClearingRecord[networkRecordId=" + networkRecordId + "]";
  }
}
