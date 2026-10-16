# Keycloak as the identity provider

Users from many Buyers, Issuer staff and machine clients (the simulators) all need tokens, and each token must carry the Buyer ID that keeps Buyers' data apart. Keycloak, run locally in Docker, provides OIDC logins, roles and client credentials, so we don't write and maintain an authorization server ourselves (as Spring Authorization Server would require).
