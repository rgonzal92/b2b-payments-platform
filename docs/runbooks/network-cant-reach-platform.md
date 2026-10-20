# The Network can't reach authorization-service, or it answers too slowly

**Signals:** Charges are declined `91` (issuer unavailable): the demo answers `"authorization": {"approved": false, "responseCode": "91", "authorizationCode": null}`. The simulator logs `The Network got no answer to Authorization <request ID> (UNAVAILABLE)` (the service can't be reached) or `(DEADLINE_EXCEEDED)` (it answered too slowly), `… declined it 91 and is reversing it`. When the service is slow, Reversals arrive there: it logs `Reversal <request ID> acknowledged` or `Reversal <request ID> came before its Authorization, which will answer 05`, and its `authorizations` table fills with `REVERSED` rows, some with response code `05`. When the Reversal can't get through either, the simulator logs at `ERROR`: `The Network couldn't reverse Authorization <request ID> after 4 attempts`.

The simulator's Network sends each Charge to `authorization-service`'s gRPC server at `simulator.platform-grpc-address` (`localhost:9090` by default; the name dates from when the platform answered, until #92), where the service listens on `127.0.0.1:9090` (`spring.grpc.server.address` and `.port`); see [The simulator](../architecture.md#the-simulator). The platform runs no gRPC server. It waits at most `simulator.network.authorization-deadline` (1 second) for the answer, as card networks do (ADR-0010). When the deadline passes (`DEADLINE_EXCEEDED`), or gRPC can't connect (`UNAVAILABLE`, at once), the Network declines the Charge to Globex itself with `91`, at once, and sends the service a Reversal for the request ID in the background, in case the service approves it too late. It sends the Reversal up to four times, 100, 200 and 400 ms apart, each with its own deadline; Reversals are safe to repeat ([`docs/learning/grpc.md`](../learning/grpc.md#the-client-side-the-simulators-network)).

Nothing is lost as long as the Reversal arrived: the card stays (or goes back to) `ACTIVE` with its Hold, in the service's copy and, once the service's `AuthorizationReversed` reaches it, in the platform, and Globex can charge it again. If no Charge reaches it within 30 days it expires and its Hold is released. A new demo Invoice charges its own new card.

## The service can't be reached

The log says `UNAVAILABLE`, and the service logs nothing for the request ID: its `authorizations` table has no row for it.

1. **Is the service running?** Its log says `gRPC Server started, listening on address: [/127.0.0.1:9090]`. If it isn't running, start it: `./mvnw -pl authorization-service -am spring-boot:run`.
2. **Is port 9090 someone else's?** `ss -ltnp | grep 9090` (or `lsof -i :9090`) shows what holds it: a platform from before the cutover (#92), still running, would be one. Stop that process, or move the service's `spring.grpc.server.port` and the simulator's `simulator.platform-grpc-address` together.
3. **Do the two settings agree?** The simulator's `simulator.platform-grpc-address` must name the service's `spring.grpc.server.address` and `.port`. `localhost` reaches a server on `127.0.0.1`, as the tests show.

On Kubernetes (M6), where the Network spreads its Charges over the service's pods (`dns:///authorization-service:9090`, `round_robin`):

1. **Is any pod ready?** `kubectl get pods -l app.kubernetes.io/name=authorization-service`. With none `1/1`, see [`pod-not-ready.md`](pod-not-ready.md).
2. **Does the name resolve to them?** `kubectl get endpointslices -l kubernetes.io/service-name=authorization-service` lists each ready pod's address. The Service must be headless (`kubectl get service authorization-service` shows `CLUSTER-IP None`): through a virtual IP, the Network's one connection sticks to one pod and breaks when that pod is replaced.
3. **Only during or just after a rollout?** The simulator's JVM must not cache DNS answers: `kubectl get deployment simulator -o yaml | grep -A1 JAVA_TOOL_OPTIONS` shows `-Dnetworkaddress.cache.ttl=0 -Dsun.net.inetaddr.ttl=0`. Without them the Network keeps the replaced pods' addresses for up to 30 s and every Charge in that window fails ([`learning/kubernetes.md`](../learning/kubernetes.md#gotchas-we-hit)). A Charge that failed `UNAVAILABLE` was declined `91` by the Network itself, and the service has no row for it, so there is nothing to reverse.

## The service answers too slowly

The log says `DEADLINE_EXCEEDED`: the service accepted the call but didn't answer within the deadline. Its Authorization may still be running, and may approve after the Network has declined; the Reversal cancels it either way (see [The Network link](../architecture.md#the-network-link)). Every query below runs against the service's development database: `docker compose exec authorization-postgres psql -U authorization authorization`.

1. **How slow is the service?** Grafana's Authorization dashboard shows the service's server-side p99 (since #96). In Explore, Tempo: `{name="payments.network.v1.IssuerAuthorization/Authorize" && duration > 500ms}` finds the slow Authorizations, each trace running from the simulator's call into the service. The target is 99% within 100 ms. The first Charge after either app starts is slower, since the Network connects and signs in first: about 200 ms locally, inside the deadline. A machine short of CPU or memory (a build running beside the apps, say) slows every Charge.
2. **Is the database holding it up?** An Authorization waits on a lock when another transaction holds its request ID or its card's `card_copy` row:

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
   select status, response_code, card_token is null as no_card_token, count(*)
     from authorizations
    where created_at > now() - interval '1 hour'
    group by 1, 2, 3;
   ```

   - `REVERSED`, `00`, with a Card Token: approved after the Network gave up, then reversed; the card is `ACTIVE` again.
   - `REVERSED`, `05`, no Card Token: the Reversal arrived before the Authorization finished, so the late Authorization answered `05` (do not honour) and moved no card.
   - `DECLINED`: the Reversal changed nothing.

   **A flood of `05`** means the service regularly takes longer than the Network's deadline: each one is a Charge the Supplier was told was declined `91`, and the Supplier may try again. Fix the latency (steps 1 and 2); the `05` rows need nothing done.

## A Reversal that never arrived

The simulator logged `The Network couldn't reverse Authorization <request ID> after 4 attempts (…)`: the service was unreachable or slow for the Reversal too. Look up that request ID in the service's database once it answers again:

```sql
select request_id, status, response_code, card_token, created_at
  from authorizations
 where request_id = '<request ID>';
```

- **No row, `DECLINED` or `REVERSED`:** nothing to undo. With no row, the service never recorded the Authorization; with `REVERSED`, a Reversal got through after all, its answer lost on the way back.
- **`APPROVED`:** the service approved a Charge Globex was told was declined. The card stays `AUTHORIZED`, in the service's copy and the platform's, so any new Charge on it answers `57`. Send the Reversal again: the service reverses an Authorization once, however many Reversals arrive. With [grpcurl](https://github.com/fullstorydev/grpcurl), from the repository root:

  ```sh
  secret=$(grep '^NETWORK_CLIENT_SECRET=' .env | cut -d= -f2-)
  token=$(curl -s -d grant_type=client_credentials -d client_id=network \
    --data-urlencode "client_secret=$secret" \
    http://localhost:8180/realms/payments/protocol/openid-connect/token | jq -r .access_token)
  grpcurl -plaintext -H "authorization: Bearer $token" \
    -import-path contracts/src/main/proto -proto issuer_authorization.proto \
    -d '{"retrieval_reference_number": "<request ID>"}' \
    localhost:9090 payments.network.v1.IssuerAuthorization/Reverse
  ```

  It answers `"responseCode": "00"`, and the row reads `REVERSED`, the card's copy `ACTIVE`; the platform's `"authorization".authorizations` row and card follow once its `AuthorizationReversed` arrives on the `authorizations` topic ([`kafka-down.md`](kafka-down.md) if it doesn't).
