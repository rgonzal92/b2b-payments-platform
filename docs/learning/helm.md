# Helm

## The problem it solves here

The stack on Kubernetes is some twenty objects (a Deployment or StatefulSet and a Service for each of nine components), most of them alike apart from a name, a port and some addresses. Applied as plain YAML they would be copied nine times and removed one by one. Helm renders them from templates and values, installs them as one named release, and upgrades or uninstalls the release as a whole: one `helm upgrade --install` brings up everything (M6 design, What runs where).

## Mental model

- **A chart is a folder of templates and default values;** `helm template` renders it to plain YAML, and `helm install` renders it and applies the result as a release, whose history Helm keeps in a Secret in the namespace. `helm upgrade` applies a new rendering and removes what it no longer contains; `helm uninstall` removes everything the release created, except volume claims made by a StatefulSet.
- **An umbrella chart bundles subcharts.** `deploy/helm/b2b-payments/` holds one subchart per app (`charts/platform`, `charts/authorization-service`, `charts/simulator`, `charts/gateway`) and one for the infrastructure (`charts/infra`). Each subchart gets the umbrella's values under its own name (`--set platform.memory=2Gi`) plus the shared `global` block (`global.hostUser`, `global.repoRoot`).
- **Templates are Go templates with Sprig functions.** `{{ .Values.x }}` reads a value; `range`, `with` and `if` loop and branch; `toYaml | nindent 4` drops a block of values in at an indent; `tpl` renders a value as a template itself, so an app's `env` can say `{{ .Values.global.repoRoot }}/var/clearing`.
- **Named templates are shared.** `templates/_app.tpl` defines `b2b-payments.app`, one app's Deployment and Service from its values; each app subchart's only template is `{{ include "b2b-payments.app" . }}`. Files starting with `_` render nothing on their own.
- **Values replace, they don't merge, inside lists.** A list in `--set` or a values file replaces the default list whole, so per-app settings that you might override one by one (`env`) are maps.

## Gotchas we hit

- **Helm 4 wants unpacked subcharts listed.** A chart under `charts/` that `Chart.yaml` doesn't name under `dependencies` fails `helm lint` with `chart metadata is missing these dependencies`. Listed by name and version, with no repository, Helm uses the folder as it is; there is nothing to `helm dependency build`.
- **Named templates live in one namespace across the chart and its subcharts.** That is what lets the app subcharts use the umbrella's `b2b-payments.app`, and also why they render only inside it: `helm template charts/platform` on its own finds no such template. Prefix every `define` with the chart's name, so two never collide.
- **Helm can't read files outside the chart.** `.Files.Get` sees only the chart's own folder, so the realm file and Grafana's provisioning can't become ConfigMaps without copying them; they reach the pods as hostPath volumes from the mounted repository instead, read where Compose reads them.
- **`--dry-run=server` checks against the real API.** `helm template` only proves the templates render; `helm install --dry-run=server` sends the objects to the API server for validation without creating them, which catches a wrong field or type.
