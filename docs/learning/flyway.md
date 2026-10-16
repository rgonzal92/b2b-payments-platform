# Flyway

## The problem it solves here

The database schema changes with the code, on every developer's machine, in every test run and later in every environment. Flyway applies versioned SQL scripts (`V1__create_buyer.sql`, `V2__...`) in order, records which ones ran, and refuses to start on a database whose history doesn't match, so the schema is always exactly what the code expects.

## Mental model

- **A migration is an immutable, numbered SQL file.** Once applied anywhere, it never changes; a fix is a new migration.
- **The history table records what ran,** with a checksum of each file, so an edited migration is caught at startup.
- **Here, every module has its own Flyway** (ADR-0009). `buyers`' migrations live in `db/migration/buyers`, run in the `buyers` schema and are recorded in `buyers.flyway_schema_history`. When a module moves into its own service, its schema and its history move with it.
- **It runs at startup, before anything queries the database:** each module declares one `FlywayMigrationInitializer` bean through `ModuleMigrations`, and Spring Boot orders database users after those beans.

## Gotchas we hit

- **Spring Boot's own Flyway is switched off** (`spring.flyway.enabled=false`). Boot would run a single Flyway over one history table for the whole app, which can't move with a module; the per-module beans replace it.
- **PostgreSQL support is a separate dependency** (`flyway-database-postgresql`); `flyway-core` alone doesn't recognise the database.
- **Migrations write unqualified names; the code queries qualified ones.** During a migration Flyway puts the module's schema on the search path, so `create table buyer` lands in `buyers`. At runtime the search path is `public`, so queries say `buyers.buyer`.
- **Every module's first migration is `V1`.** Separate history tables mean version numbers never collide across modules.
