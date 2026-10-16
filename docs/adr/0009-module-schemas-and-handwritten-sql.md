# Each module owns a schema; SQL by hand with JdbcClient, no JPA

Each module owns its own PostgreSQL schema, with no joins or foreign keys across modules, so extracting a module into a service means moving its schema rather than untangling shared tables. Data access is hand-written SQL through Spring's `JdbcClient` instead of JPA: the Ledger needs explicit control over row locks and isolation, and visible SQL keeps every query reviewable.
