# Each module owns a schema; SQL by hand with JdbcClient, no JPA

Each module owns its own PostgreSQL schema, with no joins or foreign keys across modules, so extracting a module into a service means moving its schema rather than untangling shared tables. Data access is hand-written SQL through Spring's `JdbcClient` instead of JPA: the Ledger needs explicit control over row locks and isolation, and visible SQL keeps every query reviewable.

**Amended 2026-10-18 (#88, [M5 design](../design/m5-authorization-service.md#the-services-hot-path-q13-q14-q17)):** `authorization-service` uses R2DBC with handwritten SQL, through Spring's `DatabaseClient`, so its hot path never blocks a Netty thread; the platform keeps `JdbcClient`. The rule that matters stays the same on both sides: visible SQL, no JPA or Spring Data repositories. The service is one module with its own database, so it keeps its tables in `public` rather than a module schema.
