# AegisPay observability

The Phase 10 Helm chart deploys a self-contained pull-metrics and distributed-tracing stack:

- **Prometheus** discovers annotated AegisPay Services in the release namespace and loads platform safety alerts.
- **Grafana** is provisioned with Prometheus and Tempo data sources plus the AegisPay Platform Overview dashboard.
- **OpenTelemetry Collector** accepts OTLP/HTTP on 4318 and OTLP/gRPC on 4317, applies memory limiting and batching, then exports to Tempo.
- **Tempo** retains searchable traces on persistent storage. The Collector is not treated as storage.

Metrics and traces are internal ClusterIP services. Access Grafana for local administration with:

```powershell
kubectl -n aegispay port-forward svc/aegispay-aegispay-grafana 3000:3000
```

The Grafana admin password comes from `GRAFANA_ADMIN_PASSWORD` in the runtime Secret. Do not expose Grafana or Prometheus through the public application Ingress without a separate authentication policy.

## Alert policy

Critical alerts cover uncompensated saga effects, compensation/recovery failure, ledger integrity controls, notification dead letters, durable event-delivery failure, refresh-token reuse, AI guardrail violations, model unavailability, and target loss. Warnings cover sustained model-degraded fraud decisions, missing notification templates, growing retry backlog, and elevated HTTP 5xx rates.

Metric names use Prometheus's Micrometer form (`aegis_saga_compensation_failed_total`), not Java's dot form. AI guardrail metrics intentionally use the code-authoritative names `aegis_ai_citation_hallucinated_total` and `aegis_ai_recommendation_rejected_total`.

Production installations should integrate Alertmanager routing and durable/object storage according to their cluster provider. The bundled Prometheus and single-binary Tempo are deliberately provider-neutral and suitable for a self-contained installation; they are not a substitute for multi-zone managed monitoring at large scale.
