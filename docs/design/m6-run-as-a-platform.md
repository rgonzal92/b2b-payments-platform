# M6: Everything runs on Kubernetes

**Status:** approved 2026-10-19. Decisions from the M6 grilling (Rounds 1–4, Q1–Q27, 2026-10-19).

## Problem

Every app runs as one process started by hand with `spring-boot:run`, so a redeploy is an outage: while the platform restarts, no Invoice is accepted, and while `authorization-service` restarts, the Network declines every Charge (ADR-0010). M5 made Authorization survive the platform being down, but nothing yet survives an app being replaced. M6 runs the whole stack on Kubernetes, with two copies of each serving app, so a redeploy or a crashed pod loses no request, and puts Spring Cloud Gateway in front of the platform's API to check tokens and limit each client's rate at the edge.

The force is operability, not scale (ADR-0017). One machine and a 200-a-second load test need no second copy for capacity; the second copy is there so one can always be replaced.

## Done when

1. `helm install` of one umbrella chart brings up the whole stack on a single-node k3d cluster: the four apps, both PostgreSQL databases, Kafka, Keycloak, otel-lgtm and Redis.
2. A script runs M4's Gatling simulation (unchanged apart from its address) and a new Invoice simulation through the Gateway while it rolls `authorization-service`, then the platform, then the Gateway, one pod at a time; both simulations end with 0 failed calls. CI runs it at 50 Authorizations a second for 60 seconds.
3. The same script, run once locally at M4's 200 a second for 2 measured minutes, records p50/p95/p99, errors and achieved rate in this document beside M4's and M5's, with the p99 under 100 ms.
4. A client over its rate gets a `429` Problem Details with `Retry-After` from the Gateway; a request without a valid token is refused there; with Redis down, requests still pass and a Grafana alert fires.
5. Two platform pods never run two Reconciliations at once: the scheduled run takes a lock, and an on-demand run requested during one gets a `409`.
6. Compose plus `spring-boot:run` still runs everything as before, and the build's tests are unchanged in kind (Testcontainers).
7. ADR-0017, `docs/learning/kubernetes.md`, `helm.md` and `spring-cloud-gateway.md`, runbooks for Redis down and a pod that won't become ready, a deployment view in `docs/architecture.md` and "Run it on Kubernetes" in the README are written.

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
- **The Network's gRPC skips the Gateway:** ADR-0010's latency budget has no room for another hop, and the service's own `91` already protects it. The simulator's Network reaches `authorization-service` through a headless Service with grpc-java's `round_robin` over `dns:///`, so calls spread across both pods and move to the other when one goes. In the chart, the service binds gRPC to `0.0.0.0`; development stays on loopback.
- **Admin consoles:** Grafana is reached with `kubectl port-forward`, not through the Gateway. Keycloak is a LoadBalancer Service on `127.0.0.1:8180`, because that is where clients outside the cluster get tokens (the issuer below), and its admin console comes with it. Changed in #114: the design first had both on `port-forward`, which left the host's token address unanswered.
- **The token's issuer:** Keycloak's hostname is set to `http://localhost:8180`, the address clients outside the cluster get tokens from. The apps check `iss` against it through `issuer-uri`, but fetch the signing keys inside the cluster through `jwk-set-uri`, so they never call the host address.

### Rate limiting (Q18)

