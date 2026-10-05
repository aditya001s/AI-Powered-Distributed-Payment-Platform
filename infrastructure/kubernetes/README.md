# AegisPay on Kubernetes

Phase 10 packages the complete platform as the `aegispay` Helm chart. It deploys twelve application workloads, a development-sized PostgreSQL/pgvector + Redis + single-node Kafka data plane, and an optional Prometheus/Grafana/Tempo/OpenTelemetry stack.

## Security boundary

The chart never creates credentials. Copy `secrets.template.yaml`, replace every `CHANGE_ME` value, and create the Secret out of band—or use External Secrets/Sealed Secrets. Never commit the rendered Secret. The contract has one PostgreSQL password per service database, a separate bundled-database administrator password, distinct OAuth client credentials for each outbound service, Redis/ML/AI/Grafana credentials, JWT keys, and managed-service TLS/SASL material.

```powershell
kubectl create namespace aegispay
kubectl apply -n aegispay -f .\infrastructure\kubernetes\secrets.yaml
helm upgrade --install aegispay .\infrastructure\kubernetes\aegispay `
  --namespace aegispay --create-namespace --atomic --wait --timeout 15m
```

The bundled PostgreSQL initializer creates a distinct `<database>_user` owner for each of the nine databases; application pods receive only their own username/password. Managed PostgreSQL must provision the same roles and grants. Increment `runtimeSecret.version` on every Secret rotation so Helm rolls credential consumers, including bundled PostgreSQL and Redis plus auth signing-key consumers.

For an existing bundled PostgreSQL PVC, changing Secret data alone cannot change credentials already stored in PostgreSQL. Before updating the Secret, connect through an administrator-only channel and run `ALTER ROLE <database>_user PASSWORD '<new value>';` for every rotated application role and `ALTER ROLE aegis_admin PASSWORD '<new value>';` when rotating the administrator. Then update the Secret and increment `runtimeSecret.version`; Helm restarts the data plane and application consumers. Rotate Redis by updating `REDIS_PASSWORD` and incrementing the same version. The bundled data plane is development-only, so production credential rotation must use the managed providers' transactional rotation procedures.

Production deployments must set `global.deploymentProfile: production`, disable the bundled data plane, and supply reviewed environment values for managed PostgreSQL, Redis, and Kafka. The chart itself rejects a production profile with bundled data services, disabled Ingress, example endpoints, placeholder image namespace, or a non-SHA image tag; the protected workflow applies the same policy before kubeconfig installation. External PostgreSQL uses `verify-full` and a Secret-mounted CA by default; Redis TLS is enabled; Kafka uses SASL/TLS with a Secret-backed JAAS configuration and an optional PKCS#12 truststore. The bundled Kafka topology is intentionally one broker/controller and is suitable for development and evaluation, not fault-tolerant production.

Example external values (endpoints and Secret contents are environment-specific):

```yaml
infrastructure:
  postgresql:
    enabled: false
    host: postgres.internal
    sslMode: verify-full
    rootCertificateKey: postgres-ca.crt
  redis:
    enabled: false
    host: redis.internal
    sslEnabled: true
  kafka:
    enabled: false
    bootstrapServers: broker-1.internal:9092,broker-2.internal:9092
    securityProtocol: SASL_SSL
    saslMechanism: SCRAM-SHA-512
    saslJaasConfigKey: KAFKA_SASL_JAAS_CONFIG
ingress:
  enabled: true
  host: pay.example.com
global:
  publicGatewayUrl: https://pay.example.com
  jwtIssuer: https://pay.example.com
```

The protected deployment workflow requires each GitHub Environment to provide `HELM_VALUES_B64` (the base64-encoded reviewed values file), `KUBECONFIG_B64`, and the documented namespace/release/Secret variables. It passes the selected environment into `global.deploymentProfile`; production policy is therefore enforced by both the chart and workflow.

## Image contract

The chart expects source-bound commit-SHA tags under:

```text
<global.image.registry>/<global.image.namespace>/<service>:<global.image.tag>
```

The image workflow publishes all ten JVM services, `ml-service`, and `frontend` under that convention only after CI, Security, and CodeQL succeed for the same SHA. The deploy workflow checks out that exact SHA. Each image is scanned before publication and has a 90-day SPDX SBOM workflow artifact. Commit-SHA tags are source-bound but OCI tags remain registry-mutable; environments requiring cryptographic artifact identity must pin registry digests and enforce signed provenance/SBOM attestations in registry policy. The frontend bundle uses same-origin `/api` requests, so the same built image is promotable across environments; Ingress routes those requests directly and nginx provides an internal `API_UPSTREAM` proxy for Compose/direct Service access.

## Probes, isolation, and telemetry

JVM liveness uses `/actuator/health/liveness`; readiness uses `/actuator/health/readiness`. ML uses `/health` and `/health/readiness`; frontend uses `/healthz`. Liveness never includes shared dependencies. Kafka is deliberately excluded from post-start readiness so a broker outage does not remove every API replica; Prometheus instead alerts immediately on publish failures and when pending outbox events stop draining for ten minutes. JVM traces are exported to the chart's OTel Collector over OTLP/HTTP, and Prometheus discovers annotated Services without exposing actuator paths through Ingress. Application, data-plane, and monitoring pods are non-root, capability-dropped, read-only-root where supported, and protected by namespace-scoped ingress NetworkPolicies.
