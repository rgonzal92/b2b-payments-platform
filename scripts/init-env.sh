#!/usr/bin/env bash
# Writes random development secrets into .env (gitignored). Existing values are kept, so it's safe to re-run.
set -euo pipefail
cd "$(dirname "$0")/.."
touch .env
for name in KEYCLOAK_ADMIN_PASSWORD SIMULATOR_ISSUER_ADMIN_SECRET ACME_ERP_SECRET; do
  grep -q "^${name}=" .env || echo "${name}=$(openssl rand -hex 32)" >> .env
done
echo ".env has: $(cut -d= -f1 .env | paste -sd' ')"
