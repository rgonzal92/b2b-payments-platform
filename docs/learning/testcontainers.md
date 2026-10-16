# Testcontainers

## The problem it solves here

The platform's correctness lives in PostgreSQL: row locks in the Ledger, `SKIP LOCKED` queues, per-module schemas (ADR-0009). An in-memory database like H2 speaks a different SQL dialect and has different locking, so tests against it can pass while the real database fails. Testcontainers runs the real PostgreSQL, the same version as production, in a throwaway Docker container for each test run.

## Mental model

- **The container is a Spring bean.** `TestcontainersConfiguration` declares a `PostgreSQLContainer` bean. It starts when the test's Spring context starts and stops when the context closes.
- **`@ServiceConnection` does the wiring.** Spring Boot reads the container's JDBC URL, username and password and points the `DataSource` at it. No hand-written `@DynamicPropertySource` blocks.
- **Spring caches test contexts.** Test classes that share the same configuration share one context, and so one container, which keeps the suite fast.
- **Docker must be running.** Locally that's the Docker daemon; GitHub's Ubuntu runners have Docker built in.
- A small helper container (Ryuk) removes leftover containers even if the test JVM crashes.

## Gotchas we hit

- **Initializr generated `postgres:latest`.** "latest" changes under you, so tests could silently move to a PostgreSQL version production doesn't run. We pinned `postgres:18.6`.
- **A context test can start a database and never touch it.** The connection pool opens lazily, on first use, so an empty `contextLoads()` passed without ever connecting: the review found no connection-pool log lines at all. The test now runs `select 1`, so it fails if the app can't reach the database.
- **Testcontainers 2.x renamed things.** The artifact is `testcontainers-postgresql` and the class is `org.testcontainers.postgresql.PostgreSQLContainer`, without the `<?>` generic that 1.x examples online still show.
- **We removed Initializr's `TestPlatformApplication`.** It runs the app against Testcontainers during development (`spring-boot:test-run`); we use Docker Compose for that instead (M0 design, rejected options).
