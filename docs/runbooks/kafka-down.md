# Kafka is down

**Signals:** no new messages on the `cards` or `authorizations` topic; rows in `modulith.event_publication` for `CardIssued`, `CardCancelled`, `CardExpired` or `CardCleared` keep `completion_date` null, or the service's `outbox` rows keep `sent_at` null; the platform logs the externalizer's send failing (`Node is not available`, `TimeoutException`), `Couldn't read the Authorization Copy's lag from Kafka` and `Clearing File … waits`; `POST /v1/clearing-files` answers `503`; `docker compose ps` doesn't show `kafka` as running.

## What keeps working

- **Authorizations for cards on file.** `authorization-service` answers the Network from its own copy of the cards, which it keeps as it was, so a card already on file is charged and reversed as usual. Each answer writes its event to the service's outbox and commits; only the relay stops ([The service's outbox](#the-services-outbox)).
- **The platform's own work.** Issuing, cancelling, expiring and clearing cards commit as usual, and the in-process `CardExpired` listener still ends Payments and releases Holds. Only the externalization stops, because the event publication registry is the outbox (ADR-0008): a send that fails stays an incomplete publication.

## What waits

- **Payments for their card to be on file.** New Payments wait at `CARD_ISSUED`, their Holds placed, until the service's `CardOnFile` reaches the platform (group `platform-payables`), so no Supplier gets a card the service would decline as unknown ([`payment-stuck.md`](payment-stuck.md#a-payment-stays-card_issued-its-card-isnt-on-file)). There is no time limit short of the card's 30-day expiry.
- **Clearing Files.** Before each import the platform compares the offsets the group `platform-authorization` committed on the `authorizations` topic with the topic's end offsets (#94). While the platform's Authorization Copy lags, or Kafka doesn't answer within 5 seconds, the file stays in the drop folder untouched and the platform logs `Clearing File … waits`; the drop-folder check tries it again every 30 seconds. An import through `POST /v1/clearing-files` answers `503` and leaves the file in the folder the same way. A file already in `processing/` (an import that stopped) waits too.
- **The platform's Authorization Copy.** Approved and reversed Authorizations wait in the service's outbox (or, once sent, on the topic), and the cards they name stay `ACTIVE` on the platform instead of moving to `AUTHORIZED`. Meanwhile the 7-day expiry doesn't see those cards as `AUTHORIZED`, so it doesn't expire them on time.
- **The service's copy of the cards.** Cards issued, cancelled, expired or cleared on the platform don't reach it yet. A card the platform cleared stays `AUTHORIZED` there: a Reversal of its Charge moves it back to `ACTIVE`, and a second Charge on it could get `00` until its `CardCleared` arrives (M5 design, Risks).

## What drains by itself

Once Kafka is back, nothing needs a restart:

- The platform resends every publication still incomplete after a minute, once a minute (`EventResubmissionSchedule`), so its card events reach the `cards` topic within a minute or two. A resent event may arrive twice, or after a later event for the same card; every consumer is idempotent and order-tolerant.
- The service's relay sends its outbox backlog in order on its next pass.
- Each consumer group (`authorization-service`, `platform-payables`, `platform-authorization`) resumes from its committed offset, so neither copy loses an event. Waiting Payments move on to the Remittance Advice.
- The Authorization Copy catches up, and each card's `authorized_at` is the time of the service's approving transaction (the record's timestamp is the outbox row's `created_at`), so a late send doesn't shift its 7 days.
- The next drop-folder check after the lag reaches 0 imports the waiting Clearing Files, in name order.

## Before trusting zero lag

The import's lag check sees only what reached the topic. An approved Authorization still in the service's outbox is invisible to it, so a Clearing File imported then finds no Authorization for that Charge and raises a `NO_AUTHORIZATION` Break. After an outage, check the service's outbox has no unsent rows ([The service's outbox](#the-services-outbox)) before you import a file by hand. Once Kafka is back the relay drains the outbox within seconds, well inside the drop-folder check's 30 seconds; but if the outbox count doesn't fall while the lag reads 0, the scheduled check will import files anyway, so set `clearing.drop-folder-check.enabled=false` and restart the platform until the relay is fixed. A file imported while rows were unsent leaves `NO_AUTHORIZATION` Breaks for a Reconciler to see (M5 design, Risks). To read the lag yourself:

```sh
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group platform-authorization
```

Every partition's `LAG` should be 0.

## Local development

1. `docker compose ps`. If `kafka` isn't running, `docker compose up -d` starts whatever is missing. Spring Boot only starts the stack when none of it is running, so a half-started stack needs this by hand.
2. If it starts and stops again, read `docker compose logs kafka`. Port `9092` taken by another process is the usual cause: stop it, or change the host port in `compose.yaml` and `spring.kafka.bootstrap-servers` together.
3. Once Kafka is back, wait a minute or two: the platform resends incomplete publications every minute, and `spring.modulith.events.republish-outstanding-events-on-restart=true` also sends them at the next start. Watch the count fall:
   ```sql
   select event_type, count(*)
     from modulith.event_publication
    where completion_date is null
    group by event_type;
   ```
   A restart also recreates the `cards` topic with its 3 partitions if Kafka was down when the platform started. If messages were produced before that and auto-created it with 1 partition, check with `kafka-topics.sh --describe`. Recreating it deletes its messages: first check that `authorization-service` has consumed them all (`kafka-consumer-groups.sh --describe --group authorization-service` shows a lag of 0), then recreate it, and reset the group's offsets to the start (`--reset-offsets --to-earliest --execute`) with the service stopped.
4. Read the topic back:
   ```sh
   docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
     --bootstrap-server localhost:9092 --topic cards --from-beginning \
     --property print.key=true --property print.headers=true
   ```
   The key is the Card Token and the `event-type` header names the event.

## Repeated sends

Kafka delivers at least once, and the registry may resend an event whose completion wasn't recorded. A consumer keys its write on the Card Token and event type, so a duplicate changes nothing; `authorization-service` does so for every card event. The resends after a restart can also reach the topic out of the order the events were published; `authorization-service` applies a card's events in any order to the same result.

## The service's database is down

`authorization-service` can't store a card event while its PostgreSQL (`authorization-postgres`) is down. It doesn't skip the event: Spring Kafka retries it with a pause growing to 30 seconds, without limit, and logs each failed try, so that partition waits while the others carry on. Bring the database back (`docker compose up -d authorization-postgres`) and the consumer resumes where it stopped; `kafka-consumer-groups.sh --describe --group authorization-service` shows the lag falling to 0. Meanwhile the service answers Authorizations `91`: see [`authorization-service-database-down.md`](authorization-service-database-down.md).

## The service's outbox

`authorization-service` keeps answering while Kafka is down: each approved Authorization, Reversal and new card writes its event to the service's `outbox` table and commits. Only the relay stops. Each pass's first send fails within about 5 seconds (the producer's `max.block.ms` and `delivery.timeout.ms`) and logs `Outbox row … wasn't sent`. The pass then ends and frees its database connection, and the next pass starts 100 ms later from the oldest unsent row. Once Kafka is back, the next pass sends the backlog in order, with no restart. If the service was stopped or crashed in the middle of a pass, the rows that pass had sent are sent again. That is harmless: the platform keys each event on its natural ID. A relay that logs `Outbox relay pass failed` can't reach the service's database instead: see the section above.

The Clearing import's wait on consumer lag (#94) can't see these rows, so drain the outbox before importing a Clearing File by hand. Count what waits, in the service's database (`docker compose exec authorization-postgres psql -U authorization`):

```sql
select event_type, count(*), min(created_at)
  from outbox
 where sent_at is null
 group by event_type;
```

If the count doesn't fall while Kafka is up, the oldest unsent row is failing to send: its id is in the log line. The rows behind it wait for it on purpose, so a card's events are never sent out of order. Never delete a row to unblock the relay: the platform would then never hear of that Authorization.

A row that can never be sent blocks every row behind it for good. Today's events are a few hundred bytes, but a message over the producer's `max.request.size` (1 MB) would be one. You can tell it from a broker outage: Kafka is up and other producers' messages arrive, yet every pass logs `wasn't sent` for the same row id, with a non-retriable error such as `RecordTooLargeException`. Fix the cause, for example by raising the producer's and the topic's size limits, and restart the service. Don't skip or delete the row.
