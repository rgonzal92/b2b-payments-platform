# Run on Kubernetes behind a Gateway, for operability

Everything runs on a single-node k3d cluster from one Helm chart, with two pods of the platform, `authorization-service` and the new `gateway`, so a redeploy or a crashed pod loses no request. The force is operability, not scale: one machine and the 200-a-second load test need no second copy for capacity, only so that one copy can always be replaced. Compose and `spring-boot:run` stay the development loop.

Spring Cloud Gateway fronts only the platform's REST API, where it checks the Keycloak token and limits each client's rate through Redis. The Network's gRPC skips it and reaches the service directly through a headless Service with client-side round-robin: ADR-0010's latency budget has no room for another hop, and the service's own `91` already protects it. A plain ClusterIP Service was rejected because its one long-lived HTTP/2 connection would pin the Network to one pod and fail every in-flight call when that pod is replaced.

The rate limiter fails open: with Redis down, requests pass and an alert fires. We rejected failing closed because a dead cache would then refuse every Payment, the outage this milestone exists to prevent; the cost is no limit while Redis is down.

Shared files (the Clearing drop folder the simulator and platform hand files through by atomic move, and the load test's cards file) live on a hostPath volume over the repo folder mounted into the node. This ties the cluster to one node; a multi-node cluster needs a ReadWriteMany volume, and the infrastructure stays single-replica StatefulSets rather than operators, both deferred.
