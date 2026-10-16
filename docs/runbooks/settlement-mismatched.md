# A Settlement is mismatched

**Signals:** the platform logs `Clearing File <file ID> settled at the Network's <amount>, but its posted Clearings total <amount>: see docs/runbooks/settlement-mismatched.md`; the file's summary (the `201` answer of `POST /v1/clearing-files`) shows `"settlement": {…, "matched": false}`; a row in `clearing.settlement` has `matched` false.

How a file is settled (details in [`docs/learning/double-entry-bookkeeping.md`](../learning/double-entry-bookkeeping.md)): after its import step has posted the file's Clearings, the job's `settle` step totals the file's posted Clearings and compares the total with the trailer's net settlement amount, the Network's figure. Either way it posts the Network's figure, debiting the Network Settlement Payable and crediting Issuer Cash, because the Issuer pays what the Network says, and records the Settlement with both figures. **A mismatched Settlement is already paid and posted; the import completed.** What's left is a gap on the Network Settlement Payable: the file's Clearings credited it by the platform's total, its Settlement debited it by the Network's.

- **The Network's figure is lower** (the usual case): the Network settled less than the platform posted, and the Network Settlement Payable keeps a credit balance, as if the Issuer still owed the Network the difference.
- **The Network's figure is higher:** the Network took more than the platform posted, and the Network Settlement Payable has a debit balance.

Every query below runs against the development database: `docker compose exec postgres psql -U payments payments`. Amounts are in minor units (cents for USD).

## See the gap

Every mismatched Settlement, newest first; `gap_minor` is what the file left on the Network Settlement Payable:

```sql
select s.file_id, f.sequence_number, f.business_date, s.amount_minor, s.imported_total_minor,
       s.imported_total_minor - s.amount_minor as gap_minor, s.currency, s.settled_at
  from clearing.settlement s
  join clearing.clearing_file f on f.id = s.file_id
 where not s.matched
 order by s.settled_at desc;
```

What the Network Settlement Payable holds in all, per currency; once every file is settled it's the sum of those gaps, plus any posted Clearings in a currency other than their file's trailer:

```sql
select a.currency,
       sum(case l.side when 'CREDIT' then l.amount_minor else -l.amount_minor end) as owed_minor
  from ledger.journal_line l
  join ledger.account a on a.id = l.account_id
 where a.kind = 'NETWORK_SETTLEMENT_PAYABLE'
 group by a.currency;
```

## Find what the platform counted

The file's Clearings by outcome, with how many were posted and for how much; only posted Clearings in the trailer's currency count towards `imported_total_minor`:

```sql
select outcome, cleared_currency, count(*) as clearings, count(journal_entry_id) as posted,
       sum(cleared_amount_minor) filter (where journal_entry_id is not null) as posted_minor
  from clearing.clearing
 where file_id = '<file ID>'
 group by outcome, cleared_currency;
```

Compare the number of Clearings with the file's `record_count` (`select record_count from clearing.clearing_file where id = '<file ID>';`). The usual explanations:

- **A Clearing the file carries but the platform didn't post.** Every recorded Clearing is posted, whatever its outcome (Clearing Suspense included), so `posted` equals `clearings` in each row above. Once #50 skips a record repeating a Network record ID that an earlier file brought, rather than stopping the import, that record is posted with the earlier file only, and the Network may be settling it twice.
- **A Clearing in another currency than the trailer's.** M3 settles one currency: a posted Clearing in another one isn't counted in `imported_total_minor`, and it makes the Settlement mismatched even when the totals agree (the outcome query above shows its currency).
- **The Network's figure carries something the detail records don't**, such as fees or an adjustment from an earlier day.

## Ask the Network

Send the Network the file ID, its sequence number and Business Date, both figures and the gap, and ask for the breakdown of its net settlement amount: the Network record IDs and amounts it counted, any fees or adjustments in it, and whether any record was also in an earlier file. Keep its answer with the file ID (in the issue tracker until M4), for whoever resolves the gap.

## Don't fix the books by hand

Leave the Settlement and the Network Settlement Payable as they are. Journal Entries are never changed or deleted (a trigger refuses it, ADR-0015), and a correcting entry posted by hand would hide the gap that M4's Reconciliation turns into a Break: a person resolves it there, with the Network's answer, and the entry that settles the gap is posted then. A mismatched Settlement doesn't stop anything meanwhile: later files import and settle on their own.
