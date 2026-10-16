# Grafana LGTM

## The problem it solves here

From M1 on, requests cross two apps (an Invoice from the simulator's ERP role into the platform; Charges join in M2), and the 100 ms Authorization target (ADR-0010) has to be measured, not guessed. That needs traces (where the time went), logs (what happened) and metrics (how often and how fast) in one place. `grafana/otel-lgtm` is a single container holding all three, for local development only.

## Mental model

- **L-G-T-M:** **L**oki stores logs, **G**rafana shows everything, **T**empo stores traces, and a Prometheus server stores metrics (the "M" stands for Mimir, Grafana's production metrics store, which this image replaces with Prometheus).
- **An OpenTelemetry Collector sits in front.** It receives OTLP on ports 4317 (gRPC) and 4318 (HTTP) and routes each signal to its store. The apps only ever talk to the Collector (see `opentelemetry.md`).
- **One trace ID ties the signals together.** Every log line carries the trace and span ID of its request, so a slow trace in Tempo leads straight to its log lines in Loki.
- **Grafana opens without a login** at <http://localhost:3000>. That is one reason `compose.yaml` binds every port to 127.0.0.1.

## Gotchas we hit

- **Metrics arrive once a minute.** The apps push metrics every 60 seconds, so a fresh start shows traces and logs within seconds and metrics only after the first push.
- **Searching Tempo through its API needs a time range.** A TraceQL search without `start` and `end` returned no traces even though they were stored. Grafana's Explore view adds the range for you.
