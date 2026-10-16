# A Payment is stuck

**Signals:** an approved Invoice shows no `payment` on `GET /v1/invoices/{invoiceId}` for more than a few seconds; a Payment stays `STARTED` or `HOLD_PLACED`; the platform logs `Payment … failed its step for technical reasons (n of 5 attempts)`; a Payment is `FAILED` with "Placing the Hold failed 5 times for technical reasons." or "Issuing the Single-Use Card failed 5 times for technical reasons."

How a Payment moves (details in [`docs/learning/sagas.md`](../learning/sagas.md)): the approval that completes an Invoice publishes `InvoiceApproved`; a listener inserts the Payment as `STARTED` a moment later; a worker in the platform claims due Payments every second, places the Hold (`HOLD_PLACED`), then issues the Single-Use Card (`CARD_ISSUED`). A technical error is retried after 10, 20, 40 and 80 seconds, and the fifth fails the Payment; failing at card issuance also releases the Hold. Not enough Available Credit fails it at once.

Every query below runs against the development database: `docker compose exec postgres psql -U payments payments`.

## The Invoice is approved but has no Payment

The listener didn't run, or failed. Its event is still in the registry, incomplete:

```sql
select id, publication_date, status, completion_attempts
  from modulith.event_publication
 where event_type like '%InvoiceApproved'
   and completion_date is null;
```

1. Look in the platform's logs for `Unexpected exception occurred invoking async method` naming `PaymentSaga.start`, and fix its cause (usually the database).
2. Restart the platform. It delivers incomplete publications again at startup (`spring.modulith.events.republish-outstanding-events-on-restart=true`). Starting a Payment twice is harmless: the second delivery finds the Invoice's Payment and does nothing.

## A Payment stays `STARTED` or `HOLD_PLACED`

`STARTED` waits to place the Hold; `HOLD_PLACED` waits to issue the card.

```sql
select id, invoice_id, state, attempts, next_attempt_at
  from payables.payment
 where state in ('STARTED', 'HOLD_PLACED')
 order by next_attempt_at;
```

- **`next_attempt_at` is in the future and `attempts` is above zero:** the step failed for technical reasons and waits for its retry. Find the cause in the logs by the Payment's ID (`Payment <id> failed its step for technical reasons`). It fails for good on the fifth attempt. Card issuance has no business refusal, so a `HOLD_PLACED` Payment's errors are always technical, usually the database.
- **`next_attempt_at` is in the past:** the worker isn't running, is stuck waiting for a lock, or can't claim the row.
  1. Check that `payables.payment-worker.enabled` isn't `false` in the platform's configuration or environment.
  2. Check whether the worker is waiting for a lock. Placing a Hold waits, without a time limit, for the Buyer's Credit Line row; while it waits, the worker runs nothing else on that platform instance, so every Payment stalls behind it:

     ```sql
     select pid, pg_blocking_pids(pid) as blocked_by, left(query, 80)
       from pg_stat_activity
      where cardinality(pg_blocking_pids(pid)) > 0;
     ```

     The pids in `blocked_by` hold the lock; end the one that shouldn't (see below).
  3. Check for a transaction that holds the Payment's row and never ends. The worker skips locked rows (`skip locked`), so it passes over that Payment for as long as the other transaction is open:

     ```sql
     select pid, state, xact_start, left(query, 80)
       from pg_stat_activity
      where state like 'idle in transaction%'
      order by xact_start;
     ```

     `select pg_terminate_backend(<pid>);` ends it and rolls it back. A step it was running leaves nothing behind, and the next pass runs it again.
  4. The platform logs an error from a scheduled task every second, naming no Payment: the worker can't read the Payment due longest (a state or currency the code doesn't know, say), and its pass stops there each time. This finds that row; fix the row, or the code:

     ```sql
     select id, state, currency, next_attempt_at
       from payables.payment
      where next_attempt_at <= now()
      order by next_attempt_at
      limit 1;
     ```

## A Payment `FAILED`

The Invoice stays `APPROVED` and unpaid, and the Payment holds no credit.

- **"Not enough Available Credit to place the Hold."** A business refusal, not a fault: the Buyer's Available Credit (`GET /v1/buyers/{buyerId}/credit`) was less than the Invoice amount, or the Buyer has no Credit Line.
- **"Placing the Hold failed 5 times for technical reasons."** The logs hold each attempt's error, under the Payment's ID.
- **"Issuing the Single-Use Card failed 5 times for technical reasons."** The logs hold each attempt's error, under the Payment's ID, and `Payment <id> failed for good; released its Hold`. The Hold was released in the same transaction that failed the Payment, and no card or PAN was stored: each attempt rolled back whatever it had written. To confirm the credit came back, `select * from ledger.hold where id = '<payment id>'` returns no row.

There is no way yet for Issuer staff to retry a failed Payment. In local development, once the cause is fixed (or the Credit Line raised), setting the Payment back to `STARTED` makes the worker run its steps again from the start. A rerun can't place a second Hold, because the Hold's ID is the Payment's, nor issue a second card, because the card is keyed by the Payment too; a Hold released when the card failed is placed afresh, after the credit check:

```sql
update payables.payment
   set state = 'STARTED', failure_reason = null, attempts = 0, next_attempt_at = now()
 where id = '<payment id>'
   and state = 'FAILED';
```
