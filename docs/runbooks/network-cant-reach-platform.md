# The Network can't reach the platform, or it answers too slowly

**Signals:** Charges are declined `91` (issuer unavailable): the demo answers `"authorization": {"approved": false, "responseCode": "91", "authorizationCode": null}`. The simulator logs `The Network got no answer to Authorization <request ID> (UNAVAILABLE)` (the platform can't be reached) or `(DEADLINE_EXCEEDED)` (it answered too slowly), `… declined it 91 and is reversing it`. When the platform is slow, Reversals arrive there: it logs `Reversal <request ID> acknowledged` or `Reversal <request ID> came before its Authorization, which will answer 05`, and `"authorization".authorizations` fills with `REVERSED` rows, some with response code `05`. When the Reversal can't get through either, the simulator logs at `ERROR`: `The Network couldn't reverse Authorization <request ID> after 4 attempts`.

The simulator's Network sends each Charge to the platform's gRPC server at `simulator.platform-grpc-address` (`localhost:9090` by default), where the platform listens on `127.0.0.1:9090` (`spring.grpc.server.address` and `.port`); see [The simulator](../architecture.md#the-simulator). It waits at most `simulator.network.authorization-deadline` (1 second) for the answer, as card networks do (ADR-0010). When the deadline passes (`DEADLINE_EXCEEDED`), or gRPC can't connect (`UNAVAILABLE`, at once), the Network declines the Charge to Globex itself with `91` and sends the platform a Reversal for the request ID, in case the platform approves it too late. It sends the Reversal up to four times, 100, 200 and 400 ms apart, each with its own deadline; Reversals are safe to repeat ([`docs/learning/grpc.md`](../learning/grpc.md#the-client-side-the-simulators-network)).

Nothing is lost as long as the Reversal arrived: the card stays (or goes back to) `ACTIVE` with its Hold, and Globex can charge it again. If no Charge reaches it within 30 days it expires and its Hold is released. A new demo Invoice charges its own new card.

## The platform can't be reached

The log says `UNAVAILABLE`, and the platform logs nothing for the request ID: `"authorization".authorizations` has no row for it.

1. **Is the platform running?** Its log says `gRPC Server started, listening on address: [/127.0.0.1:9090]`. If it isn't running, start it: `./mvnw -pl platform -am spring-boot:run`.
2. **Is port 9090 someone else's?** `ss -ltnp | grep 9090` (or `lsof -i :9090`) shows what holds it. Stop that process, or move the platform's `spring.grpc.server.port` and the simulator's `simulator.platform-grpc-address` together.
3. **Do the two settings agree?** The simulator's `simulator.platform-grpc-address` must name the platform's `spring.grpc.server.address` and `.port`. `localhost` reaches a server on `127.0.0.1`, as the tests show.

## The platform answers too slowly

The log says `DEADLINE_EXCEEDED`: the platform accepted the call but didn't answer within the deadline. Its Authorization may still be running, and may approve after the Network has declined; the Reversal cancels it either way (see [The Network link](../architecture.md#the-network-link)). Every query below runs against the development database: `docker compose exec postgres psql -U payments payments`.

1. **How slow is the platform?** In Grafana, Explore, Tempo: `{name="payments.network.v1.IssuerAuthorization/Authorize" && duration > 500ms}` finds the slow Authorizations, and each trace shows where the time went. The target is 99% within 100 ms. The first Charge after either app starts is slower, since the Network connects and signs in first: about 200 ms locally, inside the deadline. A machine short of CPU or memory (a build running beside the apps, say) slows every Charge.
2. **Is the database holding it up?** An Authorization waits on a lock when another transaction holds its request ID or its card's row:

   ```sql
   select pid, pg_blocking_pids(pid) as blocked_by, wait_event_type, now() - query_start as running,
          left(query, 80) as query
     from pg_stat_activity
    where datname = current_database() and state <> 'idle'
    order by running desc;
   ```

   A row with a non-empty `blocked_by` waits on those sessions; a session `idle in transaction` for long is holding its locks open. `select pg_terminate_backend(<pid>);` ends the blocker, which rolls its transaction back.
3. **What did the Authorizations record?**

   ```sql
   select status, response_code, card_token is null as before_its_authorization, count(*)
     from "authorization".authorizations
    where created_at > now() - interval '1 hour'
    group by 1, 2, 3;
   ```

   - `REVERSED`, `00`, with a Card Token: approved after the Network gave up, then reversed; the card is `ACTIVE` again.
   - `REVERSED`, `05`, no Card Token: the Reversal arrived before the Authorization finished, so the late Authorization answered `05` (do not honour) and moved no card.
   - `DECLINED`: the Reversal changed nothing.

   **A flood of `05`** means the platform regularly takes longer than the Network's deadline: each one is a Charge the Supplier was told was declined `91`, and the Supplier may try again. Fix the latency (steps 1 and 2); the `05` rows need nothing done.

## A Reversal that never arrived

The simulator logged `The Network couldn't reverse Authorization <request ID> after 4 attempts (…)`: the platform was unreachable or slow for the Reversal too. Look up that request ID once the platform answers again:

```sql
select request_id, status, response_code, card_token, created_at
  from "authorization".authorizations
 where request_id = '<request ID>';
```

- **No row, `DECLINED` or `REVERSED`:** nothing to undo. With no row, the platform never recorded the Authorization; with `REVERSED`, a Reversal got through after all, its answer lost on the way back.
- **`APPROVED`:** the platform approved a Charge Globex was told was declined. The card stays `AUTHORIZED`, so any new Charge on it answers `57`. Send the Reversal again: the platform reverses an Authorization once, however many Reversals arrive. With [grpcurl](https://github.com/fullstorydev/grpcurl), from the repository root:

  ```sh
  token=$(curl -s -u "network:$(grep '^NETWORK_CLIENT_SECRET=' .env | cut -d= -f2-)" \
    -d grant_type=client_credentials \
    http://localhost:8180/realms/payments/protocol/openid-connect/token | jq -r .access_token)
  grpcurl -plaintext -H "authorization: Bearer $token" \
    -import-path contracts/src/main/proto -proto issuer_authorization.proto \
    -d '{"retrieval_reference_number": "<request ID>"}' \
    localhost:9090 payments.network.v1.IssuerAuthorization/Reverse
  ```

  It answers `"responseCode": "00"`, and the row reads `REVERSED`, the card `ACTIVE`.
