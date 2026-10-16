package dev.rgonz.payments.platform.suppliers;

import java.time.Instant;

/** How the Payment saga sends a Supplier its Remittance Advice. */
public interface RemittanceAdvices {

  /**
   * Records the Remittance Advice with a one-time link to its card's details, valid for 7 days from
   * {@code now}, then POSTs it to the Supplier's remittance address with the Payment's ID as its
   * {@code Idempotency-Key}. Delivering the same Payment's advice again finds the record and sends
   * the same link. The record commits on its own, before the POST, so the link already works when
   * the Supplier receives it. Throws when the Supplier doesn't answer 2xx in time; the caller
   * retries.
   */
  void deliver(RemittanceAdvice advice, Instant now);
}
