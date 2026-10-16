# A Payment is stuck

**Signals:** an approved Invoice shows no `payment` on `GET /v1/invoices/{invoiceId}` for more than a few seconds; a Payment stays `STARTED`; the platform logs `Payment … failed its step for technical reasons (n of 5 attempts)`; a Payment is `FAILED` with "Placing the Hold failed 5 times for technical reasons."

How a Payment moves (details in [`docs/learning/sagas.md`](../learning/sagas.md)): the approval that completes an Invoice publishes `InvoiceApproved`; a listener inserts the Payment as `STARTED` a moment later; a worker in the platform claims due Payments every second and places the Hold. A technical error is retried after 10, 20, 40 and 80 seconds, and the fifth fails the Payment. Not enough Available Credit fails it at once.

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

## A Payment stays `STARTED`

```sql
select id, invoice_id, attempts, next_attempt_at
  from payables.payment
 where state = 'STARTED'
 order by next_attempt_at;
```

- **`next_attempt_at` is in the future and `attempts` is above zero:** the step failed for technical reasons and waits for its retry. Find the cause in the logs by the Payment's ID (`Payment <id> failed its step for technical reasons`). It fails for good on the fifth attempt.
- **`next_attempt_at` is in the past:** the worker isn't running, or can't claim the row.
  1. Check that `payables.payment-worker.enabled` isn't `false` in the platform's configuration or environment.
  2. Check for a transaction that holds the Payment's row and never ends. The worker skips locked rows (`skip locked`), so it passes over that Payment for as long as the other transaction is open:

     ```sql
     select pid, state, xact_start, left(query, 80)
       from pg_stat_activity
      where state like 'idle in transaction%'
      order by xact_start;
     ```

     `select pg_terminate_backend(<pid>);` ends it and rolls it back. A step it was running leaves nothing behind, and the next pass runs it again.

## A Payment `FAILED`

The Invoice stays `APPROVED` and unpaid, and the Payment holds no credit.

- **"Not enough Available Credit to place the Hold."** A business refusal, not a fault: the Buyer's Available Credit (`GET /v1/buyers/{buyerId}/credit`) was less than the Invoice amount, or the Buyer has no Credit Line.
- **"Placing the Hold failed 5 times for technical reasons."** The logs hold each attempt's error, under the Payment's ID.

There is no way yet for Issuer staff to retry a failed Payment. In local development, once the cause is fixed (or the Credit Line raised), setting the Payment back to `STARTED` makes the worker run the step again. A rerun can't place a second Hold, because the Hold's ID is the Payment's:

```sql
update payables.payment
   set state = 'STARTED', failure_reason = null, attempts = 0, next_attempt_at = now()
 where id = '<payment id>'
   and state = 'FAILED';
```
