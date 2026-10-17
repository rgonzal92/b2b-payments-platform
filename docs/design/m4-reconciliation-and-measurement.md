# M4: Reconciliation and measurement

**Status:** approved 2026-10-16. Decisions from the M4 grilling (Rounds 1–2, Q1–Q12, 2026-10-16).

## Problem

M3 books every Clearing and settles every file, and records how each Clearing matched, but nobody looks at what didn't match: an Undercharge, a Clearing for a card nobody issued, an Authorization that never cleared, a Settlement that disagrees with the Network. M4 builds the last step of the core path (README, step 7): a daily Reconciliation that turns every mismatch into a Break a Reconciler resolves, with Correcting Entries where money is in the wrong Account. It also measures what M5's extraction of Authorization rests on (ADR-0010): whether the monolith answers 99% of Authorizations within 100 ms, and makes the running system visible in Grafana.

## Done when

1. A Reconciliation run for a Business Date raises one Break for each mismatch below, and running it again raises no duplicates.
2. A Reconciler resolves a Break as accepted, reassigned from Clearing Suspense to a Buyer, or corrected with a balanced Correcting Entry; a resolved Break can't be resolved again, and Issuer staff can read Breaks but not resolve them.
3. `GET /v1/breaks` lists Breaks by status, kind and Buyer, and `GET /v1/breaks/{id}` shows each Break with the records it names.
4. After `POST /demo/clearing-file?unknownPan=true`, `POST /demo/reconciliation` shows the unknown-card Break, and one `curl` as a Reconciler moves the money from Clearing Suspense to the right Buyer.
5. The end-to-end test's happy path ends with a Reconciliation run that finds no Break for its Charge.
6. The Gatling load test holds 200 Authorizations a second for 2 minutes against the platform on a laptop, and its result (server-side p99, errors, hardware) is recorded in this document.
7. Grafana shows four provisioned dashboards (Authorization, Payments, Clearing, Breaks), and an alert fires when Authorization p99 stays above 100 ms for 5 minutes.
8. `docs/architecture.md`, explainers for reconciliation, Gatling and Grafana provisioning, and runbooks for a failed Reconciliation run and books out of balance are written.

## Approach

### Modules in M4

