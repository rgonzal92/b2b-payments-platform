# Reconciliation

## The problem it solves here

An Issuer pays what clears: every Clearing in the Network's file is posted, whether or not it matched the Authorization the platform approved (M3). Posting keeps the books complete, but it doesn't make them right. A Clearing above its Authorization charged the Buyer more than they approved; one for a card nobody issued sits in Clearing Suspense, owed by nobody; one below its Authorization leaves part of an Invoice unpaid (an Undercharge). Each of these is real money in a place it shouldn't be, and nobody notices unless something looks. Reconciliation is that look, done every day, by a machine: it compares what happened with what should have happened and turns every difference into a **Break**, a record a Reconciler can act on. Card issuers reconcile daily because the Network settles daily: a mismatch found the next morning is a phone call, one found at month end is an investigation.

## Mental model

- **A run reconciles one Business Date.** The Business Date is the day a Clearing File covers, by the Network's calendar in UTC. `ReconciliationSchedule` runs the day before at 00:05 UTC; Issuer staff can run any date at once with `POST /v1/reconciliation-runs`.
- **A Break is a mismatch with a kind, a record it names and a gap.** Each Clearing outcome other than `MATCHED` is a kind (`UNKNOWN_CARD`, `ABOVE_AUTHORIZED_AMOUNT`, …), and a matched Clearing below its Authorization is an `UNDERCHARGE`. The gap is how much doesn't match: above or below the Authorization, the difference; for every other kind, the whole Clearing. The Break also keeps a snapshot of the named record's facts (IDs, amounts, outcome, Business Date) as JSON, so a person, or M8's assistant, can read it without going to the Clearing.
- **The natural key makes a run safe to repeat.** A Break's identity is its kind plus the record it names (`unique (kind, record_id)`), not its generated ID. The insert is `on conflict (kind, record_id) do nothing`, so running the same date twice, or two instances running it at once, finds the existing Break instead of adding another, and a Break a Reconciler already resolved stays resolved. The run counts what it found, what was new, and what was already known.
- **Read through APIs, write only your own tables.** `reconciliation` reads Clearings through `clearing.RecordedClearings`, never `clearing`'s tables (ADR-0003, ADR-0009). A slow read then shows in the run's duration rather than as a hidden coupling.
- **It's a Spring Batch job, like the import.** One chunk-oriented step reads the day's Clearings, a processor (`ClearingBreaks`) turns each into a Break or nothing, and a writer raises them, 100 to a transaction. Each run is a job instance of its own (a new `runId` parameter), so a date can be reconciled again; a failed run is not restarted, just run again, which the natural key makes harmless. More kinds of Break (#70) are more steps in the same job.

```text
POST /v1/reconciliation-runs {businessDate}   or   ReconciliationSchedule, 00:05 UTC
  └─ Reconciliations.run(date): job "reconciliation" (businessDate, runId)
       └─ step "clearings", chunks of 100
            read:    clearing.RecordedClearings.onBusinessDate(date)
            process: ClearingBreaks: outcome → kind, or UNDERCHARGE, or nothing
            write:   insert … on conflict (kind, record_id) do nothing; count the inserted
  ← 201 {breaksFound, newBreaks, knownBreaks}
```

## Gotchas we hit

- **`jsonb` reorders keys** (shortest first), so a test comparing the stored facts as a string fails; compare parsed JSON trees.
- **Spring Batch's write count is every Break found**, new or not: the writer counts the inserts itself in the step's execution context, and the known ones are the difference.
- **A Spring Batch job instance completes once.** With the Business Date as the only identifying parameter, the second run of a date would be refused (`JobInstanceAlreadyCompleteException`); the run ID makes each run its own instance.
- **A Break never closes itself.** A Clearing whose outcome changes later doesn't remove its Break; a Reconciler resolves it (#71).
