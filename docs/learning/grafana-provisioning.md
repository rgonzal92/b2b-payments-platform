# Grafana provisioning

## The problem it solves here

M4 has to show how Authorization, Payments and Clearing are doing, and raise an alert when Authorization misses its 100 ms target (ADR-0010). A dashboard built by clicking in Grafana lives only in that container's database: `docker compose down` deletes it, nobody can review it, and the next developer starts with an empty Grafana. Provisioning makes the dashboards and the alert rule files in the repository that Grafana loads at startup, so they are versioned, reviewed in pull requests and the same on every machine.

## Mental model

- **Provisioning folders.** At startup Grafana reads YAML files from `conf/provisioning/<kind>/`: `datasources/`, `dashboards/`, `alerting/` and more. In `grafana/otel-lgtm` that is `/otel-lgtm/grafana/conf/provisioning/`; the image already puts its own data sources (Prometheus, Tempo, Loki, Pyroscope, with the UIDs `prometheus`, `tempo`, `loki`) and its sample dashboards there.
- **A dashboard provider points at JSON files.** `grafana/provisioning/dashboards.yaml` is a provider: "load every `.json` in `/otel-lgtm/b2b-payments-dashboards` into the folder B2B Payments, and look again every 30 seconds". The dashboards themselves are Grafana's own dashboard JSON, one file each, in `grafana/dashboards/`.
- **An alert rule is a query, a reduction and a condition.** `grafana/provisioning/alerting.yaml` holds one rule group, evaluated every minute. Query `A` asks Prometheus for `Authorize`'s p99 over the last 5 minutes, `B` reduces it to its last value, and `C` is true when that is above 100. The rule is *pending* while `C` is true and *firing* once it has stayed true for `for: 5m`. Without a contact point it only shows in **Alerting > Alert rules**.
- **How the files reach Grafana:** `compose.yaml` mounts `grafana/dashboards/` as a folder and the two YAML files one by one, all read-only. Mounting each file on its own, rather than our folder over `conf/provisioning/dashboards/`, keeps the image's own providers and data sources.
- **A panel's query names Prometheus metrics, not Micrometer meters.** Micrometer's `authorization.answers` counter reaches Prometheus over OTLP as `authorization_answers_total`, and its `response.code` tag as the label `response_code`; a timer such as `payments.steps` becomes `payments_steps_milliseconds_bucket`, `_count` and `_sum`. `GrafanaProvisioningTests` checks that every query names one of the metrics the platform exports.

## Editing a dashboard

1. Start the stack (`docker compose up -d lgtm` is enough to look) and open the dashboard. Change it in the UI as usual.
2. Grafana won't save over a provisioned dashboard (`allowUiUpdates: false`): choose **Export > Export as JSON** (or **Save dashboard**, which then offers the JSON to copy) and keep the export for sharing *externally* switched off, so the data source stays `{"type": "prometheus", "uid": "prometheus"}`.
3. Replace the file in `grafana/dashboards/` with it, keeping the `uid` (the URL `/d/<uid>` and the alert rule's `dashboardUid` depend on it). Within 30 seconds Grafana reloads the file.
4. Run `./mvnw -B -pl platform -am test -Dtest=GrafanaProvisioningTests -Dsurefire.failIfNoSpecifiedTests=false`.

A new dashboard is a new file in `grafana/dashboards/` with a `uid` of its own. Grafana reads alerting files when it starts, unlike dashboards, so after changing the rule run `docker compose restart lgtm`.

## Gotchas we hit

- **A counter's first increment is invisible to `rate()` and `increase()`.** They work on the difference between samples, and a series that first appears with the value 1 has no earlier sample to subtract, so one imported Clearing File showed as zero. Each counter is registered at zero for every tag value when the platform starts.
- **A provisioned time series query must say it is a range query.** Panels whose targets set neither `"range": true` nor `"instant": true` showed "No data" in Grafana 13, though the same query returned data through the API. The UI sets one when you build a panel; hand-written JSON has to. The test checks every target sets exactly one.
- **The rule's folder and the dashboards' folder are matched by title.** With a fixed `folderUid` in the dashboard provider, the alerting file created its own "B2B Payments" folder first, and Grafana ended up with two of that name. The provider names the folder by title only.
- **A percentile needs histogram buckets.** Micrometer exports a timer's count, sum and maximum only, unless `management.metrics.distribution.percentiles-histogram.<meter>` is on; `grpc.server` turns it on, with a bucket at exactly 100 ms (`slo`), so the alert compares against a real bucket edge rather than an interpolation.
- **The alert fires 5 to 10 minutes after Authorization slows down, not 5.** The p99 is computed over the last 5 minutes of data, since a shorter window holds too few of the once-a-minute samples, and that p99 must then stay above 100 ms for another 5 minutes (`for: 5m`). The two windows add up.
- **Metrics arrive once a minute**, so `$__rate_interval` is at least 4 minutes: a short burst shows as a short stretch of points, and the "in the time range" panels are the ones to read after a demo.
- **Tests don't export metrics.** Spring Boot's test support turns every exporter off; `-Dspring.test.metrics.export=true -Dmanagement.otlp.metrics.export.step=5s` on a test run sends its metrics to a running `lgtm`, which is how the dashboards were first checked.
