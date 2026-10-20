# `k3d` is red

**Signal:** the `k3d` check fails on a pull request or on `main`. The job builds the four images, creates a k3d cluster, installs the chart with `load-test-values.yaml` and runs `scripts/k8s-load-test.sh`: the Network's Charges at 50 a second and Acme's ERP's Invoices, through the Gateway, at 5 a second, both for 180 seconds, while `authorization-service`, then the platform, then the Gateway roll (M6 design, Proving it). The two simulations run side by side in one Job, so each of their log lines starts with `[authorize]` or `[invoices]`.

Open the job's log (`gh run view <run> --log-failed`, or the check's "Details") and find the first step that failed. Every step after it is skipped, except "Show the pods", which prints `kubectl get pods` and the last 50 events once the cluster exists.

## A failed call: the load test is red

The "Load test through a redeploy" step fails after Gatling's report. This is the check doing its job: a Charge or an Invoice went unanswered through a redeploy. The `[authorize]` or `[invoices]` prefix on the failing assertion says which.

- **`Global: count of failed events is 0.0 : false`:** the `---- Errors ----` table above it names each failure.
  - `statusCode.is(OK), found UNAVAILABLE`: the Network's channel had no pod to send to. Many at once, right after `rolled out`, is the channel holding replaced pods' addresses; see [`network-cant-reach-platform.md`](network-cant-reach-platform.md#the-service-cant-be-reached), the Kubernetes part. A few, as an old pod stopped, means it stopped answering before its clients moved: check the `preStop` pause and the shutdown timeouts in `deploy/helm/b2b-payments/templates/_app.tpl` ([kubernetes.md](../learning/kubernetes.md)).
  - `response…is(00), but actually found …`: the service answered, with the wrong code. That's the service's logic or the cards file, not the redeploy; `91` means its database failed ([`authorization-service-database-down.md`](authorization-service-database-down.md)).
  - `[invoices]` `status.find.is(201), but actually found 502`: the Gateway sent the Invoice to a platform pod that closed the connection under it, or reached none. Around the platform's rollout, that's a stopping pod closing a kept-alive connection the Gateway had just sent on ([architecture.md](../architecture.md#deployment), Replicas and redeploys); check the `preStop` pause and shutdown timeouts as above. `503` or `504` means no platform pod answered at all: see [`pod-not-ready.md`](pod-not-ready.md).
  - `[invoices]` `found 401`, `403` or `429`: not the redeploy. `401`/`403` is Acme's ERP's token or role (the Keycloak realm, `ACME_ERP_SECRET` in `simulator-secrets`); `429` means the Invoices outran Acme's 20-a-second limit (`INVOICE_RATE`).
  - `[invoices]` `j.u.c.TimeoutException` or `Request timeout`: an Invoice got no answer within Gatling's 60 s, a hung Gateway or platform pod.
- **No `[authorize]` report, and its log ends with Gatling's trial notice or `exit code 1` about 5 minutes in:** a Charge never got an answer, so the run couldn't end until gatling-grpc's 5-minute cap stopped it ([gatling.md](../learning/gatling.md#gotchas-we-hit)). Look for a pod that hung while stopping in "Show the pods".
- **`A simulation ended before the last old pods were gone`:** the three rollouts took longer than the run, so the redeploys weren't proven. The `The old <app> pods are gone` lines show how far they got. A slow runner makes the new pods slow to become ready; raise `DURATION` in the job, up to 240 (the simulations' tokens last 5 minutes, and gatling-grpc stops at 300 s), or see why they start slowly in "Show the pods".

## Infrastructure: the check never reached the load test

Nothing here says the Charges failed; the run proved nothing either way.

- **"Build the images":** a Maven error. `Could not find artifact dev.rgonz.payments:contracts` means the command lost its `package` (README, "Run it on Kubernetes", step 2). A failed pull of Paketo's builder or of a download is transient: re-run the job.
- **"Install k3d and Helm":** `sha256sum: WARNING: 1 computed checksum did NOT match` means the download isn't the pinned release. Re-run once; if it persists, compare the job's `*_SHA256` with the release's published checksums before changing anything, and never update a checksum just to make it pass.
- **"Create the cluster":** k3d couldn't start k3s or import an image (`the node lacks b2b-payments/<app>:local`). Re-run; see [`pod-not-ready.md`](pod-not-ready.md) for a node that stays `NotReady`.
- **"Install the chart", `kubectl wait … timed out`:** a pod didn't become ready within 10 minutes. "Show the pods" names it; follow [`pod-not-ready.md`](pod-not-ready.md).
- **The load-test step fails before Gatling starts:** the seeding `curl` failed (the simulator's log says why; with far fewer `written` than `requested`, the simulation refuses to start), or the Job's pod didn't start (`ImagePullBackOff` on the JDK image means Docker Hub refused or limited the pull: re-run).

## Reproduce it locally

The README's "Run it on Kubernetes" steps 1 to 5 with `-f deploy/helm/b2b-payments/load-test-values.yaml` added to the install, then `./scripts/k8s-load-test.sh`, as step 8 says. It runs the same Job as CI, from your checkout; `--no-restart` separates a broken load test from a broken redeploy. The Job's log stays in `var/load-test/k8s-load-test.log` and Gatling's HTML report under `load-test/target/gatling/`. Delete the cluster afterwards: `k3d cluster delete b2b-payments`.
