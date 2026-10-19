{{/*
Helm's standard labels, used by every object in this chart and its subcharts. Call with a dict of the component's
name and the template's root context: {{ include "b2b-payments.labels" (dict "name" "kafka" "context" $) }}.
"b2b-payments.selectorLabels" is the part that selects pods: a Deployment's or StatefulSet's selector can't change
after install, so it holds only what never changes (name and release), never the chart's version.
*/}}
{{- define "b2b-payments.selectorLabels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .context.Release.Name }}
{{- end -}}

{{- define "b2b-payments.labels" -}}
{{ include "b2b-payments.selectorLabels" . }}
app.kubernetes.io/managed-by: {{ .context.Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .context.Chart.Name .context.Chart.Version }}
{{- end -}}
