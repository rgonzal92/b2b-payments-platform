# authorization-service or its database is down

**Signals:** Charges are declined `91` (Issuer unavailable). `authorization-service` logs `Authorization <request ID> answered 91 and is being reversed: …`, then `Database circuit breaker: State transition from CLOSED to OPEN`, after which it refuses each Authorization at once with `91` and logs no more lines per call. The reason after `reversed:` says which outage it is: `java.util.concurrent.TimeoutException` when the database is too slow (stuck, locked, or not answering), `CannotCreateTransactionException: Could not open R2DBC Connection for transaction` when it is down or refuses connections. `OutboxRelay` logs `Outbox relay pass failed` and the cards consumer retries its events ([`kafka-down.md`](kafka-down.md#the-services-database-is-down)). If the service itself is down, it logs nothing and the Network can't connect, and after 5 minutes Grafana's `authorization-service-not-exporting` alert fires.

Every Authorization goes through a time limiter and a circuit breaker around the service's database (#93; [`learning/resilience4j.md`](../learning/resilience4j.md); settings in [`architecture.md`](../architecture.md#the-network-link)). So when `authorization-postgres` is slow or down, the service still answers the Network with `91`, inside its deadline. A `91` leaves no approval standing. The Network sends no Reversal after a `91`, because it got an answer, so the service reverses the Authorization itself in the background, retrying while the database is down. Its Reversal reverses an approval whose commit went through after all, or records the request ID as reversed (`05`) if nothing committed. A `91` from the open breaker never reached the database and needs no Reversal.

Reversals the Network sends go through the same time limit, but not the breaker. While the database is down they fail quickly, and the Network sends each one again, up to four attempts. If all four fail, the simulator logs `The Network couldn't reverse Authorization <request ID> after 4 attempts`; see [A Reversal that never arrived](network-cant-reach-platform.md#a-reversal-that-never-arrived) once the database is back.

## Is it the service's `91`, or the Network's?

The simulator's Network also makes up a `91` when it gets no answer. Read the simulator's log for the request ID:

- **`The Network got no answer to Authorization <request ID> (DEADLINE_EXCEEDED): declined it 91 and is reversing it`**, or `(UNAVAILABLE)`: the Network's `91`. The service answered too slowly or couldn't be reached, and a Reversal follows. See [`network-cant-reach-platform.md`](network-cant-reach-platform.md). With the time limiter in place, a `DEADLINE_EXCEEDED` means the slowness is before the database: the token check (Keycloak, [`keycloak-unavailable.md`](keycloak-unavailable.md)), the call threads, or the machine.
- **No such line:** the service answered `91` itself. Its log names the request ID (`answered 91 and is being reversed`) unless the breaker was open, in which case the `Database circuit breaker` lines bracket the period. The service reverses it; the Network sends no Reversal.

From #96, Grafana shows the same: `authorization_answers_total{response_code="91"}` counts the service's `91`s, and `resilience4j_circuitbreaker_state{name="authorization-database", state="open"}` is 1 while the breaker is open.

## Bring the database back

1. `docker compose ps`. If `authorization-postgres` isn't running, `docker compose up -d authorization-postgres`. If it starts and stops again, `docker compose logs authorization-postgres` says why (a full disk, port `5433` taken).
2. If it's running but slow, look for what holds it up, as in [`network-cant-reach-platform.md`](network-cant-reach-platform.md#the-service-answers-too-slowly), step 2.
3. Don't restart the service: a restart would lose its pending self-Reversals (see below). After its wait, the breaker lets a few trial Authorizations through; once they succeed the log says `State transition from HALF_OPEN to CLOSED` and Charges are answered as usual. The connection pool reconnects by itself. While the database is still down, the trial calls fail and the breaker opens again.

If the service itself was down, start it: `./mvnw -pl authorization-service -am spring-boot:run`. Its breaker starts closed.

## What happens once it's back

- **The service's own Reversals** go through, one per `91` that reached the database: the log says `Reversal <request ID> acknowledged` or `Reversal <request ID> came before its Authorization, which will answer 05`. Each leaves the request ID `REVERSED`. An approval that had committed is reversed, its card goes back to `ACTIVE` in the Card Copy, and `AuthorizationReversed` goes to the outbox.
- **The outbox:** nothing was lost. Events committed before the outage and not yet sent wait in `outbox` with `sent_at` null; the relay's next pass sends them in order, with no restart, followed by the self-Reversals' `AuthorizationReversed`. Check the backlog drains as in [`kafka-down.md`](kafka-down.md#the-services-outbox).
- **The Card Copy:** the cards consumer resumes where it stopped; cards issued meanwhile become chargeable once their events are stored.
- **Charges declined `91`:** Globex may charge the same card again with a new request ID. Once the service's Reversal of the `91`'d request ID has gone through, the card is `ACTIVE` and the new Charge is answered as usual. A resend of the `91`'d request ID itself answers `05`, or `00` with its authorization code if that approval had committed before it was reversed.

## A self-Reversal that gave up

The service retries its own Reversal only for a while, and only in memory. Two cases leave a `91`'d Authorization unreversed:

- **The database stayed down past the retry limit:** the service logs `Authorization <request ID> answered 91 and couldn't be reversed (…): it may still be approved` at ERROR.
- **The service stopped or crashed** while the Reversal was still pending: nothing is logged, so take the request IDs from the `answered 91 and is being reversed` lines before the stop.

For each of those request IDs, once the database answers, check in the service's database (`docker compose exec authorization-postgres psql -U authorization authorization`):

```sql
select request_id, status, response_code, card_token, created_at
  from authorizations
 where request_id in ('<request ID>', '<request ID>');
```

- **No row, `DECLINED` or `REVERSED`:** nothing to undo; no approval stands.
- **`APPROVED`:** the Network was told `91` but the approval committed: the card stays `AUTHORIZED` and any new Charge on it answers `57`. Send the Reversal by hand, as in [A Reversal that never arrived](network-cant-reach-platform.md#a-reversal-that-never-arrived). The Card Copy goes back to `ACTIVE`, and the platform's follows once `AuthorizationReversed` arrives.
