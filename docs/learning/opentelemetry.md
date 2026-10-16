# OpenTelemetry

## The problem it solves here

The apps need to emit traces, metrics and logs without being tied to whichever backend stores them: Grafana LGTM locally today, something else when the system is deployed (M6). OpenTelemetry is the vendor-neutral standard for that: the apps speak its protocol, OTLP, and the backend is a configuration choice.

## Mental model

- **Three signals, one protocol.** Traces, metrics and logs all leave the app over OTLP (HTTP on port 4318 here).
- **Spring Boot's OpenTelemetry starter covers traces and metrics.** Micrometer observes every HTTP request and JDBC call; the starter turns those observations into spans and meters and exports them.
- **Logs take one extra piece.** Boot can export logs over OTLP, but the Logback appender that feeds it isn't included: each app declares it in `logback-spring.xml` and connects it to the `OpenTelemetry` bean at startup (`OpenTelemetryAppenderInitializer`).
- **Sampling decides which traces are kept.** Boot keeps 10% by default; `management.tracing.sampling.probability=1.0` keeps every one, which suits local work but will need lowering when a deployed environment arrives.

## Gotchas we hit

- **The appender's version must match Boot's OpenTelemetry API.** The newest appender (2.32.0-alpha) is built against API 1.66.0, but Spring Boot 4.1.1 manages API 1.62.0. We pinned 2.28.0-alpha, the release built against 1.62.0, once in the parent `pom.xml`, to avoid runtime method mismatches.
- **Including Boot's `base.xml` adds a file appender.** It wrote every app's logs, tests included, into one shared `/tmp/spring.log`. The apps include only Boot's defaults and console appender instead.
