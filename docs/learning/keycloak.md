# Keycloak

## The problem it solves here

Three kinds of caller reach the platform: Issuer staff, people at many different Buyers, and machines (each Buyer's ERP, the simulator). Each needs a token that proves who it is, what role it plays, and, for Buyer-side callers, which Buyer it belongs to. Keycloak is the identity provider that issues those tokens (ADR-0007), so the platform only verifies them and never stores a password.

## Mental model

- **A realm is one isolated set of users, clients and roles.** This project's is `payments`, defined in `keycloak/payments-realm.json` and imported when Keycloak starts.
- **A client is an application that asks for tokens.** A machine client like `simulator-issuer-admin` uses the client-credentials grant: it sends its ID and secret and gets a token for itself, with no person involved. Its roles come from its service-account user.
- **Realm roles** (`approver`, `erp`, `issuer-admin`) say what a caller may do. A protocol mapper copies them into a flat `roles` claim, which Spring Boot turns into `ROLE_` authorities (`authorities-claim-name=roles`).
- **A Buyer-side client carries its Buyer in a `buyer_id` claim.** A hardcoded-claim mapper on the client (`acme-erp`, `acme-approver-1`, `acme-approver-2`) writes the Buyer's fixed ID into every token, and the platform takes the Buyer only from there, never from a request body.
- **The token's subject (`sub`) says who acted.** It is the user's ID in the realm, which never changes; `preferred_username` is the readable name. A client-credentials token's subject is the client's service-account user, named `service-account-<client>`, so each demo Approver is a client of its own. The platform records approvals by subject and keeps the name beside it.
- **The platform is a resource server.** It checks each token's signature against Keycloak's published keys and its `iss` against `http://localhost:8180/realms/payments`; it never calls Keycloak per request.

## Gotchas we hit

- **Secrets stay out of the realm file.** It references `${SIMULATOR_ISSUER_ADMIN_SECRET}`, which Keycloak resolves from its environment at import; Docker Compose passes it from the gitignored `.env`. `KeycloakTokenTests` imports the same file into a test Keycloak, so a broken placeholder fails the build.
- **The token's issuer depends on the address you ask.** A token fetched from `localhost:8180` says `iss: http://localhost:8180/realms/payments`; the platform's `issuer-uri` must match exactly, which is why the test sets it to the test container's address.
- **Defining client scopes in the realm file replaces Keycloak's built-in ones.** Doing that would drop the scope that adds the `sub` claim, so the roles mapper is set on each client instead.
- **The simulator uses a token URI, not an issuer URI.** An issuer URI makes Spring ask Keycloak for its configuration at startup, so the simulator couldn't start (or run its tests) without Keycloak.
- **A realm is imported only once.** `--import-realm` skips a realm that already exists, so a client added to the file later reaches a running Keycloak only after `docker compose up -d --force-recreate keycloak`. Tests always start a fresh container, so they never notice.
- **The `testcontainers-keycloak` realm import needs a classpath resource.** `withRealmImportFile` is the reliable way in; the platform's test build copies `keycloak/` onto the test classpath so the test uses the real file.
