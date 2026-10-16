# Keycloak is unavailable

**Signals:** every `/v1` request answers `401`; the platform logs failures fetching Keycloak's keys (`JwkSet`, `issuer`); the simulator fails to start with a connection error to `localhost:8180`; `docker compose ps` doesn't show `keycloak` as running.

The platform checks each token against Keycloak's published keys and caches them, so a short Keycloak outage can pass unnoticed by callers holding valid tokens; new tokens can't be issued until it's back.

## Local development

1. `docker compose ps`. If `keycloak` isn't running, `docker compose up -d` starts whatever is missing. Spring Boot only starts the stack when none of it is running, so a half-started stack needs this by hand.
2. If it starts and stops again, read `docker compose logs keycloak`:
   - **"run scripts/init-env.sh first"** from Docker Compose: `.env` is missing a value. Run `./scripts/init-env.sh`, then `docker compose up -d`.
   - **A realm import error:** the realm file is invalid or references a placeholder that isn't in the environment. `KeycloakTokenTests` checks the same file; run `./mvnw -pl platform -am test -Dtest=KeycloakTokenTests` for a precise error.
3. Port `8180` taken by something else: stop it, or change the host port in `compose.yaml` together with `issuer-uri` in the platform and `token-uri` in the simulator.

## A client's token is refused by Keycloak

The simulator logs `invalid_client` or `unauthorized_client` for one client (such as `acme-erp`) while others work. Keycloak imports the realm file only when the realm doesn't exist yet, so a client added to the file after the container was created isn't there.

1. `./scripts/init-env.sh`, which adds any new secret to `.env` and keeps the existing ones.
2. `docker compose up -d --force-recreate keycloak`. The development container keeps its data inside itself, so recreating it re-imports the realm; nothing else is lost.

## Tokens are issued but rejected

The platform answers `401` while Keycloak is up: the token's `iss` must equal the platform's `spring.security.oauth2.resourceserver.jwt.issuer-uri` exactly. A token fetched through a different host name or port than `localhost:8180` carries a different issuer.

The platform answers `403` with "Your token isn't linked to a known Buyer." to a Buyer-side client: its token's `buyer_id` claim is missing, isn't a UUID, or names a Buyer the platform doesn't have. Check the client's `buyer_id` mapper in the realm file against the Buyer's ID (for Acme, `simulator.acme.buyer-id`), and that the Buyer was created; the simulator creates Acme at startup.
