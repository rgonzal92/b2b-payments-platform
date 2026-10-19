{{/*
One app's Deployment and Service, shared by the app subcharts (charts/platform and the rest): each renders it with
{{ include "b2b-payments.app" . }} from its own values. Helm shares named templates across a chart and its subcharts,
so the subcharts render only inside this chart (docs/learning/helm.md).
*/}}
{{- define "b2b-payments.app" -}}
{{- $name := .Chart.Name -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ $name }}
spec:
  replicas: {{ .Values.replicas | default 1 }}
  selector:
    matchLabels:
      app: {{ $name }}
  template:
    metadata:
      labels:
        app: {{ $name }}
    spec:
      # Kubernetes would add PLATFORM_PORT and the like for every Service, which Spring would bind to properties.
      enableServiceLinks: false
      {{- if .Values.hostFolders }}
      # It writes under var/ in the mounted repository, so it runs as you (global.hostUser).
      securityContext:
        runAsUser: {{ .Values.global.hostUser.uid }}
        runAsGroup: {{ .Values.global.hostUser.gid }}
      {{- end }}
      containers:
        - name: {{ $name }}
          # Built by spring-boot:build-image and imported by scripts/k3d-cluster.sh: never pulled.
          image: b2b-payments/{{ $name }}:local
          imagePullPolicy: Never
          ports:
            - name: http
              containerPort: {{ .Values.httpPort }}
            {{- with .Values.extraPorts }}
            {{- toYaml . | nindent 12 }}
            {{- end }}
          env:
            # Paketo's memory calculator caps Netty's direct buffers at 10M, which the Gateway and authorization-service
            # run out of in a few requests (OutOfDirectMemoryError, cut-off answers); it keeps a size set here and
            # takes it out of the heap instead.
            - name: JAVA_TOOL_OPTIONS
              value: -XX:MaxDirectMemorySize=64M
            {{- /* Values may name others, such as {{ .Values.global.repoRoot }}. */}}
            {{- range $key, $value := .Values.env }}
            - name: {{ $key }}
              value: {{ tpl $value $ | quote }}
            {{- end }}
          {{- with .Values.secret }}
          # Its secrets, from .env by scripts/k8s-secrets.sh, as the same variables it reads in development.
          envFrom:
            - secretRef:
                name: {{ . }}
          {{- end }}
          # Basic readiness from Spring Boot's probe group, so the Service sends nothing before the app has started.
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: http
            periodSeconds: 5
          resources:
            limits:
              # Paketo's memory calculator sizes the JVM's heap to fit this.
              memory: {{ .Values.memory | default "1Gi" }}
          {{- with .Values.hostFolders }}
          volumeMounts:
            {{- range . }}
            - name: {{ base . }}
              mountPath: {{ $.Values.global.repoRoot }}/{{ . }}
            {{- end }}
          {{- end }}
      {{- with .Values.hostFolders }}
      # Folders of the repository, mounted into the k3d node (scripts/k3d-cluster.sh), at the same path in the pod.
      # This holds on one node only (M6 design, Files and secrets).
      volumes:
        {{- range . }}
        - name: {{ base . }}
          hostPath:
            path: {{ $.Values.global.repoRoot }}/{{ . }}
            type: Directory
        {{- end }}
      {{- end }}
---
apiVersion: v1
kind: Service
metadata:
  name: {{ $name }}
spec:
  type: {{ .Values.service.type | default "ClusterIP" }}
  selector:
    app: {{ $name }}
  ports:
    {{- toYaml .Values.service.ports | nindent 4 }}
{{- end -}}
