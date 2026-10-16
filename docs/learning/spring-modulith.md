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
- **Spring Initializr's "Spring Modulith" option adds more than the core.** It also brings `spring-modulith-actuator` (a `/actuator/modulith` endpoint describing the modules) and the observability modules, which trace calls between modules once telemetry is on (#3).
