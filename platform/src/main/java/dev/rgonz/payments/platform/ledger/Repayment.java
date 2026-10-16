package dev.rgonz.payments.platform.ledger;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A Buyer's payment to the Issuer that reduced its Buyer Receivable, as the journal posted it.
 *
 * @param id the ID of its Journal Entry, which debits Issuer Cash and credits the Buyer Receivable
 */
record Repayment(UUID id, BuyerId buyerId, Money amount, Instant postedAt) {}
