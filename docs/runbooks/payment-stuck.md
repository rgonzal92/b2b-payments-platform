# A Payment is stuck

**Signals:** an approved Invoice shows no `payment` on `GET /v1/invoices/{invoiceId}` for more than a few seconds; the simulator's demo (`POST /demo/invoices`) answers with a `paymentState` other than `COMPLETED`, or none; a Payment stays `STARTED`, `HOLD_PLACED`, `CARD_ISSUED` or `CARD_ON_FILE`; the gauge `payments.waiting.card.on.file` stays above zero; Grafana's `payment-waiting-card-on-file` alert fires (a Payment has waited more than 5 minutes for its card to be on file) or its `platform-not-exporting` alert (the platform is down); the platform logs `Payment … failed its step for technical reasons (n of 5 attempts)`; a Payment is `FAILED` with "Placing the Hold failed 5 times for technical reasons.", "Issuing the Single-Use Card failed 5 times for technical reasons." or "Delivering the Remittance Advice failed 5 times for technical reasons."

How a Payment moves (details in [`docs/learning/sagas.md`](../learning/sagas.md)): the approval that completes an Invoice publishes `InvoiceApproved`; a listener inserts the Payment as `STARTED` a moment later; a worker in the platform claims due Payments every second, places the Hold (`HOLD_PLACED`), issues the Single-Use Card (`CARD_ISSUED`), waits for `authorization-service` to say it has the card on file (`CARD_ON_FILE`, read from the `authorizations` topic), then POSTs the Remittance Advice to the Supplier's remittance address (`COMPLETED` on a 2xx answer). A technical error is retried after 10, 20, 40 and 80 seconds, and the fifth fails the Payment; failing at card issuance also releases the Hold, and failing to deliver the Remittance Advice also cancels the card and releases the Hold. Not enough Available Credit fails it at once.

Every query below runs against the development database: `docker compose exec postgres psql -U payments payments`. Start with where every Payment stands; on a healthy platform no state has a Payment due more than a second or two ago, and none waits at `CARD_ISSUED` (whose `due_longest` is always empty) more than a second or two:

```sql
select state, count(*), min(next_attempt_at) as due_longest
  from payables.payment
 group by state;
```

## Restarting the platform is safe

Grafana's `platform-not-exporting` alert fires when the platform has sent no metrics for 5 minutes: it is down, or can't reach `otel-lgtm`. Start it again (README, step 2); the rest of this section is why that's safe.

Stopping the platform, even with Payments under way, strands none of them. A stop mid-step rolls back that step's transaction, and the next pass after the restart runs the step again, so a Payment completes with exactly one Hold and one card (`PaymentSagaTests` stops the platform during each step to prove it). The one thing a stop leaves behind is a Remittance Advice's record, which commits before the POST (ADR-0014): a Payment stopped at `CARD_ON_FILE` after the Supplier received the advice delivers it again on restart, with the same link and `Idempotency-Key`, and the Supplier drops the repeat. Restarting is also what delivers an `InvoiceApproved` whose listener failed (next section).

## The Invoice is approved but has no Payment

The listener didn't run, or failed. Its event is still in the registry, incomplete:

```sql
select id, publication_date, status, completion_attempts
  from modulith.event_publication
 where event_type like '%InvoiceApproved'
   and completion_date is null;
```

1. Look in the platform's logs for `Unexpected exception occurred invoking async method` naming `PaymentSaga.start`, and fix its cause (usually the database).
2. Wait a minute: the platform delivers every publication still incomplete after a minute again, once a minute (`EventResubmissionSchedule`), and at startup (`spring.modulith.events.republish-outstanding-events-on-restart=true`). Starting a Payment twice is harmless: the second delivery finds the Invoice's Payment and does nothing.

## A Payment stays `CARD_ISSUED`: its card isn't on file

Grafana's `payment-waiting-card-on-file` alert fires once a Payment has waited here more than 5 minutes. A `CARD_ISSUED` Payment has no step due: it waits, its Hold placed, until `authorization-service` has copied the card from the `cards` topic and said so on the `authorizations` topic. The wait has no time limit; the card's expiry after 30 days ends it (`EXPIRED`, its Hold released). Nothing is lost while it waits, so fix the link and the Payments move on by themselves, oldest first:

```sql
select id, invoice_id, card_issued_at
  from payables.payment
 where state = 'CARD_ISSUED'
 order by card_issued_at;
```

