#!/usr/bin/env bash
# Writes random development secrets into .env (gitignored). Existing values are kept, so it's safe to re-run.
set -euo pipefail
cd "$(dirname "$0")/.."
touch .env
for name in KEYCLOAK_ADMIN_PASSWORD SIMULATOR_ISSUER_ADMIN_SECRET ACME_ERP_SECRET ACME_APPROVER_1_SECRET ACME_APPROVER_2_SECRET INITECH_ERP_SECRET INITECH_APPROVER_SECRET NETWORK_CLIENT_SECRET; do
  grep -q "^${name}=" .env || echo "${name}=$(openssl rand -hex 32)" >> .env
done
# The vault's keys (ADR-0004): 256 bits each, base64-encoded, one to encrypt card data and one to hash PANs.
# Changing one later makes the card data already stored unreadable or unfindable.
# The Remittance Advice link key: 256 bits, base64-encoded. Each link's token is an HMAC of its Payment
# under it; changing it while a delivery awaits its retry makes the retry send a link that doesn't open.
for name in VAULT_ENCRYPTION_KEY VAULT_HASH_KEY REMITTANCE_LINK_KEY; do
  grep -q "^${name}=" .env || echo "${name}=$(openssl rand -base64 32)" >> .env
done
echo ".env has: $(cut -d= -f1 .env | paste -sd' ')"
