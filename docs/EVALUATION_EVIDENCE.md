# AegisPay evaluation evidence

Evidence captured on **2026-09-17** from the current workspace. The workspace has no `.git` directory, so results cannot be tied to a commit SHA; preserve CI artifacts after pushing the final revision. This is an evaluation aid, not payment-industry certification.

## Current-revision executed results

| Area | Command/evidence | Result |
|---|---|---|
| Java unit/structural/contract | `.\mvnw.cmd -B -ntp clean test -DskipITs` plus XML aggregation | **1,378 passed** across 71 Surefire reports; 0 failures, errors, skips |
| Python ML | `.\.venv\Scripts\python.exe -m pytest` in `ml-service` | **129 passed**; one Starlette/httpx deprecation warning |
| React/TypeScript | `npm run typecheck`, `npm test -- --run`, `npm run build` | Typecheck passed; **120 passed** across 5 files; production bundle built |
| Modified-module regression runs | targeted Maven `test -DskipITs` | Passed after fixing stale mocks and one nested-type import |
| PowerShell panel helper | parser plus `help` smoke | Passed |
| Compose model | generated untracked `.env`, `docker compose config`, JSON assertions | Passed: 15 services, 9 distinct DB users, 7 OAuth clients, loopback gateway; `.env` deleted afterwards |
| Workflows | YAML parse | 5/5 syntactically parsed |
| Documentation/configuration | links, JSON, stale references, disabled tests, unfinished markers, policy assertions | Passed |
| Independent safety review | narrow source review after remediation | No remaining blocker/high issue found in saga, migration, or production-policy fixes |

The complete Java run deliberately used `-DskipITs` because Docker was unavailable. This is an explicit exclusion, not a silent skip; generated Surefire XML proves zero skips among executed tests.

## Current-revision blocked results

Docker Desktop’s Linux engine was unavailable at `npipe:////./pipe/dockerDesktopLinuxEngine`. Therefore the following are **not claimed as passed for this revision**:

| Blocked check | Expected gate when Docker is available |
|---|---|
| Java Testcontainers/Failsafe | 16 reports, **432 executions**, 0 failures/errors/skips |
| V4/V6 Flyway migrations against PostgreSQL | Fresh install plus historical citation backfill/trigger restoration |
| Full Compose startup and panel scenario | 15 healthy containers, 13 HTTP endpoints, login/wallet/payment/fraud/AI/notification/analytics/ledger journey |
| Wallet-versus-ledger live reconciliation | `MATCH`, difference 0 after event convergence |
| Container image builds/smokes | JVM, ML model/runtime, frontend nginx |
| Helm lint/render/kubeconform | default, managed-data, ingress, production-negative matrix, Secret template |
| Prometheus/Grafana | config, 16 rules, dashboard JSON from rendered chart |
| Trivy configuration/image scan | no HIGH/CRITICAL accepted by release gate |
| actionlint | all five workflows; local binary absent and container could not run |
| PostgreSQL bootstrap shell runtime | 9 DB owners/databases, extensions, cross-role denial |

Bash/WSL was also unavailable, so the new PostgreSQL bootstrap shell could not receive a local `bash -n`; its runtime belongs in the Docker gate.

## Required Docker completion command

Start Docker Desktop using Linux containers, then run:

```powershell
.\mvnw.cmd -B -ntp clean verify
```

CI additionally parses Surefire/Failsafe XML and fails unless every unit test has zero skips and all 16 integration reports contain exactly 432 executions with zero failures/errors/skips.

For assembled integration and evaluation data:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\panel-demo.ps1 prepare -TimeoutSeconds 1800
.\scripts\panel-demo.ps1 verify
```

The helper creates untracked `.panel-demo.json` and `.panel-verification.json`; the verification must show authentication roles, analytics visibility, an open impossible-travel hold, `RISK_HELD`, balanced ledger, whole-ledger consistency, and wallet/ledger liability difference zero.

## Previously verified but historical evidence

Before this final remediation, 429 Docker-backed Java integration executions, representative JVM/ML/frontend images, frontend live nginx, Helm lint/render matrices, Prometheus/Grafana, Trivy, actionlint, and bundled PostgreSQL runtime/initializer smokes passed. Those results demonstrate the baseline but are **not substituted for rerunning the changed current revision**.

## External controls that cannot be proven locally

The repository configures CodeQL (Java, JavaScript/TypeScript, Python), dependency review, Gitleaks, Trivy, Dependabot, image scanning, SPDX SBOM artifacts, same-source-SHA publication gates, and protected atomic Helm deployment. A local workspace cannot prove hosted run conclusions, GitHub Environment approval rules, registry immutability, or cloud credentials. Show commit-linked GitHub Actions and security artifacts after pushing.

No live Kubernetes cluster, managed PostgreSQL/Redis/Kafka, production ingress/TLS, backup/restore system, Alertmanager receiver, real provider rail, SMTP/SMS provider, or real customer dataset was supplied.

## Three invariants to demonstrate

1. **Money uses integer minor units.** No floating-point balance arithmetic.
2. **Accounting is append-only and balanced.** Trial balance, journal replay, and wallet-versus-ledger liability comparison expose discrepancies without auto-repair.
3. **AI cannot move money.** Investigation has no credential accepted by internal write APIs; only an attributed human decision resumes or rejects a held payment.
