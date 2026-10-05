# AegisPay

**AI-Powered Payment Orchestration & Fraud Intelligence Platform**

A cloud-native, event-driven payment platform built as a Maven multi-module
monorepo: ten deployable JVM applications (including the API gateway) on Java 25, coordinated through Kafka,
with double-entry accounting, ML fraud scoring and RAG-based AI fraud
investigation.

> **Simulation only.** This platform never contacts a real bank, card network or
> UPI switch. Payment providers are deliberately simulated so that failure,
> latency and fallback behaviour can be exercised on demand.
>
> **Presenting this project?** Follow [PANEL_DEMO.md](PANEL_DEMO.md) for one-command
> Windows setup, deterministic transactions, a fraud hold, automated integrity checks,
> a timed walkthrough, and honest evaluation boundaries.

---

## Build status

| | |
|---|---|
| **Current phase** | Phase 10 — Platform Operations ✅ |
| **Modules** | 13 code modules (10 deployable JVM applications including gateway + shared kernel + Python ML + React dashboard) |
| **Build** | `BUILD SUCCESS` — all modules compile on JDK 25; dashboard type-checks and builds |
| **Tests (current revision)** | **1,378 Java unit/structural**, **129 Python**, and **120 TypeScript** passed with zero skips/failures; **432 Docker-backed Java integration executions are configured but blocked locally because Docker Desktop is not running** |
| **Stack** | Java 25 · Spring Boot 4.0.8 · Spring Cloud 2025.1.3 · PostgreSQL 17 · pgvector 0.8 · Redis 7.4 · Kafka 3.9 (KRaft) · Python 3.12 · FastAPI · scikit-learn · React 19 · TypeScript 7 · Vite 8 |

