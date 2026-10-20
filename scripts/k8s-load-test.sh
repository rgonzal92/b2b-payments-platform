#!/usr/bin/env bash
# Runs M4's Gatling simulation inside the k3d cluster while it rolls authorization-service, one pod at a time (M6
# design, Proving it): the Network's Charges must all be answered through the redeploy. Exits non-zero on any failed
# call (a gRPC error or an unexpected answer fails Gatling's assertion; a missing answer keeps the run from ending,
# which the Job's deadline turns into a failure) or if the rollout didn't finish while the simulation ran.
#
# Needs the chart installed with load-test-values.yaml (README, "Run it on Kubernetes"). It seeds the cards the run
# charges through the simulator's demo on localhost:8081, then runs ./mvnw -pl load-test -Pload-test verify from this
# repository, mounted into the cluster, as a Job on a JDK image, signed in as the Network with the simulator's Secret.
# Maven's downloads stay in var/maven for the next run; target/ and var/ are gitignored.
#
# Usage: scripts/k8s-load-test.sh [--no-restart]. RATE (50) Authorizations a second for DURATION (60) seconds, no
# warm-up; --no-restart runs it without the redeploy. Prints how many Charges each service pod answered.
set -euo pipefail
cd "$(dirname "$0")/.."
rate=${RATE:-50}
duration=${DURATION:-60}
restart=true
[[ ${1:-} == --no-restart ]] && restart=false
job=load-test
image=eclipse-temurin:25.0.4_7-jdk-noble
log=var/load-test/k8s-load-test.log

# The simulation wants about 91% of rate × duration fresh cards; one per Charge leaves a margin.
cards=$((rate * duration))
echo "Seeding $cards cards through the simulator"
curl -sSf --max-time 1200 -X POST "localhost:8081/demo/load-test-cards?count=$cards"
echo

kubectl delete job "$job" --ignore-not-found --wait
mkdir -p var/maven
# A hung call keeps Gatling from ending: the deadline, Maven's start plus the run plus a margin, fails the Job then.
kubectl apply -f - << EOF
apiVersion: batch/v1
kind: Job
metadata:
  name: $job
spec:
  backoffLimit: 0
  activeDeadlineSeconds: $((duration + 600))
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
          command:
            - ./mvnw
            - -B
            - -pl
            - load-test
            - -am
            - -Pload-test
            - -DskipTests
            - verify
            - -Drate=$rate
            - -DwarmUpSeconds=0
            - -DmeasuredSeconds=$duration
            # Every service pod, through the headless Service, and Keycloak's Service for the token.
            - -Dtarget=dns:///authorization-service:9090
            - -DloadBalancingPolicy=round_robin
            - -DtokenUri=http://keycloak:8180/realms/payments/protocol/openid-connect/token
          env:
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
          volumeMounts:
            - name: repository
              mountPath: /b2b-payments
      volumes:
        - name: repository
          hostPath:
            path: /b2b-payments
            type: Directory
EOF

kubectl wait --for=condition=Ready "pod" -l "job-name=$job" --timeout=300s
kubectl logs -f "job/$job" > "$log" &
logs=$!
trap 'kill $logs 2> /dev/null || true' EXIT

finished() { [[ -n $(kubectl get job "$job" -o jsonpath='{.status.completionTime}{.status.failed}') ]]; }

# Gatling's first progress report, 5 s into the run, so the first Charges have spread over both pods.
until grep -q 's elapsed' "$log"; do
  finished && { cat "$log"; echo "The Job ended before the simulation started" >&2; exit 1; }
  sleep 2
done
echo "The simulation started"

if $restart; then
  kubectl rollout restart deployment/authorization-service
  kubectl rollout status deployment/authorization-service --timeout=300s
  if finished; then
    cat "$log"
    echo "The simulation ended before the rollout finished: run it longer (DURATION)" >&2
    exit 1
  fi
  echo "The rollout finished while the simulation ran"
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
