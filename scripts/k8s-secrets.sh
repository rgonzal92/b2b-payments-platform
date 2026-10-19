#!/usr/bin/env bash
# Turns the secrets scripts/init-env.sh wrote into Kubernetes Secrets in the current kubectl context (M6): one per
# app, holding only the variables it reads, under the same names. Run it before helm install, and again after changing
# the file; restart the pods to pick up the new values. Usage: scripts/k8s-secrets.sh [env file, default .env]
set -euo pipefail
cd "$(dirname "$0")/.."
env_file=${1:-.env}
[[ -r $env_file ]] || { echo "No $env_file: run scripts/init-env.sh first" >&2; exit 1; }

# The clients the simulator signs in as. Keycloak's realm also needs the Reconciler's, which only the README's curl uses.
clients="SIMULATOR_ISSUER_ADMIN_SECRET ACME_ERP_SECRET ACME_APPROVER_1_SECRET ACME_APPROVER_2_SECRET INITECH_ERP_SECRET
  INITECH_APPROVER_SECRET NETWORK_CLIENT_SECRET"
declare -A secrets=(
  [keycloak-secrets]="KEYCLOAK_ADMIN_PASSWORD SIMULATOR_RECONCILER_SECRET $clients"
  [simulator-secrets]="$clients"
  [platform-secrets]="VAULT_ENCRYPTION_KEY VAULT_HASH_KEY REMITTANCE_LINK_KEY"
  [authorization-service-secrets]="VAULT_HASH_KEY"
)

# Each Secret's variables go through a file of their own, so no value appears on a command line.
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
for secret in "${!secrets[@]}"; do
  for name in ${secrets[$secret]}; do
    grep "^${name}=" "$env_file" >> "$work/$secret" || { echo "$env_file has no $name: run scripts/init-env.sh" >&2; exit 1; }
  done
  kubectl create secret generic "$secret" --from-env-file="$work/$secret" --dry-run=client -o yaml | kubectl apply -f -
done