| Module | Builds |
|---|---|
| `reconciliation` | New. The Reconciliation job, Breaks and their resolution, the Break and run endpoints. |
| `clearing` | Read access to Clearings by Business Date with their outcomes and amounts, and to Settlements. |
| `authorization` | Read access to approved Authorizations that expired uncleared under the 7-day rule. |
| `payables` | Read access to Payments that ended `EXPIRED` or `FAILED`, with their Invoices. |
| `ledger` | Correcting Entries (including reassigning from Clearing Suspense), and the balance checks: total debits against total credits, and the Network Settlement Payable. |
| simulator | Initech (the load test's Buyer), card seeding for the load test, the `simulator-reconciler` client, `POST /demo/reconciliation`. |
| `load-test` | New Maven module: the Gatling simulation. |

`reconciliation` reads other modules only through their public APIs (ADR-0003, ADR-0009), never their tables.

### Reconciliation (Q1, Q9)

- A Spring Batch job in `reconciliation`, sharing M3's job repository: one run per Business Date, just after midnight UTC for the day before, and on demand through `POST /v1/reconciliation-runs` (Issuer staff), which answers the run's summary: Breaks found, new and already known. (Changed during #69: each run is a job instance of its own, identified by the Business Date and a new run ID, so a date reconciled already can be run again; a failed run is run again rather than restarted, which the natural key makes harmless. The daily run is at 00:05 UTC.)
- Each Break has a natural key, its kind plus the record it names, so a re-run finds an existing Break instead of adding another, and a resolved Break stays resolved.
- A Break never closes by itself. An Authorization flagged as never cleared whose Clearing arrives late gets that Clearing's own "after Hold expired" Break; the two link to each other, and a Reconciler closes both.

### Kinds of Break (Q2)

| Kind | Found from | Names |
|---|---|---|
| One per M3 outcome other than matched: above authorized amount, no Authorization, unknown card, other currency, against a reversed Authorization, after Hold expired, Authorization already cleared | the Clearing's outcome | the Clearing (and its Authorization when there is one) |
| Undercharge | a matched Clearing below its authorized amount | the Clearing, its Authorization and Invoice (Changed during #69: the Clearing and its Authorization's request ID; the Invoice isn't named yet, as `payables` has no read API; no ticket covers it so far) |
| Never cleared | an approved Authorization expired by the 7-day rule | the Authorization and its Invoice |
| Unpaid Invoice | a Payment that ended `EXPIRED` or `FAILED` | the Payment and its Invoice |
| Mismatched Settlement | a Settlement whose Network figure differed from the imported total | the Settlement and its Clearing File |
| Books out of balance | total debits ≠ total credits, or the Network Settlement Payable not zero once every file is settled | the journal (an alarm, never routine) |

Every Break records the amount gap and enough of the records' facts (amounts, dates, outcome, Buyer) for M8's assistant to draft a likely cause from `GET /v1/breaks/{id}` alone.

### Resolving a Break (Q3, Q4, Q8)

- `OPEN` → `RESOLVED`, recording the Reconciler, the time and a required note, in one of three ways:
  - **accepted**: no money moves (an Undercharge the Buyer agreed to);
  - **reassigned**: a Correcting Entry debits a Buyer Receivable and credits Clearing Suspense (an unknown-card or other-currency Clearing whose Buyer is now known);
  - **corrected**: a balanced Correcting Entry between any two Accounts, its reason the note (a Repayment recorded by mistake).
- Every correction is a new Journal Entry (ADR-0015); nothing is edited or deleted. Disputes and chargebacks stay out of scope.
- A new realm role **`reconciler`** resolves Breaks; `issuer-admin` reads them. Moving money out of Clearing Suspense or correcting the books shouldn't come with the role that sets Credit Lines and records Repayments.
- Endpoints: `GET /v1/breaks` (cursor paging, filters by status, kind and Buyer; `reconciler` or `issuer-admin`), `GET /v1/breaks/{id}`, and `POST /v1/breaks/{id}/resolution` (`reconciler` only). A second resolution answers `409`; that once-only rule makes a repeat harmless, so the endpoint takes no `Idempotency-Key` (CLAUDE.md's exception). (Changed during #71: the Reconciler names the amount of a reassignment too, rather than the platform taking the Break's gap, and Accounts are named by kind, plus the Buyer for a Buyer Receivable; an unknown Buyer, a Buyer Receivable in another currency than its Buyer's, and a field the kind doesn't take are refused with `422`, so an other-currency Clearing can't be reassigned to its Buyer by one Correcting Entry yet. The Break keeps its Correcting Entry's ID and the Reconciler's user name beside the subject.) (Changed during #72: the list is newest first by when each Break was raised, and the detail shows the facts stored when it was raised, keyed by record type, rather than reading the named records again. The gap is #71's `Amount`, not `Money`, whose currency is USD only, since an other-currency Break's gap is in the Clearing's currency.)

### The load test (Q5, Q6, Q10, Q11)

- A new `load-test` Maven module with a Gatling simulation in its Java DSL (`gatling-grpc-java`), calling the platform's `Authorize` over gRPC with a `network` token. It runs by hand against the Docker Compose stack, never in CI, whose runners are too noisy to measure a p99.
- **Cards to charge:** the simulator seeds a second Buyer, **Initech**, with its own Approver, a $1,000,000 Credit Line and an Approval Threshold above $10. `POST /demo/load-test-cards?count=N` sends N $10.00 Invoices to Globex through the real path; Globex reads each card through its one-time link and writes the details to a gitignored file for Gatling. The file holds PANs, as the Supplier's own view of its cards outside the platform; the README says to delete it after a run.
- **The mix:** about 90% approvals on fresh cards and 10% declines (a second Charge on a card, a wrong security code), so the approval path's writes dominate.
- **The profile:** a 30-second warm-up, then 200 Authorizations a second for 2 minutes.
- **Pass:** the platform's server-side p99 (Micrometer's gRPC timing, in Grafana) under 100 ms, and no failed call; Gatling's client-side numbers are reported alongside. If it misses, M4 still finishes: this document records the number, the cause found and any fix, and the number goes into M5's case for extracting Authorization.

### Grafana (Q7)

- Four dashboards as provisioned JSON in the repository, mounted into the `otel-lgtm` container: Authorization (rate, p50/p95/p99 latency, declines by response code), Payments (saga states, time spent per step), Clearing (files, outcomes, Settlements), Breaks (open Breaks by kind and age).
- Business metrics behind them through Micrometer: declines by response code, Clearings by outcome, open Breaks by kind.
- One provisioned alert rule: Authorization p99 above 100 ms for 5 minutes. It shows as firing in Grafana; no notification channel locally.
- (Changed during #73: the files live in a top-level `grafana/` folder, `dashboards/` mounted read-only as a folder and the dashboard provider and alert rule each mounted as one file into Grafana's provisioning folders, so the image's own data sources and dashboards stay. "Time spent per step" is how long each step runs (`payments.steps`), not how long a Payment waits for the worker or a backoff between steps: the payment row records no time it entered its state, so that wait isn't measured. Payments by state is a counter of Payments reaching each state, counted when the state's transaction commits, rather than a gauge of how many sit in each state now, which would need a count query against the table on every export. Clearing also counts Clearing Files by result (imported, refused, repeated) and Settlements by whether they matched; the Authorization dashboard counts every answer by response code, approvals included, beside the declines. The Breaks dashboard arrives with #72.) (Changed during #72: open Breaks by kind is a gauge, `reconciliation.breaks.open` tagged `kind` and `age`, read from the Breaks table with one grouped query every 30 seconds, not a pair of counters raised and resolved: counters restart at zero with the platform, and only the table knows each open Break's age. A Break's age counts from when it was raised, not its Business Date, in three buckets: under 1 day, 1 to 7 days, over 7 days.)

### Demo and end-to-end test (Q12)

- `POST /demo/reconciliation` runs Reconciliation for today and answers the Breaks found. After `POST /demo/clearing-file?unknownPan=true` it shows the unknown-card Break, and the README resolves it with one `curl` signed in as `simulator-reconciler`.
- The end-to-end test's happy path ends with a Reconciliation run that finds no Break for its Charge.

## Deferred

A Break for a missing Clearing File (the file list already shows sequence gaps); assigning Breaks to people, due dates and notifications; disputes and chargebacks; closing Breaks automatically; a performance gate in CI; alert routing; the Break resolution UI (M9) and the assistant that drafts causes (M8).

## Rejected options

- **Raising Breaks as Clearings are recorded:** the time-based kinds (never cleared, unpaid Invoice) still need a sweep, so it becomes two mechanisms.
- **A Reconciliation step at the end of each import:** misses everything that isn't in a Clearing File.
- **Closing Breaks automatically:** a person should see why money moved, and a self-closing Break hides a late Clearing.
- **Letting `issuer-admin` resolve Breaks:** one role could then both set credit and move money out of Clearing Suspense.
- **Seeding cards through a test-only platform endpoint:** a back door into card issuance; the real path is slower but proves nothing is bypassed.
- **Loading with declines only:** never touches the approval path's writes, so the number would flatter the platform.
- **Running the load test in CI:** shared runners make a p99 meaningless.

## Risks

- **The measurement is one laptop:** Docker, the JVM's warm-up and other processes move the p99. The run records the hardware and is repeated before the number is written down.
- **Seeding thousands of cards through the saga is slow:** the worker takes Payments one at a time; seeding may take minutes. Acceptable for a manual run; if it's too slow, the fix is the worker's batch size, recorded here.
- **`reconciliation` reads four modules:** its queries go through public APIs, so a slow one shows in the run's duration; the first ticket proves a run over a day's data before the other kinds arrive.
- **A corrected Break can move money between any two Accounts:** limited to the `reconciler` role, always balanced, always with a note, and never editing what's there.
