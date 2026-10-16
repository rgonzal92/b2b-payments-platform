# Sagas, and a state row driven by a `SKIP LOCKED` worker

## The problem it solves here

Paying an Invoice takes three steps in three modules: place a Hold in `ledger`, issue a Single-Use Card in `cards` and `vault`, send the Remittance Advice from `suppliers`. One database transaction can't cover them all: delivering the Remittance Advice is an HTTP call to the Supplier, and once modules become services (M5, M7) each has its own database. Yet each step can fail after the earlier ones succeeded. If the card can't be issued, the Buyer's credit stays reserved for a Payment that will never happen.

A saga is a sequence of local transactions, one per step. Each step that has an effect gets a compensating step that undoes it, run when a later step fails for good.

## Mental model

- **Steps and compensations, not one rollback.** Each step commits on its own, so other Payments see its effect at once (the Hold lowers Available Credit). Undoing it later is a new business action, such as releasing the Hold, not a database rollback.
- **Orchestration, not choreography (ADR-0013).** One coordinator, `payables`, tells each module what to do next and records how far each Payment got. In choreography each module would react to the previous module's event, and no single place would know a Payment's state or its undo path.
- **A business refusal is final; a technical error may pass.** Not enough Available Credit gives the same answer however often it's asked, so the Payment fails at once. A lost connection might work a minute later, so the step is retried with growing pauses (backoff) and given up after a limit.
- **Every step is idempotent, keyed by the Payment.** A retried or interrupted step may run twice. The Hold's ID is the Payment's ID, so the second run finds the first Hold instead of placing another.

## How it's built here (ADR-0014)

