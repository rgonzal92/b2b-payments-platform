# Grafana LGTM

## The problem it solves here

From M1 on, one Charge crosses two apps and several modules, and the 100 ms Authorization target (ADR-0010) has to be measured, not guessed. That needs three kinds of telemetry in one place: traces (where the time went), logs (what happened) and metrics (how often and how fast). `grafana/otel-lgtm` is a single container holding all of it, for local development only.

## Mental model

- **L-G-T-M:** **L**oki stores logs, **G**rafana shows everything, **T**empo stores traces, **M**imir/Prometheus stores metrics. An OpenTelemetry Collector in front receives OTLP on ports 4317 (gRPC) and 4318 (HTTP) and routes each signal to its store.
- **The apps only speak OTLP.** Spring Boot's OpenTelemetry starter exports traces and metrics, and a Logback appender exports logs. Nothing in the apps knows Grafana exists, so swapping the backend later changes configuration, not code.
- **Spring Boot's Docker Compose support wires it up.** It recognises the `grafana/otel-lgtm` service in `compose.yaml` and points the exporters at its ports; there are no OTLP endpoint properties.
- **One trace ID ties the signals together.** Every log line carries the trace and span ID of the request it belongs to, so a slow trace in Tempo leads straight to its log lines in Loki.

## Gotchas we hit

- **Logs need an appender Spring Boot doesn't install.** Boot exports logs over OTLP, but only once the OpenTelemetry Logback appender is added (`logback-spring.xml`) and connected to the `OpenTelemetry` bean at startup (`OpenTelemetryAppenderInitializer`, one per app).
- **The appender's version must match Boot's OpenTelemetry API.** The newest appender (2.32.0-alpha) is built against API 1.66.0, but Spring Boot 4.1.1 manages API 1.62.0. We pinned 2.28.0-alpha, the release built against 1.62.0, to avoid runtime method mismatches.
- **Metrics arrive once a minute.** The OTLP meter registry pushes every 60 seconds, so a fresh start shows traces and logs within seconds and metrics only after the first push.
- **Two apps, one stack.** By default Spring Boot stops the Compose stack when the app that started it shuts down, which would pull the database out from under the other app. Both apps set `spring.docker.compose.lifecycle-management=start-only`; `docker compose down` stops the stack.
- **Searching Tempo through its API needs a time range.** A TraceQL search without `start` and `end` returned no traces even though they were stored. Grafana's Explore view adds the range for you.
