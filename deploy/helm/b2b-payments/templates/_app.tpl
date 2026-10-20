{{/*
One app's Deployment and Service, shared by the app subcharts (charts/platform and the rest): each renders it with
{{ include "b2b-payments.app" . }} from its own values. Helm shares named templates across a chart and its subcharts,
so the subcharts render only inside this chart (docs/learning/helm.md).
*/}}
{{- define "b2b-payments.app" -}}
{{- $name := .Chart.Name -}}
{{- $labels := dict "name" $name "context" . -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ $name }}
  labels:
    {{- include "b2b-payments.labels" $labels | nindent 4 }}
spec:
  replicas: {{ .Values.replicas | default 1 }}
  selector:
    matchLabels:
      {{- include "b2b-payments.selectorLabels" $labels | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "b2b-payments.labels" $labels | nindent 8 }}
    spec:
      # Kubernetes would add PLATFORM_PORT and the like for every Service, which Spring would bind to properties.
      enableServiceLinks: false
      # Kubernetes kills whatever is left after this: the 5-second preStop pause, then Spring Boot's shutdown. Its
      # lifecycle phases stop one after another; the web server's graceful phase waits up to
      # spring.lifecycle.timeout-per-shutdown-phase (10 s below) for requests in flight. authorization-service's gRPC
      # server stops in a phase of its own before that, synchronously, for up to spring.grpc.server.shutdown.grace-period
      # (10 s, its values), which the phase timeout doesn't bound. 5 + 10 + 10 = 25 s, with a margin; requests finish
      # in milliseconds.
      terminationGracePeriodSeconds: 40
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
              value: -XX:MaxDirectMemorySize=64M{{ with .Values.javaToolOptions }} {{ . }}{{ end }}
            # Boot's default is 30 s per phase; see terminationGracePeriodSeconds.
            - name: SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE
              value: 10s
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
          # Spring Boot's probe groups (M6 design, Two copies of each app). Readiness, whether the Service sends the
          # pod traffic, leaves the database out: during a database outage every pod keeps answering, with fast errors,
          # rather than the Service emptying. Liveness, whether Kubernetes restarts the container, is the process alone. The
          # startup probe holds liveness off until the app first answers, so a slow start isn't killed for it.
          startupProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
            periodSeconds: 5
            failureThreshold: 36
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: http
            periodSeconds: 5
          # Kubernetes takes a stopping pod out of its Services while it sends SIGTERM, and the two race: the pause
          # lets every client stop sending here before Spring Boot's graceful shutdown starts refusing new requests.
          # (The buildpack image has no shell, so Kubernetes sleeps, not the container.)
          lifecycle:
            preStop:
              sleep:
                seconds: 5
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
  labels:
    {{- include "b2b-payments.labels" $labels | nindent 4 }}
spec:
  type: {{ .Values.service.type | default "ClusterIP" }}
  {{- if .Values.service.headless }}
  # Headless: the name resolves to each ready pod's address rather than one virtual IP, so a client that balances
  # (grpc-java's round_robin over dns:///) connects to every pod.
  clusterIP: None
  {{- end }}
  selector:
    {{- include "b2b-payments.selectorLabels" $labels | nindent 4 }}
  ports:
    {{- toYaml .Values.service.ports | nindent 4 }}
{{- end -}}
