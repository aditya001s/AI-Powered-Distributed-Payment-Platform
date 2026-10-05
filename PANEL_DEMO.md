# AegisPay panel demonstration

This runbook turns the completed Phase 1–10 platform into a repeatable 10–15 minute evaluation. The payment rails, funding, email, and SMS are deliberately simulated; the databases, security checks, event delivery, saga, ledger, fraud workflow, analytics projection, and audit trails are real application behavior.

## 1. Prepare before the panel

Do this while internet access is reliable—not during the presentation. The first build downloads and builds 12 images and trains the deterministic synthetic-data ML model, so allow 20–40 minutes on a cold machine.

Requirements:

- Windows 10/11 with Docker Desktop using Linux containers and Compose v2.
- At least 8 GiB allocated to Docker; 10+ GiB is recommended.
- Roughly 15 GiB free disk for images, build caches, and volumes.
- Ports `5173`, `8000`, `8080`–`8089`, `5432`, `6379`, and `29092` available.
- Internet access for the first image/dependency download. JDK, Maven, Node, and Python are not required for the Compose demo.

From the repository root:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\panel-demo.ps1 prepare -TimeoutSeconds 1800
```

`prepare` performs all of the following:

1. Creates an untracked `.env`, generates infrastructure secrets, and configures local-only demo accounts.
2. Disables random provider/notification failures in a newly generated environment so the presentation is deterministic.
3. Builds and starts the complete Compose application stack.
4. Waits for every container and 13 HTTP readiness endpoints.
5. Creates customer and merchant INR wallets and funds the customer with simulated money.
6. Completes three baseline payments in Mumbai.
7. Submits an alternating far-away payment and verifies the actual outcome; if a cold Kafka consumer has not stored the geographic anchor yet, it retries from the opposite location. The prepared result is `ON_HOLD` from `RULE_IMPOSSIBLE_TRAVEL`, never a fixed-sleep assumption.
8. Verifies analytics projection, `RISK_HELD` saga state, a blocking fraud alert, balanced double-entry accounts, whole-ledger reconciliation, and exact wallet-total versus ledger-liability equality.
9. Saves untracked `.panel-demo.json` and `.panel-verification.json` evidence.

Successful preparation ends with three local account credentials and the browser URLs. Print them again with:

```powershell
.\scripts\panel-demo.ps1 credentials
```

Do not edit demo passwords after users have been seeded; existing demo users are intentionally not overwritten. If credentials and persistent volumes get out of sync, perform the explicitly destructive reset and prepare again:

```powershell
.\scripts\panel-demo.ps1 reset -Force
.\scripts\panel-demo.ps1 prepare -TimeoutSeconds 1800
```

## 2. Panel-day preflight

Start Docker Desktop at least 15 minutes early. If the stack was stopped but images and volumes were retained:

```powershell
.\scripts\panel-demo.ps1 start -NoBuild -TimeoutSeconds 900
.\scripts\panel-demo.ps1 verify
```

Keep these tabs open:

- Operations console: <http://127.0.0.1:5173>
- Aggregated API documentation: <http://127.0.0.1:8080/swagger-ui.html>
- Architecture and engineering decisions: [README.md](README.md)
- Latest local verification evidence: `.panel-verification.json`

`verify` must finish with `[PASS] Panel verification passed`. If it fails, run:

```powershell
docker compose ps
docker compose logs --tail 100 api-gateway auth-service payment-orchestrator fraud-service analytics-service
```

Do not rebuild or factory-reset during the panel slot. Preserve `.panel-verification.json` as the fallback evidence and explain the failing external prerequisite honestly.

## 3. Recommended 10–15 minute presentation

### 0:00–2:00 — Problem and architecture

Use the architecture diagram in `README.md`.

Say:

- “AegisPay is a simulated payment platform; it never contacts a real bank or sends real SMS/email.”
- “The system is ten Spring Boot services plus a Python model and React operations console.”
- “The critical invariants are integer minor-unit money, database-enforced balanced append-only ledger entries, transactional outboxes, and AI that can recommend but has no credential capable of moving money.”
- “The gateway is public; service APIs, model scoring, data stores, metrics, and traces remain internal.”

### 2:00–4:00 — Dashboard and eventual consistency

Log into the operations console with the operator account printed by `credentials`.

1. **Overview:** show completed and held payment counts, settlement amount, latency, and freshness.
2. Point out that every analytics response reports per-topic projection freshness. Kafka replay cannot inflate totals because analytics stores one fact per payment rather than incrementing counters.
3. **Payments:** open one completed baseline transaction. Explain that `POST /api/payments` returned `202`, then the Kafka-triggered saga reserved, scored, authorized, posted the ledger, captured, and completed it.

### 4:00–7:00 — Fraud hold and human authority

1. Open **Alerts**. The prepared blocking alert should be first.
2. Explain that the first payments came from Mumbai and the held payment came from New York seconds later with the same device. The deterministic impossible-travel rule floors the result at `HIGH/HOLD`; the model cannot lower it.
3. Open the alert detail and show score, evidence, rule summary, held duration, and mandatory analyst reason.
4. Optionally claim and **release** it with reason: `Panel demonstration: identity verified by analyst.`
5. State that the decision endpoint records an append-only human decision first; payment resumption is asynchronous, so the UI correctly says “will resume” rather than falsely claiming completion.

If you release the alert, a later `verify` will report that the original open-HOLD scenario is gone. Run `seed` after the panel if another open scenario is needed.

### 7:00–9:00 — Provider orchestration and resilience

Open **Providers** and show three simulated rails, routability, breaker/health state, fees, and historical share. Explain retry versus fallback:

- A known pre-send failure may retry.
- A timeout is not retried against the same provider because the charge may have succeeded remotely.
- Provider disable/enable requires ADMIN and a reason.
- A failed saga compensates the wallet hold; uncompensated effects have a dedicated critical alert.

### 9:00–11:00 — Ledger and APIs

Open Swagger and point to:

- `GET /api/ledger/admin/trial-balance?currency=INR`
- `GET /api/ledger/admin/reconcile`
- `GET /api/orchestration/payments/{paymentId}` using the ID in `.panel-demo.json`

The preparation script has already asserted:

- `differenceMinor = 0`
- `balanced = true`
- `accountsAgreeWithJournal = true`
- `walletLedgerStatus = MATCH`
- `walletLedgerDifferenceMinor = 0`
- whole-ledger `consistent = true`

Explain that ledger posting is completed before wallet capture, so money cannot move without a durable accounting entry.

### 11:00–13:00 — Phase 10 operational evidence

Show `.github/workflows`, `infrastructure/kubernetes/aegispay`, and `infrastructure/monitoring`.

- CI requires all 16 Docker-backed integration classes and all 432 integration executions; any missing report or skip fails CI. The current laptop run did not execute them because Docker Desktop was unavailable.
- CodeQL, dependency review, Gitleaks, Trivy source/image/Kubernetes scanning, Dependabot, and SPDX SBOM generation are configured.
- The Helm chart includes Secrets contracts, non-root/read-only-root containers, NetworkPolicies, probes, PDBs, Prometheus/Grafana, Tempo, and an OpenTelemetry Collector.
- The protected deployment workflow checks out a full commit SHA and runs atomic Helm upgrades.

See [docs/EVALUATION_EVIDENCE.md](docs/EVALUATION_EVIDENCE.md) for verified counts and exact boundaries.

## 4. Honest scope and limitations

Implemented and real:

- Authentication/RBAC, refresh rotation, idempotency, wallet reservations, saga state, outbox delivery, fraud rules/model integration, analyst decisions, RAG retrieval/citation checking, notification state/retries, analytics projections, and double-entry integrity controls.

Simulated or evaluation-only:

- Payment providers and deposits do not contact financial networks.
- Email/SMS transports do not contact external providers; the inbox/retry/dead-letter state machine is real.
- ML data is synthetic. Metrics prove pipeline behavior, not performance on a real fraud population.
- The default AI composer is deterministic/offline. A hosted compatible model is optional.
- Compose is the local application demo stack. Full Prometheus/Grafana/Tempo/OpenTelemetry deployment is provided through Helm; this final revision still needs a Docker-enabled Helm render and a real-cluster installation before deployment claims can be made.
- Bundled Kubernetes PostgreSQL/Redis/Kafka and observability are development/evaluation topologies, not production HA services.
- Analytics is single-reporting-currency and does not invent FX conversion.
- No customer-facing web/mobile app exists; customer operations are exposed through secured APIs/Swagger, while the React app is an operator console.
- Internal callers use distinct OAuth machine identities and Secret keys; deployments must rotate and scope those credentials through their secret manager.
- Commit-SHA image tags and detached SBOM artifacts are source-aligned but are not signed digest-bound provenance attestations.
- Consumer-group lag requires provider/exporter telemetry; existing alerts cover failed publication and per-service stalled outboxes.

These are boundaries of the completed ten-phase scope, not hidden claims of production certification.

## 5. Useful commands

```powershell
# Show command help
.\scripts\panel-demo.ps1 help

# Check container/scenario status
.\scripts\panel-demo.ps1 status

# Re-check all runtime and business invariants
.\scripts\panel-demo.ps1 verify

# Create a fresh set of transactions and one open fraud hold
.\scripts\panel-demo.ps1 seed

# Stop but retain data for the next rehearsal
.\scripts\panel-demo.ps1 stop

# Destructive clean slate
.\scripts\panel-demo.ps1 reset -Force
```
