#!/usr/bin/env bash
# What scripts/k8s-load-test.sh's Job runs, from this repository mounted into its pod: both Gatling simulations at
# once, the Network's Authorizations over gRPC and Acme's ERP's Invoices over HTTP. Exits non-zero if either fails.
#
# Two Maven builds at once in one checkout would compile into the same target/ under each other's running simulation,
# so one build first installs contracts and compiles the simulations (and fetches Gatling's plugin), then each
# simulation runs on its own, offline, from those classes. Their lines are prefixed with the simulation's name.
#
# Takes RATE, DURATION, AUTHORIZE_TARGET, INVOICE_BASE_URL, INVOICE_RATE and TOKEN_URI from the Job's environment.
set -euo pipefail
cd "$(dirname "$0")/.."

./mvnw -B -q -pl load-test -am -Pload-test -DskipTests -Dgatling.skip=true install

simulate() {
  local name=$1 simulation=$2
  shift 2
  ./mvnw -B -o -pl load-test -Pload-test gatling:test "-Dsimulation=dev.rgonz.payments.loadtest.$simulation" \
    "-DtokenUri=$TOKEN_URI" "$@" 2>&1 | sed -u "s/^/[$name] /"
}

simulate authorize AuthorizeSimulation -Drate="$RATE" -DwarmUpSeconds=0 -DmeasuredSeconds="$DURATION" \
  -Dtarget="$AUTHORIZE_TARGET" -DloadBalancingPolicy=round_robin &
authorize=$!
simulate invoices InvoiceSimulation -Drate="$INVOICE_RATE" -DdurationSeconds="$DURATION" \
  -DbaseUrl="$INVOICE_BASE_URL" &
invoices=$!

failed=0
wait "$authorize" || { echo "The Authorize simulation failed" >&2; failed=1; }
wait "$invoices" || { echo "The Invoice simulation failed" >&2; failed=1; }
exit "$failed"
