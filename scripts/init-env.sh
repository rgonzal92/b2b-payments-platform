#!/usr/bin/env bash
# Writes random development secrets into .env (gitignored). Existing values are kept, so it's safe to re-run.
set -euo pipefail
cd "$(dirname "$0")/.."
touch .env
for name in KEYCLOAK_ADMIN_PASSWORD SIMULATOR_ISSUER_ADMIN_SECRET SIMULATOR_RECONCILER_SECRET ACME_ERP_SECRET ACME_APPROVER_1_SECRET ACME_APPROVER_2_SECRET INITECH_ERP_SECRET INITECH_APPROVER_SECRET NETWORK_CLIENT_SECRET; do
  grep -q "^${name}=" .env || echo "${name}=$(openssl rand -hex 32)" >> .env
done
# The vault's keys (ADR-0004): 256 bits each, base64-encoded, one to encrypt card data and one to hash PANs.
# Changing one later makes the card data already stored unreadable or unfindable. authorization-service reads
# the same VAULT_HASH_KEY, to hash each Charge's PAN and security code as the vault does (ADR-0016).
# The Remittance Advice link key: 256 bits, base64-encoded. Each link's token is an HMAC of its Payment
# under it; changing it while a delivery awaits its retry makes the retry send a link that doesn't open.
for name in VAULT_ENCRYPTION_KEY VAULT_HASH_KEY REMITTANCE_LINK_KEY; do
  grep -q "^${name}=" .env || echo "${name}=$(openssl rand -base64 32)" >> .env
done
# vault-service's hash key (M7, design Q9): for now the same value as VAULT_HASH_KEY, so its keyed hashes equal the
# platform's and authorization-service's. Copied, never printed; an existing value is kept.
grep -q "^CARD_HASH_KEY=" .env || echo "CARD_HASH_KEY=$(grep "^VAULT_HASH_KEY=" .env | cut -d= -f2-)" >> .env
# Dev-mode OpenBao (compose.yaml): its root token, and vault-service's token, which OpenBao creates at start under a
# policy allowing only datakey, decrypt and rewrap on card-kek (openbao/dev-init.hcl). Fixed per .env, so they survive
# OpenBao's restarts, which recreate both.
for name in OPENBAO_DEV_ROOT_TOKEN OPENBAO_TOKEN; do
  grep -q "^${name}=" .env || echo "${name}=$(openssl rand -hex 32)" >> .env
done
echo ".env has: $(cut -d= -f1 .env | paste -sd' ')"