Each Payment is a row in `payables.payment` holding its saga state: `state`, `failure_reason`, `attempts` (failed attempts at the current step) and `next_attempt_at` (when the step is due; null when there's nothing left to run). One query shows where every Payment stands.

```text
InvoiceApproved ──@ApplicationModuleListener──▶ insert payment: STARTED, due now
                                                (on conflict (invoice_id) do nothing)

worker, every second (PaymentWorkerSchedule → PaymentSaga.runDueSteps):
  up to 10 times:
    begin
      select … from payables.payment
       where next_attempt_at <= now order by next_attempt_at
       limit 1 for update skip locked                 ── claim one due Payment
      the step for its state:
        STARTED      → ledger.Holds.place(payment id, Buyer, amount)
        HOLD_PLACED  → cards.Cards.issueSingleUse(payment id, …)
        CARD_ISSUED  → suppliers.RemittanceAdvices.deliver(advice, …)   ── POST to the Supplier
      update payables.payment set state = …           ── its new state
    commit                                            ── step and state, together

    technical error anywhere in it → the whole transaction rolls back, then:
    begin
      update payables.payment set attempts = attempts + 1, next_attempt_at = now + backoff
       where id = … and state = <as claimed> and attempts = <as claimed>
      fifth attempt at HOLD_PLACED → ledger.Holds.release(payment id)   ── compensation
      fifth attempt at CARD_ISSUED → cards.Cards.cancel(card), ledger.Holds.release(payment id)
    commit
```

- **`for update skip locked` turns the table into a queue.** `for update` locks the selected row until the transaction ends. `skip locked` makes another transaction's query pass over rows that are already locked instead of waiting for them. Two workers, whether two threads or two platform instances (M6), never run the same Payment's step at once and never wait on each other.
- **The step and the new state commit in one transaction.** If the platform dies mid-step, PostgreSQL rolls back the open transaction when the connection drops: no Hold, the Payment still `STARTED` and due, its row lock gone. The next pass runs the step again.
- **One Payment per transaction.** A pass claims one Payment at a time, up to ten. Claiming ten in one transaction would let one Payment's failure roll back the other nine, and a transaction holding two Buyers' Credit Line locks could deadlock with a worker that took them in the opposite order.
- **A technical error rolls back the whole step, then a new transaction counts the attempt.** The rollback undoes the Hold. The second transaction saves the failed attempt only if the row is still as it was claimed: in the moment between the two, another worker may have claimed the Payment and moved it on, and then its record wins. At worst that worker retries once before the backoff, which is harmless because the step is idempotent.
- **Retries are columns, not framework behaviour.** After a technical error the step is due again 10, 20, 40 and 80 seconds later; the fifth failure fails the Payment for good. `Payment.stepFailed` holds that rule, and `PaymentTests` checks it without a database.
- **Compensation rides on the final failed attempt (from #18).** The second step issues the Single-Use Card (`cards.Cards`). When it fails for the fifth time, the transaction that records that attempt also releases the Hold (`ledger.Holds.release`), so the Payment turns `FAILED` and its credit comes back in one commit. If that transaction fails too, nothing is recorded and the next pass tries the step again; releasing a Hold twice is harmless, so a repeat does no damage. The third step (#19) delivers the Remittance Advice over HTTP; its fifth failure cancels the card first, then releases the Hold, undoing the steps in reverse order.
- **A step that calls another company runs inside the transaction too (from #19).** Delivering the Remittance Advice is a POST to the Supplier, made while the worker holds the Payment's row; short timeouts keep that wait bounded. The POST can't be rolled back, so the step is built to be safe to repeat: the advice's record commits on its own before the POST, every attempt sends the same link under the same `Idempotency-Key`, and the Supplier drops the repeats (see [`one-time-links.md`](one-time-links.md)).
- **Placing a Hold locks the Buyer's Credit Line row** (`select … for update`), then sums the open Holds and inserts the new one in the same transaction, so two Payments for one Buyer take turns on the last of its credit.
- **Tests drive the worker by hand.** `payables.payment-worker.enabled=false` leaves the schedule out, and tests call `runDueSteps(now)` with a later `now` to skip a backoff instead of sleeping.

## Gotchas we hit

- **A subquery inside the locking statement reads an old snapshot.** A first version read the Credit Line and the sum of its Holds in one `select … for update`. With two Payments racing, both placed a Hold. Under READ COMMITTED each statement sees the database as it was when the statement started. The second transaction's statement started, waited for the first to release the Credit Line row, then re-read that row, but its subquery over `hold` still used the snapshot from before the wait and missed the first Hold. Summing the Holds in the next statement fixes it: a new statement takes a new snapshot, which includes what committed during the wait.
- **A savepoint can't save you from a lost connection.** A first version ran the step inside a savepoint (`PROPAGATION_NESTED`), so a technical error rolled back only the step and the failed attempt was saved in the same transaction, row still locked. That worked for errors on a healthy connection. When the review asked about a real lost connection, a test that kills its own connection mid-step (`select pg_terminate_backend(pg_backend_pid())`) showed the flaw: rolling back to the savepoint needs the connection that just died, so the attempt was never counted and the error ended the worker's pass. Now any error rolls back the whole transaction and a new one, on a fresh connection, counts the attempt. That also sidesteps a Spring trap the savepoint had to work around: a `@Transactional` method that throws, such as `Holds.place`, marks the transaction it joined rollback-only, and committing that transaction then throws `UnexpectedRollbackException`.
- **A race test has to make the race happen.** Two threads started together often run one after the other, so a missing lock can pass unnoticed. `PaymentSagaTests` makes each worker wait inside `Holds.place` (a `CyclicBarrier` in a Mockito spy) until the other one arrives. Without the lock it failed with two Holds placed, and so did the subquery version above.
- **Simulating a crash.** The crash test throws an `Error` from inside the step, after its side effect (the Hold placed, the card stored, the advice POSTed). The worker catches only `RuntimeException`, so nothing more runs and the transaction rolls back, which is what happens when the JVM dies and PostgreSQL drops its connection. One parameterized test (#20) does it during the step after each state, `STARTED`, `HOLD_PLACED` and `CARD_ISSUED`, and counts what the stop left behind: nothing for the first two; for the third, only the advice's record and the advice the Supplier already has, both repeated harmlessly on restart.
- **Starting the saga must be idempotent too.** The event registry may deliver `InvoiceApproved` again (see `spring-modulith.md`), so the Payment's insert does nothing when its Invoice already has one.
