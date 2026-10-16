package dev.rgonz.payments.platform.ledger;

import dev.rgonz.payments.platform.shared.BuyerId;
import java.util.Objects;

/**
 * An Account in the Issuer's books (ADR-0015): a Buyer Receivable per Buyer, or one of the Accounts
 * the Issuer has once. Its balance is the sum of the Journal Entry lines posted to it.
 *
 * @param buyerId the Buyer of a Buyer Receivable; null for every other kind
 */
record Account(Kind kind, BuyerId buyerId) {

  Account {
    Objects.requireNonNull(kind, "kind");
    if ((kind == Kind.BUYER_RECEIVABLE) != (buyerId != null)) {
      throw new IllegalArgumentException("Only a Buyer Receivable belongs to a Buyer.");
    }
  }

  static Account buyerReceivable(BuyerId buyerId) {
    return new Account(Kind.BUYER_RECEIVABLE, buyerId);
  }

  static Account networkSettlementPayable() {
    return new Account(Kind.NETWORK_SETTLEMENT_PAYABLE, null);
  }

  static Account issuerCash() {
    return new Account(Kind.ISSUER_CASH, null);
  }

  static Account clearingSuspense() {
    return new Account(Kind.CLEARING_SUSPENSE, null);
  }

  enum Kind {
    /** What one Buyer owes the Issuer for its cleared Charges. */
    BUYER_RECEIVABLE,
    /** What the Issuer owes the Network for cleared Charges not yet settled. */
    NETWORK_SETTLEMENT_PAYABLE,
    /** The Issuer's bank account, which Settlements are paid from and Repayments arrive in. */
    ISSUER_CASH,
    /** Cleared Charges no Buyer can be charged for yet, until a person resolves them. */
    CLEARING_SUSPENSE
  }
}
