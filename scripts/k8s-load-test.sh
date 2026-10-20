#!/usr/bin/env bash
# Runs both Gatling simulations inside the k3d cluster, M4's Authorizations straight to authorization-service and Acme's
# ERP's Invoices through the Gateway, while it rolls authorization-service, then the platform, then the Gateway, two
# pods each, one pod at a time, each app once the last one's old pods are gone (M6 design, Proving it): every Charge
# and every Invoice must be answered through the redeploys. Exits non-zero on any failed call (a gRPC error, an
# unexpected answer or a non-2xx response fails a simulation's assertion; a missing gRPC answer keeps the run from
# ending until gatling-grpc's trial limit exits with an error 5 minutes after the first user, docs/learning/gatling.md,
# and an HTTP one fails at Gatling's 60-second request timeout; the Job's deadline backs both up) or if an old pod was
# still there when a simulation ended.
#
# Needs the chart installed with load-test-values.yaml (README, "Run it on Kubernetes"). It seeds the cards the run
# charges through the simulator's demo on localhost:8081, then runs scripts/k8s-load-test-job.sh from this repository,
# mounted into the cluster, as a Job on a JDK image, signed in as the Network and as Acme's ERP with the simulator's
# Secret. Maven's downloads stay in var/maven for the next run; target/ and var/ are gitignored.
#
# Usage: scripts/k8s-load-test.sh [--no-restart]. RATE (50) Authorizations and INVOICE_RATE (5) Invoices a second for
# DURATION (180) seconds, after a WARM_UP (0) of as many seconds that the Authorize report names apart and the
# rollouts wait out: three rollouts take about a minute on a workstation and over two on CI's runner, and a run,
# warm-up included, can't pass 240 s (the simulations' tokens). M4's profile, the measured run (M6 design, Measured):
# RATE=200 WARM_UP=30 DURATION=120. --no-restart runs them without the redeploys. Prints how many Charges each service
# pod answered.
set -euo pipefail
cd "$(dirname "$0")/.."
rate=${RATE:-50}
duration=${DURATION:-180}
warm_up=${WARM_UP:-0}
invoice_rate=${INVOICE_RATE:-5}
restart=true
[[ ${1:-} == --no-restart ]] && restart=false
job=load-test
image=eclipse-temurin:25.0.4_7-jdk-noble
log=var/load-test/k8s-load-test.log

# The Authorize simulation wants about 91% of its Charges' worth of fresh cards; one per Charge leaves a margin. They
# go in calls of at most 10,000 cards, each after the first appending: k3d's load balancer drops a connection idle for
# 600 s, and the simulator seeds about 25 cards a second.
cards=$((rate * (warm_up + duration)))
echo "Seeding $cards cards through the simulator"
append=false
for ((left = cards; left > 0; left -= 10000)); do
  curl -sSf --max-time 1200 -X POST "localhost:8081/demo/load-test-cards?count=$((left < 10000 ? left : 10000))&append=$append"
  echo
  append=true
done

kubectl delete job "$job" --ignore-not-found --wait
mkdir -p var/maven
# A hung call keeps Gatling from ending until its 5-minute trial limit exits; the deadline, Maven's start plus the run
# plus a margin, fails the Job should that ever not happen.
kubectl apply -f - << EOF
apiVersion: batch/v1
kind: Job
metadata:
  name: $job
