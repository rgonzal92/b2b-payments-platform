# OpenTelemetry

## The problem it solves here

The apps need to emit traces, metrics and logs without being tied to whichever backend stores them: Grafana LGTM locally today, something else when the system is deployed (M6). OpenTelemetry is the vendor-neutral standard for that: the apps speak its protocol, OTLP, and the backend is a configuration choice.

## Mental model

- **Three signals, one protocol.** Traces, metrics and logs all leave the app over OTLP (HTTP on port 4318 here).
- **Spring Boot's OpenTelemetry starter covers traces and metrics.** Micrometer observes every HTTP request and JDBC call; the starter turns those observations into spans and meters and exports them.
- **Logs take one extra piece.** Boot can export logs over OTLP, but the Logback appender that feeds it isn't included: each app declares it in `logback-spring.xml` and connects it to the `OpenTelemetry` bean at startup (`OpenTelemetryAppenderInitializer`).
- **A trace crosses a process boundary in a header.** The caller writes the W3C `traceparent` header (`00-<trace ID>-<parent span ID>-<flags>`) into its HTTP request, gRPC metadata or Kafka message, and the receiver starts its span as a child of it. Micrometer's observations do both sides: Spring's HTTP clients and servers and Spring gRPC always, Spring Kafka's template and listeners once `spring.kafka.template.observation-enabled` and `spring.kafka.listener.observation-enabled` are on. A Charge's trace runs from the simulator's Network through `authorization-service` to the platform's consumers (#96).
- **An outbox breaks the chain unless it stores the context.** The service writes an event to its `outbox` table on the gRPC call and sends it later, from a scheduler thread where the call's trace is long gone. So the row keeps the `traceparent` it was written under, and the relay reads it back as a consumer reads a header, making it the parent of the send.
- **The current trace lives in a thread-local, and Reactor hops threads.** On the service's reactive hot path, the outbox insert runs on an R2DBC thread, not the gRPC thread that held the trace. `spring.reactor.context-propagation=auto` has Reactor restore thread-locals from the subscriber's context on every step, and `contextCapture()` at the point the bridge subscribes puts the trace into that context; drop either and the row records the wrong trace.
- **Sampling decides which traces are kept.** Boot keeps 10% by default; `management.tracing.sampling.probability=1.0` keeps every one, which suits local work but will need lowering when a deployed environment arrives.

## Gotchas we hit

- **The appender's version must match Boot's OpenTelemetry API.** The newest appender (2.32.0-alpha) is built against API 1.66.0, but Spring Boot 4.1.1 manages API 1.62.0. We pinned 2.28.0-alpha, the release built against 1.62.0, once in the parent `pom.xml`, to avoid runtime method mismatches.
- **Turning tracing export off turns propagation off.** Spring Boot 4 configures the W3C propagator only while `management.tracing.export.enabled` is true, so with it false spans are still made but no header is written or read, and every hop starts a new trace. The e2e harness turns off just the OTLP exporter (`management.tracing.export.otlp.enabled=false`).
- **Only Docker Compose support tells an app where `otel-lgtm` is for traces and logs.** Metrics default to `localhost:4318`; traces and logs have no default endpoint. The platform and simulator get all three from Docker Compose support; `authorization-service` has none, so its `application.properties` names the trace and log endpoints.
- **A frequent `@Scheduled` task is a trace every run.** The outbox relay's 100 ms schedule made ten empty traces a second; an `ObservationPredicate` drops that task's observation, and each row's send still runs in its own trace.
- **Including Boot's `base.xml` adds a file appender.** It wrote every app's logs, tests included, into one shared `/tmp/spring.log`. The apps include only Boot's defaults and console appender instead.