- The Gateway's `RequestRateLimiter` with its Redis token bucket, one Redis replica in the cluster.
- **Keys:** each Keycloak client (the token's `azp`), 20 requests a second with bursts to 40; the unauthenticated remittance link by client IP, 10 a minute.
- **Over the limit:** `429` with `Retry-After`, as RFC 9457 Problem Details like every other platform error.
- **Redis down:** the limiter lets requests through, Grafana alerts, and the runbook covers it. Refusing every Payment because the limiter's store died would turn a protection into the outage M6 exists to prevent.

### Two copies of each app (Q14, Q17, Q19)

Most background work already tolerates a second instance: the drop-folder check claims a file by atomic move, the Payment worker and card expiry claim rows with `for update skip locked`, the service's outbox relay takes an advisory lock, Kafka's consumer groups split the partitions, and resent events are tolerated by design. Flyway locks its own history table, so two pods starting together migrate once.

- **Reconciliation** doesn't: the 00:05 schedule has no lock, so two platform pods would start two runs. Each run now holds a PostgreSQL advisory lock on its own connection for the length of the run; a pod that can't take it logs and skips, and an on-demand run (`/v1/reconciliation-runs`) during one gets `409`. Rejected: ShedLock (a new dependency for one job) and keying the Batch job by date (it would block rerunning a date on demand).
- **Probes and shutdown:** each app's liveness and readiness probes read Spring Boot's actuator probe groups. Graceful shutdown (Boot's default) plus a `preStop` pause of about 5 seconds lets Kubernetes stop routing to a pod before it stops answering; a stopping gRPC server finishes its calls first. Readiness leaves the database out: a database outage should answer fast errors and `91`, not empty the Service of pods. Liveness checks the process only.
- **State that stays per pod:** each service pod has its own Resilience4j breaker, which is fine, and its own in-memory self-Reversal retries. A redeploy loses a pending self-Reversal only when the database is down at that moment; with it up, the retry finishes in milliseconds. M5's accepted risk extends to that case, and its runbook says so.

### Files and secrets (Q6, Q15, Q21, Q22)

- **Shared files:** the k3d cluster mounts the repo root into its single node, and the pods use hostPath volumes under it: the Clearing drop folder (`var/clearing`), which the simulator and platform must share for atomic moves, and the load test's cards file (`var/load-test/cards.csv`), so the files sit where they do in development. This holds only on one node; a multi-node cluster needs a ReadWriteMany volume (deferred).
- **Secrets:** `scripts/init-env.sh` still writes `.env`; a script turns it into Kubernetes Secrets, which the apps read as environment variables as today. Nothing is committed. The Secrets include `NETWORK_CLIENT_SECRET`, which `init-env.sh` has generated since M2. Sealed Secrets and an external vault wait for M7, where the vault's keys make them matter.

### Proving it (Q1, Q12, Q22, Q23)

- **The load test runs inside the cluster** as a Kubernetes Job on a JDK 25 image, running `./mvnw -pl load-test gatling:test` from the mounted repo. A `kubectl port-forward` from the host would pin every call to one pod and fail on the first redeploy. M4's simulation changes only its address: `dns:///` and `round_robin` on the headless Service. The first Kubernetes ticket proves gatling-grpc accepts both; if it doesn't, the fallback is a small change to the simulation's channel setup.
- **A second simulation** covers the platform and Gateway: an ERP client submits Invoices through the Gateway at 5 a second, each with its own `Idempotency-Key`.
- **The script** starts both, then `kubectl rollout restart`s the service, the platform and the Gateway in turn, each waiting for the previous rollout to finish. Any gRPC error, missing answer or non-2xx response fails the run.
- **CI** gets a job that builds the images, creates a k3d cluster, installs the chart and runs the script at 50 a second for 60 seconds. The full 200-a-second run is local, recorded in a "Measured" section here, as M4 and M5 did.

### Observability (Q24)

- The apps export OTLP to the in-cluster otel-lgtm Service; each pod's `service.instance.id` is its pod name.
- Dashboards and alerts keep summing across pods; the app-down alerts stay per job, so one pod of two going away doesn't fire them. The Authorization p99 panel gains a per-pod breakdown.
- Pod restarts and readiness need kube-state-metrics: deferred.

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
- **gatling-grpc may not take a `dns:///` target and a balancing policy:** the first Kubernetes ticket checks it; the fallback changes only the simulation's channel setup, not its load.
- **Readiness without the database:** a pod whose database connection is broken stays in rotation and answers errors; acceptable, because every pod shares the one database and dropping them all would be worse.
- **Self-Reversals lost in a redeploy during a database outage:** see Two copies of each app; the runbook finds them by the logged request IDs.
- **hostPath ties the cluster to one node and to the repo's folder:** fine for a learning cluster; noted in ADR-0017.
- **The measurement is still one shared machine:** both apps' two pods, the databases and Gatling share its CPU and disk, so the rolling run's p99 may move with what else runs.
