# The books are out of balance

**Signals:** the platform logs `The books are out of balance: <Business Date>/<check>/<currency> is off by <amount> <currency>; see docs/runbooks/books-out-of-balance.md` during a Reconciliation run; `reconciliation.break` has an `OPEN` row of kind `BOOKS_OUT_OF_BALANCE`.

**This is an alarm, never routine.** Each Reconciliation run checks the whole journal (details in [`docs/learning/double-entry-bookkeeping.md`](../learning/double-entry-bookkeeping.md)), in each currency, through `ledger.Books`:

- **`DEBITS_AGAINST_CREDITS`**: the total of every debit isn't the total of every credit. Every Journal Entry the platform posts is balanced (`JournalEntry` refuses any other), so this means a row reached `ledger.journal_line` some other way: SQL by hand, a migration, a restored backup missing rows, or a bug that bypassed `JournalEntries.post`. The gap is how far apart the two totals are.
- **`NETWORK_SETTLEMENT_PAYABLE`**: every Clearing File received (refused ones aside) has its Settlement, yet the Network Settlement Payable isn't zero. Until every file is settled it rightly holds what is owed for the rest, so the check waits. The usual cause is a **mismatched Settlement**: the Network settled a figure other than the platform's total and the Payable keeps the gap; that file has a `MISMATCHED_SETTLEMENT` Break too, and [`settlement-mismatched.md`](settlement-mismatched.md) explains it. With no mismatched Settlement, look for a Clearing or Settlement entry without its counterpart. The gap is the Payable's balance, either way.

The Break's natural key is the Business Date, the check and the currency, so a re-run of the day raises nothing more, while each later day's run raises its own Break until the books balance again. Its `facts.journal` keeps the totals the run saw: debits, credits, the Payable's balance, and whether every file was settled. The checks read the journal as it stands when the run runs, not as it stood on the Business Date: a run of an old date records today's books under that date, so read the Break's `created_at` as when the books were out.

Every query below runs against the development database: `docker compose exec postgres psql -U payments payments`. Amounts are in minor units (cents for USD).

## See the Breaks

```sql
select record_id, gap_minor, gap_currency, facts -> 'journal' as journal, created_at
  from reconciliation.break
 where kind = 'BOOKS_OUT_OF_BALANCE' and status = 'OPEN'
 order by created_at;
```

## Find the unbalanced entries

Each Journal Entry whose own lines don't balance; with debits equal to credits overall there are none, so for `DEBITS_AGAINST_CREDITS` this finds the cause:

```sql
select e.id, e.kind, e.idempotency_key, e.posted_at, a.currency,
       sum(l.amount_minor) filter (where l.side = 'DEBIT') as debits_minor,
       sum(l.amount_minor) filter (where l.side = 'CREDIT') as credits_minor,
       count(*) as lines
  from ledger.journal_entry e
  join ledger.journal_line l on l.entry_id = e.id
  join ledger.account a on a.id = l.account_id
 group by e.id, e.kind, e.idempotency_key, e.posted_at, a.currency
having coalesce(sum(l.amount_minor) filter (where l.side = 'DEBIT'), 0)
    <> coalesce(sum(l.amount_minor) filter (where l.side = 'CREDIT'), 0)
 order by e.posted_at;
```

An entry with lines in two currencies shows as two rows, each unbalanced: `JournalEntry` refuses that too. An entry with no lines at all doesn't show, and unbalances nothing.

For `NETWORK_SETTLEMENT_PAYABLE`, compare each file's Clearings with its Settlement on the Payable. A Clearing entry's idempotency key is its Network record ID; a Settlement entry's is its file ID:

```sql
select c.file_id,
       sum(c.cleared_amount_minor) filter (where c.journal_entry_id is not null) as posted_minor,
       s.amount_minor as settled_minor, s.matched
  from clearing.clearing c
  left join clearing.settlement s on s.file_id = c.file_id
 group by c.file_id, s.amount_minor, s.matched
having coalesce(sum(c.cleared_amount_minor) filter (where c.journal_entry_id is not null), 0)
    <> coalesce(s.amount_minor, 0)
 order by c.file_id;
```

Settlement entries with no Settlement recorded, or Settlements whose entry is missing:

```sql
select e.idempotency_key as file_id, e.posted_at as at, 'entry without Settlement' as problem
  from ledger.journal_entry e
  left join clearing.settlement s on s.file_id = e.idempotency_key
 where e.kind = 'SETTLEMENT' and s.file_id is null
union all
select s.file_id, s.settled_at, 'Settlement without entry'
  from clearing.settlement s
 where s.journal_entry_id is not null
   and not exists (select 1 from ledger.journal_entry e where e.id = s.journal_entry_id);
```

## Who to call

- **The developer on duty for `ledger`**, for `DEBITS_AGAINST_CREDITS` always: the platform can't post an unbalanced entry, so finding how one got in is a bug hunt (the entry's `posted_at` and the deploys and migrations around it are the first leads).
- **A Reconciler**, for `NETWORK_SETTLEMENT_PAYABLE` with a mismatched Settlement behind it: the gap is the Settlement's, and resolving that Break, with the Network's answer, is what clears the Payable.
- **The Issuer's finance lead**, whenever the books stay out of balance past a day: they decide whether figures already reported need restating.

## Don't edit the entries

Leave every Journal Entry and line as it is: the journal is append-only (a trigger refuses any change or delete, ADR-0015), and the wrong entry is the evidence of what happened. Once the cause is known, the books are put right with a **Correcting Entry**, a new balanced entry posted through the resolution of the Break (#71), its reason the note; until that lands, record the finding on the Break's issue and leave the Break `OPEN`. The Break never closes by itself, even once the books balance again.
