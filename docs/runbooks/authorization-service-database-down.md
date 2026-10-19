# authorization-service or its database is down

**Signals:** Charges are declined `91` (Issuer unavailable). `authorization-service` logs `Authorization <request ID> answered 91: …` (a `TimeoutException` when its database was too slow, an R2DBC error when it failed), then `Database circuit breaker: State transition from CLOSED to OPEN`, after which it refuses each Authorization at once with `91` and logs no more lines per call. `OutboxRelay` logs `Outbox relay pass failed` and the cards consumer retries its events ([`kafka-down.md`](kafka-down.md#the-services-database-is-down)). If the service itself is down, it logs nothing and the Network can't connect.

Every Authorization goes through a time limiter (500 ms) and a circuit breaker around the service's database (#93; [`learning/resilience4j.md`](../learning/resilience4j.md)), so when `authorization-postgres` is slow or down the service still answers the Network, `91`, well inside its 1-second deadline. A `91` records nothing: no row in `authorizations`, no card moved, no outbox row. The Network sends no Reversal after it, because it got an answer. Reversals don't go through the breaker: while the database is down they fail or time out, and the Network resends them (four attempts).

## Is it the service's `91`, or the Network's?

The simulator's Network also makes up a `91` when it gets no answer. Read the simulator's log for the request ID:

- **`The Network got no answer to Authorization <request ID> (DEADLINE_EXCEEDED): declined it 91 and is reversing it`**, or `(UNAVAILABLE)`: the Network's `91`. The service answered too slowly or couldn't be reached, and a Reversal follows. See [`network-cant-reach-platform.md`](network-cant-reach-platform.md). With the time limiter in place, a `DEADLINE_EXCEEDED` means the slowness is before the database: the token check (Keycloak, [`keycloak-unavailable.md`](keycloak-unavailable.md)), the call threads, or the machine.
- **No such line:** the service answered `91` itself. Its log names the request ID (`answered 91: …`) unless the breaker was open, in which case the `Database circuit breaker` lines bracket the period. No Reversal follows, and none is needed.

From #96, Grafana shows the same: `authorization_answers_total{response_code="91"}` counts the service's `91`s, and `resilience4j_circuitbreaker_state{name="authorization-database", state="open"}` is 1 while the breaker is open.

## Bring the database back

1. `docker compose ps`. If `authorization-postgres` isn't running, `docker compose up -d authorization-postgres`. If it starts and stops again, `docker compose logs authorization-postgres` says why (a full disk, port `5433` taken).
2. If it's running but slow, look for what holds it up, as in [`network-cant-reach-platform.md`](network-cant-reach-platform.md#the-service-answers-too-slowly), step 2.
3. Don't restart the service. Five seconds after the breaker opened, the next Authorization moves it to half-open and three are let through; once they succeed the log says `State transition from HALF_OPEN to CLOSED` and Charges are answered as usual. While the database is still down, the trial calls fail and it opens again for another 5 seconds.

If the service itself was down, start it: `./mvnw -pl authorization-service -am spring-boot:run`. Its breaker starts closed.

## What happens once it's back

- **The outbox:** nothing was lost. Events committed before the outage and not yet sent wait in `outbox` with `sent_at` null; the relay's next pass, 100 ms after the last failed one, sends them in order, with no restart. A `91` wrote no outbox row, so there's nothing to send for it. Check the backlog drains as in [`kafka-down.md`](kafka-down.md#the-services-outbox).
- **The card copy:** the cards consumer resumes where it stopped; cards issued meanwhile become chargeable once their events are stored.
- **Charges declined `91`:** Globex may charge the same card again with a new request ID, and it's answered as usual; the card wasn't moved.

## A `91` whose Authorization was recorded anyway

One case escapes "records nothing": the time limit passed while PostgreSQL was already confirming the Authorization's commit, which can't be taken back (M5 design, Risks). The Network got `91`, but the service recorded its answer, and a resend of the request ID gets that answer. If it was `00`, the card stays `AUTHORIZED` and any new Charge on it answers `57`, and since the Network sends no Reversal after a `91`, nothing frees it.

Once the database is back, look up the request IDs the service logged as `answered 91: java.util.concurrent.TimeoutException` (a call the open breaker refused never reached the database), in the service's database (`docker compose exec authorization-postgres psql -U authorization authorization`):

```sql
select request_id, status, response_code, card_token, created_at
  from authorizations
 where request_id in ('<request ID>', '<request ID>');
```

- **No row:** the usual case; nothing to undo.
- **`DECLINED`:** nothing to undo; the card didn't move.
- **`APPROVED`:** send the Reversal the Network didn't, as in [A Reversal that never arrived](network-cant-reach-platform.md#a-reversal-that-never-arrived). The card's copy goes back to `ACTIVE`, and the platform's follows once `AuthorizationReversed` arrives.
