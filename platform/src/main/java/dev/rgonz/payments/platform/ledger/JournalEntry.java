package dev.rgonz.payments.platform.ledger;

import dev.rgonz.payments.platform.shared.Money;
import java.util.List;
import java.util.Objects;

/**
 * A balanced record of money moving between Accounts (ADR-0015): its debits equal its credits, in
 * one currency, so constructing any other entry fails. Posted once per kind and idempotency key.
 */
record JournalEntry(Kind kind, String idempotencyKey, List<Line> lines) {

  JournalEntry {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    lines = List.copyOf(lines);
    if (lines.size() < 2) {
      throw new IllegalArgumentException("A Journal Entry needs a debit and a credit.");
    }
    var currency = lines.getFirst().amount().currency();
    var debits = 0L;
    var credits = 0L;
    for (var line : lines) {
      if (!line.amount().currency().equals(currency)) {
        throw new IllegalArgumentException("A Journal Entry's lines share one currency.");
      }
      if (line.side() == Side.DEBIT) {
        debits = Math.addExact(debits, line.amount().minorUnits());
      } else {
        credits = Math.addExact(credits, line.amount().minorUnits());
      }
    }
    if (debits != credits) {
      throw new IllegalArgumentException(
          "A Journal Entry's debits (" + debits + ") must equal its credits (" + credits + ").");
    }
  }

  enum Kind {
    CLEARING,
    SETTLEMENT
  }

  enum Side {
    DEBIT,
    CREDIT
  }

  /** One side of the entry: a positive amount debited or credited to one Account. */
  record Line(Account account, Side side, Money amount) {

    Line {
      Objects.requireNonNull(account, "account");
      Objects.requireNonNull(side, "side");
      if (amount.minorUnits() <= 0) {
        throw new IllegalArgumentException("A Journal Entry's line moves a positive amount.");
      }
    }
  }
}
