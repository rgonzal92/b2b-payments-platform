package dev.rgonz.payments.platform.buyers;

import dev.rgonz.payments.platform.shared.BuyerId;

/** What other modules may ask about Buyers, instead of reading the buyers schema (ADR-0009). */
public interface BuyerDirectory {

  boolean exists(BuyerId buyer);
}
