# M3: Clearing and Settlement

**Status:** approved 2026-10-16. Decisions from the M3 grilling (Round 1–3, Q1–Q23, 2026-10-16).

## Problem

M2 ends with an approved Authorization and a Hold that never turns into money: nothing posts spend, nothing pays the Network, and the Buyer's credit stays reserved until the card expires. M3 builds the next part of the core path (README, step 6): the Network's daily Clearing File arrives, the platform posts every Clearing to a double-entry journal and pays the Network the file's net total. It's the first batch workload and the first time money is booked rather than reserved, so the books have to balance and a file has to be safe to import twice, partly, or out of order.

## Done when

1. After the demo charges a card, `POST /demo/clearing-file` produces a Clearing File the platform imports: the card is `CLEARED`, the Buyer Receivable carries the cleared amount, Available Credit counts it instead of the Hold, and the file's Settlement is posted.
2. Every Clearing is posted and its outcome recorded as the table below says, including an unknown card posted to Clearing Suspense; every Journal Entry balances.
3. The same file sent twice (same file ID or same content) is refused and a repeated record is skipped: nothing is posted twice. A file whose record count or amount total doesn't match its trailer is refused whole.
4. An import stopped mid-file restarts from its last committed chunk and posts every Clearing exactly once.
5. A Settlement whose imported total differs from the trailer's net settlement amount is posted at the Network's figure and recorded as mismatched.
6. Issuer staff record a Buyer's Repayment, which raises Available Credit; a Repayment above what the Buyer owes is refused.
7. An `AUTHORIZED` card with no Clearing 7 days after its Authorization expires, its Hold is released and its Payment ends `EXPIRED`.
8. The end-to-end test runs from Invoice through the approved Charge to an imported Clearing File and a posted Settlement.
9. `docs/architecture.md`, explainers for Spring Batch and double-entry bookkeeping, and runbooks for a Clearing File arriving twice, a refused file, a missing file, an import that stopped, and a mismatched Settlement are written.

## Approach

### Modules in M3

| Module | Builds |
|---|---|
| `clearing` | New. Clearing Files and their Clearings, the Spring Batch import job, the drop-folder poller, Settlement, and the Issuer-staff endpoints for files. |
| `ledger` | The journal: Accounts and Journal Entries (ADR-0015); posting a Clearing, a Settlement and a Repayment; Holds marked `CLEARED`; Available Credit read from the Buyer Receivable; the Repayment endpoint. |
| `cards` | Status `CLEARED`; the expiry job also expires `AUTHORIZED` cards 7 days after their Authorization; `cancel` only cancels an `ACTIVE` card. |
| `authorization` | A lookup of a card's Authorization by authorization code, for matching. |
| `vault` | Unchanged: `cardFor(pan)` finds the card. `clearing` joins the modules that may call it (ADR-0004, amended). |
| `suppliers` | A Remittance Advice link answers `410` for any card that isn't `ACTIVE` or `AUTHORIZED`. |
| `payables` | Unchanged: the 7-day rule reuses the `CardExpired` path from #34. (Changed during #54: only wording changes; `PaymentSaga.expire`'s log line says "its card expired", no longer "expired uncharged", and its javadoc and `Payment.cardExpired`'s say the same.) |

### The Clearing File (Q1, Q8)

