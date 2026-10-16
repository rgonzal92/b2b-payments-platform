# Double-entry bookkeeping

## The problem it solves here

Until M3 the platform only reserved credit: a Hold says "this much may be spent". When the Network's Clearing File arrives, the Issuer has actually lent the Buyer money and owes it to the Network. Those are two facts about two parties, and they must always agree: every dollar the Buyer owes for a Clearing is a dollar the Issuer owes the Network until it settles. A single "posted spend" counter per Buyer would record only one side, couldn't show what the Issuer owes the Network, and couldn't prove nothing was lost on the way. A double-entry journal records both sides of every movement in one entry that must balance, so the books check themselves (ADR-0015).

## Mental model

- **An Account is a running balance; a Journal Entry moves money between Accounts.** Every entry has at least two lines, each a positive amount *debited* or *credited* to one Account, and its debits equal its credits. Money is never created or destroyed by an entry, only moved, so the sum of every Account's debits always equals the sum of every Account's credits.
- **Debit and credit are sides, not good and bad.** For an asset (something the Issuer owns or is owed), a debit increases it and a credit decreases it. For a liability (something the Issuer owes), a credit increases it and a debit decreases it.
- **The four Accounts (ADR-0015):**

  | Account | Kind | Grows with | One per |
  |---|---|---|---|
  | Buyer Receivable | asset: what a Buyer owes the Issuer | debit | Buyer (and currency) |
  | Network Settlement Payable | liability: what the Issuer owes the Network | credit | currency |
  | Issuer Cash | asset: the Issuer's bank account | debit | currency |
  | Clearing Suspense | asset: Clearings no Buyer can be charged for yet | debit | currency |

- **The entries M3 posts:**

  | Event | Debit | Credit | Ticket |
  |---|---|---|---|
  | A Clearing | the card's Buyer Receivable (or Clearing Suspense) | Network Settlement Payable | #49, #51 |
  | A Settlement | Network Settlement Payable | Issuer Cash | #52 |
  | A Repayment | Issuer Cash | the Buyer Receivable | #53 |

  Follow $1,000 through: the Clearing makes the Buyer owe $1,000 and the Issuer owe the Network $1,000; the Settlement pays the Network from Issuer Cash ($1,000 out); the Buyer's Repayment brings $1,000 back into Issuer Cash and clears its Receivable. Every Account is back at zero, and every step balanced.
- **A suspense account holds what can't be booked to its owner yet.** The Issuer pays the Network for every Clearing, even one no Buyer can be charged for: the vault knows no card with its PAN, or it is in another currency than its card's, while the Buyer's Credit Line and Receivable are in the card's. That Clearing still credits the Network Settlement Payable, and debits Clearing Suspense instead of a Buyer Receivable, so the entry balances, the Network Settlement Payable still shows everything the Network will settle, and the suspense balance is the list of questions a person must answer. Answering one is a new entry that moves the amount out (debit the right Buyer Receivable, credit Clearing Suspense), never an edit of the first; M4's Breaks are where people will do it.
- **Balances come from the journal.** An Account's balance is the sum of its lines, nothing else. Available Credit is the Credit Line minus the Buyer Receivable balance minus open Holds, so a Clearing replaces its Hold with posted spend: the Hold is marked `CLEARED`, and the Receivable carries the cleared amount. A Clearing below the Hold frees the rest of it.
- **Entries are never changed or deleted.** A mistake is corrected by a new entry that reverses it, so the books keep their history and every balance can be explained line by line.
- **Each entry has an idempotency key.** A Clearing's is its Network record ID: posting it twice (a retried chunk, a repeated record) finds the first entry and posts nothing.

## How it's built here

```text
ledger.account        id, kind, buyer_id (Buyer Receivable only), currency
                        unique nulls not distinct (kind, buyer_id, currency): created on first posting
ledger.journal_entry  id, kind (CLEARING), idempotency_key, posted_at
                        unique (kind, idempotency_key)
ledger.journal_line   entry_id, line_number, account_id, side (DEBIT/CREDIT), amount_minor > 0

Journal.postClearing(networkRecordId, buyerId, amount)          joins the caller's transaction
Journal.postClearingToSuspense(networkRecordId, amount)         the same, debiting Clearing Suspense
  └─ JournalEntries.post(entry)                                 the entry balances, or it was never built
       ├─ insert journal_entry … on conflict (kind, idempotency_key) do nothing
       │    nothing inserted: return the first entry's ID, post nothing
       └─ each line: find or add its Account; insert the line
```

- **A `JournalEntry` that doesn't balance can't be built.** Its constructor refuses fewer than two lines, lines in different currencies, and debits that don't equal credits. Integer minor units throughout (ADR-0005), with `Math.addExact` so an overflow fails instead of wrapping.
- **The database refuses changes too.** A trigger on `journal_entry` and `journal_line` raises an error on any `UPDATE`, `DELETE` or `TRUNCATE`, so not even a hand-written fix in `psql` can rewrite the books.
- **No running balance column, so no row locks.** ADR-0005 carries over core-ledger's row locks in a fixed order, which a stored balance needs: two transactions updating the same balance row must take turns, and always in the same order, or they deadlock. Every Clearing credits the one Network Settlement Payable, so a stored balance would make every import chunk queue on that row. Summing lines needs no lock: an insert never conflicts with another insert. The cost is a sum over an Account's lines on each read (indexed by Account); a stored balance can be added later if that sum becomes slow.
- **One snapshot for Available Credit.** `CreditLines` reads open Holds and the Buyer Receivable in one SQL statement. A Clearing that commits between two separate reads could be seen twice (Hold still open, Receivable already debited) or not at all (Hold already cleared, Receivable not yet debited, which would let a Hold overspend).
- **Proof:** `JournalEntryTests` (unit tests) refuses an unbalanced entry, one in two currencies and a line that isn't positive. `JournalTests` (PostgreSQL): the same idempotency key posted twice returns the first entry and debits Available Credit once; an update or delete of a posted entry or line fails. `ClearingFileImportTests` checks each outcome's two lines (the Buyer Receivable, or Clearing Suspense for an unknown card and for another currency, debited; the Network Settlement Payable credited), Available Credit afterwards, and that after a file mixing outcomes the journal's debits equal its credits.

## Gotchas we hit

- **A unique constraint ignores nulls by default.** The three global Accounts have no Buyer, and in a plain `unique (kind, buyer_id, currency)` two rows with a null `buyer_id` never conflict, so two transactions could each add a Network Settlement Payable. `unique nulls not distinct` (PostgreSQL 15+) treats the nulls as equal.
- **The same key in two kinds of entry.** A Clearing's key is its Network record ID; a Settlement's (#52) will be its file ID. Both come from the Network and could look alike, so the key is unique per kind of entry, not across the journal.
- **Posting the same key with different figures returns the first entry silently.** That's what makes a replay harmless, but a Network that reused a record ID for another Clearing would be ignored. The import guards against it separately: a Network record ID already recorded in `clearing.clearing` fails the chunk.
