{{- define "aegispay.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "aegispay.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name (include "aegispay.name" .) | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{- define "aegispay.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
app.kubernetes.io/part-of: aegispay
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "aegispay.selectorLabels" -}}
app.kubernetes.io/part-of: aegispay
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/name: {{ .name }}
{{- end }}

{{- define "aegispay.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "aegispay.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{- define "aegispay.image" -}}
{{- $root := index . 0 -}}
{{- $repository := index . 1 -}}
{{- printf "%s/%s/%s:%s" $root.Values.global.image.registry $root.Values.global.image.namespace $repository $root.Values.global.image.tag -}}
{{- end }}

{{- define "aegispay.componentName" -}}
{{- $root := index . 0 -}}
{{- $component := index . 1 -}}
{{- printf "%s-%s" (include "aegispay.fullname" $root) $component | trunc 63 | trimSuffix "-" -}}
{{- end }}

{{- define "aegispay.runtimeSecret" -}}
{{- required "runtimeSecret.existingSecret is required; create it from secrets.template.yaml or an external secret manager" .Values.runtimeSecret.existingSecret -}}
{{- end }}

{{- define "aegispay.validateDeploymentProfile" -}}
{{- if eq .Values.global.deploymentProfile "production" -}}
  {{- if or .Values.infrastructure.postgresql.enabled .Values.infrastructure.redis.enabled .Values.infrastructure.kafka.enabled -}}
    {{- fail "production profile requires managed PostgreSQL, Redis, and Kafka (disable all bundled data-plane components)" -}}
  {{- end -}}
  {{- if ne .Values.infrastructure.postgresql.sslMode "verify-full" -}}
    {{- fail "production profile requires infrastructure.postgresql.sslMode=verify-full" -}}
  {{- end -}}
  {{- if not .Values.infrastructure.postgresql.rootCertificateKey -}}
    {{- fail "production profile requires a PostgreSQL root CA Secret key" -}}
  {{- end -}}
  {{- if not .Values.infrastructure.redis.sslEnabled -}}
    {{- fail "production profile requires Redis TLS" -}}
  {{- end -}}
  {{- if ne .Values.infrastructure.kafka.securityProtocol "SASL_SSL" -}}
    {{- fail "production profile requires Kafka SASL_SSL" -}}
  {{- end -}}
  {{- if or (not .Values.infrastructure.kafka.saslMechanism) (not .Values.infrastructure.kafka.saslJaasConfigKey) -}}
    {{- fail "production profile requires Kafka SASL mechanism and JAAS Secret key" -}}
  {{- end -}}
  {{- if eq (toString .Values.applicationConfig.allowUnassessedPayments) "true" -}}
    {{- fail "production profile forbids unassessed payments" -}}
  {{- end -}}
  {{- if eq (toString .Values.applicationConfig.demoSeedEnabled) "true" -}}
    {{- fail "production profile forbids demo identity seeding" -}}
  {{- end -}}
  {{- if not .Values.ingress.enabled -}}
    {{- fail "production profile requires ingress.enabled=true" -}}
  {{- end -}}
  {{- if or (contains ".example" .Values.global.publicGatewayUrl) (contains ".example" .Values.global.jwtIssuer) (contains ".example" .Values.ingress.host) -}}
    {{- fail "production profile must not use example public endpoints" -}}
  {{- end -}}
  {{- if eq .Values.global.image.namespace "your-organization" -}}
    {{- fail "production profile requires a real global.image.namespace" -}}
  {{- end -}}
  {{- if not (regexMatch "^[0-9a-f]{40}$" .Values.global.image.tag) -}}
    {{- fail "production profile requires global.image.tag to be a full lowercase 40-character source SHA" -}}
  {{- end -}}
{{- end -}}
{{- end }}