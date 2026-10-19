# The rate limiter's Redis is down

**Signals:** Grafana's alert **Gateway letting requests through unlimited, its Redis is down** (`rate-limiter-redis-down`) fires; `gateway_rate_limiter_failed_open_total{job="gateway"}` rises in Prometheus; the Gateway logs `Error calling rate limiter lua` at `ERROR` from `RateLimits$FailOpenCountingRateLimiter` for every request, caused by `Currently not connected. Commands are rejected.` or a `RedisCommandTimeoutException`, and Lettuce's `ConnectionWatchdog` logs `Cannot reconnect to [redis…:6379]`; no client gets a `429` however fast it calls; `kubectl get pods` shows `redis-0` missing or not `1/1`, or `docker compose ps` doesn't show `redis`.

The Gateway keeps each client's token bucket in Redis (M6 design, Rate limiting). When it can't reach Redis it lets every request through and counts it (ADR-0017: it fails open), so Payments go on and nobody is refused; what's lost is the limit itself. A client can then send as fast as it likes, and the platform and its database take the whole load. That is the accepted cost, and the reason to bring Redis back promptly rather than urgently.

## What keeps working

- Every request with a valid token still reaches the platform, and the remittance link still answers; the Gateway's token check doesn't need Redis.
- No request waits for Redis: while Lettuce reconnects it rejects each command at once, and a Redis that answers nothing counts as down after 250 ms (`spring.data.redis.timeout`).

## In the cluster

1. `kubectl get pods redis-0` and `kubectl get statefulset redis`. If it was scaled down, `kubectl scale statefulset redis --replicas=1`.
2. If the pod restarts or isn't ready, follow [`pod-not-ready.md`](pod-not-ready.md); `kubectl logs redis-0` says why it stopped.
3. `kubectl exec redis-0 -- redis-cli ping` answers `PONG` once it's up.

Redis has no volume and needs none: the buckets refill within seconds, so a Redis that starts empty only hands each client a fresh burst.

## Local development

`docker compose ps`; if `redis` isn't running, `docker compose up -d redis`. The Gateway connects to `localhost:6379`.

## After fixing

The Gateway reconnects by itself, with a backoff of up to about 30 seconds (Lettuce's `ReconnectionHandler` logs `Reconnected to redis`); no restart is needed. Then a burst of more than 40 requests from one client gets `429` with `Retry-After` again, `gateway_rate_limiter_failed_open_total` stops rising, and the alert resolves on its next evaluations.

## Why not refuse requests instead

Failing closed would turn a dead cache into refused Payments for every client, the outage M6 exists to prevent (ADR-0017). Measured on the k3d cluster with Redis scaled to 0: 80 requests at once and 5 a second for 5 minutes all reached the platform, the slowest in 0.18 s, and the alert fired 3 minutes after Redis went away.
