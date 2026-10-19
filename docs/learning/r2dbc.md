# R2DBC

## The problem it solves here

`authorization-service` will answer the Network on Netty's few event-loop threads (M5 design, "The service's hot path"). A JDBC call blocks its thread until PostgreSQL answers, and a blocked event loop stalls every call it serves. R2DBC is the reactive counterpart of JDBC: a query returns a `Mono` or `Flux` that completes when the rows arrive, and no thread waits in between. #88 brings it in for the service's copy of the cards; the hot path that needs it arrives with #89.

## Mental model

- **Nothing runs until something subscribes.** `DatabaseClient.sql(...).bind(...).then()` only describes the statement; it runs when a subscriber subscribes to the `Mono`, as WebFlux does with a handler's result, or `block()` does by waiting for it.
- **`DatabaseClient` is the reactive `JdbcClient`:** handwritten SQL with named parameters (ADR-0009), `.map(row -> …)` to read columns, `.one()` for at most one row, `.then()` to ignore the result. `CardCopies` uses only this; no Spring Data repositories.
- **Blocking is allowed where no event loop waits.** The Kafka consumer has its own thread, so `CardsTopicListener` calls `block()` and keeps one partition's events applied in order. Reactor refuses `block()` on a Netty thread with an `IllegalStateException`, which is the guard on the hot path.
- **Flyway still needs JDBC.** It has no R2DBC driver, so the service keeps the PostgreSQL JDBC driver for Flyway alone: Spring Boot's Flyway module (not the JDBC starter, so no Hikari pool) opens its own connection from `spring.flyway.url`, migrates at startup, and closes it. The application never uses JDBC.

## Gotchas we hit

- **Two URLs for one database.** Locally the service sets both `spring.r2dbc.url` (`r2dbc:postgresql://…`) and `spring.flyway.url` (`jdbc:postgresql://…`). In tests, Testcontainers' `@ServiceConnection` gives both, but the R2DBC one only with `testcontainers-r2dbc` on the test classpath.
- **`spring-boot-starter-flyway` brings a pool.** It depends on the JDBC starter, whose HikariCP would give the app a `DataSource` and JDBC transaction manager beside R2DBC's. Depending on `spring-boot-flyway` instead gives Flyway alone.
- **A null needs its type.** `bind(name, null)` fails; `bindNull(name, String.class)` tells the driver which type the null is, as for a card issued without an Acceptor ID.
- **Timestamps come back as `OffsetDateTime`.** The PostgreSQL R2DBC driver reads `timestamptz` as `OffsetDateTime`; `CardCopies` binds an `Instant` at UTC and converts back with `toInstant()`.