> **⚠️ Behaviour change in Phase 6.** A payment can now stop and wait for a human. The saga
> has a new persisted state, `RISK_HELD`, and a held payment's funds stay reserved until an
> analyst decides. See [The held state, and why the sweep must ignore
> it](#the-held-state-and-why-the-sweep-must-ignore-it).

> **⚠️ API contract change in Phase 5.** `POST /api/payments` now returns **`202 Accepted`**
> with status `CREATED`, not `201` with `COMPLETED`. The payment is accepted, not finished.
> Poll `GET /api/payments/{paymentId}` or wait for the notification. See
> [The API contract changed, and it had to](#the-api-contract-changed-and-it-had-to).

The project is being built in ten phases. Phase 1 delivered the foundation: module
structure, shared kernel, all ten service skeletons, infrastructure, migrations,
OpenAPI, health checks, structured logging and centralised error handling. Phase 2
delivered authentication and authorisation end-to-end, including a transaction-boundary
audit that found and fixed three inoperative security controls (see
[Transaction boundaries](#transaction-boundaries)). Phase 3 delivered wallets,
deposits and withdrawals, the reserve/capture/release primitives, the payment state
machine, two-layer idempotency and payment events (see
[Phase 3 — Payments & Wallet](#phase-3--payments--wallet)). Phase 4 delivered the
double-entry journal that is the platform's book of record — append-only and always
balanced, both enforced by the database rather than by convention (see
[Phase 4 — Ledger](#phase-4--ledger)). Phase 5 moved payment execution out of
payment-service and into a saga that routes each charge across three simulated payment
providers, retries, falls back, breaks circuits and compensates — which is also what turned
the payment API asynchronous (see
[Phase 5 — Payment Orchestration](#phase-5--payment-orchestration)). Phase 6 replaced the
saga's auto-approved risk verdict with a real one: a Python FastAPI service scores a
calibrated fraud probability from a gradient-boosted model, a deterministic rule engine covers
the archetypes that model measurably misses, and the two combine into APPROVE / REVIEW / HOLD / REJECT
(see [Phase 6 — Fraud Intelligence](#phase-6--fraud-intelligence)). Phase 7 explains those
verdicts: a retrieval-augmented service pulls the fraud policy that applies to a flagged payment
out of a pgvector store and writes a cited narrative, with every citation checked against what
was actually retrieved — and no credential that would let it act on its own conclusion (see
[Phase 7 — AI Investigation](#phase-7--ai-investigation)). Phase 8 tells the customer: email, SMS and an
in-app inbox, de-duplicated so at-least-once event delivery cannot produce two "payment successful"
messages, and with a delivery retry queue of its own because a slow mail host must not be allowed to
stall a Kafka partition (see [Phase 8 — Notifications](#phase-8--notifications)). Phase 9 gives the
operators somewhere to look: a read model that keeps **one row per payment** and derives every total on
demand — so at-least-once delivery cannot inflate a figure — and a React console that shows the risk
distribution, the settlement latency tail and a queue of payments waiting on a human decision, with every
screen stating how far behind the projection is (see
[Phase 9 — Admin Dashboard](#phase-9--admin-dashboard)).

See [Roadmap](#roadmap) for the complete ten-phase delivery history.

---

## Architecture

```
                          ┌──────────────┐
                          │   React SPA  │  operations console
                          └──────┬───────┘
                                 │ HTTPS
                    ┌────────────▼─────────────┐
                    │       api-gateway        │  :8080  WebFlux
                    │  JWT auth · rate limit   │  the only public port
                    │  correlation ID · routes │
                    └────────────┬─────────────┘
                                 │
   ┌──────────────┬──────────────┼──────────────┬──────────────────┐
   │              │              │              │                  │
┌──▼───────┐ ┌────▼─────┐ ┌──────▼──────┐ ┌─────▼──────┐ ┌─────────▼────────┐
│   auth   │ │  wallet  │ │   payment   │ │   fraud    │ │    analytics     │
│  :8081   │ │  :8082   │ │   :8084     │ │   :8086    │ │     :8089        │
│ issues   │ │ balances │ │ public API  │ │ rules + ML │ │ read models      │
│ all JWTs │ │ + holds  │ │ 202 + idem. │ │ verdict    │ │ admin dashboards │
└──────────┘ └────▲─────┘ └──────┬──────┘ └──┬───┬───┘ └──────────────────┘
                  │              │           │   │
                  │ reserve      │ payment-  │   │ score (X-API-Key)
                  │ capture      │ created   │   ▼
                  │ release      │           │ ┌────────────────────────┐
                  │              │           │ │   ml-service  :8000    │
                  │              │           │ │ Python · FastAPI       │
                  │              │           │ │ calibrated probability │
                  │              │           │ │ + ablation reasons     │
                  │              │           │ │ MEASURES, never decides│
                  │              │           │ │ NO public gateway route│
                  │              │           │ └────────────────────────┘
                  │  ┌───────────▼───────────▼────────────┐
                  │  │            Apache Kafka            │
                  │  │  payment-created · fraud-checked   │
                  │  │  fraud-decision-applied            │
                  │  │  payment-approved/rejected         │
                  │  │  payment-completed/failed          │
                  │  │  wallet-movement-recorded          │
                  │  │  notification-required  (+ .DLT)   │
                  │  └────┬───────────┬───────────┬───────┘
                  │       │           │           │
             ┌────┴───────▼──┐ ┌──────▼──────┐ ┌──▼──────────────┐
             │  orchestrator │ │   ledger    │ │  notification   │
             │     :8085     │ │   :8083     │ │     :8088       │
             │ saga · routing│─▶│ double-entry│ │ email/SMS (sim) │
             │ retry·fallback│  │  journal    │ │ in-app · retry  │
             │ breaker·recovery│└─────────────┘ └─────────────────┘
             └───────┬───────┘   ▲
                     │           └── posted BEFORE the capture, synchronously,
                     │               so money never moves unrecorded
                     │
                     ├── assess risk, synchronously ──▶ fraud-service :8086
                     │   APPROVE/REVIEW → continue
                     │   HOLD → park at RISK_HELD, extend the wallet hold,
                     │           wait for fraud-decision-applied
                     │   REJECT → compensate
                     │
        ┌────────────▼─────────────┐   ┌──────────────────────────┐
        │ SWIFTPAY · NEXUSPAY      │   │ ai-investigation  :8087  │
        │ PAYBRIDGE   (SIMULATED)  │   │ RAG · pgvector · LLM     │
        │ no real rail is contacted│   │ recommends, never acts   │
        └──────────────────────────┘   └──────────────────────────┘

  Infrastructure:  PostgreSQL 17 (database per service) · Redis 7.4 · Kafka 3.9
```

### Why it is shaped this way

**Database per service.** Nine separate databases, not one shared schema. A
service cannot read another's tables even by accident, and each owns its own
migration history. Cross-service reporting is built from the event stream by
`analytics-service` rather than by joining across boundaries.

**Asymmetric JWT.** `auth-service` is the only token issuer. It signs with an RSA
private key and publishes the public half at `/.well-known/jwks.json`; every other
service validates against that endpoint. No other service holds signing material,
so compromising one cannot mint an admin token — which is exactly the risk a shared
HMAC secret would carry.

**Money as integer minor units.** Every amount is a `long` count of paise, never a
`double`. Binary floating point cannot represent `0.01` exactly, so accumulating it
loses money. See `Money` in the shared kernel.

**Transactional outbox.** Services never write to the database and then publish to
Kafka — a crash between the two either loses the event or announces something that
rolled back. Instead the event is inserted into an `outbox_events` row in the *same
transaction*, and a poller drains it to Kafka. One database, one commit, no
distributed transaction.

**Deny by default.** Every security chain ends in `anyRequest().authenticated()`,
so a newly added endpoint is protected until someone deliberately opens it.

**Orchestration has exactly one trigger.** payment-orchestrator starts a saga from the
`payment-created` event and from nothing else — there is no endpoint that initiates a payment and
no endpoint that edits a saga's state. One way in means there is no second path to keep
consistent with it, and no way for an operator to park a payment in a state the machine forbids.

**The model measures; the platform decides.** `ml-service` returns a calibrated probability and
per-feature explanations, and nothing else — no risk level and no APPROVE/REVIEW/HOLD/REJECT. Bucketing a
probability needs the deterministic rules as well as the model, and only `fraud-service` has both.
It also means retuning a threshold is an environment change reviewed by a fraud team rather than a
model redeploy with two services moving in step.

---

## Technology

| Layer | Choice | Note |
|---|---|---|
| Language | **Java 25** (LTS) | Virtual threads enabled; workload is I/O-bound |
| Framework | **Spring Boot 4.0.8** | Spring Framework 7, Jakarta EE 11, **Jackson 3** |
| Gateway | **Spring Cloud Gateway 5.0** (2025.1.3) | Reactive WebFlux |
| Persistence | **PostgreSQL 17** + Hibernate 7.2 | Flyway owns the schema; `ddl-auto=validate` |
| Cache / locks | **Redis 7.4** (Lettuce) | Idempotency fast path, distributed locks, rate limiting |
| Messaging | **Kafka 3.9** (KRaft) | `acks=all` + idempotent producer; per-topic `.DLT` |
| Resilience | **Resilience4j 2.4** | Used *programmatically*, not via the starter (see below) |
| ML service | **Python 3.12 · FastAPI · pydantic 2** | Measurement only; no database, no state |
| Model | **scikit-learn `HistGradientBoostingClassifier`** + isotonic `CalibratedClassifierCV` | Same family as LightGBM, already a dependency, native NaN handling — and no native wheel to build. Named `fraud_hgb`, because claiming LightGBM would be false |
| Vector search | **pgvector** | Fraud-policy retrieval for AI investigation |
| Docs | **springdoc-openapi 3.0.3** | Swagger UI per service, aggregated at the gateway |
| Observability | Micrometer → Prometheus, OpenTelemetry → OTLP | Structured JSON logs with trace + correlation IDs |
| Dashboard | **React 19 · TypeScript 7 · Vite 8** | `strict` plus `noUncheckedIndexedAccess`; charts are hand-drawn SVG, no chart library, so the scaling arithmetic is testable |
| Dashboard serving | **nginx 1.27-alpine**, unprivileged | Static assets only. No Node in the runtime image: once built the app is a directory of files |
| Tests | JUnit 5, AssertJ, Mockito, **Testcontainers 2.0**, **Vitest 5** + Testing Library | Integration tests run against real Postgres/Kafka |

<details>
<summary><b>Why Resilience4j is wired programmatically rather than via its Spring Boot starter</b></summary>

The annotation-based starter resolves circuit breakers by name from YAML. A typo in
`@CircuitBreaker(name = "providerA")` silently creates a *brand-new* breaker with
default settings instead of failing — the protection appears to be there and is not.
Building the registries in a `@Configuration` class makes every threshold explicit,
unit-testable without a Spring context, and impossible to mistype into existence. It
also allows one breaker per payment provider to be created dynamically as providers
register, which annotations cannot express.

</details>

---

## Quick start

### Prerequisites

For the full Compose demo, only **Docker Desktop with Compose v2** is required. The standard profile benefits from 8+ GiB allocated to Docker. On an **8-GB total-RAM PC**, use the complete constrained profile in [docs/LOW_MEMORY_DOCKER.md](docs/LOW_MEMORY_DOCKER.md), which caps all 15 containers at 4.98 GiB and builds/starts them serially in stages. Keep approximately 15 GiB disk free. JDK 25, Node 22, and Python 3.12 are needed only when building/testing those components directly on the host.

Low-memory setup:

```powershell
.\scripts\low-memory.ps1 config
.\scripts\low-memory.ps1 build
.\scripts\low-memory.ps1 start -TimeoutSeconds 1200
```

### Panel-ready Windows setup

The supported evaluation path creates an untracked `.env`, generates local infrastructure secrets, starts every service, waits for readiness, and prepares deterministic dashboard data:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\panel-demo.ps1 prepare -TimeoutSeconds 1800
```

Run the cold build before the evaluation; it builds twelve images and trains the deterministic ML artifact. On panel day, use cached images and retained volumes:

```powershell
.\scripts\panel-demo.ps1 start -NoBuild
.\scripts\panel-demo.ps1 verify
.\scripts\panel-demo.ps1 credentials
```

See [PANEL_DEMO.md](PANEL_DEMO.md) for the timed walkthrough, prepared fraud scenario, fallback evidence, and limitations.

### Manual Compose startup

```powershell
Copy-Item .env.example .env
# Replace every CHANGE_ME value in .env.
docker compose up -d --build
docker compose ps
```

All Compose ports are loopback-only; the application edge is **http://127.0.0.1:8080**. The operations console is at **http://127.0.0.1:5173**. Sign in with the `ADMIN`/`FRAUD_ANALYST` account from the generated `.env`.

### Verify it is alive

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/readiness
Invoke-RestMethod http://127.0.0.1:8081/.well-known/jwks.json
.\scripts\panel-demo.ps1 verify
```

The helper checks every service endpoint plus authentication, analytics projection, the open fraud hold, saga state, trial balance, and whole-ledger reconciliation.

### Build and test locally

```powershell
.\mvnw.cmd clean test -DskipITs       # 1,378 current unit/structural tests
.\mvnw.cmd clean verify                # adds 432 Testcontainers executions; Docker required
.\mvnw.cmd -pl platform-common test
.\mvnw.cmd -pl auth-service -am test
```

The Python ML service has its own suite, which needs no Docker either:

```powershell
Push-Location ml-service
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m pytest  # 129 tests
Pop-Location
```

So does the dashboard. `npm run build` runs `tsc --noEmit` first, so a type error fails the build rather
than shipping a bundle that renders `undefined` where a number should be:

```bash
cd frontend
npm ci                            # exact lockfile versions; fails if the manifest disagrees
npm run typecheck
npm test                          # 120 tests
npm run build
```

Integration tests (`*IT.java`) are annotated `@Testcontainers(disabledWithoutDocker =
true)`, so a machine without a container runtime **skips** them and the build still
passes. They are not optional in CI, which provides Docker: they assert committed
database state and are the only thing that can catch a transaction-rollback bug, a
double-spend under concurrency, or a stranded fund reservation.

> **Current Docker verification status.** This final revision’s **16 integration classes / 432 executions** were not run locally because Docker Desktop’s Linux engine was unavailable. They are not counted as passed. CI runs `clean verify`, parses every Failsafe XML report, and fails unless all 16 classes, all 432 executions, and zero skips are present. The immediately preceding revision had 429/429 Docker-backed executions pass; this final pass added concurrent-refresh and wallet idempotency crash-window integration cases, so the current revision still requires a Docker-enabled CI/local run before release.
>
> The current dashboard suite ran in full under jsdom: 120 tests cover the API client,
> refresh serialization, chart scaling, freshness states, and approve/block workflow.

Integration tests authenticate through real RS256 tokens rather than a mocked security
context. `platform-common` publishes a **test-jar** containing
`com.aegispay.common.testsupport`: `TestJwkServer` serves a JWKS over loopback HTTP and
`TestJwtIssuer` signs tokens against it, so each service validates signature, issuer and
expiry through its own production resource-server chain. That is what lets the suite
assert that a forged token, an expired token, or a service token missing
`scope=internal` is actually refused — none of which `@WithMockUser` can detect, because
it bypasses token validation entirely.

### Stop

```bash
docker compose down          # keep data
docker compose down -v       # also delete database/Kafka volumes
```

---

## Repository layout

```
aegispay/
├── platform-common/                 shared kernel — depended on by every service
│   └── src/main/java/com/aegispay/common/
│       ├── money/                   Money: integer minor units, exact arithmetic
│       ├── domain/                  PaymentStatus state machine, RiskLevel, FraudDecision
│       ├── events/                  EventEnvelope, KafkaTopics, all event payloads
│       ├── kafka/                   codec, publisher, DLQ error handler, de-duplication
│       ├── outbox/                  transactional outbox entity, recorder, drainer
│       ├── lock/                    Redis distributed lock (SET NX + Lua compare-and-delete)
│       ├── idempotency/             request fingerprinting, idempotency guard
│       ├── security/                JWT conversion, roles, service-to-service tokens
│       ├── resilience/              Resilience4j registries
│       ├── web/                     correlation filter, global exception handling
│       └── observability/           metric name constants
│       └── src/main/resources/
│           ├── aegis-defaults.yml   shared config imported by every service
│           └── logback-spring.xml   JSON logs with trace/correlation IDs
│
├── api-gateway/                :8080  reactive edge — routing, auth, rate limiting
├── auth-service/               :8081  identities, JWT issuance, refresh rotation, JWKS
├── wallet-service/             :8082  balances and fund reservations
├── ledger-service/             :8083  immutable double-entry journal
├── payment-service/            :8084  public payment API (202), idempotency, state machine
├── payment-orchestrator/       :8085  saga coordinator; routing, retry, fallback, breakers,
│   └── src/main/java/com/aegispay/orchestrator/
│       ├── domain/                   PaymentSaga, SagaStatus machine, SagaStep, ProviderAttempt
│       ├── provider/                 3 SIMULATED rails, gateway, router, health monitor
│       ├── service/                  SagaOrchestrator (no transactions), mutation writer,
│       │                             recovery sweep, provider kill switch
│       ├── client/                   wallet + ledger HTTP clients with error translation
│       └── messaging/                the single entry point: payment-created
├── fraud-service/              :8086  rule engine + ML scoring, alerts, analyst workflow
│   └── src/main/java/com/aegispay/fraud/
│       ├── domain/                   FraudAssessment (immutable), FraudAlert (has a lifecycle),
│       │                             AnalystDecision (append-only), PaymentObservation
│       ├── repository/               the feature pipeline, expressed as SQL
│       ├── rules/                    8 deterministic rules with stable ids; escalate-only
│       ├── service/                  FeatureBuilder, RiskVerdict (where APPROVE/REVIEW/HOLD/REJECT is
│       │                             decided), scoring orchestration, mutation writer, queries
│       ├── client/                   MlScoringClient — short timeout, breaker, pinned wire names
│       ├── messaging/                builds the read model from payment events
│       └── api/                      /internal/fraud (saga) + /api/fraud (analysts)
├── ml-service/                 :8000  Python 3.12 / FastAPI — MEASUREMENT ONLY
│   ├── app/
│   │   ├── dataset.py                synthetic generator with bidirectional label noise
│   │   ├── features.py               the feature contract, shared by training and scoring
│   │   ├── model.py                  HistGradientBoosting + isotonic calibration
│   │   ├── train.py                  runs at IMAGE BUILD time, not on boot
│   │   ├── scoring.py                one batched predict_proba; ablation explanations
│   │   └── main.py                   /score, /model, /health, /metrics; X-API-Key auth
│   ├── models/                       the baked artifact + its metrics and provenance
│   └── tests/                        129 tests, incl. the pinned wire contract
├── ai-investigation-service/   :8087  RAG investigation narratives (recommends only)
├── notification-service/       :8088  email / SMS (simulated) + a real in-app inbox,
│                                     with its own delivery retry queue and DLQ
├── analytics-service/          :8089  read-model projections for the admin dashboard
│   └── src/main/java/com/aegispay/analytics/
│       ├── domain/                   PaymentFact (one row per payment; every setter is a
│       │                             merge), StatusRank (the out-of-order guard), StreamState
│       ├── repository/               every aggregate, as native SQL. FILTER for the tiles,
│       │                             date_trunc for the series, percentile_cont for latency
│       ├── service/                  AnalyticsWindow + Granularity (validation and bucket
│       │                             alignment), PaymentProjectionService (the only writer),
│       │                             AnalyticsQueryService (all read-only)
│       ├── messaging/                7 topics consumed; nothing published
│       └── api/                      /api/admin/analytics — GET only, by design
│
├── frontend/                   :5173  React + TypeScript operations console
│   └── src/
│       ├── api/                      types (hand-written, nullability copied faithfully),
│       │                             client (one refresh per 401), endpoints
│       ├── auth/                     AuthProvider: access token in memory, refresh token in
│       │                             sessionStorage, concurrent refreshes collapsed
│       ├── components/               Layout, DataTable, KpiTile, StatusBadge,
│       │                             FreshnessBanner, WindowPicker
│       │   └── charts/               scale.ts — the arithmetic, tested; LineChart, BarChart
│       ├── hooks/useLoader.ts        loading / failed / ready, with request cancellation
│       ├── format.ts                 money from minor units via BigInt; absent is not zero
│       └── pages/                    Overview, Payments, Risk, Alerts (approve/block),
│                                     Providers, Notifications
│
├── infrastructure/
│   ├── docker/
│   │   ├── Dockerfile.service            one parameterised multi-stage build for all 10
│   │   ├── Dockerfile.frontend           node build → unprivileged nginx
│   │   ├── nginx/dashboard.conf          SPA fallback, cache rules, CSP
│   │   └── postgres/00-init-service-databases.sh  creates 9 isolated owners/databases + extensions
│   ├── kubernetes/
│   │   ├── aegispay/                     Phase 10 Helm chart (apps + data + observability)
│   │   └── secrets.template.yaml         required Secret keys, never real credentials
│   └── monitoring/README.md              dashboards, alerts and tracing operations
│
├── .github/
│   ├── workflows/                        CI, CodeQL, security, images and atomic deploy
│   └── dependabot.yml                    Maven, pip, npm, Docker and Actions updates
│
│  (ml-service has its own Dockerfile: it is a Python image, and it trains the model
│   during the build rather than at startup.)
│
├── docker-compose.yml
├── .env.example
└── pom.xml                      aggregator; pins the whole dependency matrix
```

### One Dockerfile, ten images — plus one for Python and one for the dashboard

`ml-service` and `frontend` are the two services that are not a *java-service in Compose, and
deliberately so in both cases.

`ml-service` has no Postgres, Redis or Kafka dependency at all. The model is a pure function of its
request — `fraud-service` owns the state, the read model and the decision — so attaching a database would
only invite somebody to put state in it.

`frontend` needs none of them either: once built it is a directory of static assets. Its only dependency is
the gateway, and that is at runtime in the operator's browser rather than from the container, which is why
it depends on `service_started` and not `service_healthy` — the login page renders perfectly well while the
gateway is still coming up, and the browser retries. It is published on loopback only, unlike the gateway: a
console that lists every payment on the platform and can release a held one should not be reachable from the
network just because Compose is running on a shared machine.


`infrastructure/docker/Dockerfile.service` is parameterised by a `MODULE` build
argument. Ten near-identical Dockerfiles would drift — a JVM flag or hardening step
added to one and forgotten in the others is invisible in review. It builds as
non-root, uses a JRE (not JDK) runtime, sizes the heap from the *container* limit via
`MaxRAMPercentage`, and uses the exec-form entrypoint so the JVM receives `SIGTERM`
directly and Spring Boot's graceful shutdown actually drains in-flight requests.

---

## Security

Implemented in Phase 1–2:

- **Password hashing** — BCrypt cost 12 via `DelegatingPasswordEncoder`, so hashes
  are algorithm-prefixed (`{bcrypt}$2a$12$…`) and a future migration is possible
  without invalidating existing passwords.
- **Password policy** — 12-character floor, mixed case, digit, symbol, plus a
  deny-list that also catches decorated variants (`Password123!` is rejected, not
  just `password`).
- **No account enumeration** — every login failure returns an identical 401
  whatever the cause, and the unknown-email path performs a dummy hash comparison
  so response timing does not reveal whether an account exists.
- **Account lockout** — temporary, with a decaying failure counter, so a
  credential-stuffing attempt against a victim's email cannot be used to
  permanently lock them out.
- **Refresh token rotation with reuse detection** — tokens are single-use and
  stored only as SHA-256 hashes. Replaying a consumed token is treated as theft and
  revokes the entire session family.
- **Service-to-service auth** — OAuth2 client credentials, not a static shared API
  key: short-lived, attributable to a named client, validated by the same JWKS
  machinery as user tokens.
- **`/internal/**` unreachable from outside** — the gateway has *no route* for it
  and additionally denies the path outright.
- **Append-only audit trail** — every authentication decision recorded with its
  correlation ID.
- **Secrets from the environment only** — nothing hardcoded, `.env` git-ignored,
  and startup fails loudly on a blank secret.

Roles: `USER`, `MERCHANT`, `FRAUD_ANALYST`, `ADMIN`, `SERVICE`.

Added in Phase 5:

- **The operational saga surface is staff-only, by method security.** Every handler on
  `/api/orchestration/**` carries `@PreAuthorize`, and that is doing real work rather than
  duplicating a chain rule: the shared chain auto-restricts `/api/admin/**`, and these paths are
  not under it because the gateway routes to this service by prefix. Without it a customer token
  could read every payment's saga — counterparties, amounts and provider routing across the whole
  platform. Reading a saga and disabling a payment rail are also separate privileges: analysts get
  the first, admins the second.
- **The simulation surface is protected twice.** `POST /internal/providers/{id}/simulate` can make
  a payment rail start failing on demand. The gateway has no route for `/internal/**` and denies
  the path outright, *and* every handler requires a client-credentials token with `ROLE_SERVICE`.
  Worth two independent controls even in a sandbox.

Added in Phase 6:

- **The fraud console is staff-only, and the data behind it is more sensitive than the payments.**
  Every handler on `/api/fraud/**` carries `@PreAuthorize`, for the same reason as the saga
  surface: the shared chain auto-restricts `/api/admin/**` and these paths are not under it.
  Without it, any authenticated customer could read every other customer's risk score, device
  fingerprint, IP and coordinates — data the customer never knowingly provided, which makes it a
  worse leak than the payment records themselves.
- **`ml-service` has no public route at all.** The gateway publishes nothing for it, and the model
  is reachable only from `fraud-service` inside the network. An exposed scoring endpoint would let
  anyone map the model's decision boundary by probing it, which is the single cheapest way to make
  a fraud model evadable.
- **`ml-service` authenticates with a static API key, not a bearer token.** It is a Python service
  with no place in the platform's OAuth2 trust chain, and attaching an internal bearer token would
  be misleading about how it is actually secured — as well as leaking that token to a service that
  has no use for it. An empty `ML_API_KEY` logs a warning at startup and leaves scoring
  unauthenticated, which is acceptable on a laptop and not in a shared environment.
- **The analyst identity always comes from the token.** `decidedBy` on an `AnalystDecision` is read
  from the validated JWT and never from a request field. "Who authorised this" is the first
  question asked about a released payment that turned out to be fraud, and an answer the caller
  could choose for itself would be worthless. `FraudTransactionBoundaryTest` asserts that no
  handler even accepts such a parameter.
- **The audit trail is append-only at the database.** A trigger refuses `UPDATE` and `DELETE` on
  `analyst_decisions`, and every column is `updatable = false` so Hibernate never generates one.
  The reason a customer's payment was released or refused must not be rewritable through any route,
  including psql.
- **Risk signals are personal data and are not rendered to staff by default.** The saga stores
  device, IP and coordinates in `payment_sagas.risk_signals` so a resumed payment scores the right
  context, but `SagaView` deliberately omits the column: the orchestration console is read by staff
  investigating other people's payments, and nothing on it needs that data.

Added in Phase 7:

- **The AI service holds no credentials, and that is the security control.** With
  `aegis.service-auth.enabled: false` there is no token `ai-investigation-service` could present to
  any internal write API, so it cannot move money, refuse a payment or restrict an account even if
  a future handler tried to. Naming a recommendation carefully is a convention; holding no
  credential is a guarantee. `AiTransactionBoundaryTest` scans the service's fields so a wallet,
  ledger, payment or fraud client cannot be added later without the test failing, and
  `AiPropertiesBindingTest` asserts the flag is `false` in the shipped configuration.
- **The recommendation vocabulary is not configurable.** It is a compiled enum plus a database
  `CHECK`, deliberately not an environment variable, because it is the one field a prompt injection
  targets — an extendable list would be a config change away from a value some consumer treats as a
  command. No value reads as an instruction: the most severe is
  `RECOMMEND_REJECT_AND_REVIEW_ACCOUNT`, and it crosses the wire as a `String` rather than an enum.
- **What leaves the platform is bounded, and the default is that nothing does.** The prompt carries
  the payment id, amount, feature values, device and location facts and customer-supplied text. It
  carries **no** customer name, email, phone number or wallet id — those are not in the evidence
  pack, which is why the pack is assembled from the event rather than by fetching the customer
  record. The default generator makes no outbound call at all, so a deployment that has not
  consciously accepted sending payment data to a model provider is not doing it because a config
  file shipped that way. The hosted generator uses a dedicated request factory rather than the
  shared internal client, which would otherwise attach the platform's own service token to a
  third-party request.
- **The investigation surface is staff-only and rate-limited as an admin surface.** Every handler on
  `/api/investigations/**` requires ADMIN or FRAUD_ANALYST. The gateway applies admin limits rather
  than customer ones, which matters because the on-demand `POST` is the only endpoint on the
  platform where one request can trigger a paid language-model call.
- **The investigation audit trail is append-only at the database.** Triggers refuse `UPDATE` and
  `DELETE` on `investigations`, `investigation_citations` and `investigation_reviews`. A citation set
  that could be edited after the fact would let a hallucinated citation be quietly removed, which is
  precisely the record worth keeping.

Added in Phase 8:

- **A customer's inbox is scoped by their token, not by a parameter.** No endpoint on
  `/api/notifications/**` names a user, so there is nothing for a client to change. That matters more here
  than it looks: a notification carries an amount, a payment reference and a free-text note written by a
  counterparty, so a broken scope would leak a conversation as well as a transaction.
- **Reading somebody else's notification returns 404, not 403.** A 403 would confirm that the id exists and
  belongs to someone, which is more than the caller is entitled to learn from an id they should not have.
- **Customer-controlled text is defanged before it is put in front of another customer.** A payment
  description written by the payer is delivered to the payee under the platform's own name — the one path
  here where one customer can address another. Links are stripped from every interpolated value, newlines
  are flattened so a note cannot forge a new section of an email, and subjects are flattened because a
  newline in a subject is header injection in any real mail transport.
- **Variables whose names look like credentials are redacted on the way in.** Not merely before rendering:
  a value that was only un-rendered would still be in the database and in the admin API response, which are
  read by more people than the message is.
- **Delivery addresses are masked everywhere they are displayed or logged** — in application logs, in the
  transport's own error messages (which are stored as `last_error` and returned by the admin API), and in
  the customer's own preferences response. The customer already knows their number; returning it in full
  only puts it in every cache and proxy log that handles the response.
- **The admin surface sits behind three independent controls.** It lists which customers were not reached
  and can re-send messages to them, so it is under `/api/admin/**` — restricted by `GatewaySecurityConfig`,
  restricted again by the shared security chain, and carrying its own class-level `@PreAuthorize`.
- **SMS cannot be enabled without a number and a consent timestamp**, enforced by a database CHECK rather
  than by application code alone. An SMS to somebody who did not opt in is a regulatory problem, not a bug
  to be found in review.
- **What a customer was told is append-only at the database.** Triggers refuse UPDATE and DELETE on
  `notifications` and on the attempt trail. "We did notify them" is a claim made in a dispute, and it is
  worth nothing if the row backing it could have been written afterwards.

Added in Phase 9:

- **The bucket unit is a closed enum, because it is interpolated into SQL.** PostgreSQL will not bind the
  unit of an interval literal, so `date_trunc(:unit, …)` takes its value from the statement text. A
  caller-supplied string there is an injection point no parameter binding protects. `Granularity` has four
  constants, and Spring rejects anything else at request binding with a 400 before a query is built.
- **The dashboard's route gate is usability, not security, and the code says so.** Every route it hides
  calls an endpoint the gateway restricts to `ROLE_ADMIN` or `ROLE_FRAUD_ANALYST` and the owning service
  checks again. Hiding a link stops somebody being shown a page that would answer every request with a 403;
  it is not what keeps them out.
- **analytics-service has no write endpoint and no service client**, and a structural test fails the build
  if `AnalyticsController` ever declares a `POST`, `PUT`, `PATCH` or `DELETE`. An analyst's decision belongs
  in the append-only trail of the service that raised the alert, attributed to their own token; proxying it
  through a reporting service would add a hop to the chain of custody of a record that has to stand up to
  being audited.
- **The platform-wide payment list omits the payment description and every risk signal.** The description is
  free text written by a customer, and the signals are device and location data the customer never knowingly
  handed to a person. This list is read by staff looking at other people's payments, and neither is needed to
  do that. fraud-service shows the signals where there is a specific reason to.
- **The access token never leaves memory in the browser.** It opens every admin endpoint, and a copy in
  `localStorage` would be readable by any injected script for its whole lifetime and would survive a reload
  after the operator walked away. The refresh token is in `sessionStorage` — tab-scoped, cleared when the
  browser closes, which matters on a shared operations machine.
- **One token refresh per 401, never a loop.** A loop turns a revoked session into a request storm against
  the auth service: a self-inflicted outage that is also indistinguishable from an attack. Concurrent
  refreshes collapse into a single promise, because the tokens rotate and the second caller would spend an
  already-invalidated one.
- **The dashboard cannot be framed.** `X-Frame-Options: DENY` plus `frame-ancestors 'none'`. This console can
  release or reject a held payment, and a framed copy is how a click on another site becomes a click on a
  decision button here. `Referrer-Policy: no-referrer` because a URL here can contain a payment id.
- **The CSP names the gateway as the only permitted `connect-src`.** The application talks to exactly one
  origin, so an injected script cannot exfiltrate to a third party even if it manages to run. Two
  relaxations are documented rather than hidden: `style-src 'unsafe-inline'` for the SVG charts' inline
  attributes, and `img-src data:` for inline SVG.
- **Nothing secret can be configured into the dashboard.** Vite inlines every `VITE_*` variable into the
  bundle, so anything passed at build time is readable by anyone who opens it. The application needs no
  secret: it exchanges the operator's own credentials for a token at runtime, and both the Dockerfile and
  `.env.example` say so at the point where somebody would be tempted.

### Transaction boundaries

A review of the auth flows found three security controls that were **written and then
discarded by transaction rollback**. All three followed the same shape — record the
security event, then throw to reject the request — and Spring rolls back on any
`RuntimeException`, so the record was undone. Each failed on *every* attempt, not
intermittently, and each failed invisibly: audit rows are written in their own
transaction, so the logs reported an outcome the database did not have.

| Control | Symptom before the fix |
|---|---|
| Account lockout | `failed_login_attempts` never advanced past 0, so accounts never locked. Unlimited password guesses. |
| Refresh-token reuse detection | A replayed token returned 401 but the family stayed `ACTIVE`, so a thief's other tokens kept working. |
| Disabled-account refresh | Sessions were never actually revoked. |

The fix is structural: security-state writes are delegated to `LoginAttemptService` and
`TokenRevocationService`, whose `REQUIRES_NEW` transactions commit independently of
whatever the caller throws next. Both live on **separate beans**, because Spring applies
transaction advice through a proxy and a self-invocation would silently bypass it —
which is exactly how the original reuse-detection bug arose, from a private method
called on `this`.

Two related changes came out of the same review:

- **BCrypt no longer runs inside a transaction.** Login previously held a
  `SELECT ... FOR UPDATE` row lock across ~250 ms of password hashing, so concurrent
  logins for one account serialised — a cheap denial-of-service against a single user.
  Hashing now happens with no transaction open; the row lock covers only the counter
  update. `AuthService` is deliberately non-transactional and orchestrates short
  transactional collaborators.
- **Every `@Modifying` bulk query now flushes and clears.** A JPQL bulk `UPDATE`
  bypasses the persistence context, so without `flushAutomatically` a later entity
  flush could overwrite the rows it had just modified, and without `clearAutomatically`
  already-loaded entities kept serving stale pre-update state.

`TransactionBoundaryTest` enforces all of this structurally — it asserts the annotations
themselves, runs in milliseconds with no infrastructure, and fails immediately if the
original shape is reintroduced. That matters because this class of bug is invisible to a
unit test with mocked repositories: the mock records the call regardless of whether the
database keeps it.

Phase 3 applies the same shape. `WalletService` and `PaymentService` are
**non-transactional orchestrators**; every durable write lives on `WalletMutationService`
or `PaymentMutationService`. `WalletTransactionBoundaryTest` (19 tests) and
`PaymentTransactionBoundaryTest` (16 tests) guard it structurally, and additionally assert
that `PaymentService` holds no transaction at all — it makes HTTP calls, and a
transaction spanning them would pin a database connection across a network round trip
until the pool emptied.

Phase 5 raises the stakes and `SagaTransactionBoundaryTest` (37 tests) covers it.
`SagaOrchestrator` holds **no transaction anywhere**: four of its six steps are network calls
and one of them is allowed three seconds. Two further properties are asserted there because
neither fails loudly:

- `SagaMutationService.beginStep`, `stepFailed` and `recordProviderAttempt` use
  `Propagation.REQUIRES_NEW`. These write the audit trail, and if they joined the caller's
  transaction a rollback would take the record of the failure with it — leaving the saga with no
  explanation of why it failed, which is precisely the state nobody can debug.
- Batch queries that lock more than one row impose an `ORDER BY`. Two replicas sweeping
  concurrently and locking in different orders would deadlock; ordering by a stable column means
  they always contend on the same row first, so one simply waits.

Phase 6 repeats the pattern in `fraud-service`, where `FraudTransactionBoundaryTest` (36 tests)
asserts that `FraudScoringService` holds no transaction — it makes an HTTP call to `ml-service`, and
a transaction spanning it would pin a database connection across the network until the pool
emptied. It also asserts what cannot be checked any other way without a database: that the alert
lock exists and is bounded, that every verdict field on an assessment is `updatable = false`, and
that the analyst-decision repository declares no modifying query.

Phase 7 repeats it twice more in `ai-investigation-service`, where the call being spanned is longer
still: `AiTransactionBoundaryTest` (24 tests) asserts that `InvestigationService` holds no
transaction, because generation can be a twenty-second model call and every write lives in
`InvestigationMutationService` behind a real bean boundary. `KnowledgeBaseSeeder` is the same shape —
it loops over documents computing an embedding for each — which is where the **self-invocation trap
caught this project for the second time**: `seedOne` was `@Transactional protected` and called as
`this.seedOne(...)`, so Spring's proxy was bypassed and every seed write ran untransacted with no
symptom at all. Extracting `PolicyWriter` as its own bean is what makes the annotation take effect.

Phase 8 applies the same split in `notification-service`, where the call being spanned is a mail send.
`NotificationTransactionBoundaryTest` (13 tests) asserts that `NotificationDeliveryService` and
`DeliveryRetryWorker` declare **no** `@Transactional` anywhere — a transaction around an SMTP timeout would
empty the connection pool during exactly the outage it was trying to record — and that every write on
`NotificationMutationService` has one. It also asserts that the four per-attempt recorders use
`REQUIRES_NEW`, which is load-bearing rather than decorative: the caller loops over a claimed batch of
fifty, and one delivery's failure must not roll back the outcomes already recorded for the other
forty-nine. `beginAttempt` is the most important of them — if the process dies mid-send the incremented
count must already be committed, or a delivery that crashes the JVM on every attempt would be retried
forever, with each restart looking like a first try.

The same test asserts the *opposite* property where it applies: the mutable columns on a delivery must stay
mutable. A copy-pasted `updatable = false` on the status would silently stop every retry being recorded —
the row would be claimed, attempted, and left exactly as it was.

---

## Phase 3 — Payments & Wallet

### Money is an integer, everywhere

Every amount is a `BIGINT` count of **minor units** (paise). Never `double`, which cannot
represent `0.01` exactly, and never `NUMERIC`, which invites accidental fractional
arithmetic. `Money` is the only way to construct one and it refuses mixed-currency
operations.

On the wire, amounts are accepted as a decimal string and returned **both** ways —
`amount` and `amountMinor`. `amountMinor` is authoritative: `JSON.parse` maps every
number to a double, so a JavaScript client that reads `amount` can be a paise out.

### Two balance columns, not one

```
balance_minor   everything the customer owns
held_minor      the part reserved by in-flight payments
available       = balance_minor - held_minor      ← what a payment is checked against
```

Reservations are tracked separately rather than by debiting early. Debiting on
initiation and crediting back on failure would show the customer a phantom
debit-and-refund pair for every abandoned payment. Three `CHECK` constraints make the
invariants unpersistable rather than merely enforced in Java:
`balance_minor >= 0`, `held_minor >= 0`, and `held_minor <= balance_minor`. The last one
is what makes a double-spend impossible to commit even if an application check were
bypassed.

A withdrawal is validated against **available**, so it cannot spend money already
promised to a payment in flight.

### Holds are rows, so reserve is idempotent by construction

`wallet_holds` has one row per reservation with `UNIQUE (payment_id)`. A retried
`reserve` therefore *cannot* create a second hold — idempotency comes from the schema,
not from the caller remembering to pass a key.

A bare "reserved amount" counter was rejected: it cannot tell which payment reserved
what, so capturing payment A would discharge whatever happened to be reserved at that
moment, and two payments releasing out of order would silently corrupt the total with
nothing in the data able to detect it afterwards.

Every hold carries a mandatory `expires_at`, and `WalletHoldSweeper` releases anything
still `ACTIVE` past it. Without that, one crashed orchestrator permanently reduces a
customer's usable balance and the only remedy is manual database surgery.

### Capture is one transaction across both wallets

`captureAndTransfer` debits the payer and credits the payee in a **single** transaction.
Two separate calls would leave a window in which the money existed in neither wallet, and
a crash inside that window would destroy customer funds with no record of where they
went.

Both wallet rows are locked with `SELECT ... FOR UPDATE` in **sorted wallet-ID order**.
That is the whole reason the code sorts rather than locking source-then-destination:
simultaneous A→B and B→A transfers would each hold the row the other needs, and
PostgreSQL would kill one as a deadlock victim. A consistent global lock order makes a
cycle impossible. `WalletReservationIT` fires exactly that crossing pair and requires
both to succeed.

### Idempotency has two layers, and only one of them is the guarantee

| Layer | Role | May fail? |
|---|---|---|
| Redis (`SET NX PX`) | answers a replay in microseconds, absorbs duplicate bursts | **yes** — fails open |
| PostgreSQL `PRIMARY KEY (scope, idempotency_key)` | concurrent claim and stored response authority | no |
| Money-operation natural key | survives a commit after the generic response record is lost | no |

A second natural key closes the commit boundary the generic response table cannot make atomic across two transactions. Wallet deposits/withdrawals store `operation_key` on the immutable movement row, and refunds derive and store a deterministic `refund_id`. If money committed but completion of `idempotency_keys` did not, a retry recovers that business row rather than applying the movement again. Reusing the key with different business data remains a conflict.

`IdempotencyStore`'s methods are `REQUIRES_NEW` so the claim **commits before** the
guarded operation runs; a claim invisible to concurrent transactions would let two
identical requests both see "no existing claim" and both execute. A failed attempt is
marked `FAILED` rather than deleted, so the client can retry the same key while the
attempt stays visible for diagnostics.

Replaying a key with the *same* body returns the original response. Replaying it with a
*different* body is a `409` — returning the first response would silently swallow a real
second transfer, and executing it would break the client's own contract. Both integration
suites include a "flush Redis, then replay" test, which is what distinguishes this from a
Redis-only design that would start double-charging during a cache outage.

### The payment walks the real state machine

```
CREATED → FUNDS_RESERVED → RISK_ASSESSED → AUTHORIZED → CAPTURED → COMPLETED
```

Phase 3 auto-approved risk and left `providerId` null. Jumping straight to `COMPLETED` was
rejected: it is an illegal transition, and it would have left no seam for Phase 5 (saga +
providers) or Phase 6 (real fraud scoring) to slot into.

**Since Phase 5 this service does not drive those transitions.** payment-orchestrator owns the
saga and this row catches up from its outcome events, which is why `Payment.advanceTo` exists
alongside `transitionTo` — see
[Out-of-order events are expected](#out-of-order-events-are-expected-not-exceptional). The
states and their edges are unchanged; only who decides.

`PARTIALLY_REFUNDED` is the **only** state allowed to self-transition, so repeat partial
refunds are legal; safety comes from the cumulative refund cap, enforced in the aggregate
*and* by `CHECK (refunded_minor <= amount_minor)`.

### Failure leaves no money stranded

Compensation releases the reservation on every failure from the reserve step onwards —
**including when `reserve` itself failed**. That case looks wrong but is not: a failed reserve
may be a lost *response* with the hold already created, and from the caller's side the two are
indistinguishable. Release is idempotent and tolerates a hold that never existed, so the
harmless call strictly beats the optimistic assumption. Compensation never throws; a failed
release is logged loudly, flagged on the `payment-failed` event, and left to the hold-expiry
sweeper.

Phase 3 ran this inside `PaymentService`. Phase 5 moved it into the saga, where the side effects
now live, and extended it to cover the provider authorisation and the ledger posting as well —
see [Compensation is driven by the handles](#compensation-is-driven-by-the-handles-not-by-the-status).
The one compensating action payment-service still owns is the refund path, which is still
synchronous.

### Events go through the outbox

Each state change records its event to `outbox_events` **in the same transaction**. That
is the only way to make "the payment completed" and "the world was told" atomic without a
distributed transaction across PostgreSQL and Kafka — publishing directly either loses the
event on a crash after commit, or announces a payment that later rolled back.
`OutboxRecorder.record` is `Propagation.MANDATORY`, so calling it outside a transaction
fails loudly instead of writing an auto-committed row that could publish a lie.

### Reconciliation is queryable on demand

`GET /api/wallets/admin/{id}/reconcile` recomputes the balance by replaying every recorded
movement and recomputes `held` by summing `ACTIVE` holds, then compares both to the stored
columns. `GET /api/wallets/admin/reconcile-ledger?currency=INR` separately compares the
platform-wide wallet total to ledger-service’s customer-liability snapshot in exact minor units.
A first cross-service mismatch is reported as `MISMATCH_OBSERVED` because Kafka may still be
propagating the corresponding posting; a persistent non-zero difference requires investigation.
Neither endpoint repairs data. `consistent: false` means money was created or destroyed relative to the audit
trail. The integration suite deliberately corrupts a balance behind the API's back and
requires the mismatch to be reported — an endpoint that can only ever return
`consistent: true` manufactures confidence rather than providing it.

### Two defects fixed while writing the Phase 3 tests

- **`internalRestClient` could not be constructed when service auth was disabled.** It
  injected `ServiceTokenProvider` directly, but that bean is conditional on
  `aegis.service-auth.enabled`. Any service running with it off — including auth-service,
  which *issues* tokens and never consumes one — failed at startup with an opaque
  "no qualifying bean" error. It now takes an `ObjectProvider` and degrades **closed**:
  the client exists, sends no `Authorization` header, and any `/internal/**` call is
  refused by the callee, with a startup warning naming the property.
- **A frozen wallet returned HTTP 500.** `Wallet` threw raw `IllegalStateException` for
  status refusals, which the global handler's catch-all turns into an opaque 500. `FROZEN`
  is a routine state that fraud controls use daily, so a refusal is a business outcome the
  customer is entitled to hear about and clients must not retry. Status refusals are now
  `ConflictException` → `409 INVALID_PAYMENT_STATE`. The genuinely impossible cases
  (capturing more than was reserved, releasing more than is held) still throw
  `IllegalStateException`, because those mean the saga is broken and a 500 with a stack
  trace is the correct response.

---

## Phase 4 — Ledger

Wallet balances are a projection customers read. This journal is the thing that has to be
defensible to an auditor, which sets a higher bar: not "the code is careful" but "the
alternative is impossible".

### Three guarantees, enforced by the database

An application-level rule holds only for the code paths that remember to call it. A
constraint holds against every migration, every hotfix, and every DBA with a `psql`
prompt. So the properties that make the journal trustworthy live in the schema:

| Guarantee | Mechanism |
|---|---|
| **Append-only** | `BEFORE UPDATE OR DELETE` triggers on both journal tables that raise unconditionally |
| **Every posting balances** | A `DEFERRABLE INITIALLY DEFERRED` constraint trigger re-checking debits == credits == declared total at **commit** |
| **Cached balances cannot drift** | A CHECK constraint deriving `balance_minor` from the account's own debit/credit totals |

The balance check has to be deferred. An entry and its lines are separate `INSERT`s, so
anything firing per-statement would see a half-written posting and reject a valid one.
Deferring to commit means an unbalanced posting cannot be committed by *any* route —
including a hand-written `INSERT`, which no amount of application validation can cover.

`LedgerImmutabilityIT` attacks all of this with raw SQL rather than through the service,
because the guarantees are worth having precisely to the extent that they do not depend on
the application behaving.

**Scope note.** `TRUNCATE` is deliberately *not* blocked. It is a table-owner privilege, so
a trigger buys little against anyone who already holds it, and blocking it would make test
isolation impossible. The production hardening for that threat is a privilege boundary —
run the application as a role with only `INSERT` and `SELECT` on the journal, reserving the
owner role for migrations — not another trigger.

### Direction depends on account type, and that is where ledgers go wrong

A debit increases an asset and decreases a liability. **Customer wallets are a
LIABILITY**: the money is the customer's and the platform owes it to them, so a credit
increases a wallet. Modelling wallets as an asset inverts every posting downstream and
every individual number still looks plausible, which is why it survives to audit.

Each account keeps three numbers:

```
debit_total_minor    raw totals, direction-neutral
credit_total_minor
balance_minor        natural-direction balance, DERIVED from the two above
```

The trial balance is stated in raw debit/credit terms rather than natural balances, so the
check does not depend on the per-type sign rules that are themselves part of what could be
wrong. `debitIncreases()` is duplicated between `SharedEnums.AccountType` and a CHECK
constraint on purpose: the point is for the database to catch a Java sign error rather than
store its result.

### The ledger books deposits too, or reconciliation is theatre

A ledger fed only by payment events sees internal transfers and nothing else. Wallet
liability balances would then be missing every deposit ever made, the trial balance could
never sum to zero, and comparing the ledger to wallet-service would be guaranteed to fail.

So the platform books both sides of its boundary:

```
Deposit      DEBIT  asset:external-funding:INR   (an asset arrived)
             CREDIT wallet:WAL-…                 (we now owe the customer)

Withdrawal   the exact reverse

Transfer     DEBIT  wallet:PAYER                 (we owe the payer less)
             CREDIT wallet:PAYEE                 (we owe the payee more)
```

Both transfer legs are liabilities, so the platform's total obligation is unchanged —
correctly, because an internal transfer moves *who* is owed, not *how much*. A deposit
genuinely does increase both assets and obligations. That distinction is asserted directly
in `PostingBuilderTest`.

The funding source is simulated. This platform never contacts a real bank or payment rail,
and the account name says so.

### Two event streams, one boundary, no double-counting

Feeding a ledger from more than one stream makes double-counting the most likely way to
corrupt it, so the boundary is drawn on ownership rather than left to a filter somewhere
downstream:

| Stream | Owner | Covers |
|---|---|---|
| `wallet-movement-recorded` | wallet-service | Deposits and withdrawals — the counterparty is outside the platform and only wallet-service knows they happened |
| `payment-completed`, `payment-refunded` | payment-service | Movements between two wallets already inside it |

wallet-service deliberately does **not** emit movement events for a payment's transfer
legs; `payment-completed` already describes them. `LedgerEventFlowIT` asserts that a
payment produces exactly one deposit plus one transfer and nothing else.

**All accounting knowledge lives in ledger-service.** The producing services publish what
happened in business terms and `PostingBuilder` decides how to book it. The alternative —
each service constructing its own postings — spreads the chart of accounts across nine
codebases and makes changing it a nine-service deployment.

### Idempotency is a natural key, not a side table

Every posting carries a caller-supplied `ledgerTransactionId`, unique in the journal. That
single constraint is the entire mechanism. It is stronger than the `idempotency_keys` table
the payment and wallet services use, because there is no way to bypass it.

Keys are **derived, never random**: `LTX-PAY-<paymentId>`, `LTX-RFD-<refundId>`,
`LTX-WLT-<walletTransactionId>`. A redelivered Kafka record therefore produces the same key
and is recognised as a replay. A random key would make every redelivery a new posting,
which is the one failure a ledger cannot tolerate. Keys are refused rather than truncated
if they would overflow the column, because a truncation collision would silently discard
the second movement as a duplicate.

Note what this service does *not* have: Redis. `ProcessedEventRegistry` degrades to a no-op
and suppresses nothing, so the consumer runs with de-duplication switched off — which makes
the redelivery test the strongest form of itself rather than the weakest.

### Why `LedgerService` must not be transactional

A duplicate-key violation in PostgreSQL aborts the *entire* transaction. Recovering from a
lost race by re-reading the posting the winner committed therefore cannot happen inside the
transaction that just failed. So `LedgerService` is non-transactional and calls
`JournalWriter` across a real bean boundary; a `this.append(...)` call would bypass Spring's
proxy and run with no transaction at all. `LedgerTransactionBoundaryTest` pins both halves.

Account provisioning is a third bean, on `REQUIRES_NEW`, committed *before* the posting
opens — otherwise two concurrent postings colliding over an empty account row would abort
the whole posting.

### Corrections are new entries, never edits

A mistake is fixed by appending the exact opposite, leg for leg. Both entries stay visible
and net to zero on every account they touch; deleting the original would destroy the
evidence that it ever happened, which is what an audit needs. A posting may be reversed at
most once, enforced by a partial unique index, so two concurrent reversals cannot both
succeed.

A refund is typed `REFUND`, **not** `REVERSAL`, even when it is a full refund. `REVERSAL`
annuls a posting and is limited to one per entry; a refund is a real, separate movement a
customer can request repeatedly up to the original amount. Typing it as a reversal would
block the second legitimate refund.

There is deliberately no `reversedBy` column — back-filling one would require an `UPDATE`.
"What reversed this" is answered by querying in the other direction.

### Concurrency

Accounts are locked with `SELECT … FOR UPDATE` in **sorted account-ID order**, the same
deadlock-avoidance rule wallet-service applies to multi-wallet transfers. Two postings
touching the same pair in opposite orders would otherwise each hold the row the other
needs. `LedgerPostingIT` fires exactly that crossing pair and requires both to succeed.

`journal_sequence` is monotonic but **not gapless** — a rolled-back transaction still
consumes a value. A gap is normal and is not evidence of deletion. Making it gapless would
mean serialising every posting behind one lock, capping throughput at one posting at a
time; the immutability triggers give tamper-evidence without that cost.

### Reconciliation that can actually fail

Four overlapping checks: the trial balance, cached account totals replayed from the journal
lines, per-posting integrity, and each account's running-balance chain.

The unbalanced-posting check is interesting because a constraint trigger already makes an
unbalanced posting impossible to commit. Anything it finds therefore means the *trigger* was
dropped, disabled or bypassed — and the report says so rather than merely listing sequence
numbers. `LedgerReconciliationIT` disables that trigger to reach the state, which is the
only honest way to prove the check is real.

Reconciliation is read-only throughout and never repairs what it finds. Silently correcting
a discrepancy destroys the evidence of how it arose, and the cause matters far more than the
symptom. A report that can only ever return `consistent: true` manufactures confidence, so
the suite deliberately corrupts the ledger — insert journal lines without updating the
cached balances — and requires the mismatch to be reported.

A cheap trial-balance check also runs on a schedule. Reconciliation that only happens when
someone remembers to ask is not a control.

### Two Phase 3 gaps this phase closed

- **Refunds emitted nothing.** `applyRefund` changed the payment and moved wallet balances
  while publishing no event, so the book of record would have shown every transfer and
  never its reversal. It now records `payment-refunded` on the outbox inside the same
  transaction. The payload also gained both wallet IDs: the ledger books the reverse of the
  original transfer and cannot look them up from the earlier `payment-completed` posting,
  because Kafka gives no ordering guarantee across two topics.
- **wallet-service had the outbox enabled and never used it.** Deposits and withdrawals now
  record `wallet-movement-recorded`, which is what makes the ledger complete enough for
  cross-service reconciliation to mean anything.

### Fees: refused in Phase 4, booked to the platform in Phase 5

Through Phase 4, `PostingBuilder` **rejected** any payment carrying a non-zero fee. The
wallet transfer moves exactly `amount`, so booking an extra fee leg would have claimed a
movement the wallet never made and reconciliation would have flagged every single payment.
Which party bears a provider fee is a product decision, and until it was made refusing was
the only option that could not silently desynchronise the books.

Phase 5 made that decision — **the platform bears it** — and `PostingBuilder` now books it
as a four-leg entry. See
[The platform bears the provider fee](#the-platform-bears-the-provider-fee). A fee that
names no provider is still refused, because its credit leg has to come from a specific
provider's settlement balance and there is no defensible way to guess which.

---

## Phase 5 — Payment Orchestration

Payment execution moved out of payment-service and into **payment-orchestrator**, which drives
a saga across three simulated providers and compensates when a step fails.

> **Nothing here integrates with a real payment rail.** SWIFTPAY, NEXUSPAY and PAYBRIDGE are
> invented names for simulated providers. No bank, card network or UPI rail is contacted, and
> none should ever be described as such. What is faithfully modelled is the behaviour a caller
> has to defend against: authorise-then-void semantics, declines that are not faults, fees,
> capability limits, latency, and a charge that succeeds while its acknowledgement is lost.

### The API contract changed, and it had to

`POST /api/payments` used to run the whole sequence inline and return `201` with status
`COMPLETED`. It now returns **`202 Accepted`** with status `CREATED` and a `Location` header.

The reason is what the sequence now contains. Executing a payment means routing across
providers, retrying, and falling back when one fails or declines. Each provider call has a
three-second deadline of its own and a payment may legitimately try three of them. Holding the
customer's HTTP connection open across that would mean a request that can take ten seconds, a
client timeout that says nothing about whether the money moved, and throughput capped by the
slowest rail the platform talks to.

So the honest answer is "accepted", and the status code says so. A `201` with a status the
saga has not reached would be worse than the extra round trip, because a client would render a
payment as successful before anything had been charged.

What still happens synchronously, before the `202`, is everything that can make a request
*obviously* impossible: wallet resolution, currency agreement, self-transfer, and a balance
pre-check. None is authoritative — wallet-service re-checks the balance under a row lock when
the saga reserves — but a fast, precise rejection is worth far more to a caller than a `202`
followed by a silent failure. A payment refused here writes **no row and emits no event**, so
no saga starts and no funds are ever reserved for it.

Replaying an idempotency key returns the original *acceptance* — status `CREATED` — even if the
payment has since completed. The idempotency store exists to make the write happen once; it is
not a status endpoint.

**Refunds stay synchronous.** A refund has no provider leg — it is a wallet-to-wallet reversal
— so there is nothing to route, nothing to fall back to, and no reason to make the caller poll.
It can still fail for a real reason (the payee has already spent the funds), and that answer is
more useful delivered immediately than as an event.

### The saga, and why the steps are in this order

```
  payment-created ──▶ payment-orchestrator

    RESERVE_FUNDS   → wallet-service     make the money unspendable
    ASSESS_RISK     → fraud-service      APPROVE/REVIEW continue · HOLD parks at RISK_HELD
                                         REJECT compensates          (Phase 6)
    AUTHORIZE       → provider           route, retry, fall back
    POST_LEDGER     → ledger-service     write the books BEFORE the money moves
    CAPTURE_FUNDS   → wallet-service     debit payer, credit payee, one transaction
    COMPLETE        → outbox             announce it

  payment-completed / payment-failed / payment-rejected ──▶ payment-service
```

Two orderings are load-bearing:

- **Reserve before authorise.** Asking a provider to authorise money the customer does not
  have wastes a provider call and produces an avoidable decline. The reservation is also what
  stops a second concurrent payment claiming the same funds mid-saga.
- **Post to the ledger before capturing.** Money should never move that has not been recorded.
  A crash between the two leaves books that claim a transfer the wallets have not made —
  detectable by the ledger's own reconciliation and undone by a reversal. The opposite order
  moves real money with no record of why, which is far worse to discover.

The saga calls ledger-service **synchronously** for that posting, via
`POST /internal/ledger/postings/for-payment`, because "post before capture" only means
something if the saga knows the posting committed. Phase 6's risk step is synchronous for the
same reason: the saga cannot continue without a verdict, and an event-driven request/reply would
park it awaiting a reply the recovery sweep would then read as stalled. Waiting for an event the
saga publishes itself would deadlock.

The later Kafka `payment-completed` is not a duplicate: ledger-service derives
the same idempotency key from the payment ID either way, so it is recognised as a replay. One
accounting code path, two triggers.

The orchestrator passes the payment's *facts* rather than a list of accounts. Deciding which
accounts a payment touches is an accounting decision and belongs with the chart of accounts; an
orchestrator that built its own postings would put the chart of accounts in two places.

### `advance()` is a loop over the persisted status

The driver is a `switch` over the saga's stored state rather than a straight-line sequence. A
saga picked up by the recovery sweep after a crash therefore re-enters at whatever state it
reached, with **no separate resumption path** to keep in step with the happy path — which is
exactly where that class of bug lives.

Phase 6 relies on exactly that property. A payment released by an analyst re-enters the loop at
`RISK_ASSESSED` and takes the same steps it would have taken had it never been held; the loop
gained one extra exit condition (`isParked()`) and no new path.

### Compensation is driven by the handles, not by the status

`holdId`, `authorizationId` and `ledgerTransactionId` are each written the moment their step
succeeds, and their presence is what tells the compensator there is a real side effect to undo.

Nothing is inferred from the status. A crash between "provider approved" and "status updated"
leaves a real authorisation outstanding while the status still says `RISK_ASSESSED`;
compensating on the status would miss it and the charge would sit at the rail forever. So each
step asks *"is there a handle for this"*, not *"did we get that far"*.

Effects are undone in reverse order of creation — ledger reversed, authorisation voided,
reservation released last — so the customer's funds become spendable only once nothing else
could still need them.

Compensation never throws. The caller is already handling one failure and a second here would
mask the original cause. A failure to compensate is reported through the return value and lands
on the saga as `compensationComplete = false`, which travels on the `payment-failed` event and
is surfaced at `GET /api/orchestration/sagas/uncompensated`. That endpoint is expected to be
empty; every entry is money that may be reserved at a wallet or authorised at a provider with
nothing scheduled to release it.

**Once the funds are captured, compensation is no longer available.** The state machine has no
`CAPTURED → COMPENSATING` edge. Releasing a reservation that was already captured would hand
back money legitimately taken, so the only correct direction is forward: finish recording the
outcome.

### Declines are not failures, and the whole design turns on it

A provider signals the two outcomes differently:

- A **decline** is *returned*. The provider worked correctly and said no. It is invisible to
  the retry and to the circuit breaker, and it does not count against the provider's health.
- A **fault** is *thrown*. The provider failed to answer. It counts, may trip the breaker, and
  is worth retrying or falling back from.

Conflating them is how a payment platform ends up retrying a decline until it accidentally
succeeds, or taking a perfectly healthy rail out of service because a run of customers happened
to be short of funds. Declines are excluded from the reliability denominator as well as the
numerator, so a provider serving a poorer segment is not permanently penalised for it.

The distinction reaches the customer. Every provider declining is `REJECTED` — "this will not
work". Every provider broken is `FAILED` — "try again". They are different states with
different events.

### Routing: three stages, in this order

1. **Eligibility.** Can this provider even process this charge — currency, amount limits,
   payment type? Checked *first*, so an ineligible provider can never win the scoring and then
   be discarded, which would silently promote a worse candidate.
2. **Availability.** Has an operator disabled it, and would its circuit breaker let a call
   through? Both are hard exclusions that no score can override.
3. **Scoring.** Whatever survives is ranked on observed reliability (0.55), price (0.25) and
   latency (0.20).

Weights rather than a fixed priority order, because the trade-off genuinely changes with
conditions: the cheapest rail is the right choice while it is healthy and the wrong one the
moment it is not. Reliability carries the heaviest weight because a failed payment costs far
more than an expensive one — the customer's money is stuck mid-saga and somebody has to explain
it.

The router returns an **ordered list**, not a single choice. That is what makes fallback
trivial: the saga walks the list until one provider authorises. A router that returned only its
favourite would force the caller to ask again after every failure, and the second answer would
be computed from signals the failed attempt had already moved.

Health for routing comes from the **live circuit breaker**, never from the stored health value —
a stored value is stale the moment it is written, so routing on it would send a payment to a
provider that failed a second ago.

| | SWIFTPAY | NEXUSPAY | PAYBRIDGE |
|---|---|---|---|
| Fee | 25 bp | 55 bp | 95 bp |
| Baseline latency | 40 ms | 120 ms | 320 ms |
| Currencies | INR | INR, USD | INR, USD, EUR, GBP |
| Ceiling | ₹50,000 | ₹50,00,000 | effectively none |
| Declines | round amounts ≥ ₹25,000 | nothing | nothing |

The three are deliberately different. One preferable but frequently ineligible means the
capability filter does real work on ordinary traffic rather than only in contrived cases; two
that decline nothing mean a failed payment can be attributed unambiguously to routing, to a
fault, or to the third one's decline rule.

### Resilience order: `CircuitBreaker(Retry(TimeLimiter(call)))`

The order is the design:

- **TimeLimiter innermost**, so each individual attempt has its own deadline. Outside the retry
  it would bound the whole sequence, and a first attempt that hung would consume the entire
  budget leaving no time to retry at all.
- **Retry in the middle**, so the breaker sees one outcome per *logical call* rather than one
  per attempt. Wrapped the other way round, three retries of a dead provider would register as
  three failures and trip the breaker three times faster than its configured threshold — the
  threshold would silently mean something different from what it says.
- **CircuitBreaker outermost**, so an open breaker rejects the call before any time or thread is
  spent on it. That is the whole point of having one: not to fail gracefully, but to stop
  asking.

Composed programmatically rather than through annotations. The annotation-based starter resolves
breakers by name from YAML, so a typo would silently create a fresh breaker with default
settings and the protection would appear present while doing nothing — and it cannot create a
breaker per provider at runtime, which is the shape of this problem.

**Retry only where it is safe.** `authorize` is not idempotent, so a blind retry risks charging
twice. The predicate fires only for faults known to have happened *before* the request reached
the provider. A read timeout is excluded: the charge may have been authorised and only the
acknowledgement lost, so the safe response is to fall back to a different provider and void
whatever the first one may have left behind. Backoff is exponential **with jitter**, because
plain exponential backoff synchronises every caller that failed at the same instant into
retrying at the same instant, reproducing the overload they are recovering from.

### The orphaned authorisation, which is the case that makes naive compensation wrong

A provider that authorises a charge and then fails to answer leaves a real charge with no handle
on our side. The saga has no authorisation id, so there is nothing to void and nothing to
reconcile against — the charge would sit there indefinitely, referenced by nothing.

So `PaymentProvider.findAuthorizationId(idempotencyKey)` exists, the saga passes its own saga id
as the provider-facing idempotency key, and after a timeout it **looks the charge up and voids it
before falling back**. Every real rail offers a lookup by merchant reference for exactly this
reason; using it is the difference between compensation that works and compensation that hopes.

The simulation models this faithfully: the `TIMEOUT` fault mode records the authorisation
*before* throwing, so the orphan genuinely exists and the recovery path has something to find.

### Fault injection is deterministic, not probabilistic

The obvious way to simulate an unreliable provider is a random failure rate. That would make
every test of fallback, retry and circuit breaking flaky — the behaviour under test would appear
only on some runs, and a genuine regression would be indistinguishable from an unlucky seed.

So faults are armed and **counted down**: "fail the next N calls". A test arms the fault, makes
exactly the calls it needs, and asserts with no randomness anywhere. The same mechanism lets an
operator rehearse a provider outage against a sandbox without waiting for a real one.

`POST /internal/providers/{id}/simulate` exposes it over HTTP, because the interesting behaviour
— a breaker opening, a saga falling back, an orphaned authorisation being discovered — only
appears when the orchestrator is running against a real database and a real broker. Reaching
into the provider beans works for a unit test and not for an integration test.

### Health probing exists even though there is a circuit breaker

A breaker learns only from real traffic, so it cannot notice a provider that has gone dark until
a customer's payment is routed into it and pays the latency cost of finding out. At low volume
that is the common case: the first payment in ten minutes becomes the probe.

The two signals do different jobs and neither replaces the other. The breaker is the
*protection* — it stops calls. Probing is the *observation*, and it feeds the admin view and the
operator's alerts. Probes deliberately **bypass the breaker**: routing a liveness check through
an open breaker would guarantee it never discovers recovery.

A provider is declared unhealthy only after consecutive failed probes. A single failed probe is
far more likely to be a blip in the prober than an outage at the provider, and reacting to it
would flap traffic between providers on noise.

### The kill switch persists; the breakers do not

Circuit breaker state lives in memory, and that is correct: a breaker measures what *this
instance* is currently seeing, and one restored from a database would be reasoning about another
instance's past failures.

What must survive a restart is the deliberate human decision. An operator disabling a provider
during an incident has to stay disabled through a rolling deploy, or the deploy silently undoes
the mitigation at the worst possible moment. So `provider_state.enabled` is a row, and a
mandatory reason is enforced by the aggregate *and* by the schema — "who turned this off and
why" is unanswerable an hour later otherwise, and the answer decides whether it is safe to turn
back on.

Re-enabling resets observed health to `UNKNOWN` rather than `HEALTHY`: whether the provider has
actually recovered is for the next probe to establish, not for the operator to assert.

### Stalled-saga recovery

A saga is driven by a thread holding no transaction across four network calls. If that thread
dies — a crash, a pod eviction, a redeploy mid-payment — the saga is left in whatever state it
reached, with a reservation and possibly a provider authorisation outstanding and nothing
scheduled to resolve them. The customer's money stays unspendable and the payment neither
completes nor fails.

The sweep picks those up and re-drives them through `advance()`. Staleness is measured on the
**last state change**, not on age: a saga waiting on a slow provider is still making progress and
must not be disturbed, whereas one whose last write was minutes ago has almost certainly lost its
thread. Using creation time instead would compensate payments that are simply taking a while,
releasing the reservation out from under a charge about to be authorised.

Safe to run on every replica, because each saga is re-read under a row lock and driven from its
persisted state — two concurrent sweeps cannot both advance the same one. Every increment of
`aegis.saga.recovered` means an orchestration was interrupted, so a non-zero rate is worth
alerting on rather than routine noise.

### The platform bears the provider fee

The payer is debited exactly `amount` and the payee credited exactly `amount`. The fee is a
separate pair of legs that touches neither customer:

```
  DEBIT   wallet:PAYER                 amount   we owe the payer less
  CREDIT  wallet:PAYEE                 amount   we owe the payee more
  DEBIT   expense:provider-fees:CCY    fee      a cost we incurred
  CREDIT  asset:settlement:PROV:CCY    fee      taken from our balance at them
                                      -------
  debits == credits == amount + fee
```

This is not an accounting preference; it is forced by what the wallets actually did.
wallet-service moves exactly `amount` at capture, so deducting the fee from the payee's credit
or adding it to the payer's debit would make the ledger claim a movement the wallet never made —
and customer-liability reconciliation would then fail on every payment that carried a fee.

Settlement accounts are `ASSET` with `allows_negative = TRUE`, which is not laziness: until the
platform pre-funds them, every fee a provider deducts drives the balance further negative, and
that correctly reads as "we owe the provider". Customer wallets remain `allows_negative = FALSE`.

The fee stored on the saga is the **provider's** number, not one recomputed from a rate.
Recomputing could disagree with what was actually charged, and the ledger would then book a fee
nobody levied.

### Who owns which event

The split is deliberate, and both halves matter.

payment-orchestrator publishes `payment-approved`, `payment-completed`, `payment-failed` and
`payment-rejected`, because only it knows the provider, the fee and the ledger posting.
payment-service *consumes* those and brings its own row into step; it emits none of them.
Publishing from both sides would put two differently-shaped payloads for one payment on the same
topic and leave ledger-service and analytics with no way to tell which was authoritative.

payment-service keeps `payment-created` — the sole trigger for orchestration —
`payment-refunded`, and **every customer notification**. The orchestrator owns the saga's facts;
payment-service owns the customer-facing record and everything the customer is told about it.

### Out-of-order events are expected, not exceptional

Kafka orders records within one partition of one topic. The four outcome events travel on four
topics, so `payment-completed` can genuinely arrive before `payment-approved`.

`Payment.advanceTo` therefore walks forward to the state an event asserts, taking every
intervening hop through the guarded transition rather than a single step. A later event is
strictly more informative than an earlier one, so applying it and then ignoring the straggler is
both correct and simpler than buffering. It refuses to move backwards and refuses to re-enter the
forward path once a payment has left it, so a stale `payment-approved` cannot resurrect a payment
the saga has already failed and compensated.

### Two defects fixed while writing the Phase 5 tests

- **`hold_id` on `payments` became a handle nobody could act on.** Compensation moved into the
  saga, which holds the authoritative `payment_sagas.hold_id`. A second copy in payment-service
  looked actionable and was not, so `V3__saga_handover.sql` drops it. A column that looks
  actionable but is not is worse than no column, because the next person to read it will write
  code against it.
- **A late failure could overwrite a completed saga.** `PaymentSaga.failed()` wrote
  `compensationComplete = false` before checking whether the transition was legal, so a recovery
  sweep racing a completion could put a perfectly settled payment into the operator's "money may
  be stranded" queue — and `markFailed` would then emit `payment-failed`, sending the customer a
  failure notification for money that had arrived. Both now return `false` when the saga is
  already terminal, and no event is recorded.

---

## Phase 6 — Fraud Intelligence

Phase 5 left the risk step auto-approved. Phase 6 fills it in: a Python FastAPI service scores
a calibrated fraud probability from a gradient-boosted model, a deterministic rule engine covers
the archetypes that model measurably misses, and `fraud-service` combines the two into a
0–100 score and an `APPROVE` / `REVIEW` / `HOLD` / `REJECT` decision. A held payment stops the saga and waits
for a human, with the customer's funds still reserved.

### ml-service measures; it does not decide

`ml-service` returns a probability, the same number as a 0–100 score, per-feature explanations
and the model version. It returns **no risk level and no decision**.

That split is deliberate. Bucketing a probability needs the deterministic rules as well as the
model, and only `fraud-service` has both. Putting the thresholds in the model service would also
mean that retuning one — a business decision a fraud team reviews — required a model redeploy and
two services moving in step. As it stands, `FRAUD_HIGH_RISK_THRESHOLD` is an environment
variable.

### The rules exist because the model's weaknesses were measured

Not belt-and-braces, and not distrust of the model. Recall per attack archetype on held-out data,
at the blocking threshold:

| Archetype | Recall @ 0.80 | Covered by |
|---|---|---|
| `account_takeover` | 0.713 | the model |
| `card_testing` | 0.596 | the model, plus `RULE_CARD_TESTING_VELOCITY` |
| `mule_cashout` | 0.328 | `RULE_UNFAMILIAR_CASH_OUT_CORRIDOR` |
| `bust_out` | **0.147** | `RULE_BUST_OUT_PATTERN`, almost alone |
| `friendly_fraud` | **0.000** | nothing — and correctly so |

**Bust-out is the genuinely hard case.** A long-standing account that behaved normally for a year
and then sends its largest-ever payment to a brand-new counterparty is, feature for feature,
nearly identical to a customer finally buying a car. The model cannot separate them and the
measurement says so. A rule can, because it can insist on the *conjunction* — established
history, a large multiple of the historic maximum, **and** a payee never paid before — and accept
the false positives that conjunction still produces in exchange for catching a pattern that would
otherwise walk straight through.

**`friendly_fraud` at 0.000 is not a bug.** A customer who genuinely made a payment and later
denies it produces a transaction that is, by construction, indistinguishable from a legitimate
one. No feature separates them, because there is nothing to separate. A model that scored it
would be fitting noise, and the honest thing is to report zero rather than tune until the number
looks better.

Two other things rules do that a probability cannot. They express **physical impossibility**:
6,000km in eleven minutes is not a risk score, it is a contradiction, and the impossibility lives
in the *ratio* of two features rather than in either one. And they carry the platform's
`CRITICAL` band, which the `RiskLevel` contract reserves for confirmed patterns — a model score,
however high, is a statistical claim.

### Rules escalate only, and only a rule can refuse

A rule can make a verdict more cautious and never less. If a rule could lower a risk level the
model raised, then *adding* a rule would be capable of letting fraud through, and two such rules
would interact in ways nobody could reason about. So the combination is a floor.

`CRITICAL` / `REJECT` is unreachable from a probability, however high — a 0.999 score is still a
statistical claim. It is reachable from exactly one rule: `RULE_CONFIRMED_FRAUD_HISTORY` at three
or more prior frauds an analyst has already confirmed. That is evidence needing no further
interpretation, which is the bar an automatic refusal has to meet. Everything else the engine can
do is a **hold**, which keeps the customer's funds reserved and waits for a person: a false
positive costs a delay, where a false rejection costs the customer their payment.

`RiskVerdictTest` asserts both directions — that a `HOLD` rule raises a 0.02 score to HIGH, and
that an advisory hit cannot lower a 0.91 one.

### Calibration is what makes a threshold mean anything

The classifier is wrapped in an isotonic `CalibratedClassifierCV`. Without that, "0.80" would be
an arbitrary dial: a raw gradient-booster score is monotonic in risk but is not a probability, so
comparing it to a number a fraud team chose would be meaningless. With it, `score = round(p × 100)`
and 0.80 reads directly as a risk score of 80.

The training run reports the calibration error so the claim is checkable rather than asserted:

| Metric | Value |
|---|---|
| ROC AUC | 0.9353 |
| Average precision | 0.7255 |
| Brier score | 0.00533 (baseline 0.01694) |
| Expected calibration error | 0.00089 |

At the two configured thresholds:

| Threshold | Precision | Recall | Alert rate |
|---|---|---|---|
| 0.45 (`REVIEW`) | 0.804 | 0.847 | 1.82% |
| 0.80 (`HOLD`) | 0.809 | 0.441 | 0.94% |

**The alert rate is the number that decides whether a threshold is usable at all.** A threshold
that flags 10% of traffic is not a fraud control, it is a manual review department, and no
precision figure rescues it. The training run prints precision, recall *and* alert rate at every
candidate value for exactly that reason.

Rescaling the scores to fill 0–100 was considered and rejected: it would make the distribution
prettier and break the threshold semantics, since 80 would no longer mean "80% likely".

### The training data is synthetic, and the metrics are honest about it

Every number above describes **generated transactions**, not any real population. Read them as
evidence that the pipeline works end to end.

The first generator produced an ROC AUC of **0.9999**, which is worthless — it meant the fraud
patterns were separable by a rule the model could learn exactly. Making the metrics meaningful
required deliberate, **bidirectional label noise**:

| Parameter | Value | What it caps |
|---|---|---|
| `UNREPORTED_FRAUD_SHARE` | 0.0035 | fraud labelled legitimate → caps **precision** |
| `FRIENDLY_FRAUD_SHARE` | 0.12 | legitimate labelled fraud → caps **recall** |
| `LEGITIMATE_CONFUSION_RATE` | 0.13 | legitimate traffic that looks fraudulent |

One-directional noise would not have been enough. Only unreported fraud makes a perfect model
look imprecise; only friendly fraud makes it look insensitive. Both are needed for the ceiling to
resemble a real one.

**An artefact worth naming.** The generator initially drew fraudulent amounts as round numbers
(`50000`, `100000`) while legitimate ones had cents. `amount_minor % 100 == 0` was therefore a
near-perfect fraud detector, and the model found it immediately. That is not a signal, it is a
tell in the data generator, and a model that learned it would have scored beautifully and detected
nothing. Legitimate amounts are now round at a realistic rate too.

### The wire contract that nearly broke silently

`ml-service` is a pydantic application. Its default camelCase alias generator turns
`payment_count_1h` into **`paymentCount1H`** — capital H — because it treats the digit as a word
boundary. Jackson serialising the equivalent Java field produces **`paymentCount1h`**. Six fields
are affected:

```
paymentCount1h  paymentCount24h  amountSum24hMinor
failedPaymentCount24h  distinctDevices30d  distinctCountries30d
```

`ml-service` rejects unknown fields, so in production this fails loudly — but only once both
services are deployed together. Until then the symptom would have been six behavioural features
quietly absent from every score, with no error anywhere and a model still returning plausible
numbers.

Both halves are now pinned and tested. `tests/test_wire_contract.py` asserts the aliases on the
Python side; `MlScoringClientTest` asserts the serialised bytes on the Java side against a real
loopback HTTP server. The Java test runs without Docker, which is the point: this is the check
most likely to catch a genuine break, so it must run on every build.

### Every window excludes the payment being scored

`fraud-service` keeps its **own** read model of payment history (`payment_observations`), built
from the events it already receives. Fetching the aggregates from payment-service and
wallet-service at scoring time would put two more network calls on the payment's critical path and
would make a fraud decision fail during an unrelated redeploy.

The subtle part is the window boundary. The saga calls `fraud-service` *milliseconds* after
`payment-created` is published, so whether the current payment's own observation has been consumed
yet is a race between two consumers. Every aggregate query therefore excludes it explicitly by
`payment_id`:

```sql
WHERE o.initiator_user_id = :userId
  AND o.paymentId <> :excludedPaymentId
  AND o.occurredAt >= :since
```

Without that clause the same payment would score differently depending on which consumer won —
and the feature contract the model was trained against defines these windows as **prior to** the
payment, so including it would push every request slightly outside the trained distribution with
no error anywhere. A consequence worth stating because it looks like a bug: `amountSum24hMinor`
can legitimately be *smaller* than the amount being scored, and is zero on a customer's first
payment of the day.

### Absent is not zero

A brand-new account has no average payment amount. Reporting `0` would tell the model that this
account's usual payment is nothing, which is a different and false claim — and every rule dividing
by it would produce a meaningless ratio. So the pipeline is `Optional`/nullable throughout,
`ml-service` is trained to treat absence as its own signal, and the ablation substitute for a
mostly-missing feature is NaN rather than a median.

The same distinction applies to devices: "no device supplied" is not "an unfamiliar device", and
conflating them would flag every API-channel payment on the platform. `isNewDevice` is `null` when
no device was supplied, `false` when it is recognised, `true` when it genuinely is not.

### Explainability without SHAP

SHAP could not be installed in this environment, so explanations come from **leave-one-feature-out
ablation**: re-score the payment with each feature blanked in turn, and report how far the score
falls. Implemented as **one batched `predict_proba` over an `(N+1, N)` matrix** rather than N+1
calls, because the whole explanation has to fit inside a scoring budget measured in milliseconds.

It is a weaker attribution than SHAP — it ignores interactions between features — and that is
worth saying plainly rather than describing it as "SHAP-like". What it does give is a signed,
per-feature, human-readable statement of what moved this payment's score, which is what an analyst
reading a held payment actually needs.

Only contributions that *raised* the score appear in the reason list. A contribution that lowered
it ("the device was already known") is genuinely useful in the full explanation and reads as
nonsense in a list headed "why this was held".

### The model is trained at image build time, and its version is behavioural

Training on boot was rejected outright: two replicas would each train their own model, so the same
payment could score differently depending on which pod answered — indefensible in a dispute. The
artifact is baked into the image, and if it is missing, readiness fails and `/score` returns 503
rather than the service quietly training a fresh one.

The model version is a **behavioural fingerprint**: a SHA-256 of the model's predictions over a
fixed probe matrix. Two alternatives were rejected. A build timestamp would give two byte-identical
models different versions, so a redeploy would look like a model change on every dashboard. A hash
of the hyperparameters would give two genuinely different models the *same* version, which is
worse — it would let a retrained model hide behind an unchanged label.

### Degraded mode: unknown is not safe

When `ml-service` cannot be reached, the deterministic rules alone decide and the verdict carries
`degradedMode: true`. The stored probability is `0.0`, and **that means "unknown", not "safe"**. A
`LOW` risk level with `degradedMode: true` means "the model was unreachable and the rules found
nothing", which is a completely different level of assurance from "the model scored this as
routine".

Because a consumer must be able to tell those apart, the flag travels on the assessment row, on
the `fraud-checked` event, on the API response, **and** onto the saga's own row — the saga records
that *it* chose to continue a payment on a verdict of reduced assurance, which is the fact an audit
asks about. The feature snapshot is `null` in degraded mode, because nothing was scored and storing
a vector would imply the model saw inputs it never did.

**The default is to proceed, and that is an arguable choice.** Refusing every payment while the
model is down converts a fraud-service outage into a total payment outage — a far larger incident
than the fraud it prevents — and the rules cover precisely the archetypes the model scores worst
on, which is exactly the coverage that matters when it is absent. An operator who prefers the other
trade-off sets `FRAUD_REJECT_WHEN_ML_UNAVAILABLE=true`. Either way `degradedAssessments24h` is one
of the two numbers on `/api/fraud/stats` worth alerting on, because a degraded fraud posture
produces no errors and is otherwise invisible.

### An unreachable fraud-service is treated more strictly than an unreachable model

The orchestrator's default is the opposite: if `fraud-service` itself cannot be reached, the
payment **fails**.

The difference is whether a compensating control exists. A model outage still leaves the rules
running — a real, if reduced, assessment. A fraud-service outage leaves nothing: no score, no
rules, no verdict. Continuing then would not be a degraded assessment, it would be an *unassessed*
payment recorded as an approved one, and that is a false statement in the audit trail of an
automated decision. Failing is recoverable — nothing was charged, compensation releases the
reservation, the customer retries — and it makes the outage visible as failed payments instead of
invisible as unprotected ones.

`ALLOW_UNASSESSED_PAYMENTS=true` chooses availability instead. The saga then records the verdict
explicitly as degraded with **no** assessment id, so the choice is visible on the payment rather
than implied by configuration.

### The held state, and why the sweep must ignore it

`SagaStatus` gains `RISK_HELD`:

```
STARTED ─▶ FUNDS_RESERVED ─▶ RISK_ASSESSED ─▶ AUTHORIZED ─▶ LEDGER_POSTED ─▶ CAPTURED ─▶ COMPLETED
                 │      ▲
                 ▼      │  analyst RELEASE
            RISK_HELD ──┘
                 │  analyst REJECT
                 ▼
           COMPENSATING ─▶ REJECTED
```

**Parked is not stalled, and that distinction is load-bearing.** The recovery sweep finds
abandoned sagas by looking for a `updated_at` that has stopped moving. A held payment's
`updated_at` stops moving *precisely because it is behaving as designed* — it is waiting on a
person and may wait for hours. Without an explicit exclusion, the sweep would "recover" every held
payment on every pass, incrementing `attempt_count` each time and re-entering a saga that has
nothing to do, until a perfectly healthy payment looked like a chronic failure. Nothing would fail
loudly; the signal that distinguishes an abandoned saga from a waiting one would just quietly stop
working.

So `RISK_HELD` is excluded from `findStalled`, from the partial index that backs it, and defensively
from `SagaOrchestrator.resume`. `SagaTransactionBoundaryTest` asserts the exclusion against the
query text, because there is no way to check it without a database and the alternative is
discovering it in production as a mysteriously rising attempt count.

Held payments are **reported** instead, at `GET /api/orchestration/sagas/held`, ordered by how long
each customer has been waiting. That list growing is the only signal that the decision consumer has
stopped working — there are no errors and no failures, just customers whose funds are reserved.

A released payment lands in `RISK_ASSESSED`, not `AUTHORIZED`. It then takes exactly the same steps
it would have taken had it never been held; a dedicated resume path would be a second
implementation of the same sequence, and the two would drift.

**The engine's verdict is not rewritten by the override.** After a release, `fraud_decision` still
reads `HOLD`, alongside `risk_reviewed_by` and `risk_review_reason`. Overwriting it with `APPROVE`
would erase the fact that the engine stopped this payment, which is precisely what somebody
reviewing a released fraud needs to see.

### A fraud hold outlives the reservation TTL

The saga reserves funds for 15 minutes — comfortably longer than a payment that runs to
completion, short enough that a crash frees the money in minutes. A payment held for review is a
different shape entirely: it waits on a human.

Without an extension, the wallet's hold-expiry sweeper would release the funds while the saga still
believed they were held. The customer could then spend the money, and the analyst releasing the
payment would capture against a balance that had moved underneath them. So Phase 6 adds
`POST /internal/wallets/extend`, and the saga extends the reservation before parking a payment and
again when one is released.

Three properties, each for a reason:

- **Monotonic.** A request for a shorter lifetime never shortens an expiry that is already further
  out. A shortened expiry could sweep a reservation a saga is still relying on, and a caller asking
  for one is almost certainly passing a default it did not mean to.
- **Capped**, at seven days per call in the domain and two days by orchestrator default. Extension
  must not become a way to freeze a customer's money indefinitely — that is the exact failure
  `expires_at` exists to prevent, and it would arrive by extension rather than by omission.
- **Non-fatal on failure.** A failed extension does not fail the payment. The hold is still valid
  for its original window, so the worst case is that a long review outruns it, the capture fails,
  and the saga compensates — a failed payment rather than money moving with no funds behind it.

### The analyst decision travels as an event, not a call

`fraud-service` records the decision and emits `fraud-decision-applied` **in one commit** through
its outbox. The orchestrator consumes it and unparks the saga.

Calling the orchestrator over HTTP from the decision handler was rejected. It could fail *after*
the alert was resolved, leaving the payment parked forever with the alert reading as handled —
invisible from both ends, because nothing failed and the payment would simply never move again. The
event also means an orchestrator redeployment does not stop analysts working the queue.

The consumer is idempotent because Kafka delivers at least once: a redelivered decision finds the
saga no longer parked and does nothing. That same no-op covers a decision on an alert that only
ever asked for a `REVIEW` and never stopped the payment, which is a legitimate thing for an analyst
to do.

### Chargebacks the platform cannot have

`prior_chargeback_count` is one of the strongest features in real fraud detection. This platform
has no card network, so it has no chargebacks, and inventing a column called `chargebacks` would be
describing something impossible.

Instead the feature is derived from **analyst REJECT decisions**: a human confirming that a payment
was fraudulent. That is the closest honest equivalent, it is what `RULE_CONFIRMED_FRAUD_HISTORY`
reads, and it closes a real loop — a refusal today makes the next payment on that account score
higher.

### Two defects fixed while writing the Phase 6 tests

- **Time-of-day was derived from the wrong clock.** `FeatureBuilder.toScoreRequest` computed
  `hourOfDay` and `dayOfWeek` from `Instant.now()`, with a comment claiming they came from the
  payment's own timestamp. The saga calls seconds later on a good day and minutes later after a
  recovery sweep, so a 03:00 payment would have been scored as a 03:07 one — or, after a
  redelivery hours later, as a daytime payment altogether. Time-of-day is a real fraud feature, so
  the drift was silent and wrong in the direction that matters. `FraudContext` now carries
  `occurredAt` and both features are derived from it.
- **The saga did not retain the risk context.** Device, IP, coordinates and channel arrive on
  `payment-created`, and nothing needed them while the risk step was auto-approved. A saga resumed
  by the recovery sweep no longer has the event, so it would have scored the payment with
  `RiskSignals.unknown()` — every device and location feature absent, with no error. `risk_signals`
  is now a `JSONB` column on `payment_sagas`, stored verbatim and forwarded unchanged.

### What is deliberately absent

- **No route to `ml-service`.** The gateway publishes nothing for it; the model is reachable only
  from `fraud-service` inside the network. An exposed scoring endpoint would let anyone map the
  decision boundary by probing it, which is what makes a fraud model cheap to evade.
- **No endpoint that sets a score or edits a verdict.** An assessment is a statement about a
  payment at a moment in time. An analyst's disagreement is recorded *alongside* it as an
  append-only `AnalystDecision`, and a database trigger refuses `UPDATE` and `DELETE` on that table
  — the same discipline the ledger applies to its journal, for the same reason.
- **No configurable country risk list.** `ml-service` uses country as a feature, and the geography
  handling lives in reviewed source rather than in an environment variable. A country risk list
  that can change without review is a discrimination exposure.



---

## API documentation

Each service serves its own OpenAPI 3 document; the gateway aggregates them.

| | |
|---|---|
| Aggregated Swagger UI | http://localhost:8080/swagger-ui.html |
| Per-service spec | `http://localhost:<port>/v3/api-docs` |

Core platform endpoints:

```
POST   /api/auth/register              create an account, returns a token pair
POST   /api/auth/login                 authenticate
POST   /api/auth/refresh               rotate the refresh token
POST   /api/auth/logout                revoke this session, or all sessions
POST   /api/auth/token                 client-credentials grant (services only)
GET    /.well-known/jwks.json          public keys for token validation

GET    /api/users/me                   profile
PATCH  /api/users/me                   update profile
POST   /api/users/me/change-password   revokes all sessions on success
GET    /api/users/me/sessions          active sessions

GET    /api/admin/users                search            (ADMIN | FRAUD_ANALYST)
POST   /api/admin/users/{id}/disable   disable + revoke  (ADMIN)
GET    /internal/users/{id}            identity view     (SERVICE)
```

wallet-service (port 8082). Movement endpoints require an `Idempotency-Key` header:

```
POST   /api/wallets                          open a wallet in one currency
GET    /api/wallets                          list the caller's wallets
GET    /api/wallets/balance?currency=INR     balance / held / available
GET    /api/wallets/{id}                     one wallet          (owner only)
GET    /api/wallets/{id}/transactions        statement, newest first
POST   /api/wallets/{id}/deposit             credit    (Idempotency-Key)
POST   /api/wallets/{id}/withdraw            debit     (Idempotency-Key)
GET    /api/wallets/admin/{id}/reconcile     per-wallet consistency (ADMIN | FRAUD_ANALYST)
GET    /api/wallets/admin/reconcile-ledger   compare all wallet balances with ledger liabilities
                                             (ADMIN | FRAUD_ANALYST; retry first mismatch after Kafka catch-up)

POST   /internal/wallets/reserve             hold funds          (SERVICE)
POST   /internal/wallets/capture             debit + credit, one transaction (SERVICE)
POST   /internal/wallets/release             compensating release (SERVICE)
POST   /internal/wallets/extend              keep a hold alive past its TTL (SERVICE)
                                             monotonic, capped at 7 days
GET    /internal/wallets/{id}                balance view        (SERVICE)
GET    /internal/wallets/by-user/{id}        resolve by user     (SERVICE)
```

payment-service (port 8084):

```
POST   /api/payments                    submit a transfer    (Idempotency-Key)
                                        -> 202 Accepted, status CREATED
                                           Location: /api/payments/{paymentId}
GET    /api/payments/{id}               read a payment       (either party)
                                           poll this for the outcome
GET    /api/payments/history            history, filterable by status
POST   /api/payments/{id}/refund        full or partial refund (Idempotency-Key)
                                           -> 200, synchronous
```

payment-orchestrator (port 8085). **Staff only**, and read-mostly: there is deliberately no
endpoint that starts a payment, and none that edits a saga's state. Orchestration is triggered by
`payment-created` and by nothing else, and a saga advances only through the orchestrator or the
recovery sweep — both of which drive it through the state machine rather than around it.

```
GET    /api/orchestration/payments/{id}          a payment's saga in full: state, step log
                                                 and every provider call
GET    /api/orchestration/sagas/{sagaId}         the same, by saga id
GET    /api/orchestration/sagas                  list, filterable by status or provider
GET    /api/orchestration/sagas/uncompensated    money that may be stranded. Expected empty.
GET    /api/orchestration/sagas/held             payments parked awaiting a fraud analyst,
                                                 longest wait first. Excluded from the
                                                 recovery sweep, so this list growing is the
                                                 ONLY signal the decision consumer has stopped.
GET    /api/orchestration/stats                  in-flight and terminal counts, throughput
                                                 per provider, fallback count, held count
GET    /api/orchestration/providers              kill switch + observed health + live breaker
                                                 state, plus the combined `routable` verdict
GET    /api/orchestration/providers/routing      explain how a charge would be routed
                                                    (all of the above: ADMIN | FRAUD_ANALYST)

POST   /api/orchestration/providers/{id}/disable take a rail out of service   (ADMIN)
                                                 mandatory reason; persists
POST   /api/orchestration/providers/{id}/enable  return it to service         (ADMIN)
POST   /api/orchestration/recovery/sweep         run the stalled-saga sweep now (ADMIN)

POST   /internal/providers/{id}/simulate         arm a fault on a SIMULATED rail  (SERVICE)
POST   /internal/providers/{id}/simulate/reset   clear its faults and authorisations (SERVICE)
GET    /internal/providers/simulation            what each rail is rigged to do (SERVICE)
POST   /internal/providers/probe                 run the health sweep now       (SERVICE)
```

ledger-service (port 8083). The staff-facing surface is **read-only**: a posting is a
consequence of a business event, never something entered by hand.

```
GET    /api/ledger/journal                          browse postings, newest first
GET    /api/ledger/journal/{sequence}               one posting with all of its legs
GET    /api/ledger/postings/{ledgerTransactionId}   one posting by idempotency key
GET    /api/ledger/payments/{id}/postings           every posting a payment produced
GET    /api/ledger/accounts                         the chart of accounts
GET    /api/ledger/accounts/{accountId}             one account balance
GET    /api/ledger/accounts/{accountId}/entries     account statement
                                                    (all of the above: ADMIN | FRAUD_ANALYST)

GET    /api/ledger/admin/trial-balance              debits == credits, the core identity
GET    /api/ledger/admin/reconcile                  every consistency check
GET    /api/ledger/admin/accounts/{id}/reconcile    replay one account from the journal

POST   /internal/ledger/postings                    post a balanced entry     (SERVICE)
POST   /internal/ledger/postings/for-payment        book a completed payment, returning
                                                    once durably committed     (SERVICE)
                                                    the saga's pre-capture call
POST   /internal/ledger/postings/{id}/reverse       append the exact opposite (SERVICE)
GET    /internal/ledger/postings/{id}               read a posting            (SERVICE)
GET    /internal/ledger/payments/{id}/postings      postings for a payment    (SERVICE)
GET    /internal/ledger/wallet-liabilities          balances for cross-checking (SERVICE)
```

fraud-service (port 8086). The staff surface is read-mostly: there is deliberately no endpoint
that sets a score or edits a verdict. An analyst's disagreement is recorded *alongside* the
machine's verdict as an append-only decision, never as an edit.

```
GET    /api/fraud/assessments                    browse verdicts, newest first. Filterable
                                                 by riskLevel, decision or userId.
GET    /api/fraud/assessments/{paymentId}        the verdict on one payment
GET    /api/fraud/assessments/{paymentId}/detail the dispute view: verdict + the exact feature
                                                 vector scored + every human decision on it
GET    /api/fraud/alerts                         the work queue. Ordered by blocksPayment
                                                 BEFORE risk score: a held payment is a
                                                 customer waiting right now.
                                                 ?blockingOnly=true narrows it to those.
GET    /api/fraud/alerts/{alertId}               one alert
GET    /api/fraud/alerts/{alertId}/decisions     every decision on it, oldest first
GET    /api/fraud/stats                          24h decision mix, degraded count, blocking
                                                 count, mean latency, score-by-model drift
GET    /api/fraud/engine                         thresholds, every rule id, and the model's
                                                 own provenance + synthetic-data caveat

POST   /api/fraud/alerts/{alertId}/take          claim it; assignee comes from your token
POST   /api/fraud/alerts/{alertId}/decide        RELEASE or REJECT. Reason MANDATORY.
                                                 Returns as soon as the decision is durable;
                                                 the payment resumes asynchronously.
                                                    (all of the above: ADMIN | FRAUD_ANALYST)

POST   /internal/fraud/assess                    the saga's risk gate. Idempotent on
                                                 paymentId; always 200, check degradedMode
                                                                              (SERVICE)
GET    /internal/fraud/assessments/{paymentId}   read a stored verdict after a lost response
GET    /internal/fraud/engine                    configuration + model provenance   (SERVICE)
```

ml-service (port 8000). **No gateway route** — reachable only from `fraud-service` inside the
network. Authenticates with `X-API-Key`, not a bearer token.

```
POST   /score                          calibrated probability, 0-100 score, per-feature
                                       ablation explanations, model version.
                                       Returns NO risk level and NO decision.
GET    /model                          model provenance, training metrics, synthetic-data caveat
GET    /health                          liveness
GET    /health/readiness                additionally requires the model artifact loaded and warmed
GET    /metrics                         Prometheus
```

ai-investigation-service (port 8087). Nothing here acts: the service holds no credential for any
internal write API, so the only effect an authenticated analyst can have is causing a report to be
written. **No `/internal/**` endpoints at all** — it is driven by Kafka, and having none is part of
the point.

```
GET    /api/investigations                       browse reports
GET    /api/investigations/{id}                  one report
GET    /api/investigations/{id}/detail           the report + its citations + the evidence vector
                                                 it was written from + every human verdict.
                                                 Read `hallucinated` on a citation first: set
                                                 means the generator named a document it was
                                                 never shown.
GET    /api/investigations/payments/{paymentId}  every investigation of one payment, newest
                                                 first. More than one is normal: an analyst
                                                 re-run is recorded separately from the
                                                 automatic pass.

POST   /api/investigations                       investigate on demand. The evidence is SUPPLIED,
                                                 not fetched, because this service cannot look a
                                                 payment up. `paymentDescription` is treated as
                                                 untrusted customer text.
POST   /api/investigations/{id}/review           whether you agreed. Your own assessment in the
                                                 same vocabulary, not a bare agree/disagree: the
                                                 DIRECTION of a disagreement is what tells you
                                                 whether the AI is consistently too cautious or
                                                 too relaxed. One review per report.

GET    /api/investigations/policies              the closed corpus a report may cite
GET    /api/investigations/policies/{documentId} one policy in full
GET    /api/investigations/engine                retrieval + generation configuration, including
                                                 `semanticRetrieval: false` — the default
                                                 embedder is lexical, so "no policy matched"
                                                 means "no shared vocabulary", not "nothing
                                                 relevant exists"
GET    /api/investigations/stats                 24h volume, recommendation mix, per-generator
                                                 confidence and grounding. Two numbers deserve
                                                 alerts: `ungrounded24h` (reports written with
                                                 no written policy behind them) and
                                                 `withHallucinations24h` (a generator inventing
                                                 sources). Neither produces an error.
                                                    (all of the above: ADMIN | FRAUD_ANALYST)
```

notification-service (port 8088). Two surfaces, deliberately in two controllers so they are not one
`@PreAuthorize` slip apart. The customer one takes its user id from the token and never from a path
parameter — there is no endpoint below naming a user.

```
GET    /api/notifications                        the caller's in-app inbox, newest first.
                                                 ?unreadOnly=true narrows it. The body is the
                                                 text rendered when the notification was
                                                 created, retained verbatim: re-wording a
                                                 template does not change what is shown.
GET    /api/notifications/unread-count           its own endpoint so a client can poll a badge
                                                 without fetching bodies
POST   /api/notifications/{deliveryId}/read      idempotent; the first read is the one recorded.
                                                 404 (not 403) for somebody else's notification:
                                                 a 403 would confirm the id exists.
POST   /api/notifications/read-all               mark every unread in-app entry read

GET    /api/notifications/preferences            returns defaults for a user who has never set
                                                 any, WITHOUT creating a row - a read that wrote
                                                 would put a write lock on this table in the
                                                 delivery path. The phone number is masked even
                                                 for its owner.
PUT    /api/notifications/preferences            email / in-app / locale. Governs ROUTINE
                                                 notifications only: a customer cannot switch off
                                                 being told their payment failed.
POST   /api/notifications/preferences/sms        opt in. Records the E.164 number and the consent
                                                 TOGETHER, because one without the other is
                                                 either a number held for no stated purpose or a
                                                 setting that can never send.
DELETE /api/notifications/preferences/sms        withdraw consent and FORGET the number
GET    /api/notifications/history                every notification to the caller across all
                                                 channels, with each channel's status
                                                                    (all of the above: the caller)

GET    /api/notifications/{id}/detail            what exactly we told this customer and when:
                                                 rendered subject and body per channel plus the
                                                 full attempt history
                                                                    (ADMIN | FRAUD_ANALYST)
```

```
GET    /api/admin/notifications/dead-letters     THE queue that matters. Every row is a customer
                                                 who was never told something the platform
                                                 decided to tell them - and the payment itself
                                                 succeeded, so nothing else reports it.
                                                 ?channel=EMAIL narrows it. Addresses masked.
GET    /api/admin/notifications/deliveries/{id}/attempts
                                                 why it kept failing. Four identical timeouts is
                                                 a host that is down; four different errors is
                                                 something else.
POST   /api/admin/notifications/dead-letters/{id}/replay
                                                 requeue once the cause is fixed. The attempt
                                                 budget is EXTENDED, not reset. Does not
                                                 re-render: a template fix needs the notification
                                                 re-emitted, not replayed.

GET    /api/admin/notifications/engine           which channels can actually be delivered.
                                                 `transportsSimulated: true` on this platform,
                                                 and PUSH is listed under unavailableChannels.
GET    /api/admin/notifications/stats            24h volume, per-channel/status counts,
                                                 deadLettered24h
GET    /api/admin/notifications/templates        the copy that exists. A key not listed here is
                                                 suppressed with reason NO_TEMPLATE.
GET    /api/admin/notifications/payments/{id}    who was told what about one payment
                                                                    (ADMIN | FRAUD_ANALYST)
```

Every error uses one body shape, with a `correlationId` that can be quoted to
support to locate the request across all ten services:

```json
{
  "timestamp": "2026-09-03T10:15:30.123Z",
  "status": 422,
  "code": "INSUFFICIENT_FUNDS",
  "message": "Insufficient available balance.",
  "path": "/api/payments",
  "correlationId": "c7f1e2a9-4b3d-4e8a-9f21-7c5d3a1b8e40"
}
```

---

## Observability

- **Structured JSON logs** with `traceId`, `spanId`, `correlationId`, `userId` and
  `service` on every line. `SPRING_PROFILES_ACTIVE=local` switches to a readable
  console format.
- **Correlation IDs** originate at the gateway and survive every hop — including
  Kafka, where they travel as record headers and are restored by the consumer.
- **Prometheus metrics** at `/actuator/prometheus`; names are centralised in
  `MetricNames` because a metric name is an API that dashboards depend on.
- **Orchestration metrics worth alerting on**, all from Phase 5:
  `aegis.saga.compensation.failed` and `aegis.saga.uncompensated` — every non-zero value is
  money that may be reserved at a wallet or authorised at a provider with nothing scheduled to
  release it; `aegis.saga.recovered` — each increment means an orchestration was interrupted, so
  a sustained rate is a real signal rather than background noise;
  `aegis.provider.fallback` — a payment that fell back looks like an ordinary success everywhere
  else, so this is the only visible sign that routing did its job;
  `aegis.provider.health` (1 when routable, 0 when not) and
  `resilience4j_circuitbreaker_state`. Note that `aegis.payment.accepted` and
  `aegis.payment.outcome` are deliberately separate meters: since Phase 5 "accepted" and
  "completed" are different events at different times recorded by different services, and
  combining them would make the success rate read as 100% for as long as the saga takes.
- **Fraud metrics worth alerting on**, from Phase 6:
  `aegis.fraud.degraded.decision` — every increment is a verdict reached **without** the model, on
  rules alone. A degraded fraud posture produces no errors and is otherwise invisible, so this is
  the one number that reports it. It has its own counter as well as a `degraded` tag on
  `aegis.fraud.decision`, so an alert can fire on the rate directly rather than summing a tagged
  series;
  `aegis.fraud.score` — a distribution summary, because a fraud model degrades *silently*: it keeps
  returning numbers, and the shape of their distribution is what moves first. Grouped by model
  build on `/api/fraud/stats` so a shift can be attributed to a deployment rather than to the
  world;
  `aegis.fraud.decision.latency` — this call sits on the payment's critical path, so it is also the
  saga's remaining headroom before the provider timeout;
  `aegis.fraud.rule.hit` — tagged by `ruleId`, which is why those ids are constants rather than
  inline strings: renaming one silently breaks a dashboard and orphans its history.
  Two numbers on `/api/fraud/stats` deserve their own alerts: `degradedAssessments24h` and
  `blockingAlerts` — the second is a count of customers whose funds are reserved waiting on a
  human.
- **AI investigation metrics worth alerting on**, from Phase 7:
  `aegis.ai.hallucinated.citation` — tagged with the invented document id, and the single most
  important number this service produces. A generator that cites a document it was never shown is
  producing something that looks like evidence and is not, and nothing else in the system would
  report it. The tag matters as much as the count: a generator reliably citing `POLICY-AML` is
  telling you the corpus is missing a document somebody expects to exist;
  `aegis.ai.ungrounded.investigation` — a report where nothing cleared the retrieval floor. Not an
  error, and the narrative says so honestly, but a rising rate means the knowledge base has drifted
  away from the traffic;
  `aegis.ai.rejected.recommendation` — the model answered outside the permitted vocabulary. Either a
  confused model or a successful prompt injection, and both are worth a look;
  `aegis.ai.retrieved.chunks` — a distribution summary, because retrieval degrades the same silent
  way a model does: it keeps returning rows;
  `aegis.ai.retrieval.latency`, tagged by embedder, because swapping in a hosted embedding model
  changes it by orders of magnitude and an untagged timer would average the two into a number that
  describes neither.
- **Notification metrics worth alerting on**, from Phase 8:
  `aegis.notification.dead.lettered` — tagged by channel, and the number to page on. Every increment is a
  customer who was never told something the platform decided to tell them, and because the payment itself
  succeeded no error surfaces anywhere else;
  `aegis.notification.suppressed` — tagged by channel *and reason*, which is what makes the reason an enum
  rather than free text. A large `SMS_NOT_OPTED_IN` count is normal; a rising `NO_TEMPLATE` count means a
  producer is emitting notifications nobody wrote copy for and those customers are receiving nothing;
  `aegis.notification.retry.backlog` — a gauge, refreshed on the sweep the worker was going to run anyway
  so a metrics scrape costs no database load. A backlog is normal; a backlog that stops falling is not, and
  only the trend distinguishes them;
  `aegis.notification.delivery.attempt` — counts *attempts*, not notifications, tagged by channel and
  outcome. A per-notification count would hide a channel that fails every time, because the notification as
  a whole would still look delivered;
  `aegis.notification.duplicate` — expected to be non-zero forever. This is de-duplication working rather
  than a fault, so it is watched for a change in rate rather than for any value at all.
- **Health probes** — `/actuator/health/liveness` and `/health/readiness`.
  Readiness includes the database and Redis so an unhealthy pod leaves the load
  balancer; **liveness deliberately excludes every dependency**, because a liveness
  probe that checked Redis would restart every pod during a Redis outage and turn a
  degraded cache into a total outage.

---

## Phase 7 — AI Investigation

Phase 6 produced verdicts. Phase 7 explains them: `ai-investigation-service` takes a flagged
payment, retrieves the fraud policy that applies to it, and writes a narrative that cites what it
used — then checks every citation against what was actually retrieved.

The interesting problem here is not generating readable text. It is that **a wrong investigation
reads exactly like a right one.** A narrative citing irrelevant policy, a narrative citing a
document that does not exist, and a narrative with no grounding at all are all fluent, plausible
and confident. Almost every design decision below exists to make one of those states detectable.

### The AI can only recommend, and that is structural

The guarantee is enforced by **absence**, not by naming. The service runs with
`aegis.service-auth.enabled: false`, so it holds **no credential that any internal write API would
accept**. It cannot move money, refuse a payment or restrict an account — not because no handler
tries to, but because there is nothing for one to authenticate with.

Everything else follows from that:

- **It cannot fetch evidence either.** That is the cost, and it is why the evidence pack is
  assembled entirely from the `investigation-requested` event, and why the on-demand endpoint makes
  the analyst supply what they are asking about.
- **`Recommendation` is a closed enum plus a database `CHECK`.** Seven values, and none of them
  reads as a command — there is no `BLOCK_ACCOUNT` and no `RELEASE_PAYMENT`. The most severe value
  is `RECOMMEND_REJECT_AND_REVIEW_ACCOUNT`, which is a sentence addressed to a person.
- **The recommendation crosses the wire as a `String`, not an enum**, so no consumer can
  deserialise it into something it then switches on as an instruction.
- **`AiTransactionBoundaryTest`** scans the service's fields to stop a wallet, ledger, payment or
  fraud client being added later. The absence is the control, so something has to defend it.

### The embedder is lexical, and the README says so

The default embedder is **deterministic offline feature hashing**: word unigrams and bigrams plus
character 4-grams, sublinear term frequency, L2-normalised, FNV-1a seeded, 384 dimensions. No model
file, no network, no API key.

**It is lexical, not semantic.** It cannot see that "the account drained itself" and "funds were
rapidly withdrawn" mean the same thing, and it cannot see negation at all —
`HashingEmbedderTest.Limits` asserts those failures rather than hiding them. Calling it a semantic
embedder would be the single most dishonest thing this service could do, because it changes how a
null result must be read: with a lexical embedder, "no policy matched" means *no policy shared
vocabulary with the query*, not that no relevant policy exists. That distinction reaches the API as
`semanticRetrieval: false`.

Rejected alternatives, and why:

- **A hosted embedding API.** Needs a key the platform must run without, and makes retrieval
  untestable offline.
- **IDF weighting.** Would make the embedder *stateful*: adding one policy would silently change
  every existing vector, so a citation written last year would no longer be reproducible.
- **`String.hashCode`.** Clusters badly on short similar strings, which is exactly the input here.

What makes the lexical embedder defensible is that the query vocabulary is *rule ids and feature
names*, and the corpus is authored to explain those exact tokens. Plus determinism: the same
evidence retrieves the same policy in five years, which is what makes a citation an audit record
rather than a snapshot of a vendor's model.

### Retrieval is a hybrid, and the declared half comes first

Two routes, in order:

1. **The author-declared mapping.** Each policy's front matter lists the rule ids it explains, and
   a fired rule looks its policies up **exactly**. When `RULE_BUST_OUT_PATTERN` fires, the document
   defining bust-out is not merely relevant — it is what a reviewer expects to see cited, and its
   presence must not depend on an embedder having learned that a machine token relates to a
   paragraph of prose.
2. **Vector search**, which widens the net to policy relevant to the payment's *shape* — a large
   amount, a new device, an unfamiliar country — and is the only route available for a model-only
   verdict where no rule fired.

Declared matches outrank searched ones regardless of similarity, so the prompt budget is spent on
the authoritative document before the merely similar one. A declared match reports similarity
`1.0`, because nothing was compared and reporting a measured value would invent a number.

The lookup uses `LIKE '%,' || :ruleId || ',%'` over a comma-delimited string **on both sides**. A
bare `LIKE '%id%'` would let `RULE_CARD_TESTING` match `RULE_CARD_TESTING_VELOCITY`, and a citation
pointing at the wrong policy is worse than no citation because it looks correct.
`PolicyDocumentLoaderTest` also asserts the corpus declares every rule the engine can fire **and no
rule it cannot** — a misspelled id there would silently match nothing forever while reading in
review as though it were wired up.

### The similarity floor is the most consequential number in the service

An approximate nearest-neighbour search **always returns its k nearest neighbours, however far away
they are.** Without a floor, a payment with no relevant policy retrieves the five least-irrelevant
documents in the corpus and the narrative cites them as though they mattered — fluently, with no
error anywhere.

The floor (`0.28` for the default embedder) is what makes **"no policy matched" an expressible
outcome**, and a report that says so is far more useful than one padded with the closest available
text.

It is applied **in SQL, after the ANN ordering**: the subquery does `ORDER BY embedding <=> :query
LIMIT :k` so the HNSW index is used, and the outer `WHERE similarity >= :floor` filters the result.
Inverted, the floor becomes a predicate on a computed distance, the planner drops the index, and
retrieval turns into a sequential scan over the corpus.

Related choices in the same area:

- **HNSW, not IVFFlat.** An IVFFlat index must be built *after* the data is loaded and is silently
  useless when built empty — and this corpus is seeded at startup, so the index would be created
  before there was anything to cluster.
- **The `embedding` column is deliberately not JPA-mapped.** Hibernate has no type for
  `vector(384)`, and `<=>` has no JPA expression, so a mapped field would be a field nothing could
  use. `VectorStore` owns the column through `JdbcTemplate`; `PolicyChunk` carries the metadata.
  This is also why `ddl-auto: validate` passes.
- **The embedder id is stored on every chunk and every investigation, and retrieval filters on
  it.** Comparing vectors across embedders does not fail — it returns confident nonsense with
  entirely reasonable-looking similarities. Filtering makes an embedder change *loud*: retrieval
  returns nothing until the corpus is rebuilt, which the startup seeder then does automatically.

### Citation validation is the strongest check in the service

Hallucination is normally hard to detect because there is nothing to check against. Here there is:
**the corpus is closed and the prompt contents are known exactly**, so a citation naming anything
outside the retrieval set was *invented* — not "probably wrong", invented, with certainty.

An invented citation is **recorded, not dropped**. Dropping it is the obvious move — it is
worthless as evidence — and it would destroy the only trace of the generator having fabricated
something, which is the most important thing this service can tell anyone about the model it is
running. So it is persisted with `hallucinated = true`, zero similarity and no chunk id. This is
also why `investigation_citations` deliberately has **no foreign key** to `policy_documents`: the
table has to be able to store a reference to a document that does not exist.

Hallucinated ids are **excluded from the `investigation-completed` event** while staying in the
audit trail. A consumer receiving one would have no way to know it was invented.

### The offline composer is a composer, not a model

The default generator writes a real report from the evidence and the retrieved policy, with no
network and no key. It is called a **composer** rather than an "offline LLM" because that is what it
is: it **cannot hallucinate** — it has no facts beyond its arguments — it is reproducible, and its
recommendation is a stated rule rather than a judgement. Every report it writes is flagged
`deterministicFallback: true`, so an analyst never mistakes its restraint for analysis.

It **escalates only.** Its recommendation starts from a floor set by the `RiskLevel` in the verdict
and can go up, never down. fraud-service had the full feature vector; an investigation that softened
its conclusion would be overruling a better-informed control with a worse-informed one.

`Recommendation.whenUncertain()` returns `ESCALATE_TO_SENIOR_ANALYST`, never
`NO_ACTION_REQUIRED`. **A generation failure is not evidence that a payment is fine.**

### Prompt injection, and where it actually stops

The customer controls the payment description, the user agent and the merchant category. All three
are treated as hostile:

- **Isolated in their own type.** `EvidencePack.Untrusted` is a separate record rather than three
  more fields, so a future field cannot be added to the trusted side by accident — putting a
  customer-supplied string next to `fraudProbability` would otherwise be a one-line change with no
  visible consequence until somebody wrote it into a prompt as a fact.
- **Placed last, after the instructions**, capped at 240 characters, newlines flattened, control
  characters stripped. A newline is more dangerous than the text: it can forge structure inside the
  prompt, closing a delimiter block and opening what looks like a new instruction section.
- **Delimiter markers neutralised.** The fence was decorative until a test pointed out that customer
  text can simply *contain* the closing marker; `neutralise()` now replaces it with
  `[removed: delimiter]`.
- **Excluded from the retrieval query**, which is built from platform-derived fields only. Otherwise
  a customer could choose which policy their own investigation cites.
- **`ReportParser` keeps the *first* of any duplicated section.** A second, softer
  `RECOMMENDATION:` further down is precisely the shape a successful injection produces; taking the
  last would hand it the win.

None of that is the real defence. **The real defence is the closed vocabulary**: the worst a fully
successful injection achieves is a value already on the permitted list, and anything unrecognised
becomes `ESCALATE_TO_SENIOR_ANALYST` with the model's own rationale replaced — quoting it beside the
escalation would explain a decision nobody made. There is deliberately **no fuzzy matching**:
guessing at the closest permitted value is exactly how an injected instruction becomes a decision
about somebody's money.

### Two asymmetries worth stating

**Seeding the knowledge base is fatal; a missing LLM key only warns.** A service that came up with
an empty corpus would answer every investigation ungrounded and report no error — the reports would
still be produced, still be read, and still look like the grounded ones. So an unusable corpus
prevents startup. A missing API key falls back to the composer, because a fallback generator still
produces a truthful report while there is **no fallback for having no policy to reason from**.

**Investigations arrive by Kafka, not by a synchronous call** — the opposite of the saga's risk
step, and for the opposite reason. The saga cannot continue without a verdict, so `ASSESS_RISK` is
synchronous. An investigation is **not** on the payment's critical path: fraud-service has already
decided and the payment is already held, so a twenty-second model call must not sit in front of it.

Idempotency is a **partial unique index** — `UNIQUE (payment_id) WHERE NOT on_demand` — so at-least-
once delivery cannot produce two automatic reports, while an analyst can still deliberately re-run
one. Two differently-worded narratives about the same payment, with no way to tell which one the
other analyst read, is a worse outcome than a missing report.

### Four defects found by writing the Phase 7 tests

- **A self-invoked `@Transactional` method, again.** `KnowledgeBaseSeeder.seedOne` was
  `@Transactional protected` and called as `this.seedOne(...)`, so Spring's proxy was bypassed and
  every seed write ran **untransacted** with no symptom. Same trap as the Phase 2 auth defect.
  Extracted into a `PolicyWriter` bean so the annotation takes effect at all.
- **The citation validator ran twice and double-counted every hallucination.** Once to get the
  count for the report, once to build the rows. A metric that reports double is worse than no
  metric, because a threshold set against it looks calibrated. Restructured into
  `validate(...) -> Result` with `Result.citationsFor(investigationId)`, called exactly once. The
  same fix added claimed-id de-duplication — a model repeating a citation would otherwise produce
  two rows for one document and violate `uq_citation_per_investigation`, failing the whole report
  over a formatting quirk.
- **The prompt fence was decorative.** Customer text could contain the literal
  `<<<END_UNTRUSTED_CUSTOMER_TEXT>>>` marker and close the block itself.
- **Every correctly-formatted citation was being recorded as a hallucination.** The prompt asks for
  comma-separated ids, but a model routinely renders one section in the style of its neighbour, and
  `FINDINGS` directly above it is a bullet list. `parseCitations` stripped `[`, backticks and
  parentheses but **not** list markers, so `- POLICY-BUST-OUT` did not match `POLICY-BUST-OUT`. The
  hallucination alert would have fired on every investigation, the completed event would have
  carried no verified sources, and the narrative would have read perfectly — the failure would have
  looked like a dishonest model rather than a parser bug, which is the worst possible place to send
  somebody debugging.

---

## Phase 8 — Notifications

Every phase before this one changed something about a payment. This one tells the customer: email, SMS and
an in-app inbox, rendered from a template catalogue and delivered once per channel.

The integration point already existed. `payment-service` has been publishing `notification-required` from
its outbox since Phase 3 — one event per interested party, carrying a template key, variables and a
deduplication key rather than pre-rendered text. Phase 8 is the consumer.

**Consuming that one topic, rather than the payment events directly, is the first real decision.**
Subscribing to `payment-completed` would have been easy and wrong twice over: payment-service already
emits a notification request for it, so every customer would get two of everything; and *whether* to
notify — and which party — is a question about the payment, which only payment-service can answer. This
service owns how a message is worded and delivered. That split is what lets copy change without
redeploying payment-service.

### The distinction the whole service is built around

**A failure to persist throws. A failure to send does not.**

The platform already gives this service a retry-and-dead-letter path for free: the shared
`DefaultErrorHandler` retries a failed record and publishes it to `notification-required.DLT`. That is the
right mechanism for a record that could not be *processed*, and it is still used.

It is the wrong mechanism for a message that could not be *sent*:

- **A partition is ordered.** Backing off a record because one SMTP host is slow stops every later
  notification on that partition — other customers, other payments.
- **Redelivery replays the whole event.** Channels that already succeeded would be attempted again, so a
  transient SMS failure would re-send the email that already arrived. The customer would get one SMS and
  four emails, and every one of those emails would be correct — which is exactly why nobody would spot it
  in a log.
- **A DLT is a topic, not a work queue.** Nothing retries it automatically. Inspection, reconciliation, approval, and header-preserving replay are defined in [`docs/KAFKA_DLT_RUNBOOK.md`](docs/KAFKA_DLT_RUNBOOK.md).

So a send failure is committed as state — `status = FAILED`, `next_attempt_at` set — and a scheduled
worker drains it. The Kafka offset advances, because the event genuinely *has* been processed: the durable
record of what must be sent now exists.

The converse matters just as much and is easy to get wrong in the other direction. If the notification
cannot be *written*, nothing durable exists and Kafka's redelivery is the only thing that will ever bring
the event back — so that exception propagates. `NotificationDeliveryService` throws
`IllegalArgumentException` for an unusable payload specifically because the shared handler treats it as
non-retryable and dead-letters it on the first attempt: a payload with no recipient will be just as
unusable on the fourth try, and retrying only delays the record somebody needs in order to fix the
producer.

### Two tables, because they fail differently

`notifications` records **what** the customer was told and is immutable — append-only at the database,
with `updatable = false` on every column so Hibernate never generates an UPDATE to be refused. A corrected
message is a new notification, because editing the record of a message that has already been sent only
makes the record wrong.

`notification_deliveries` records **whether** each channel got it, and is mutable by design: it is the work
queue. One row per `(notification, channel)`, with a unique constraint on the pair.

Splitting them is not tidiness. If the retry schedule lived on the notification, a transient SMS failure
would re-send the email — see above.

A third table, `notification_delivery_attempts`, holds one append-only row per transport call. A counter
says a delivery failed four times; these rows say it failed four times with the *same* connection timeout,
which is a host that is down, versus four different errors, which is something else needing a different
fix. There is a second reason: replaying a dead delivery overwrites its `last_error`, so without this the
history of why it died would be destroyed at the moment an operator started investigating it, by the
action they took to investigate it.

### Idempotency, in three layers

1. **`ProcessedEventRegistry`** — Redis, 24-hour window, free from `EventConsumerSupport`. An
   optimisation.
2. **`UNIQUE (deduplication_key)`** — the guarantee. The application-level check before it is *also* only
   an optimisation: two consumer threads on two instances would both pass it, and only the database can
   arbitrate. Losing that race is treated as success, because it is — the notification exists and another
   thread is delivering it.
3. **`UNIQUE (notification_id, channel)`** — stops a retry racing a redelivered event into two EMAIL rows
   for one notification, which would send the message twice.

The Redis layer alone would not be enough even if Redis never failed: two *different* events can legitimately
carry the same deduplication key, and the semantic identity is the key rather than the event id.

### The retry queue

Claimed with `FOR UPDATE SKIP LOCKED`, which is what makes the worker safe on every instance rather than
leader-elected. Without it two pods would read the same rows and both send; with a plain `FOR UPDATE` they
would not duplicate but would serialise — the second pod blocking for the length of an SMTP timeout, so
adding instances would make the backlog drain no faster.

The claim query returns *ids*, not entities, and each delivery is then re-read in its own short
transaction. Holding the claim lock across a series of transport calls is precisely what this design set
out to avoid. The one race that remains — the worker holding a row while an operator replays it — is caught
by an optimistic-lock column.

Ordering is by priority and then by due time, using a `CASE` rather than `ORDER BY priority`: the column
stores the enum name, and alphabetically `HIGH` sorts before `LOW` before `NORMAL`, which would put the
least urgent notifications in the middle and look approximately right in testing.

Backoff is exponential with **jitter**, and the jitter is not optional. Every instance sweeps on the same
interval and a provider outage fails every delivery at once; without it the whole backlog would come due
in the same second and re-overload the provider that had just recovered, then fail together again in
lockstep until the attempts ran out. The ceiling is applied *before* jitter, so the configured maximum
bounds the real schedule rather than being exceeded by the jitter factor.

A **replay extends the attempt budget rather than resetting it.** A delivery that already failed eight
times resumes with a smaller allowance; zeroing the counter would let a permanently broken destination be
replayed indefinitely, each replay looking like a fresh start and costing a full run of attempts.

### What a preference cannot do

Channel preferences govern LOW and NORMAL priority only. A **HIGH-priority notification ignores the
opt-out entirely**, and that rule is compiled in rather than configurable — a flag able to switch it off
would be one environment variable between a customer and never being told their account was accessed from
a new device, and the change would read as routine tuning in a diff.

The reasoning is that a preference exists to stop routine noise. Treating "tell me nothing" as covering a
security or money-movement notification would turn a convenience setting into a way of hiding an account
takeover from its victim.

### Where the phone number lives, and why not with the email

**The email address is resolved from auth-service per delivery.** It is an identity attribute: the customer
registered with it and it is verified at signup, so a changed address takes effect on the next
notification rather than whenever somebody remembers to synchronise. It is cached for ten minutes, which
is an honest trade — a customer who changes their email keeps receiving notifications at the old one until
the entry expires, and ten minutes puts that window inside one support call rather than one billing cycle.

**The phone number lives in this service, beside the SMS consent that justifies it.** Nobody logs in with
a phone number here, so it is not an identity attribute; it exists for exactly one purpose. Keeping the two
together means they cannot become separated — a database CHECK pairs them, so SMS cannot be enabled without
both — and it keeps a phone number out of the service every other service queries for identity.
auth-service's internal view deliberately omits it, and Phase 8 did not widen that view.

Withdrawing consent **deletes** the number rather than keeping it for convenience. Its only justification
was the consent, and holding personal data whose stated purpose has been withdrawn is what a consent record
exists to prevent.

### Customer-controlled text is a phishing channel

One notification variable originates with a customer and travels intact: a payment `description`. On
`payment.received` it is written by the **payer** and delivered to the **payee** — the only path on this
platform where one customer can put chosen text in front of another under the platform's own name. An
email from the payment provider saying "your account needs verification, sign in at ..." is more convincing
than anything an attacker could send directly.

So:

- **Every interpolated value is escaped and defanged**, not just the ones believed to be untrusted. A
  per-variable allow-list was rejected: it would need updating whenever a producer adds a field, and the
  cost of forgetting is a live phishing vector. Escaping `2000.00` costs nothing.
- **Links are removed from values.** The rule is that *template text may contain a link and an interpolated
  value may not* — templates are written by this team and reviewed, values are not. A complete rule with no
  judgement calls in it.
- **Newlines are flattened.** A description ending with a blank line and "-- AegisPay Security Team" reads
  as a second, official section of the email.
- **HTML is escaped for email only.** Escaping everywhere would deliver "Rent &amp;amp; bills" to an SMS.
- **Subjects are flattened**, because a newline in a subject is header injection in any real mail
  transport — done here rather than trusted to a future real transport.
- **The SMS version of `payment.received` omits the note entirely.** There is no room for the warning in
  160 characters, and a bare attacker-written sentence arriving as a bank text is a phishing message the
  platform paid to send.

Values whose *names* look like credentials (`otp`, `token`, `apiKey`, …) are redacted **on the way in**, not
just before rendering — a credential that was merely un-rendered would still be sitting in the database and
in the admin API response, which are read by more people than the message is.

### Honest about what is simulated

Email and SMS contact nothing. The classes are named `SimulatedEmailTransport` and `SimulatedSmsTransport`
so nobody has to read them to find out, and their message ids are prefixed `sim-` because a reference
shaped like a real provider's would eventually be pasted into a support ticket and looked up somewhere it
does not exist.

What is *not* simulated is everything around them: rendering, escaping, per-channel idempotency, the retry
schedule, the dead-letter queue and the audit trail all sit above the `NotificationTransport` interface and
behave identically either way. Swapping in a real transport means adding `spring-boot-starter-mail`,
implementing one interface, and mapping its exceptions onto the retryable/permanent distinction.

**The in-app channel is not simulated at all.** An in-app notification is a row this service owns and an
API a client reads, so there is nothing to stand in for — the `IN_APP` delivery row *is* the inbox entry,
with a `read_at` column restricted to that channel by a database CHECK. Nothing here tracks whether an
email was opened, so nothing is allowed to claim it was.

`aegis.notification.transports.simulated-failure-rate` defaults to **zero**. Raising it is how the retry
and dead-letter paths get exercised deliberately — a queue that only fills during a real provider outage is
a mechanism nobody has ever seen work. A non-zero default would make every local run produce dead letters
and teach everyone to ignore the queue.

**PUSH is deliberately unimplemented.** There is no mobile application and no device-token registry, so
`NotificationPayload.security(...)` asking for it produces a row recorded as `SUPPRESSED` with reason
`CHANNEL_UNAVAILABLE` rather than a silent gap. The template parser rejects a `--- PUSH ---` section with
an explanation, because somebody adding push copy is making a reasonable assumption — the channel is in the
shared enum — and deserves to be told why it cannot work.

### Why a missing template suppresses rather than improvises

An unknown template key means a producer is emitting a notification nobody wrote copy for. The tempting
fallback is a generic "there was an update to your payment" so the customer gets *something*. Rejected: the
message would be about somebody's money, and a vague message about money either alarms a customer for no
reason or reassures them when it should not. Nothing is sent, the delivery is recorded as `SUPPRESSED` with
reason `NO_TEMPLATE`, and it is counted loudly. A late notification can be replayed once the copy exists; a
wrong one cannot be recalled.

The same reasoning covers an unresolved placeholder. Sending "You sent {{amount}} INR" is worse than
sending nothing, because the notification is immutable and the broken version becomes the permanent record
of what the customer was told.

Every suppression carries a reason, and suppression is *recorded* rather than skipped: a missing row and a
suppressed row look identical to a customer and completely different to support, and "why did I not get an
SMS" needs an answer.

### Two defects found by writing the Phase 8 tests

- **The link defanger mangled ordinary prose.** The first pattern treated "any word followed by a colon" as
  a URI scheme, on the reasoning that `javascript:` and `data:` have no `//`. It therefore matched
  "Invoice: 4471", which rendered as "[link removed] 4471". The damage would have been silent and permanent:
  notifications are immutable, so every affected message was mangled for good; the customer would see their
  own note corrupted and conclude the platform had lost it; and no error, metric or log line would have
  reported anything, because from the renderer's point of view it had successfully defanged a link. The
  pattern now names the dangerous schemes explicitly and matches a bare domain only for TLDs a mail client
  linkifies — plus any domain followed by a path, since `bit.ly` demonstrates that a TLD allow-list can
  never be complete.
- **Every email greeted "HiAlice,".** The greeting was supplied as `" Alice"` — a bare name carrying a
  significant leading space — so a template could write `Hi{{greetingName?}},` and degrade to "Hi," when no
  name was known. It never worked: every interpolated value is stripped as part of sanitising it, so the
  space vanished. The lesson generalises beyond the bug — a value that carries its own significant
  whitespace cannot survive sanitising, because normalising whitespace *is* part of what sanitising means.
  The service now supplies a complete greeting line and no template does spacing arithmetic.

### One integration defect found while wiring the gateway

`/api/admin/**` was already routed to analytics-service as a catch-all, so `/api/admin/notifications/**`
would have been proxied there and answered with a 404 — which looks like a missing endpoint rather than a
misrouted one, and would have sent somebody debugging the wrong service. The admin route is now declared
*above* the catch-all, and both entries carry a comment saying that the ordering is load-bearing.

Keeping the admin surface under `/api/admin/**` rather than beside the customer inbox was deliberate: that
prefix is what the shared security chain and `GatewaySecurityConfig` both restrict, so three independent
controls now stand in front of a surface that lists which customers were not reached and can re-send
messages to them.

---

## Phase 9 — Admin Dashboard

Two pieces: a read model in **analytics-service**, and a React + TypeScript operations console in
`frontend/`. The console is where an operator sees what the platform did and releases or rejects a payment
that is waiting on a human.

### Counters are not idempotent; projections are

This is the decision the whole read model is shaped around.

Kafka delivers at least once. A redelivered `payment-completed` handed to a counter increments it twice,
and **nothing in the stored data reveals it happened** — the correct value was never written down, so
there is nothing to compare against and no way to repair it. The figure is simply wrong, permanently,
and looks exactly like a busy afternoon.

So analytics-service keeps **one fact row per payment** in `payment_facts` and derives every total, rate
and time series on demand with `date_trunc` and `FILTER`. A redelivered event re-writes the same row: a
no-op. At-least-once delivery therefore *cannot* inflate a total.

Rejected alternatives:

- **Incrementing counters.** Faster to read, unverifiable. See above.
- **A pre-aggregated rollup table.** A counter with extra steps. Same failure, one indirection further away.

The honest trade: at millions of payments a day, deriving aggregates over a scan will need a rollup. The
right shape for one is a job that **replaces** a bucket by recomputing it from the fact rows, never one
that increments — which keeps the property that a redelivery changes nothing.

### Out-of-order events, and the guard that survives them

Kafka orders records within one topic partition and promises nothing across topics. `payment-created` and
`payment-completed` are different topics, so a completion is routinely applied **before** the creation it
belongs to — always when replaying history into a fresh read model, occasionally under normal load.

Without a guard the late `payment-created` overwrites `COMPLETED` with `CREATED`, and the dashboard
reports a settled payment as in-flight. Forever: every event for that payment has already been consumed,
so nothing is left to correct it. No error, no log line, no dead letter.

`StatusRank` fixes an ordering over `PaymentStatus` and `applyStatus` only ever moves forward. Two details
carry the design:

- **`COMPLETED`, `REJECTED` and `FAILED` share one rank**, and the test is *strictly* greater. That makes
  the three endings mutually exclusive: the first to arrive wins, and a second terminal event — a
  redelivery, or a producer defect — cannot rewrite the outcome. A payment reaches exactly one of them.
- **Refunds rank above completion**, because a refund legitimately follows a settled payment and has to
  pass the same guard that blocks a second ending.

The second half of the guard is that a later event never nulls a field an earlier one filled.
`payment-completed` carries no payment type, and applying it must not erase the type `payment-created`
recorded. Both rules live in the aggregate rather than in the six listeners — a rule implemented six times
is a rule implemented five times.

`created_at` is the time axis of every chart, and it **only ever moves earlier**. A payment cannot have
been created after something that already happened to it, so when the authoritative `payment-created`
arrives late it corrects the seeded value downwards. A `max()` there would let an out-of-order arrival
invent a creation time in the future, and the payment would sort to the wrong end of every chart.

### Seven topics, and why not the other eight

Consumed: `payment-created`, `fraud-checked`, `payment-approved`, `payment-completed`, `payment-failed`,
`payment-rejected`, `payment-refunded`.

Not consumed, each for a specific reason:

| Topic | Why not |
|---|---|
| `investigation-requested` / `investigation-completed` | Read from ai-investigation-service, which returns the trust caveats a narrative must be read alongside. The completion event is retained as an outbound integration/audit contract; no internal projection duplicates it. |
| `wallet-movement-recorded`, `ledger-posting-requested`, `ledger-posted` | ledger-service is the book of record and already publishes a trial balance. `ledger-posting-requested` is an authenticated asynchronous input contract for external/operator adjustments; `ledger-posted` is an outbound confirmation. Neither is projected into a competing running total. |
| `fraud-decision-applied` | fraud-service owns the append-only decision trail and serves it. |
| `notification-required` | notification-service owns delivery state, including which customers were never reached. |

The service publishes nothing. Its outbox is disabled: it is a read model, and it has no events of its own.

### Every response states how stale it is

A projection that stops updating does not go blank. It keeps serving the last figures it managed to build,
confidently and indefinitely. An operator reading **"0 payments held for review"** cannot tell whether the
queue is clear or the consumer died three hours ago — and those call for opposite responses.

So `projection_stream_state` holds a high-water mark per topic, and every analytics response carries a
freshness block. **Per topic, not one global number**: a single figure only looks stale once *every*
consumer has stopped, so a dead `fraud-checked` listener would hide behind six healthy ones while every
risk figure silently decayed.

`projection_stream_state` deliberately stores **no event count**. A count is a counter.

`last_event_at` and `last_applied_at` are separate columns because during a replay they diverge by hours,
and conflating them would make a backfill read as an outage.

### Granularity is a closed enum because the unit reaches SQL

The bucket unit is interpolated into the statement text — `date_trunc(:unit, …)` and
`CAST('1 ' || :unit AS interval)` — because PostgreSQL will not accept a bind parameter as the unit of an
interval literal. An unvalidated unit there is an injection point that **no parameter binding protects**.

`Granularity` has four constants, so the string reaching SQL can only ever be one of four literals chosen
in the enum, and Spring rejects an unknown value at request binding with a 400 before any query is built.

Each value also carries a maximum span. An hourly series over five years is 43,800 buckets: the query
would succeed, return megabytes, and render as a smear. An over-long request is **refused, naming the
granularity to use** — never silently coarsened, because quietly returning day buckets for a request that
asked for minutes makes the chart's own axis labels wrong.

`AnalyticsWindow` adds a second, separate ceiling of 400 days on the *span*. `Granularity` bounds the
number of buckets; this bounds the number of rows scanned. A five-year weekly window is only 260 buckets
but still reads every payment the platform has ever taken.

### What the dashboard reads from where, and what it does not duplicate

analytics-service adds only what nothing else can answer: the **cross-customer payment list**, time
series, risk distribution and end-to-end latency. payment-service is customer-scoped by design, and the
orchestrator's saga list describes execution rather than outcome.

Everything else the console reads from the service that owns it, through the gateway: the alert queue and
assessments from fraud-service, held sagas and provider health from the orchestrator, dead letters from
notification-service. Re-serving those here would create a staler copy — and showing an analyst an alert
that has already been decided is precisely the mistake this platform must not make.

Provider health is the clearest case. A projected circuit-breaker state would be a stale answer to "is
this rail usable right now", which is the only reason anybody opens that page during an incident.

### Numbers that are deliberately null rather than zero

- **`successRate` is null** when nothing in the window reached a terminal state. Zero says every payment
  failed; null says none have finished yet. On a quiet morning those look identical on a tile and mean
  opposite things.
- **Latency percentiles cover completed payments only.** An in-flight payment counted as zero would make
  the platform look faster the more backlog it had. p50/p95/p99, not a mean: one rail timing out at thirty
  seconds barely moves an average and is the whole story in a p99. The sample count sits next to them,
  because a p99 over four payments is not a p99.
- **`UNSCORED` is a real risk band.** Payments refused before the engine scored them are counted apart
  from `LOW`; folding them in would overstate how many genuinely low-risk payments there were, and the band
  counts would stop summing to the total.
- **Degraded verdicts are counted separately, and the flag latches.** Once a verdict on a payment was
  reached without the ML model, that stays true however many later events arrive. Clearing it would erase
  the only trace that the platform decided blind, and a `LOW` band full of them reads as "low risk"
  instead of "nothing was found by the half of the system that was working".

### The approve/block path, and why it does not go through this service

analytics-service has **no write endpoint and no service client**, and there is a structural test asserting
that `AnalyticsController` declares no `POST`, `PUT`, `PATCH` or `DELETE`.

Releasing or rejecting a held payment goes **browser → `POST /api/fraud/alerts/{id}/decide`** directly, so
the decision lands in the append-only trail of the service that raised the alert, attributed to the
analyst's own token. Proxying it through a reporting service would insert a hop into the chain of custody
of a record that has to stand up to being audited, for a decision this service played no part in.

The console calls `take` before `decide`. Two analysts working the same queue from two browsers is not
hypothetical, and claiming first makes the collision happen when somebody *starts* work rather than after
they have typed a paragraph of justification. Both return `409`, and the UI says plainly that nothing was
changed.

The reason field is mandatory server-side (`@NotBlank`, `@Size(max = 512)`) and the console enforces the
same limit before submitting. It is the only record of why a human overrode the engine, and whoever reads
it in six months — an auditor, or whoever handles the customer's complaint — has nothing else to go on.

The success message says the decision was **recorded** and the payment **will resume shortly**. It never
says the payment completed: `decide` returns as soon as the decision is durable, and the payment resumes
asynchronously through an outbox event the orchestrator consumes. Claiming completion would be a guess, and
an operator who believed it would stop watching for the case where it does not happen.

### Frontend decisions worth stating

- **Same-origin browser API with environment-specific runtime routing.** The browser always calls root-relative `/api` paths. Vite proxies them to the local gateway during source development, Compose nginx uses `API_UPSTREAM`, and Kubernetes Ingress routes them directly. This keeps one frontend image promotable across environments and avoids compiling deployment URLs into the bundle; gateway and service security still enforce the real bearer-token and role checks.
- **Hand-drawn SVG charts, no chart library.** Fewer dependencies to churn, and — the reason that mattered
  — the scaling arithmetic becomes testable. Every way an SVG chart can be wrong is arithmetic: a division
  by an empty dataset, an axis that starts somewhere other than zero, a bar with a negative height, a chart
  drawn upside down. `scale.ts` is pure functions and has 38 tests.
- **Types are hand-written, not generated from OpenAPI.** Generation needs every service running to produce
  the spec, and a build that cannot run offline is a build that eventually does not run. Nullability is
  copied faithfully, which is the part that matters.
- **The access token lives in memory; the refresh token in `sessionStorage`.** The access token opens every
  admin endpoint, and a copy in `localStorage` would be readable by any injected script for its whole
  lifetime. The refresh token has to survive a reload — an operator who presses F5 mid-queue should not be
  signed out — and `sessionStorage` is tab-scoped and cleared when the browser closes, which matters on a
  shared operations machine. An httpOnly cookie would be stronger; the platform authenticates from an
  `Authorization` header, so that is a backend change and brings CSRF back into scope.
- **One refresh on a 401, never a loop.** A loop turns a revoked session into a request storm against the
  auth service — a self-inflicted outage that is also indistinguishable from an attack. Concurrent refreshes
  collapse into one promise, because refresh tokens rotate and six panels loading at once would otherwise
  each spend the token and five would fail against a revoked one, destroying the session by its own
  recovery.
- **Money is formatted from `amountMinor` with `BigInt`.** `1999 / 100` is not exactly `19.99` in binary
  floating point. A display rounding would usually hide that, and "usually" is not a property this platform
  accepts near an amount.
- **`toQueryString` drops null and undefined but keeps `false` and `0`.** `blockingOnly=false` and an
  absent filter are different queries to the fraud service.

### Two defects found while wiring this phase

**`/api/admin/users/**` had no gateway route.** Those handlers live in auth-service's `UserController`, but
under the `/api/admin/**` prefix — which the analytics catch-all claims. Every request fell through to
analytics-service and came back `404`: an endpoint that plainly exists, reported as missing, with the 404
logged by a service that has never heard of it. A route is now declared above the catch-all, which is the
second time this ordering has caught something and is why both entries carry a comment saying so.

**The payment list's `degradedOnly` and `minRiskScore` filters did not exist.** The console sent them and
Spring ignored them silently, so the "degraded verdicts only" filter returned every payment while looking
like it had worked. The migration already had `idx_payment_facts_degraded` and `idx_payment_facts_score` —
partial indexes built for predicates nothing queried, which is what made the omission obvious. Both filters
are now real, from the repository query through to the controller, with `minRiskScore` validated `0..100`
and narrowed to the `smallint` the column is.

### Tests

**analytics-service — 116 unit and structural tests, no infrastructure required.**

| Suite | What it pins down |
|---|---|
| `StatusRankTest` (22) | The ordering, exhaustively over the enum. That the three endings share a rank and none can replace another; that a late `payment-created` cannot regress a settled payment; that refunds still apply. |
| `PaymentFactTest` (23) | Idempotence and commutativity, by applying events in the wrong order on purpose. A method that is individually correct and collectively order-dependent is the bug that reaches production. |
| `GranularityTest` (22) | That every `sqlUnit()` is one of four alphabetic literals — the whole safety argument for interpolating it — and that `fitting()` always returns something that permits the span it was given. |
| `AnalyticsWindowTest` (17) | Bucket alignment, including that ISO weeks start on Monday to match `date_trunc('week', …)`. If Java and SQL disagreed, the first and last buckets would be partial and the totals would not match the tiles above them. |
| `PaymentProjectionServiceTest` (15) | The insert race as the expected path, not an error path. That the high-water mark advances even on a redelivery that changed nothing, and that an older event never pushes it backwards. |
| `AnalyticsTransactionBoundaryTest` (11) | That the projection writer is transactional, every query is `readOnly`, the listeners hold no transaction of their own, and the controller exposes no mutating method. |
| `AnalyticsPropertiesBindingTest` (6) | Binds the shipped `application.yml`. A misspelled key neither fails to compile nor fails to bind — the API would keep answering with a different staleness tolerance than the file claims. |

**Dashboard — 120 tests.** `format.test.ts` (31) covers the money path and every "absent is not zero" case.
`scale.test.ts` (38) covers the chart arithmetic, with the empty and all-zero datasets getting the most
attention: their failure mode is `NaN` coordinates, which a browser renders as nothing at all —
indistinguishable from the chart having failed to load. `client.test.ts` (23) covers the single refresh,
non-JSON error bodies and the query-string rules. `AlertsPage.test.tsx` (19) covers the approve/block flow:
the mandatory reason, `take` before `decide`, both `409` paths, and that the success message does not claim
the payment completed. `FreshnessBanner.test.tsx` (9) covers current, behind and never-populated staying
distinguishable.

> **Verified against PostgreSQL.** `AnalyticsFlowIT` now runs under Docker with the rest of the
> integration suite. Its 30 tests exercise the native `FILTER`, `date_trunc`, and `percentile_cont`
> queries, concurrent projection insert, database constraints, filter combinations, freshness,
> and bucket alignment; all pass with zero skips.

### Running the dashboard

```bash
cd frontend
npm ci
npm run dev          # http://127.0.0.1:5173, expects the gateway on :8080
npm run typecheck
npm test
npm run build
```

Or through Compose, which builds the bundle and serves it with nginx. Start the full stack for a fresh environment; the frontend-only command assumes its gateway and domain services are already running:

```powershell
.\scripts\panel-demo.ps1 start
# or, for an already-running stack:
docker compose up -d --build frontend
# http://127.0.0.1:5173
```

Sign in with the `ADMIN` or `FRAUD_ANALYST` demo account from `.env.example`. Browser API calls
use same-origin `/api` paths, so one immutable dashboard image works in every environment. Vite proxies
those paths to `http://127.0.0.1:8080` during source development; Compose nginx proxies them internally
to `api-gateway:8080`; and Kubernetes Ingress routes them directly to the gateway without rewriting.

### Endpoints added

All under `/api/admin/analytics`, all `GET`, all requiring `ROLE_ADMIN` or `ROLE_FRAUD_ANALYST` at the
gateway and again in the service:

| Endpoint | Returns |
|---|---|
| `/overview` | KPI tiles for a window, in one SQL statement. `successRate` null when nothing settled. |
| `/timeseries` | Volume, outcome and settled value per bucket. |
| `/risk` | Risk bands including `UNSCORED`, plus a ten-point score histogram. |
| `/performance` | Latency percentiles over completed payments, provider share, top failure codes. |
| `/payments` | The cross-customer payment list. Filters combine: status, risk level, user, provider, minimum amount, minimum score, degraded-only. |
| `/payments/{paymentId}` | One projected payment. A `404` means *not projected*, and says payment-service is authoritative for whether it exists. |
| `/projection` | What the read model covers, what it deliberately does not, and per-topic lag. |

---

## Phase 10 — Platform Operations

Phase 10 turns the verified application into a deployable and observable platform. The provider-neutral
Helm chart at `infrastructure/kubernetes/aegispay` renders all twelve application images with non-root
security contexts, source-SHA image configuration, dependency waits, persistent data services, topology
spreading, disruption budgets, network policies, and separate liveness/readiness probes. The bundled
PostgreSQL/pgvector, Redis, and single-node KRaft Kafka workloads make a self-contained cluster possible;
production values should disable them and point the chart at managed, fault-tolerant services.

Secrets are deliberately outside the chart. `infrastructure/kubernetes/secrets.template.yaml` defines the
required key contract without containing usable credentials. The auth signing pair is mounted read-only;
each service database has a distinct owner credential; hosted AI, Kafka SASL/TLS, and managed PostgreSQL
CA material have explicit Secret paths; and workloads receive only the credentials they need. Incrementing
`runtimeSecret.version` rolls consumers after a Secret rotation. The public Ingress routes only the gateway
API/JWKS and frontend; metrics, traces, data stores, and internal service ports remain ClusterIP-only behind
NetworkPolicies.

The optional in-chart observability stack is complete rather than a collector-shaped placeholder:

- Prometheus discovers annotated services and loads alerts for money-safety, ledger integrity, event
  delivery, token reuse, fraud degradation, AI guardrails, notification dead letters, and HTTP errors.
- Grafana is provisioned with Prometheus and Tempo data sources and an AegisPay operations dashboard.
- JVM services export OTLP/HTTP traces to a two-replica OpenTelemetry Collector, which memory-limits and
  batches them before Tempo persists them. Metrics remain pull-based; logs remain structured stdout.
- Liveness checks only the process. Readiness includes dependencies, so a Redis or database outage removes
  a pod from traffic without causing a restart storm.

GitHub Actions under `.github/workflows` implement the delivery boundary:

1. `ci.yml` runs Java 25 `clean verify` with Docker, then parses Failsafe XML and refuses a green build
   unless all 16 integration classes / 432 tests execute with zero skips. It also aggregates Surefire reports and refuses any unit-test skip. Python and frontend suites run independently, and Helm/Compose configuration is rendered and schema-validated.
2. `codeql.yml` analyzes Java, JavaScript/TypeScript, and Python. `security.yml` adds dependency review,
   full-history secret scanning, dependency/source scanning, and Docker/Kubernetes misconfiguration scans.
3. `images.yml` builds all twelve images only after same-SHA CI, Security, and CodeQL workflows succeed,
   scans each image before publication, emits an SPDX SBOM, and pushes a commit-SHA tag to GHCR.
4. `deploy.yml` is manual, protected by a GitHub Environment and an explicit `DEPLOY` confirmation. It
   accepts only a full commit SHA, checks out that exact chart revision, validates all coordinates before
   installing the kubeconfig, and requires a protected `HELM_VALUES_B64` environment profile. Production
   policy rejects bundled data StatefulSets, disabled Ingress, and example endpoints before
   `helm upgrade --atomic --wait`. Pull requests and forks never receive publication or deployment credentials.

Validate the chart locally without installing Helm:

```powershell
docker run --rm -v "${PWD}:/workspace" -w /workspace alpine/helm:3.18.6 `
  lint --strict infrastructure/kubernetes/aegispay

docker run --rm -v "${PWD}:/workspace" -w /workspace alpine/helm:3.18.6 `
  template aegispay infrastructure/kubernetes/aegispay --namespace aegispay |
  docker run --rm -i ghcr.io/yannh/kubeconform:v0.7.0 -strict -summary
```

Deployment and secret-management details are in `infrastructure/kubernetes/README.md`; alert semantics and
operator access are in `infrastructure/monitoring/README.md`.

---

## Roadmap

| Phase | Scope | Status |
|---|---|---|
| **1** | Monorepo, 10 service skeletons, Docker Compose, Postgres/Redis/Kafka, Flyway, OpenAPI, health checks, logging, error handling, unit tests | ✅ **Done** |
| **2** | Auth service: registration, login, logout, JWT + refresh rotation with reuse detection, RBAC, password hashing, protected APIs, transaction-boundary audit | ✅ **Done** |
| **3** | Wallets, deposits/withdrawals, reserve/capture/release holds, payment state machine and history, refunds, two-layer idempotency, Kafka payment events via the outbox, reconciliation | ✅ **Done** |
| **4** | Double-entry journal, chart of accounts, database-enforced immutability and balance, trial balance, reconciliation, reversals, event-driven posting from payments and wallet movements | ✅ **Done** |
| **5** | 3 simulated providers, intelligent routing, provider health checks, retry with exponential backoff and jitter, automatic fallback, Resilience4j circuit breakers, saga + transactional outbox, stalled-saga recovery, operator kill switch | ✅ **Done** |
| **6** | Python FastAPI ML service, calibrated gradient-boosted fraud model, 0–100 risk score, APPROVE/REVIEW/HOLD/REJECT, deterministic rule engine covering the archetypes the model measurably misses, analyst alert queue with an append-only audit trail, `RISK_HELD` saga state and wallet hold extension | ✅ **Done** |
| **7** | RAG over a pgvector store, offline deterministic embedder, 11-document fraud-policy knowledge base seeded and embedded at startup, hybrid declared-mapping + vector retrieval with a similarity floor, grounded cited narratives, hallucinated-citation detection, closed recommendation vocabulary, authority boundary enforced by holding no credentials | ✅ **Done** |
| **8** | Multi-channel notifications: simulated email and SMS transports, a real in-app inbox, template catalogue with per-channel copy, delivery de-duplicated on the producer's notification key, a persistent per-channel retry queue with exponential backoff and jitter, an operator-replayable dead-letter queue, an append-only attempt trail, SMS consent captured with the number, and link-defanging of customer-controlled text | ✅ **Done** |
| **9** | Analytics read model built from seven event topics as one row per payment with every aggregate derived on demand, per-topic projection freshness on every response, a closed granularity enum because the bucket unit is interpolated into SQL, cross-customer payment list, and a React + TypeScript operations console with hand-drawn SVG risk visualisation and an approve/block queue that decides against fraud-service directly | ✅ **Done** |
| **10** | Kubernetes/Helm, Prometheus + Grafana, OpenTelemetry tracing, GitHub Actions CI/CD, security scanning | ✅ **Done** |

---

## Notes on Spring Boot 4

Boot 4 is a larger jump than the version number suggests. Issues encountered and
resolved while building Phase 1, recorded here because they are not obvious:

- **Jackson 3.** `ObjectMapper` moved to `tools.jackson.databind`. Annotations stay
  in `com.fasterxml.jackson.annotation`. `JsonProcessingException` is gone —
  exceptions are unchecked `JacksonException`. Date/time toggles moved out of
  `SerializationFeature` into `DateTimeFeature`, so the property key is
  `spring.jackson.datatype.datetime.write-dates-as-timestamps`.
- **`EntityScan` moved** to `org.springframework.boot.persistence.autoconfigure`.
- **`ClientHttpRequestFactorySettings` renamed** to `HttpClientSettings`.
- **`spring-boot-starter-aop` no longer exists.**
- **Testcontainers versions are no longer managed** by Boot, and Testcontainers 2.x
  renamed its modules (`postgresql` → `testcontainers-postgresql`).
- **`HttpStatus.UNPROCESSABLE_ENTITY` is deprecated** in favour of
  `UNPROCESSABLE_CONTENT`.
- **`TestRestTemplate` was removed.** Integration tests use Spring Framework 7's
  `RestTestClient` (`org.springframework.test.web.servlet.client.RestTestClient`)
  instead, via `bindToServer().baseUrl(...)`.
- **An imported config document takes precedence over the file that imported it.**
  This one is genuinely counter-intuitive: a key in an imported "defaults" file
  cannot be overridden by the service that imported it. `aegis-defaults.yml`
  therefore contains only settings no service needs to vary, and this is documented
  at the top of that file.

---

## License

MIT
