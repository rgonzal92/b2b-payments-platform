package dev.rgonz.payments.platform.reconciliation;

import dev.rgonz.payments.platform.ledger.Account;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.util.Objects;

/**
 * How a Reconciler resolves a Break (M4 design, "Resolving a Break"): its kind, the note saying
 * why, and for a reassignment or correction the Correcting Entry to post. Constructing any other
 * resolution fails.
 *
 * @param debited the Account the Correcting Entry debits; null when accepted
 * @param credited the Account it credits; null when accepted
 * @param amount what it moves, more than zero; null when accepted
 */
record Resolution(Kind kind, String note, Account debited, Account credited, Money amount) {

  Resolution {
    Objects.requireNonNull(kind, "kind");
    if (note == null || note.isBlank()) {
      throw new IllegalArgumentException("A resolution needs a note saying why.");
    }
    if (kind == Kind.ACCEPTED) {
      if (debited != null || credited != null || amount != null) {
        throw new IllegalArgumentException("An accepted Break moves no money.");
      }
    } else {
      if (debited == null || credited == null || amount == null) {
        throw new IllegalArgumentException(
            "A " + kind.name().toLowerCase() + " Break needs both Accounts and an amount.");
      }
      if (debited.equals(credited)) {
        throw new IllegalArgumentException("A Correcting Entry moves money between two Accounts.");
      }
      if (amount.minorUnits() <= 0) {
        throw new IllegalArgumentException("A Correcting Entry's amount must be more than zero.");
      }
    }
  }

  /** Accepts the Break as it is: no money moves. */
  static Resolution accepted(String note) {
    return new Resolution(Kind.ACCEPTED, note, null, null, null);
  }

  /**
   * Reassigns a Clearing carried in Clearing Suspense to the Buyer now known: debits its Buyer
   * Receivable and credits Clearing Suspense.
   */
  static Resolution reassigned(String note, BuyerId buyerId, Money amount) {
    if (buyerId == null || amount == null) {
      throw new IllegalArgumentException("A reassigned Break needs the Buyer and an amount.");
    }
    return new Resolution(
        Kind.REASSIGNED,
        note,
        Account.buyerReceivable(buyerId),
        Account.clearingSuspense(),
        amount);
  }

  /** Corrects the books with a balanced entry between any two Accounts. */
  static Resolution corrected(String note, Account debited, Account credited, Money amount) {
    return new Resolution(Kind.CORRECTED, note, debited, credited, amount);
  }

  boolean movesMoney() {
    return kind != Kind.ACCEPTED;
  }

  enum Kind {
    ACCEPTED,
    REASSIGNED,
    CORRECTED
  }
}
