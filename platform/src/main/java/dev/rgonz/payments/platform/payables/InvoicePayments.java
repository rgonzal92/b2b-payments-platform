package dev.rgonz.payments.platform.payables;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * How other modules read which Payments left their Invoices unpaid, and which Invoice a card pays.
 */
public interface InvoicePayments {

  /**
   * Every Payment that ended {@code FAILED} or {@code EXPIRED} on this day, by the UTC calendar, in
   * the order they ended.
   */
  List<UnpaidPayment> endedUnpaidOn(LocalDate day);

  /** The ID of the Invoice the card's Payment pays; empty when no Payment has the card. */
  Optional<UUID> invoiceForCard(String cardToken);
}
