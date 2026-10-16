package dev.rgonz.payments.platform.buyers;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.util.Optional;

/** What other modules may ask about Buyers, instead of reading the buyers schema (ADR-0009). */
public interface BuyerDirectory {

  boolean exists(BuyerId id);

  /** The Buyer's Approval Threshold; empty when there is no such Buyer. */
  Optional<Money> approvalThreshold(BuyerId id);
}
