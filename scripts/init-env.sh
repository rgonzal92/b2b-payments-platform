#!/usr/bin/env bash
# Writes random development secrets into .env (gitignored). Existing values are kept, so it's safe to re-run.
set -euo pipefail
cd "$(dirname "$0")/.."
touch .env
for name in KEYCLOAK_ADMIN_PASSWORD SIMULATOR_ISSUER_ADMIN_SECRET ACME_ERP_SECRET ACME_APPROVER_1_SECRET ACME_APPROVER_2_SECRET; do
  grep -q "^${name}=" .env || echo "${name}=$(openssl rand -hex 32)" >> .env
done
# The vault's keys (ADR-0004): 256 bits each, base64-encoded, one to encrypt card data and one to hash PANs.
# Changing one later makes the card data already stored unreadable or unfindable.
for name in VAULT_ENCRYPTION_KEY VAULT_HASH_KEY; do
  grep -q "^${name}=" .env || echo "${name}=$(openssl rand -base64 32)" >> .env
done
echo ".env has: $(cut -d= -f1 .env | paste -sd' ')"