Then each one's card, in its own module's schema (ADR-0009):

```sql
select card_token, issued_at from cards.card where payment_id = '<payment id>';
```

The gauges `payments.waiting.card.on.file` and `payments.waiting.card.on.file.oldest` (seconds) show the same in Grafana, refreshed every 30 seconds.

1. **The service isn't running**, or is failing: <http://localhost:8082/actuator/health> should answer `UP`. Start it beside the platform (README, step 2).
2. **Kafka is down**, or the card never left the platform: see [`kafka-down.md`](kafka-down.md). An incomplete `CardIssued` in `modulith.event_publication` means the platform hasn't sent it yet; it sends it again every minute while it stays incomplete.
3. **The service hasn't stored the card:** in its database (`docker compose exec authorization-postgres psql -U authorization`), `select status from card_copy where card_token = '<token>';`. No row, with its consumer's lag at 0 (`kafka-consumer-groups.sh --describe --group authorization-service`), means it skipped the message (its log says why), or the card was cancelled, expired or cleared before its `CardIssued` arrived, which is never put on file. An unsent `CardOnFile` row in its `outbox` means its relay is stuck: [`kafka-down.md`](kafka-down.md#the-services-outbox).
4. **The platform isn't reading the topic:** `docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group platform-payables` shows each partition's lag. A lag that doesn't fall means the platform's listener is retrying a message it can't apply, usually because the platform's database is down: its log repeats the failure, and the partition resumes once the cause is fixed. No group, or no member, means the platform's listener isn't running (`spring.kafka.listener.auto-startup=false`?). A message the platform can't read (malformed JSON, no `cardToken`, no value, no `event-type` header) is skipped and logged by partition and offset, and a card it never issued is skipped too (`Skipped CardOnFile for card …`).
5. **A partition is never assigned:** if `kafka-topics.sh --describe --topic authorizations` shows fewer than 3 partitions, or the group shows partitions with no owner, restart the platform: its consumer was assigned the topic as it was when it subscribed. Both apps declare both topics with 3 partitions, so this happens only if something else created a topic first.

## A Payment stays `STARTED`, `HOLD_PLACED` or `CARD_ON_FILE`

`STARTED` waits to place the Hold; `HOLD_PLACED` waits to issue the card; `CARD_ON_FILE` waits for the Supplier to accept the Remittance Advice.

```sql
select id, invoice_id, state, attempts, next_attempt_at
  from payables.payment
 where state in ('STARTED', 'HOLD_PLACED', 'CARD_ON_FILE')
 order by next_attempt_at;
```

- **`next_attempt_at` is in the future and `attempts` is above zero:** the step failed for technical reasons and waits for its retry. Find the cause in the logs by the Payment's ID (`Payment <id> failed its step for technical reasons`). It fails for good on the fifth attempt. Card issuance has no business refusal, so a `HOLD_PLACED` Payment's errors are always technical, usually the database. A `CARD_ON_FILE` Payment's errors are usually the Supplier's: see the next section.
- **`next_attempt_at` is in the past:** the worker isn't running, is stuck waiting for a lock, or can't claim the row.
  1. Check that `payables.payment-worker.enabled` isn't `false` in the platform's configuration or environment. A running worker leaves one trace a second in Grafana: under **Explore**, **Tempo**, the TraceQL query `{name="task paymentWorkerSchedule.runDueSteps"}` lists them. None in the last minute means it isn't running; one that lasts seconds means a step is waiting (on a lock, or a Supplier).
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

## The Supplier's remittance address doesn't accept the Remittance Advice

A `CARD_ON_FILE` Payment retries while the Supplier's address doesn't answer 2xx within the timeouts (2 seconds to connect, 5 to answer). The log line under the Payment's ID says why: `The Supplier's remittance address answered 503.` (any answer but 2xx, a redirect included), a `ResourceAccessException` naming a timeout, a refused connection or an unknown host. Find the address the Payment delivers to, one module's schema at a time (ADR-0009):

```sql
select supplier_id from payables.payment where id = '<payment id>';
select name, remittance_address from suppliers.supplier where id = '<supplier id>';
```

- **The address is wrong or the Supplier is down.** The address comes from the first Invoice naming the Supplier's tax ID, and later Invoices don't change it. There is no way yet to correct it through the API; in local development, `update suppliers.supplier set remittance_address = … where id = …` takes effect at the next retry. Locally, the simulator serves Globex's address (`http://localhost:8081/globex/remittance-advice`); check it is running.
- **The Supplier may have the link anyway.** An attempt whose answer timed out may have reached the Supplier, and the link works from the first attempt on. Whether the Supplier has opened it:

  ```sql
  select created_at, link_expires_at, link_used_at
    from suppliers.remittance_advice
   where id = '<payment id>';
  ```

  Every retry sends the same link under the same `Idempotency-Key`, so a Supplier that already has it drops the repeat.
- **The worker waits for each Supplier.** Each attempt holds the worker for up to the timeouts, so many unreachable Suppliers at once slow every Payment behind them on that platform instance.

## A Payment `FAILED`

The Invoice stays `APPROVED` and unpaid, and the Payment holds no credit.

- **"Not enough Available Credit to place the Hold."** A business refusal, not a fault: the Buyer's Available Credit (`GET /v1/buyers/{buyerId}/credit`) was less than the Invoice amount, or the Buyer has no Credit Line.
- **"Placing the Hold failed 5 times for technical reasons."** The logs hold each attempt's error, under the Payment's ID.
- **"Issuing the Single-Use Card failed 5 times for technical reasons."** The logs hold each attempt's error, under the Payment's ID, and `Payment <id> failed for good; released its Hold`. The Hold was released in the same transaction that failed the Payment, and no card or PAN was stored: each attempt rolled back whatever it had written. To confirm the credit came back, `select status, released_at from ledger.hold where id = '<payment id>'` shows `RELEASED`; a released Hold keeps its row.
- **"Delivering the Remittance Advice failed 5 times for technical reasons."** The Supplier's address never accepted the advice (see the section above), and the logs hold `Payment <id> failed for good; cancelled its card if still ACTIVE and released its Hold`. The Hold shows `RELEASED`. The card (`select status from cards.card where payment_id = '<payment id>'`) is usually `CANCELLED`, so its link answers `410` even if the Supplier received it. Check `link_used_at` as above: a Supplier that opened the link holds the details of a card that no longer pays, and has to be told; there is no tool for that yet. A card that is `AUTHORIZED` instead was charged by the Supplier before the Payment failed, and only an `ACTIVE` card is cancelled: the approved Charge stands, though its Hold was released too.

There is no way yet for Issuer staff to retry a failed Payment. In local development, once the cause is fixed (or the Credit Line raised), setting the Payment back to `STARTED` makes the worker run its steps again from the start. A rerun can't place a second Hold, because the Hold's ID is the Payment's, nor issue a second card, because the card is keyed by the Payment too; a Hold released when the card failed is placed afresh, after the credit check, or the rerun fails for lack of credit.

Don't rerun a Payment that failed delivering the Remittance Advice: its card is cancelled for good, or already charged, and the rerun would find that same card and send the Supplier its link again, under an `Idempotency-Key` the Supplier may already have dropped. The update below leaves such Payments alone:

```sql
update payables.payment
   set state = 'STARTED', failure_reason = null, attempts = 0, next_attempt_at = now()
 where id = '<payment id>'
   and state = 'FAILED'
   and failure_reason not like 'Delivering the Remittance Advice%';
```

## A card stays `ACTIVE` past its expiry, or `AUTHORIZED` past its 7 days

The expiry job in `cards` expires an `ACTIVE` card at its expiry, and an `AUTHORIZED` card 7 days after its Authorization when no Clearing has come. When it is switched off (`cards.expiry-job.enabled=false`) or failing (an error from the scheduled task `cardExpirySchedule.expireDue` in the logs, usually the database), the card's Hold stays placed and the Buyer's Available Credit stays short by its amount. These list the cards waiting; once the job runs again it expires them all:

```sql
select card_token, payment_id, expires_at from cards.card where status = 'ACTIVE' and expires_at <= now();
select card_token, payment_id, authorized_at from cards.card
 where status = 'AUTHORIZED' and authorized_at <= now() - interval '7 days';
```

An `AUTHORIZED` card that was already authorized when the platform first ran with the 7-day rule (the `cards` migration V3) counts its 7 days from that migration, not from its Charge.

A card that is `EXPIRED` while its Payment isn't is waiting for its `CardExpired` listener: query the registry as above with `event_type like '%CardExpired'`, fix the cause in the logs (`PaymentSaga.expire`), and restart.