spec:
  backoffLimit: 0
  activeDeadlineSeconds: $((warm_up + duration + 600))
  template:
    spec:
      restartPolicy: Never
      enableServiceLinks: false
      # As you, so target/ and var/maven belong to you.
      securityContext:
        runAsUser: $(id -u)
        runAsGroup: $(id -g)
      containers:
        - name: gatling
          image: $image
          workingDir: /b2b-payments
          command: [scripts/k8s-load-test-job.sh]
          env:
            - name: RATE
              value: "$rate"
            - name: DURATION
              value: "$duration"
            - name: WARM_UP
              value: "$warm_up"
            - name: INVOICE_RATE
              value: "$invoice_rate"
            # Every service pod, through the headless Service; the Gateway's Service, as ERPs reach the platform; and
            # Keycloak's Service for the tokens.
            - name: AUTHORIZE_TARGET
              value: dns:///authorization-service:9090
            - name: INVOICE_BASE_URL
              value: http://gateway:8080
            - name: TOKEN_URI
              value: http://keycloak:8180/realms/payments/protocol/openid-connect/token
            # Maven's wrapper (from \$HOME) and repository (from Java's user.home, which the image's passwd would set
            # otherwise) under var/maven, kept between runs.
            - name: HOME
              value: /b2b-payments/var/maven
            # No DNS caching, as the simulator's Network (its chart values say why); JAVA_TOOL_OPTIONS reaches the JVM
            # Gatling forks too.
            - name: JAVA_TOOL_OPTIONS
              value: -Duser.home=/b2b-payments/var/maven -Dnetworkaddress.cache.ttl=0 -Dsun.net.inetaddr.ttl=0
            - name: NETWORK_CLIENT_SECRET
              valueFrom:
                secretKeyRef:
                  name: simulator-secrets
                  key: NETWORK_CLIENT_SECRET
            - name: ACME_ERP_SECRET
              valueFrom:
                secretKeyRef:
                  name: simulator-secrets
                  key: ACME_ERP_SECRET
          volumeMounts:
            - name: repository
              mountPath: /b2b-payments
      volumes:
        - name: repository
          hostPath:
            path: /b2b-payments
            type: Directory
EOF

# kubectl wait -l fails at once while no pod matches, so wait for the Job's pod to exist first.
until [[ -n $(kubectl get pods -l "job-name=$job" -o name) ]]; do sleep 1; done
kubectl wait --for=condition=Ready "pod" -l "job-name=$job" --timeout=300s
kubectl logs -f "job/$job" > "$log" &
logs=$!
trap 'kill $logs 2> /dev/null || true' EXIT

finished() { [[ -n $(kubectl get job "$job" -o jsonpath='{.status.completionTime}{.status.failed}') ]]; }

# Each simulation's first progress report, 5 s into its run, so the first calls have spread over the pods.
until grep -q '^\[authorize\] .*s elapsed' "$log" && grep -q '^\[invoices\] .*s elapsed' "$log"; do
  finished && { cat "$log"; echo "The Job ended before both simulations started" >&2; exit 1; }
  sleep 2
done
echo "Both simulations started"

if $restart; then
  # The rollouts land in the measured time, after the warm-up.
  for ((i = 0; i < warm_up; i++)); do
    finished && break
    sleep 1
  done
  for app in authorization-service platform gateway; do
    old=$(kubectl get pods -l "app.kubernetes.io/name=$app" -o name)
    kubectl rollout restart "deployment/$app"
    kubectl rollout status "deployment/$app" --timeout=300s
    # rollout status returns once the last old pod is marked for termination, before its preStop pause and its
    # graceful shutdown: an app's redeploy is over only once its old pods are gone, and only then does the next start.
    # shellcheck disable=SC2086 # one argument per pod
    kubectl wait --for=delete $old --timeout=120s
    echo "The old $app pods are gone"
  done
  # Gatling ends each run with "Simulation <class> completed in <n> seconds".
  if grep -q '^\[authorize\] .*completed in' "$log" || grep -q '^\[invoices\] .*completed in' "$log" || finished; then
    cat "$log"
    echo "A simulation ended before the last old pods were gone: run it longer (DURATION)" >&2
    exit 1
  fi
  echo "The old pods were gone while both simulations ran"
fi

until finished; do sleep 2; done
wait "$logs" || true
cat "$log"

echo "Charges answered per pod since it started:"
for pod in $(kubectl get pods -l app.kubernetes.io/name=authorization-service -o name); do
  echo "  $pod: $(kubectl logs "$pod" | grep -c 'Authorization .* answered' || true)"
done

[[ $(kubectl get job "$job" -o jsonpath='{.status.succeeded}') == 1 ]] || { echo "The load test failed" >&2; exit 1; }
echo "The load test passed: no failed calls"