- **Fixed-width text, modelled loosely on Visa Base II:** a header (file ID, the Network's sequence number, Business Date), one detail record per Clearing, and a trailer (record count, amount total, net settlement amount). The trailer's control totals are how the platform knows the file arrived whole.
- **A detail record carries** the Network record ID, the PAN (DE 2), amount and currency (DE 4, DE 49), retrieval reference number (DE 37), authorization code (DE 38), Acceptor ID (DE 42) and the Charge date.
- **The layout is a contract, defined once in `contracts`** (a layout document plus a sample file both the simulator's writer and the platform's reader are tested against), like the OpenAPI document and the `.proto`. (Changed during #49: `contracts/src/main/resources/clearing-file/`, 120 characters a record, record types `90`, `05` and `92` after Base II's transaction codes; the trailer also carries the totals' currency, as every amount here is paired with one.)

### Arrival and duplicates (Q2, Q4, Q11, Q19, Q20)

- **A drop folder** stands in for an SFTP landing zone: the simulator's Network writes files there and the platform checks it every 30 seconds. Issuer staff can also import a named file at once. The folder sits in a gitignored directory. (Changed during #49: the Network writes a file under a name starting with `.` and renames it once it's whole, and the platform ignores such names, so it never reads half a file. Before an import starts, the file moves into the folder's `processing/` folder in one atomic rename, so two importers never take the same file; it waits there while it's imported, and stays there when its import stops partway, until importing it again restarts it. Issuer staff import with `POST /v1/clearing-files` and the file's name: `201` with the file's summary, `404` for an unknown name, `409` when it was already imported or is importing, `422` when it doesn't follow the layout, `500` when its import stopped partway.)
- **Duplicates are harmless at two levels:** a file whose file ID or SHA-256 was already imported is refused and moved aside; a record whose Network record ID was already imported is skipped (Q20 rule 1).
- **After a successful import the file is deleted;** the platform keeps its hash, header and trailer figures and its counts per outcome. A refused or failed file waits in `failed/` until a person resolves it, then is deleted. The PAN never outlives the import outside the vault.
- **Each file is settled on its own,** so a second file for the same Business Date is accepted. Files import in whatever order they arrive; a gap in the Network's sequence numbers is shown on the clearing-files list, and a runbook says what to do about a missing file.

### The import job: Spring Batch (Q12, Q15)

- `spring-boot-starter-batch-jdbc` (Spring Batch 6, managed by Boot 4.1.1) inside the platform, its job repository tables in their own `batch` schema. One job run per file, keyed by its file ID, so Spring Batch also refuses to run a file twice.
- **Step 1, validate:** the file ID and hash are new, the header and trailer exist, and the record count and amount total match the trailer. Anything wrong refuses the whole file and posts nothing.
- **Step 2, import:** chunks of 100 records, each chunk one transaction. For each Clearing the step finds the card and its Authorization, decides the outcome, and calls `ledger` (post the Journal Entry, mark the Hold `CLEARED`), `cards` (`CLEARED`) and `clearing` (record the Clearing) through their public APIs, synchronously, inside the chunk's transaction. A stopped run restarts from its last committed chunk; the Network record ID's unique constraint keeps a replayed record from posting twice.
- **Step 3, settle:** below.
- (Changed during #49: the job's key, the file ID, comes from the header, which the platform reads before starting the job; `validate` also parses every detail record, so `import` never meets a malformed line, and a step's errors name a line and field, never a value, because Spring Batch logs them and keeps them in its job repository. A last step, `complete`, marks the file `IMPORTED` in the transaction that records the job's final step, so a file is never `IMPORTED` before its last chunk commits; `settle` goes before it. The job repository keeps each step's saved state as JSON, so a person and the "no PAN" check can read it.)

### Matching and outcomes (Q6, Q7, Q9, Q21, Q22)

The PAN finds the card through the vault; the authorization code finds that card's Authorization. The Issuer pays what clears (Q20), so every Clearing is posted except a repeated record:

| Case | Posted | Outcome |
|---|---|---|
| An approved Authorization, at its amount or less | yes; the Hold's unused rest is freed with it | matched (both amounts kept, so M4 sees an undercharge) |
| Above the authorized amount | yes, the full cleared amount | above authorized amount |
| No Authorization found for the card | yes, to the card's Buyer | no Authorization |
| A PAN the vault doesn't know | yes, to Clearing Suspense | unknown card |
| The Authorization was `REVERSED` | yes | against a reversed Authorization |
| The Hold was already released (7-day rule or expiry) | yes | after Hold expired |
| The Authorization already has a Clearing (another record ID) | yes | Authorization already cleared |
| The same Network record ID again | no | skipped |

- A Clearing always moves its card to `CLEARED`, whatever its status, marks the card's Hold `CLEARED` if it is still open, and leaves the Payment alone: the Issuer's part ended when the Supplier received the card.
- M3 records the outcome on each Clearing; M4's Reconciliation turns every outcome other than "matched" into a Break that a person resolves.
- (Changed during #49: #49 builds the matched row only, which also needs the card still `AUTHORIZED`: moving it to `CLEARED` is the conditional update that decides between two Clearings for one card. Every other Clearing is recorded unmatched and not posted, and a repeated Network record ID fails its chunk instead of being skipped, until #51 brings the table.)

### The journal (ADR-0015, Q5)

Four kinds of Account: a Buyer Receivable per Buyer, Network Settlement Payable, Issuer Cash and Clearing Suspense. A Clearing debits the Buyer Receivable (or Clearing Suspense) and credits the Network Settlement Payable; a Settlement debits the Network Settlement Payable and credits Issuer Cash; a Repayment debits Issuer Cash and credits the Buyer Receivable. Journal Entries balance, are never changed or deleted, and carry an idempotency key. Available Credit becomes the Credit Line minus the Buyer Receivable balance minus open Holds; an above-authorized Clearing can push it below zero, which already happens when a Credit Line is lowered. (Changed during #49: an Account's balance is the sum of its lines, with no running balance column, so posting takes no row locks and import chunks never queue on the one Network Settlement Payable. An idempotency key is unique per kind of entry, so a Clearing's Network record ID and a Settlement's file ID can't collide. Accounts are per currency, created by their first posting. A database trigger refuses any change to a posted entry.)

### Settlement (Q13)

The settle step totals the file's posted Clearings (Clearing Suspense included) and compares the total with the trailer's net settlement amount. It records a Settlement and posts the Network's figure either way, because the Issuer pays what the Network says; when the two differ the Settlement is recorded as mismatched for M4, and the Network Settlement Payable keeps a balance that shows the gap. No real money moves; a JMS settlement instruction is a bonus milestone.

### Repayment (Q14, Q23)

`POST /v1/buyers/{id}/repayments`, Issuer staff only, with an `Idempotency-Key` (it moves money). It posts the Repayment and raises Available Credit. An amount above the Buyer Receivable balance, or of zero or less, answers `422`. Statements and billing stay a bonus milestone. (Changed during #53: an amount in another currency than the Credit Line's is `422` too, and the request takes any whole number and any three-letter code so that zero, a negative amount and another currency reach these `422` rules rather than the schema's `400`; a Buyer without a Credit Line answers `404`, like an unknown Buyer, as the credit endpoint does. The answer is `201` with the Repayment: its Journal Entry's ID, the amount and when it was posted. The `Idempotency-Key` is the Journal Entry's idempotency key (kind `REPAYMENT`), not a table of keys like `payables`', whose table that module owns: a resend with the same Buyer and amount gets the first answer, rebuilt from the entry, and posts nothing; the same key with another Buyer or amount is `422`, so keys are one space for all Issuer staff. Two Repayments for one Buyer take turns on its credit line row lock, the one placing a Hold takes, so they can't together repay more than it owes.)

### The 7-day rule (Q10)

`cards` records when a card became `AUTHORIZED`. The expiry job from #34 also expires an `AUTHORIZED` card 7 days after that, through the same `CardExpired` event, so its Hold is released and its Payment ends `EXPIRED`. A Clearing that arrives later is still posted, as "after Hold expired".

- Decided during #54:
  - `cards.card.authorized_at` (`cards` migration V3) is set by `Cards.authorize`, to the time the platform approved the Charge, and cleared by `Cards.reverse`, so a reversed card waits for its own expiry. A card already `AUTHORIZED` when V3 runs gets the migration's time: its 7 days can't start before its Charge.
  - The 7 days are `SingleUseCards.CLEARING_WINDOW`, beside the card's 30-day `VALIDITY`, and the job expires an `AUTHORIZED` card whose `authorized_at` is 7 days or more before its run. Each of its transactions still expires up to 100 cards: `ACTIVE` cards past their expiry first, then `AUTHORIZED` cards past their 7 days in what is left, found through a partial index on `authorized_at` covering `AUTHORIZED` cards, as V2's does for `ACTIVE` ones.
  - A card authorized just before its 30-day expiry keeps its Hold up to 7 days past it, beyond ADR-0011's "up to 30 days". Before #54 such a card kept it until a Clearing came, possibly forever, so the rule narrows the gap rather than opening it; ADR-0011 is left for the owner to amend.

### What people read (Q16)

Issuer staff get `GET /v1/clearing-files` (newest first, cursor paging, with sequence gaps shown) and `GET /v1/clearing-files/{id}` (status, Business Date, counts per outcome, the Settlement and whether it matched). The credit endpoint adds the Buyer Receivable balance next to open Holds and Available Credit. Browsing the journal waits for the M9 UI. (Changed during #53: the credit endpoint didn't show open Holds yet, so it now shows both, `openHolds` and `buyerReceivable`.)

### Simulator and end-to-end test (Q3, Q17)

- Globex submits each approved, unreversed Charge for Clearing through its Acquirer; the Network writes the Issuer's file for a Business Date with every Clearing not yet sent, just after midnight UTC and on demand.
- `POST /demo/clearing-file` writes the file and asks the platform, signed in as Issuer staff, to import it at once; it answers with the import summary. Switches inject each edge case on purpose: a duplicate record, an amount above the authorized one, a Clearing with no Authorization, an unknown PAN, a wrong trailer total, and the same file sent twice.
- The end-to-end test continues from the approved Charge to the imported file: the card `CLEARED`, the Buyer Receivable posted, the Settlement posted.

### M2 follow-ups (Q18)

In the ticket that adds `CLEARED`: `Cards.cancel` leaves a card that isn't `ACTIVE` alone, and a Remittance Advice link answers `410` for a card that isn't `ACTIVE` or `AUTHORIZED`. (Changed during #48: in a ticket of its own, ahead of the one that adds `CLEARED` (#49). The link checks an allow-list of `ACTIVE` and `AUTHORIZED`, so `CLEARED` is refused without another change.)

## Deferred

Breaks and Reconciliation (M4); SFTP delivery; the JMS settlement instruction (bonus); statements, billing, refunds and credit balances; disputes and chargebacks; an encrypted archive of Clearing Files; multiple currencies.

## Rejected options

- **CSV or JSON lines for the file:** easier to read, but no control totals and nothing like a real bank file.
- **SFTP or an upload endpoint now:** SFTP adds a server and a client library before the file logic exists; networks don't upload files to issuers.
- **Skipping bad records:** a file is the Network's statement of what it will settle; a partial import would settle a total nobody agreed.
- **An event per Clearing:** the file would count as imported before the books caught up, and its totals couldn't be checked in one place.
- **A "posted spend" column instead of a journal:** see ADR-0015.
- **One Settlement per Business Date:** nothing tells the platform a day's last file has arrived.
- **Allowing overpayment:** a credit balance needs refunds, which are out of scope.
- **Creating Breaks in M3:** keeps M3 to file processing and money and M4 to matching and the people workflow.

## Risks

- **Spring Batch 6 is new here,** and its API moved since Spring Batch 5 (job launching and the job repository), so most blog posts are out of date: the first ticket proves a restartable two-chunk import before the matching rules arrive.
- **A chunk spans four modules' writes in one transaction:** one failing record rolls back its chunk's other 99, so a record that always fails stops the file; the job reports it and the runbook covers it.
- **The file holds PANs on disk until it's imported:** it's deleted after import and the folder is gitignored, but a failed file stays readable in `failed/` until someone resolves it.
- **Several platform instances (M6) polling one folder** could pick the same file; the job run keyed by file ID and an atomic move into a processing folder keep it to one import.
