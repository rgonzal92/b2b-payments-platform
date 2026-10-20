# M6: Everything runs on Kubernetes

**Status:** approved 2026-10-19; done 2026-10-20 (#119 checked every "Done when" item and recorded the measurement). Decisions from the M6 grilling (Rounds 1–4, Q1–Q27, 2026-10-19).

## Problem

Every app runs as one process started by hand with `spring-boot:run`, so a redeploy is an outage: while the platform restarts, no Invoice is accepted, and while `authorization-service` restarts, the Network declines every Charge (ADR-0010). M5 made Authorization survive the platform being down, but nothing yet survives an app being replaced. M6 runs the whole stack on Kubernetes, with two copies of each serving app, so a redeploy or a crashed pod loses no request, and puts Spring Cloud Gateway in front of the platform's API to check tokens and limit each client's rate at the edge.

The force is operability, not scale (ADR-0017). One machine and a 200-a-second load test need no second copy for capacity; the second copy is there so one can always be replaced.

## Done when

1. `helm install` of one umbrella chart brings up the whole stack on a single-node k3d cluster: the four apps, both PostgreSQL databases, Kafka, Keycloak, otel-lgtm and Redis. **Proven** (#114, PR #124): CI's `k3d` job creates a fresh cluster, installs the chart and waits for every Deployment to be available on each PR; the README's "Run it on Kubernetes" steps 3 to 5.
2. A script runs M4's Gatling simulation (unchanged apart from its address) and a new Invoice simulation through the Gateway while it rolls `authorization-service`, then the platform, then the Gateway, one pod at a time; both simulations end with 0 failed calls. CI runs it at 50 Authorizations a second for 60 seconds (Changed in #117: 180 seconds, see Proving it). **Proven** by CI's `k3d` job, which runs `scripts/k8s-load-test.sh` on each PR and fails on any failed call or a simulation that ended before the last old pod was gone (service #116, PR #127; platform, Gateway and the Invoice simulation #117, PR #129).
3. The same script, run once locally at M4's 200 a second for 2 measured minutes, records p50/p95/p99, errors and achieved rate in this document beside M4's and M5's, with the p99 under 100 ms. **Recorded** in [Measured (#119)](#measured-119): a server-side p99 of 13.4 ms, 0 failed calls in both simulations, 198.7 Authorizations a second.
4. A client over its rate gets a `429` Problem Details with `Retry-After` from the Gateway; a request without a valid token is refused there; with Redis down, requests still pass and a Grafana alert fires. **Proven** by [`GatewayTests`](../../gateway/src/test/java/dev/rgonz/payments/gateway/GatewayTests.java): no token or a broken one gets the Gateway's `401` (#113, PR #123); a client past its burst gets `429` Problem Details with `Retry-After`, and with Redis stopped requests still reach the platform and are counted (#115, PR #125); the `rate-limiter-redis-down` rule by [`GrafanaProvisioningTests`](../../platform/src/test/java/dev/rgonz/payments/platform/GrafanaProvisioningTests.java), and checked once firing on the k3d cluster with Redis scaled to 0 (#115).
5. Two platform pods never run two Reconciliations at once: the scheduled run takes a lock, and an on-demand run requested during one gets a `409`. **Proven** by [`OneReconciliationAtATimeTests`](../../platform/src/test/java/dev/rgonz/payments/platform/reconciliation/OneReconciliationAtATimeTests.java) (#112, PR #121; fix PR #126), and checked once on the k3d cluster with two platform pods (#117, see Two copies of each app).
6. Compose plus `spring-boot:run` still runs everything as before, and the build's tests are unchanged in kind (Testcontainers). **Recorded**: every address keeps its development default, overridden only by the chart's environment (#120, PR #122); `compose.yaml` only gained Redis, for the Gateway (#115, PR #125); CI's `build` job runs `./mvnw -B verify`, Testcontainers only, on every PR. The Compose loop wasn't run by hand again in #119.
7. ADR-0017, `docs/learning/kubernetes.md`, `helm.md` and `spring-cloud-gateway.md`, runbooks for Redis down and a pod that won't become ready, a deployment view in `docs/architecture.md` and "Run it on Kubernetes" in the README are written. **Done**: [ADR-0017](../adr/0017-kubernetes-behind-a-gateway-for-operability.md), [`kubernetes.md`](../learning/kubernetes.md), [`helm.md`](../learning/helm.md), [`spring-cloud-gateway.md`](../learning/spring-cloud-gateway.md) (with [`buildpacks.md`](../learning/buildpacks.md) and [`redis.md`](../learning/redis.md)), [`rate-limiter-redis-down.md`](../runbooks/rate-limiter-redis-down.md) and [`pod-not-ready.md`](../runbooks/pod-not-ready.md), [Deployment](../architecture.md#deployment) in `architecture.md` (final for M6 in #119) and the README's [Run it on Kubernetes](../../README.md#run-it-on-kubernetes).

## Approach

### What runs where (Q2, Q3, Q9, Q10, Q13)

| What | In the cluster |
|---|---|
| `platform`, `authorization-service`, `gateway` | A Deployment each, 2 replicas. |
| `simulator` | A Deployment, 1 replica: its Network holds the Clearing File sequence number in memory, so two would write clashing files. |
| PostgreSQL ×2, Kafka, Keycloak, otel-lgtm, Redis | A hand-written StatefulSet each, 1 replica, same images as `compose.yaml`. |

- **One chart:** `deploy/helm/b2b-payments/`, an umbrella chart with a subchart per app and one for the infrastructure; one `helm install` brings up everything.
- **Images:** Cloud Native Buildpacks through `spring-boot:build-image`, no Dockerfiles (Q5). k3d imports them, so no registry is needed.
- **Compose stays** the development loop, with `spring-boot:run` and Testcontainers unchanged; k3d is "run it as a platform", in a second README section.
- **Infrastructure is not highly available:** M6's force is the apps' operability. Operators (CloudNativePG, Strimzi, Keycloak's) are deferred.

### Traffic (Q4, Q7, Q8, Q11, Q16, Q20)

- **Into the cluster:** k3d's bundled Traefik is turned off. The Gateway is a LoadBalancer Service on `127.0.0.1:8080`, so clients use the same address as today; the simulator's demo gets a second host port.
- **The Gateway** is a new Maven module, `gateway`, on Spring Cloud Gateway's WebFlux server. It routes the platform's REST `/v1` API: the ERP, Approvers, the Supplier's remittance link, Issuer staff and Reconcilers. It validates the Keycloak token, so a flood without one stops at the edge, and passes the token on unchanged; the platform stays the authority and keeps its own checks. The first ticket proves a Spring Cloud release that works on Spring Boot 4.1.1, as #88 proved Pact on Java 25.
- **The Network's gRPC skips the Gateway:** ADR-0010's latency budget has no room for another hop, and the service's own `91` already protects it. The simulator's Network reaches `authorization-service` through a headless Service with grpc-java's `round_robin` over `dns:///`, so calls spread across both pods and move to the other when one goes. In the chart, the service binds gRPC to `0.0.0.0`; development stays on loopback. Changed in #116: the gRPC clients' JVMs cache no DNS answers (`networkaddress.cache.ttl=0`, `sun.net.inetaddr.ttl=0`); with grpc-java's 30-second default, a rollout left the channel only the old pods' addresses and failed every Charge for about 25 seconds.
- **Admin consoles:** Grafana is reached with `kubectl port-forward`, not through the Gateway. Keycloak is a LoadBalancer Service on `127.0.0.1:8180`, because that is where clients outside the cluster get tokens (the issuer below), and its admin console comes with it. Changed in #114: the design first had both on `port-forward`, which left the host's token address unanswered.
- **The token's issuer:** Keycloak's hostname is set to `http://localhost:8180`, the address clients outside the cluster get tokens from. The apps check `iss` against it through `issuer-uri`, but fetch the signing keys inside the cluster through `jwk-set-uri`, so they never call the host address.

### Rate limiting (Q18)

- The Gateway's `RequestRateLimiter` with its Redis token bucket, one Redis replica in the cluster.
- **Keys:** each Keycloak client (the token's `azp`), 20 requests a second with bursts to 40; the unauthenticated remittance link by client IP, 10 a minute.
- **Over the limit:** `429` with `Retry-After`, as RFC 9457 Problem Details like every other platform error.
- **Redis down:** the limiter lets requests through, Grafana alerts, and the runbook covers it. Refusing every Payment because the limiter's store died would turn a protection into the outage M6 exists to prevent.

### Two copies of each app (Q14, Q17, Q19)

Most background work already tolerates a second instance: the drop-folder check claims a file by atomic move, the Payment worker and card expiry claim rows with `for update skip locked`, the service's outbox relay takes an advisory lock, Kafka's consumer groups split the partitions, and resent events are tolerated by design. Flyway locks its own history table, so two pods starting together migrate once.

- **Reconciliation** doesn't: the 00:05 schedule has no lock, so two platform pods would start two runs. Each run now holds a PostgreSQL advisory lock on its own connection for the length of the run; a pod that can't take it logs and skips, and an on-demand run (`/v1/reconciliation-runs`) during one gets `409`. Rejected: ShedLock (a new dependency for one job) and keying the Batch job by date (it would block rerunning a date on demand). Proven in #117 on a throwaway platform build with the cron set to every minute, two pods on the k3d cluster: each minute logged one run on one pod and `This pod skipped the daily run` on the other.
- **Probes and shutdown:** each app's liveness and readiness probes read Spring Boot's actuator probe groups. Graceful shutdown (Boot's default) plus a `preStop` pause of about 5 seconds lets Kubernetes stop routing to a pod before it stops answering; a stopping gRPC server finishes its calls first. Readiness leaves the database out: a database outage should answer fast errors and `91`, not empty the Service of pods. Liveness checks the process only.
- **State that stays per pod:** each service pod has its own Resilience4j breaker, which is fine, and its own in-memory self-Reversal retries. A redeploy loses a pending self-Reversal only when the database is down at that moment; with it up, the retry finishes in milliseconds. M5's accepted risk extends to that case, and its runbook says so.

### Files and secrets (Q6, Q15, Q21, Q22)

- **Shared files:** the k3d cluster mounts the repo root into its single node, and the pods use hostPath volumes under it: the Clearing drop folder (`var/clearing`), which the simulator and platform must share for atomic moves, and the load test's cards file (`var/load-test/cards.csv`), so the files sit where they do in development. This holds only on one node; a multi-node cluster needs a ReadWriteMany volume (deferred).
- **Secrets:** `scripts/init-env.sh` still writes `.env`; a script turns it into Kubernetes Secrets, which the apps read as environment variables as today. Nothing is committed. The Secrets include `NETWORK_CLIENT_SECRET`, which `init-env.sh` has generated since M2. Sealed Secrets and an external vault wait for M7, where the vault's keys make them matter.

### Proving it (Q1, Q12, Q22, Q23)

- **The load test runs inside the cluster** as a Kubernetes Job on a JDK 25 image, running `./mvnw -pl load-test gatling:test` from the mounted repo. A `kubectl port-forward` from the host would pin every call to one pod and fail on the first redeploy. M4's simulation changes only its address: `dns:///` and `round_robin` on the headless Service. The first Kubernetes ticket proves gatling-grpc accepts both; if it doesn't, the fallback is a small change to the simulation's channel setup. Changed in #116: the Job runs `./mvnw -B -pl load-test -am -Pload-test -DskipTests verify` (the `load-test` profile binds `gatling:test`, and `-am` builds `contracts`, which the simulation needs, from the same checkout), with Maven's repository in `var/maven`. Besides its gRPC address (`target`, `loadBalancingPolicy`), the simulation takes Keycloak's token address from a `tokenUri` property, since `localhost:8180` isn't reachable from a pod; all three keep their host defaults.
- **A second simulation** covers the platform and Gateway: an ERP client submits Invoices through the Gateway at 5 a second, each with its own `Idempotency-Key`. Changed in #117: `InvoiceSimulation`, Gatling's HTTP DSL, which has no user cap, so an open workload; Acme's ERP sends $10.00 Invoices to a Supplier only the load test uses, which stay `RECEIVED`, over connections its users share, as an ERP's HTTP client keeps a keep-alive pool.
- **The script** starts both, then `kubectl rollout restart`s the service, the platform and the Gateway in turn, each waiting for the previous rollout to finish. Any gRPC error, missing answer or non-2xx response fails the run. Changed in #117: two Maven builds in one checkout would compile into the same `target/` under each other's running simulation, so the Job (`scripts/k8s-load-test-job.sh`) builds once (`install` into `var/maven`, with Gatling's plugin), then runs both simulations side by side offline from those classes. Each rollout waits for its old pods' deletion as well as `rollout status`, and the run fails if either simulation ended before the last old pod was gone.
- **CI** gets a job that builds the images, creates a k3d cluster, installs the chart and runs the script at 50 a second for 60 seconds. Changed in #117: for 180 seconds. Three rollouts of 2 pods took about 65 s on a workstation, and #116's one rollout already took 40 s of CI's 60; 180 s leaves CI's three a margin and stays inside the simulations' 240 s token limit and gatling-grpc's 300 s cap. The rate stays 50, so the run seeds 9,000 cards. The full 200-a-second run is local, recorded in a "Measured" section here, as M4 and M5 did. Changed in #119: the script takes a `WARM_UP` (0 by default, so CI's run is unchanged), which the Authorize simulation gets as its warm-up, the Invoice simulation runs through, and the rollouts wait out, so M4's profile runs with the rollouts in its measured 2 minutes; and it seeds in calls of at most 10,000 cards, appending, since one call of 25,000 outlasts k3d's load balancer's 600-second idle timeout.
- **Seeding the cards.** Changed in #116: the script seeds them through the simulator's demo, but thousands of Invoices, their Payments' polling and Globex's link openings from one pod exceed the Gateway's limits many times over, so the cluster the script runs on is installed with `load-test-values.yaml`, which points the simulator and the Remittance Advice links at the platform directly; CI therefore installs the chart with that file, not with the README's defaults. The Network's Charges skip the Gateway either way.

### Observability (Q24)

- The apps export OTLP to the in-cluster otel-lgtm Service; each pod's `service.instance.id` is its pod name.
- Dashboards and alerts keep summing across pods; the app-down alerts stay per job, so one pod of two going away doesn't fire them. The Authorization p99 panel gains a per-pod breakdown.
- Pod restarts and readiness need kube-state-metrics: deferred.

### Measured (#119)

**Result: the cluster passes the 100 ms target through three rollouts, with no failed call in either simulation.** At M4's 200 Authorizations a second, while the script rolled `authorization-service`, the platform and the Gateway inside the measured 2 minutes, the service's server-side p99 was 13.4 ms and 99.978% of calls took 100 ms or less; Acme's ERP's 750 Invoices through the Gateway all got `201`. The figures sit with M5's run 1, on the same kind of storage (btrfs with copy-on-write on), not with its run 2: two pods per app, the rollouts and the Invoice traffic added no visible tail. One run can't separate a change from the run-to-run spread M4 showed, so this says the cluster holds the target, not that it is faster than M5.

| | M4 run 1 (platform) | M4 run 2 (platform) | M5 run 1 (service) | M5 run 2 (service, copy-on-write off) | M6 (cluster, through 3 rollouts) |
|---|---|---|---|---|---|
| Server-side p50 / p95 / p99 | 2.4 / 3.7 / 35.6 ms | 2.5 / 4.1 / 104.2 ms | 4.5 / 7.8 / 16.2 ms | 3.7 / 6.3 / 8.2 ms | 3.7 / 8.6 / 13.4 ms |
| At or under 100 ms | 99.705% (pass) | 98.768% (fail) | 99.961% (pass) | 100.000% (pass) | 99.978% (pass) |
| Failed calls | 0 | 0 | 0 | 0 | 0 Authorizations, 0 Invoices |
| Achieved rate | 190.7/s | 187.6/s | 197.7/s | 199.5/s | 198.7/s |

- **When:** 2026-10-20, 04:40 to 04:42 UTC, on a cluster created and installed for the run.
- **Hardware:** as M4's and M5's: AMD Ryzen 9 5900X (12 cores, 24 threads), 31 GiB RAM, CachyOS Linux (kernel 7.2.11), Docker 29.9.1 with its storage on btrfs (the NVMe disk's root subvolume, mounted without `nodatacow`). k3d 5.8.3 ran k3s v1.37.1 in one node container; the chart from `main` at a18696a plus this ticket's script change, images built from it; Java 25.0.4 in the buildpack images; PostgreSQL 18.6 with default settings. Every pod and the Gatling Job shared the node, with no CPU limits; no build or test ran. Unrelated home-server containers (Plex and others) ran beside it.
- **Storage:** both PostgreSQL StatefulSets on k3s's `local-path` volumes, which live in the node container's Docker volume for `/var/lib/rancher/k3s`, on the same btrfs subvolume with copy-on-write on: M5 run 1's storage, not run 2's.
- **Profile:** `RATE=200 WARM_UP=30 DURATION=120 INVOICE_RATE=5 ./scripts/k8s-load-test.sh` on a cluster installed as the README's step 8 says, with `--set authorization-service.env.MANAGEMENT_OTLP_METRICS_EXPORT_STEP=10s --set platform.env.MANAGEMENT_OTLP_METRICS_EXPORT_STEP=10s` added, the 10-second metrics step M4's and M5's apps ran with (Boot's 1-minute default is too coarse for a 2-minute window). M4's simulation, unchanged apart from its address: 5 paced users, 200 a second, a 30-second warm-up then 2 measured minutes, about 90% approved Charges on fresh cards, 5% second Charges (`57`), 5% wrong security codes (`N7`), through `dns:///authorization-service:9090` with `round_robin` over the service's two pods. Beside it, the Invoice simulation at 5 a second for the whole 150 seconds through `gateway:8080`.
- **Rollouts:** the script waits out the warm-up, so all three fell inside the measured 2 minutes (04:40:34 to 04:42:34): `authorization-service` from 04:40:43 (9 s in) until its old pods were gone at about 04:41:00, the platform from 04:41:03, the Gateway from 04:41:21 until its last old pod went at about 04:41:40 (66 s in). The last 54 seconds ran on the new pods.
- **Server side** (`grpc.server` for `Authorize`, `job="authorization-service"`, the histogram the Authorization dashboard reads, from the in-cluster Prometheus through `kubectl port-forward pod/lgtm-0 9090`; each pod's cumulative buckets differenced over the window and summed, interpolated within buckets as `histogram_quantile` does, as in M4 and M5. The window starts at each pod's first export after the warm-up; a pod that started during it counts from zero, and a stopping pod's last export, at shutdown, closes its share):

  | Calls in window | p50 | p95 | p99 | At or under 100 ms | Warm-up p99 |
  |---|---|---|---|---|---|
  | 22,652 | 3.7 ms | 8.6 ms | 13.4 ms | 99.978% (pass) | 12.5 ms |

  The window holds about 95% of the measured calls: the old pods' first exports after the warm-up came 3 and 8 seconds into it. 5 calls took over 100 ms, none over 134 ms; one run can't say whether they fell in a rollout. Per pod: the old pods answered 2,272 and 1,198 of the window's calls, the new ones 9,886 and 9,296.
- **Gatling, client side** (measured 2 minutes; no failed call):

  | Measured calls | Achieved rate | Approved Charge p50 / p95 / p99 / max | Second Charge p99 | Wrong security code p99 |
  |---|---|---|---|---|
  | 23,845 | 198.7/s | 4 / 9 / 14 / 152 ms | 15 ms | 14 ms |

  The Invoice simulation: 750 Invoices, 750 `201`, 0 failed; p50 / p95 / p99 / max 11 / 19 / 146 / 412 ms through the Gateway and three rollouts.
- **Seeding:** 30,000 cards in three calls of 10,000 (#119: k3d's load balancer drops a connection idle for 600 seconds, which a first try of 25,000 in one call hit after about 16,600 cards), about 18 minutes in all.
- **Not measured:** a `--no-restart` run to give the rollouts' own share (not needed once the target passed), more than one run, a run with copy-on-write off, and hardware not shared with other services.

## Deferred

Operators and highly available infrastructure; multi-node clusters and ReadWriteMany storage; autoscaling (HPA); kube-state-metrics and pod dashboards; Sealed Secrets and an external vault (M7); a service mesh or mutual TLS (M7); routing gRPC through the Gateway.

## Rejected options

- **Only the apps in the cluster, infrastructure in Compose:** the apps would still reach `localhost`, so "everything runs on Kubernetes" would mean nothing.
- **Routing the Network's gRPC through the Gateway:** a hop inside ADR-0010's budget that protects nothing the service's `91` doesn't.
- **Jib or Dockerfiles:** buildpacks are Spring Boot's own tool and add no files.
- **Upstream Helm charts for the infrastructure:** most were Bitnami's, which moved its free images to a legacy repository in 2025, and they drift from the versions Compose pins.
- **A plain ClusterIP Service for gRPC:** its one long-lived HTTP/2 connection pins the Network to one pod, which fails every in-flight call when that pod is replaced.
- **A fail-closed rate limiter:** a dead Redis would refuse every Payment.
- **Running the load test from the host:** a port-forward pins it to one pod.
- **ShedLock for Reconciliation:** a dependency for one job the advisory-lock pattern already covers.

## Risks

- **Spring Cloud on Spring Boot 4.1.1 is unproven:** the first Gateway ticket proves a release before anything depends on it. Proven in #113: release train 2025.1.3 (Oakwood, Gateway 5.0.3). Spring Cloud's compatibility table gives 2025.1.x for Boot 4.0.x and, from 2025.1.2, 4.1.x; 2026.0.x is for Boot 4.2. The `gateway` module starts on Boot 4.1.1 and `GatewayTests` passes against a real Keycloak. Pinned in the root `pom.xml`.
- **gatling-grpc may not take a `dns:///` target and a balancing policy:** the first Kubernetes ticket checks it; the fallback changes only the simulation's channel setup, not its load. Proven in #116: gatling-grpc 3.16.0's server configuration has `forTarget` and `useCustomLoadBalancingPolicy`, and a run with `dns:///authorization-service:9090` and `round_robin` split 2,966 Charges 1,483 and 1,484 between the two pods.
- **Readiness without the database:** a pod whose database connection is broken stays in rotation and answers errors; acceptable, because every pod shares the one database and dropping them all would be worse.
- **Self-Reversals lost in a redeploy during a database outage:** see Two copies of each app; the runbook finds them by the logged request IDs.
- **hostPath ties the cluster to one node and to the repo's folder:** fine for a learning cluster; noted in ADR-0017.
- **A request on a kept-alive connection that a stopping pod closes:** HTTP/1.1 has no way to say "don't send on this connection", so a request sent just as a stopping platform or Gateway pod closes an idle connection can be lost. The `preStop` pause moves new connections elsewhere first, and both clients resend in the common cases: the Gateway's Reactor Netty retries once if the connection closed before it sent anything, and Gatling resends once when a pooled connection closes before the answer, which the `Idempotency-Key` makes safe. Measured in #117: 0 failed of about 7,900 Invoices through 7 platform and 7 Gateway rollouts, up to 18 a second. A request lost after the Gateway sent it would be a `502`; an ERP retrying with the same key, as the API asks, gets the original answer.
- **The measurement is still one shared machine:** both apps' two pods, the databases and Gatling share its CPU and disk, so the rolling run's p99 may move with what else runs. Measured in #119: 13.4 ms through three rollouts, with the host's other containers running ([Measured (#119)](#measured-119)).
