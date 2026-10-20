# A pod won't become ready

**Signals:** `kubectl rollout status` times out, or `kubectl get pods` shows a pod `0/1` for more than a minute after an install or a restart, with status `Running` (not ready), `CrashLoopBackOff`, `Pending`, `ContainerCreating`, `ErrImageNeverPull` or `CreateContainerConfigError`; the Gateway on `127.0.0.1:8080`, the demo on `8081` or Keycloak on `8180` doesn't answer, or answers `502` (the platform isn't ready behind the Gateway).

A pod that isn't ready gets no traffic from its Service, so callers see the component as down; one app's pod waiting doesn't stop the others. Only the cluster from the README's "Run it on Kubernetes" exists so far (M6), so this is about that one.

## Find which pod and why

1. `kubectl get pods` shows each pod's status and restarts. The infrastructure starts first in practice; an app that starts before its database fails, restarts and comes up once the database is ready, so give a fresh install two or three minutes.
2. `kubectl describe pod <pod>`: the **Events** at the bottom say what Kubernetes is waiting for, and **Last State** the exit code of the last crash (`137` with `OOMKilled` is the memory limit).
3. `kubectl logs <pod>` for the app's own log, and `kubectl logs <pod> --previous` for the run that crashed.

## By status

- **`ErrImageNeverPull`:** the node doesn't have the app's image. Build the images and run `scripts/k3d-cluster.sh` again: it imports them and checks each is on the node.
- **`CreateContainerConfigError`, `secret "…-secrets" not found`:** the Secrets weren't created, or not in this namespace. Run `scripts/k8s-secrets.sh`, then `kubectl rollout restart deployment/<app>` (or `kubectl delete pod keycloak-0` for Keycloak).
- **`ContainerCreating` with `hostPath type check failed`:** a folder the chart mounts from the repository doesn't exist in the node. `scripts/k3d-cluster.sh` creates `var/clearing` and `var/load-test` and mounts the repository root it ran from; a cluster created from another clone mounts that clone. Delete the cluster and create it again from this one.
- **`Pending`, `unbound PersistentVolumeClaims`:** k3s's local-path storage hasn't made the volume yet; `kubectl get pods -n kube-system` should show `local-path-provisioner` running.
- **`CrashLoopBackOff`:** the container keeps stopping. Either the app exits by itself, and its log says why, or a probe kills it, and its log just stops, often mid-startup. Tell them apart with `kubectl describe pod <pod>`:
  - **Killed by a probe:** the Events show `Startup probe failed` or `Liveness probe failed` (`connection refused`, or a status code), followed by `Container <app> failed startup probe, will be restarted` or `failed liveness probe`; `Last State: Terminated` has the exit code of a stop on SIGTERM, usually `143`. A startup probe gives the app 3 minutes (36 tries, 5 s apart) to answer `/actuator/health/liveness`; a cluster too busy to start a JVM in that time, or an app stuck before its web server starts (waiting on a dependency that never answers, `kubectl logs <pod> --previous` shows where it stopped), is killed and restarted. Free the machine, or fix what it waits for. A pod killed by the liveness probe after running had stopped answering health checks: a thread dump would show why, but the restart is the remedy, so look for what recurs in the previous log.
  - **Exits by itself:** `Last State: Terminated` with `Reason: Error` and the app's own exit code (`1`), and `kubectl logs <pod> --previous` ends with the exception. The common causes:
  - **A missing secret:** the platform stops without its vault or link keys ([`vault-key-invalid.md`](vault-key-invalid.md)). A `.env` from before a milestone lacks newer keys: run `scripts/init-env.sh`, then `scripts/k8s-secrets.sh`, and restart the pods.
  - **Its database refused the connection:** the database's pod isn't ready yet; it settles by itself once it is. If it doesn't, follow the database's pod down this list.
  - **`OOMKilled`:** raise the app's limit (`--set platform.memory=2Gi`); Paketo's memory calculator sizes the heap from it.
- **`Running` but `0/1` for minutes:** the readiness probe (`/actuator/health/readiness`) keeps failing. `kubectl describe pod` shows the probe's answer. The app may still be starting: a cold JVM in a busy cluster can take a minute.
- **A rollout that never finishes:** `kubectl rollout status deployment/<app>` times out (`error: timed out waiting for the condition`, or `deadline exceeded`), and `kubectl get pods` shows the old pods still `1/1` beside a new one that isn't. The Deployment starts a new pod before stopping an old one and waits for it to be ready, so the new pod's problem is the rollout's: find its status above. The old pods keep serving meanwhile; nothing is down. Once fixed, the rollout carries on by itself. To go back instead: `kubectl rollout undo deployment/<app>`, which rolls back to the previous template (after a `rollout restart` that is the same image, so fix the cause first). `kubectl rollout history deployment/<app>` lists the revisions.
- **Every pod in the cluster stuck, and the node `NotReady`:** k3s itself is restarting. `docker logs k3d-b2b-payments-server-0` shows why; `failed to create vxlan device: operation not supported` means the host can't load the kernel modules k3s needs, often because the kernel was upgraded without a reboot ([kubernetes.md](../learning/kubernetes.md#gotchas-we-hit)). Reboot.

## After fixing

`kubectl get pods` shows every pod `1/1 Running`. `curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/v1/invoices` answers `401` from the Gateway (no token), and `curl -s -X POST http://127.0.0.1:8081/demo/invoices` runs the demo's payment.
