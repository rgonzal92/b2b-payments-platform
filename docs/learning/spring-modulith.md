# Spring Modulith

## The problem it solves here

The `platform` starts as one Spring Boot app holding nine modules (ADR-0003). In a plain Spring app nothing stops `authorization` from reaching into `ledger`'s internal classes, and once a few of those shortcuts exist, extracting a module into its own service (M5, M7) turns into surgery. Spring Modulith turns the module boundaries into something a test can check.

## Mental model

- **A module is a direct sub-package of the main application package.** `dev.rgonz.payments.platform.ledger` is the `ledger` module.
- **The module's top-level package is its public API.** Types there can be used by other modules.
- **Sub-packages are internal.** `ledger.internal.Posting` is invisible to every other module.
- **`ApplicationModules.of(PlatformApplication.class).verify()`** scans the compiled classes and fails on two things: a module using another module's internal type, and dependency cycles between modules. `ModularityTests` runs it on every build.
- **Events between modules** (from M1, ADR-0008): a module publishes an application event, and Spring Modulith stores it in the same transaction as the change, then delivers it to listeners in other modules and retries until they succeed.

## Gotchas we hit

- **The guard was proven, not assumed.** A throwaway `probea` module that used `probeb.internal.Secret` made `verify()` fail with `Module 'probea' depends on non-exposed type ... within module 'probeb'`. The violation was listed twice, once for the field type and once for the constructor call: each reference to an internal type is reported separately.
- **With no modules yet, `verify()` passes trivially.** It only starts guarding something once M1 adds the first module packages.
- **Spring Initializr's "Spring Modulith" option adds more than the core.** It also brings `spring-modulith-actuator` (a `/actuator/modulith` endpoint describing the modules) and the observability modules, which trace calls between modules. Nothing used them yet, so we removed them; they come back when telemetry and real modules give them something to show.

## Events and the publication registry (from #16)

### The problem it solves here

When an Invoice gets its final approval, the Payment saga must start. Telling it after the approval commits loses the message if the platform stops in between; telling it inside the approval's transaction ties approvals to the saga's success. Writing the event into a table in the same transaction as the approval, an outbox, means it is never lost and never sent for an approval that rolled back. Spring Modulith's JDBC event publication registry (`spring-modulith-starter-jdbc`) is that outbox, with no broker to run (ADR-0008).

### Mental model

- **Publish inside the transaction.** `Approvals.approve` calls `ApplicationEventPublisher.publishEvent(new InvoiceApproved(...))` inside its `@Transactional` method. For each transactional listener of that event, the registry inserts a row into `event_publication` (listener, event type, the event as JSON) in the same transaction: a rollback leaves no row.
- **One row per listener, completed after it runs.** After the commit each listener is called; when it returns, its row gets a `completion_date`. A listener that throws leaves its row incomplete, to be resubmitted through `IncompleteEventPublications`.
- **The event is part of the publishing module's API.** `InvoiceApproved` is a public record in `payables`' top-level package, like `buyers.BuyerDirectory`. A listener in another module depends on `payables`, never the other way round.
- **Listeners will usually be `@ApplicationModuleListener`:** asynchronous, in a transaction of their own, recorded by the registry. The saga's listener (#17) is the first.

### Gotchas we hit

- **No listener, no row.** The registry records a publication only for a transactional listener that receives it. Until #17 adds one, approving an Invoice writes nothing to `event_publication`, so a broken registry setup would go unnoticed. `ApprovalsApiTests` registers a stand-in `@TransactionalEventListener` and reads the publication back through `CompletedEventPublications`, which proves the table, the event's JSON and the completion all work.
- **Table creation is on by default, whatever the metadata says.** `spring.modulith.events.jdbc.schema-initialization.enabled` is listed in Spring Modulith 2.1.1's property metadata with default `false`, but its auto-configuration runs when the property is missing. The platform sets it to `true` so the file says what happens.
- **The table has a schema of its own.** `spring.modulith.events.jdbc.schema=modulith` makes the registry run `CREATE SCHEMA IF NOT EXISTS modulith`, create the table there and qualify its queries with it. That isn't a Flyway migration: the registry's `CREATE TABLE IF NOT EXISTS` script runs at every start.
- **`PublishedEvents` sees every event, registry or not.** `@ExtendWith(PublishedEventsExtension.class)` on a `@SpringBootTest` captures what a test publishes (`@ApplicationModuleTest` adds it by itself). That proves what is published and when, but not that it went through the registry, which is why the stand-in listener test exists too.
