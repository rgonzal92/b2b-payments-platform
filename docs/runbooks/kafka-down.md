# Kafka is down

**Signals:** no new messages on the `cards` or `authorizations` topic; rows in `modulith.event_publication` for `CardIssued`, `CardCancelled` or `CardExpired` keep `completion_date` null, or the service's `outbox` rows keep `sent_at` null; the platform logs the externalizer's send failing (`Node is not available`, `TimeoutException`); `docker compose ps` doesn't show `kafka` as running.

The platform keeps working: issuing, cancelling and expiring cards commit as usual, and the in-process `CardExpired` listener still ends Payments and releases Holds. Only the externalization lags, because the event publication registry is the outbox (ADR-0008). `authorization-service` keeps its copy of the cards as it was and keeps running; its consumer resumes from its group's committed offset once Kafka is back, so the copy catches up without losing an event.

## Local development

1. `docker compose ps`. If `kafka` isn't running, `docker compose up -d` starts whatever is missing. Spring Boot only starts the stack when none of it is running, so a half-started stack needs this by hand.
2. If it starts and stops again, read `docker compose logs kafka`. Port `9092` taken by another process is the usual cause: stop it, or change the host port in `compose.yaml` and `spring.kafka.bootstrap-servers` together.
3. Once Kafka is back, restart the platform. Incomplete publications are not retried while the app runs; `spring.modulith.events.republish-outstanding-events-on-restart=true` sends them at the next start. Before and after:
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

`authorization-service` can't store a card event while its PostgreSQL (`authorization-postgres`) is down. It doesn't skip the event: Spring Kafka retries it with a pause growing to 30 seconds, without limit, and logs each failed try, so that partition waits while the others carry on. Bring the database back (`docker compose up -d authorization-postgres`) and the consumer resumes where it stopped; `kafka-consumer-groups.sh --describe --group authorization-service` shows the lag falling to 0.

## The service's outbox

`authorization-service` keeps answering while Kafka is down: each approved Authorization, Reversal and new card writes its event to the service's `outbox` table and commits. Only the relay stops. Each pass's first send fails within about 5 seconds (the producer's `max.block.ms` and `delivery.timeout.ms`) and logs `Outbox row … wasn't sent`. The pass then ends and frees its database connection, and the next pass starts 100 ms later from the oldest unsent row. Once Kafka is back, the next pass sends the backlog in order, with no restart. If the service was stopped or crashed in the middle of a pass, the rows that pass had sent are sent again. That is harmless: the platform keys each event on its natural ID. A relay that logs `Outbox relay pass failed` can't reach the service's database instead: see the section above.

The platform's Clearing lag check can't see these rows, so drain the outbox before importing a Clearing File. Count what waits, in the service's database (`docker compose exec authorization-postgres psql -U authorization`):

```sql
select event_type, count(*), min(created_at)
  from outbox
 where sent_at is null
 group by event_type;
```

If the count doesn't fall while Kafka is up, the oldest unsent row is failing to send: its id is in the log line. The rows behind it wait for it on purpose, so a card's events are never sent out of order. Never delete a row to unblock the relay: the platform would then never hear of that Authorization.

A row that can never be sent blocks every row behind it for good. Today's events are a few hundred bytes, but a message over the producer's `max.request.size` (1 MB) would be one. You can tell it from a broker outage: Kafka is up and other producers' messages arrive, yet every pass logs `wasn't sent` for the same row id, with a non-retriable error such as `RecordTooLargeException`. Fix the cause, for example by raising the producer's and the topic's size limits, and restart the service. Don't skip or delete the row.
