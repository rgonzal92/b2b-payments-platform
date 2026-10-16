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
- **A container several test classes share starts once, outside Spring (from #20).** Keycloak takes several seconds to start and import the realm, and two test classes need it. `TestKeycloak` holds one `KeycloakContainer` in a static field and starts it in a static initializer, the first time a class uses it; Ryuk removes it when the test JVM exits. Each class still points its own Spring context at it with `@DynamicPropertySource`.
- **We removed Initializr's `TestPlatformApplication`.** It runs the app against Testcontainers during development (`spring-boot:test-run`); we use Docker Compose for that instead (M0 design, rejected options).

## Two apps in one JVM (from #35)

### The problem it solves here

The end-to-end test (`e2e`, `CorePathEndToEndTests`) runs the demo the way a developer does: both apps, real PostgreSQL and Keycloak, the simulator's `POST /demo/invoices`. Running the apps as containers would mean building images in CI first. Both are Spring Boot apps on the test's classpath, so the test starts them itself, in its own JVM, once Testcontainers has started PostgreSQL and Keycloak.

### Mental model

- **Each `SpringApplicationBuilder.run(args)` is a separate app.** It builds its own application context, with its own beans, environment and embedded Tomcat, and closing the context stops it. The test starts the platform, then the simulator, and closes both after the test.
- **Each app gets its settings as command-line arguments** (`--server.port=...`), which outrank its `application.properties`: ports, Keycloak's and PostgreSQL's addresses, test-only secrets.
- **What the apps share is the classpath and the JVM's static state.** Every gotcha below comes from one or the other.

### Gotchas we hit

- **The apps' jars must stay plain.** `spring-boot-maven-plugin`'s `repackage` goal replaces each app's jar with the executable one during `package`, with the app's classes moved under `BOOT-INF/classes`. In `./mvnw verify` the reactor hands `e2e` those jars, and compiling the test failed with "package dev.rgonz.payments.platform does not exist". `./mvnw test` hides it, since it stops before `package` and hands over `target/classes`. Both apps give the executable jar the `exec` classifier, so the plain jar stays the main artifact; `spring-boot:run` runs from `target/classes` and is unaffected.
- **Only one `application.properties` wins.** Both jars hold one, and `classpath:application.properties` finds the first on the classpath, for both apps: the simulator started with the platform's settings and failed with "Client id of registration 'acme-erp' must not be empty". The test points each app at the file in its own jar with `spring.config.location`.
- **Auto-configuration follows the classpath, not the app.** The simulator saw the platform's JDBC starter and tried to configure a DataSource, then Spring Modulith's event registry, which needs one. The simulator excludes `DataSourceAutoConfiguration`, `JdbcEventPublicationAutoConfiguration` and `EventPublicationAutoConfiguration`. The platform needed nothing: the simulator's OAuth2 client starter does nothing without client registrations. Component scanning stays apart: each app scans only its own package, and the test sits in a third, `dev.rgonz.payments.e2e`.
- **Logback is set up once per JVM.** Spring Boot finds Logback already configured when the second app starts and skips that app's logging settings, so every line carried the first app's name, `[platform]`, even the simulator's. The test turns the name off (`logging.include-application-name=false`): the logger's package tells the apps apart. The first app to close also removes Boot's bridge from `java.util.logging` for both apps, so the other app's last lines that go through it (Tomcat's "Stopping service", the OpenTelemetry SDK's) don't show.
- **OpenTelemetry's Logback appender is static.** `OpenTelemetryAppender.install` runs in each app, and the second one wins, so both apps' logs go to the simulator's OpenTelemetry SDK. The test exports nothing, so it does no harm here.
- **Metrics push to `localhost:4318` by default.** OTLP metrics export is on without any configuration and failed with a `ConnectException` at shutdown. The test turns off all OTLP export (metrics, traces, logs), which also keeps an `OTEL_EXPORTER_OTLP_*` variable in a developer's environment from turning it on. Both apps also add their registries to Micrometer's global registry, which nothing here reads.
- **Ports are picked before the apps start.** The platform builds Remittance Advice links, and the simulator Globex's remittance address, from their own port at startup, so `server.port=0` would give them port 0. The test asks `TestSocketUtils` for two free ports first.
- **Boot guesses the main class from the call stack.** It named Surefire's `ForkedBooter` in "Starting ..." lines; `SpringApplicationBuilder.main(...)` names the app instead.
- **Docker Compose support is an optional dependency** of both apps, so it never reaches `e2e`'s classpath and starts nothing.
