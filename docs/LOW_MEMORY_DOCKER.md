# Low-memory Docker development (8-GB Windows PC)

This profile runs the complete 15-container AegisPay architecture. It does **not** remove PostgreSQL, Redis, Kafka, JWT security, wallet/ledger/payment processing, fraud detection, ML scoring, AI/RAG, notifications, analytics, the gateway, or the frontend. It also does not change the test lifecycle.

The normal `docker-compose.yml` remains the unconstrained/production-like development definition. Low-memory limits apply only when `docker-compose.local.yml` is selected explicitly.

## Recommended Docker Desktop and WSL settings

For an 8-GB host, reserve enough memory for Windows, Kiro/VS Code, and a browser:

1. Use Docker Desktop's WSL 2 backend and current WSL.
2. Create or edit `C:\Users\<you>\.wslconfig`:

   ```ini
   [wsl2]
   memory=5GB
   processors=4
   swap=2GB

   [experimental]
   autoMemoryReclaim=gradual
   sparseVhd=true
   ```

3. Apply it from PowerShell, after stopping this Compose project:

   ```powershell
   .\scripts\low-memory.ps1 stop
   wsl --shutdown
   ```

   Restart Docker Desktop afterward. Do not run `wsl --shutdown` while database containers are writing.
4. Keep Docker Desktop Resource Saver enabled for idle periods. Close unrelated WSL distributions and memory-heavy browsers during a cold build.
5. Keep at least 15 GB disk free for images, Maven layers, Kafka logs, and PostgreSQL. The 2-GB swap is an emergency cushion, not normal operating memory; sustained swapping will make the demo unresponsive.

Microsoft documents `.wslconfig` as the global WSL 2 VM configuration and supports size units such as `GB`; `autoMemoryReclaim` accepts `disabled`, `gradual`, or `dropCache`. Docker recommends current WSL and configuring WSL VM memory/CPU/swap limits when Docker Desktop resource use is a concern. Sources: [Microsoft WSL advanced settings](https://learn.microsoft.com/en-us/windows/wsl/wsl-config), [Docker Desktop WSL best practices](https://docs.docker.com/desktop/features/wsl/best-practices/). Content was rephrased for compliance with licensing restrictions.

> The current verification machine exposes only about 3.8 GiB to Docker. The constrained profile is designed to fit at idle there, but `memory=5GB` is recommended on the user's 8-GB machine to provide startup and workload headroom.

## Exact local limits

The hard-limit sum is **5,100 MiB (4.98 GiB)**. Limits are ceilings, not preallocated memory.

| Service | Hard limit | Reservation | JVM heap | Reason |
|---|---:|---:|---:|---|
| PostgreSQL | 256 MiB | 96 MiB | — | Measured ~82 MiB; local `shared_buffers=64MB`, 80 connections |
| Redis | 128 MiB | 32 MiB | — | Measured ~12 MiB; 64-MiB dataset leaves AOF/allocator headroom |
| Kafka | 512 MiB | 160 MiB | 256 MiB | Measured ~321 MiB; separate health-probe JVM capped at 64 MiB |
| API gateway | 300 MiB | 96 MiB | 192 MiB | No JPA/Kafka; retains Netty native-buffer headroom |
| Auth | 320 MiB | 112 MiB | 192 MiB | Reached 96% of 300 MiB during authenticated panel checks |
| Wallet | 384 MiB | 144 MiB | 192 MiB | Remained above 95% of 352 MiB after endpoint checks |
| Notification | 320 MiB | 112 MiB | 192 MiB | Reached 85% of 300 MiB during the final cold-start check |
| Ledger | 384 MiB | 144 MiB | 192 MiB | Remained above 95% after cold-start caches settled |
| Payment | 416 MiB | 160 MiB | 192 MiB | Remained above 91% after settling and delayed panel reads under load |
| Fraud | 384 MiB | 144 MiB | 192 MiB | Reached 88% of 352 MiB while starting listeners and the ML client |
| Orchestrator | 416 MiB | 160 MiB | 192 MiB | Remained above 93% of 384 MiB after settling and endpoint checks |
| AI investigation | 352 MiB | 128 MiB | 192 MiB | RAG startup measured safely below this cap with retained corpus |
| Analytics | 352 MiB | 128 MiB | 192 MiB | Seven listeners reduced to local concurrency one |
| ML/FastAPI | 512 MiB | 192 MiB | — | One worker, one BLAS thread, pre-trained model |
| Frontend | 64 MiB | 16 MiB | — | nginx-only runtime; measured about 4 MiB |

All Java services use `-Xms64m -Xmx192m`, Serial GC, a 48-MiB code-cache ceiling, and 512-KiB platform thread stacks. The local profile reduces Kafka listener concurrency from two to one, Hikari pools from 12/2 to 4/1, Tomcat's platform-thread maximum to 50, and tracing sampling from 100% to 10%. The orchestrator's local slow-call threshold is 5 seconds because successful fraud calls measured about 2.2 seconds on the constrained VM; failure-rate protection is unchanged. These changes preserve behavior and durability while reducing local throughput, connection count, retained telemetry, and native thread/client overhead.

The larger limits are based on measured baseline RSS, not applied uniformly. Before optimization, analytics used ~404 MiB and fraud ~360 MiB; they therefore receive more non-heap headroom than gateway/auth. Increase only the failing service by 32–64 MiB after confirming `OOMKilled=true` or a Java OOM; do not increase every limit.

## Build without a memory spike

Do not run a bare `docker compose build` on an 8-GB PC. The helper sets `COMPOSE_PARALLEL_LIMIT=1`, uses a 768-MiB Maven build heap, and builds Java images in manageable groups:

```powershell
.\scripts\low-memory.ps1 config
.\scripts\low-memory.ps1 build
```

The sequence is infrastructure image pulls, four serialized Java groups, ML, then frontend. Image builds still compile test sources and produce `platform-common`'s test JAR; they use `-DskipTests` only because integration tests run separately, not inside runtime-image assembly.

ML training remains at image build time—never container startup. The Dockerfile isolates training inputs (`train.py`, model, features, dataset, and config) from serving-only FastAPI code, so changing an endpoint no longer invalidates the deterministic training layer. Normal restarts load and warm the baked artifact once. AI/RAG retains startup validation: with the PostgreSQL volume retained, unchanged policy documents are detected and not re-embedded.

## Staged startup

After the images exist:

```powershell
.\scripts\low-memory.ps1 start -TimeoutSeconds 1200
```

The helper uses `--no-build --no-deps` and waits between these waves:

1. PostgreSQL, Redis, Kafka
2. Auth
3. Wallet, ledger, payment, notification, analytics, orchestrator
4. ML runtime
5. Fraud and AI/RAG
6. API gateway
7. Frontend
8. Existing 15-container and 13-endpoint readiness checks

The local override uses `restart: "no"` to prevent all 15 services from cold-starting simultaneously after Docker Desktop restarts. Start them through the helper instead. Named volumes are retained by `stop`:

```powershell
.\scripts\low-memory.ps1 stats
.\scripts\low-memory.ps1 stop
```

Never use `docker compose down -v` unless you intentionally want to delete every local service database, Redis state, Kafka history, and the persisted RAG corpus.

## Verification and troubleshooting

Check effective limits rather than trusting YAML alone:

```powershell
.\scripts\low-memory.ps1 status
.\scripts\low-memory.ps1 stats
docker compose -f docker-compose.yml -f docker-compose.local.yml config
```

Inspect failures before raising limits:

```powershell
docker compose -f docker-compose.yml -f docker-compose.local.yml logs --since 10m
docker inspect aegis-analytics-service --format '{{.State.OOMKilled}} {{.RestartCount}}'
```

Look for `OutOfMemoryError`, `OOMKilled=true`, restart-count increases, repeated connection refusal, Kafka broker/controller errors, PostgreSQL `FATAL` after readiness, Redis eviction/write errors, and health flapping. Startup is expected to be slower under Serial GC and constrained CPU/RAM; longer startup is preferable to removing services or allowing Windows to page the whole stack.

Tests remain mandatory and unchanged. Run them while the demo stack is stopped so Maven/Testcontainers have memory available:

```powershell
.\scripts\low-memory.ps1 stop
$env:MAVEN_OPTS='-Xms128m -Xmx512m -XX:+UseSerialGC'
.\mvnw.cmd -B -ntp clean verify
```

## Cold-start latency on the first call

Container health gates prove a service is alive, not that its request path is warm. Under `-Xmx192m` with Serial GC on a 3.8-GiB VM, a service pays its whole cold-start cost on the **first** real request through each path: JIT compilation, the first Hibernate aggregate query plan, and cold gateway filters such as the JWKS fetch and the rate limiter's Redis script. Measured warm latency for the analytics overview is 0.04–1.34 s, but the first call after a cold boot can exceed the gateway's 15 s `response-timeout` and surface as a `504 Gateway Timeout`.

This is a startup characteristic, not a memory fault. The gateway and all business timeouts are deliberately left at their production values. Only the verification harness was made patient enough to absorb it:

- `panel-demo.ps1 verify` primes the heavyweight authenticated read paths before asserting, so assertions measure steady-state behaviour;
- the harness per-request timeout is 20 s normally and 90 s when `AEGIS_LOW_MEMORY_MODE=true`;
- the first payment submission retries on a transient timeout or 5xx **reusing the same `Idempotency-Key`**, which is safe by the endpoint's own guarantee; a genuine rejection still fails immediately.

If a first call does time out, the saga layer is designed for it. An abandoned payment is not compensated on an uncertain wallet-capture outcome; the orchestrator's recovery job resumes it by payment id. This was observed and confirmed: one abandoned payment logged `wallet capture outcome is uncertain; retry by payment id instead of compensating`, was resumed from `LEDGER_POSTED`, completed via SWIFTPAY, and left the ledger balanced with `MATCH` and zero difference.

Two further notes when reading logs under this profile:

- a transient `Redis health check failed` WARN (Lettuce `Connection initialization timed out after 2 second(s)`) can appear in `analytics-service` during peak seeding; Redis is not memory-starved at 128 MiB (it uses under 16 MiB), this is CPU contention on the constrained VM and the service stays healthy;
- `/api/admin/analytics/*` defaults to a **1-day** window (`AnalyticsWindow.DEFAULT_SPAN`). A `.panel-demo.json` older than that will fail verification with "the prepared panel scenario is incomplete" even though the rows are still in PostgreSQL. Re-run `.\scripts\panel-demo.ps1 seed`; this is correct behaviour, not data loss.

## Verification snapshot

The final profile was built and started on Docker Desktop 29.7.2 with only **3.824 GiB** exposed to its Linux VM—less than the recommended 5 GiB. After creating and verifying the complete panel scenario, `docker stats --no-stream` reported:

| Container | Actual usage | Limit |
|---|---:|---:|
| payment-service | 360.6 MiB | 416 MiB |
| payment-orchestrator | 347.5 MiB | 416 MiB |
| ai-investigation-service | 325.7 MiB | 352 MiB |
| ledger-service | 303.6 MiB | 384 MiB |
| analytics-service | 295.2 MiB | 352 MiB |
| Kafka | 268.7 MiB | 512 MiB |
| fraud-service | 242.4 MiB | 384 MiB |
| wallet-service | 204.3 MiB | 384 MiB |
| notification-service | 183.5 MiB | 320 MiB |
| auth-service | 150.8 MiB | 320 MiB |
| API gateway | 127.1 MiB | 300 MiB |
| PostgreSQL | 92.0 MiB | 256 MiB |
| ML/FastAPI | 38.7 MiB | 512 MiB |
| Redis | 15.5 MiB | 128 MiB |
| Frontend | 3.3 MiB | 64 MiB |
| **Total** | **2,959.0 MiB (2.89 GiB)** | **5,100 MiB (4.98 GiB)** |

Observed verification results:

- merged Compose configuration valid and hard limits total exactly 5,100 MiB;
- all infrastructure and application images built in the documented order; auth was rebuilt again after correcting its service-token wire contract;
- 15/15 containers healthy and 13/13 externally testable endpoints successful;
- every container had `restartCount=0` and `OOMKilled=false` after the final scenario;
- Kafka listed 29 topics, PostgreSQL accepted connections, and authenticated Redis returned `PONG`;
- no OOM, native-thread, startup-fatal, connection-failure, Kafka ERROR/FATAL, PostgreSQL PANIC/FATAL/ERROR, Redis ERROR/OOM/AOF-failure, or application ERROR patterns appeared after final verification;
- ML startup executed the trainer zero times, loaded and warmed the baked model once;
- RAG reported 11 unchanged documents and 68 usable chunks, proving retained-corpus startup avoided re-embedding;
- the targeted auth/shared unit run passed 164 tests with zero failures, errors, or skips;
- panel preparation created three completed baseline payments and one impossible-travel `RISK_HELD` payment with an open blocking alert;
- full `panel-demo.ps1 verify` passed with a non-null scenario, four scenario payments visible in analytics, balanced accounts, consistent ledger, zero findings, and wallet-ledger status `MATCH` with zero difference.

Three pre-existing panel/runtime contract defects were exposed and corrected rather than hidden: the verifier now compares raw API roles (`ADMIN`, `FRAUD_ANALYST`), Windows PowerShell explicitly enumerates the wallet-list JSON array, and scenario polling reads parked `RISK_HELD` state from orchestration instead of expecting a payment-service hold event that the architecture intentionally does not publish. Auth's machine-token response now emits RFC field names (`access_token`, `token_type`, `expires_in`, `scope`), matching every shared service-token client. The full Maven integration lifecycle remains enabled and unchanged.

## Re-validation after the auth and ML build changes

The whole profile was re-validated end to end after the auth token-contract fix and the ML Dockerfile training-layer split, with the stack stopped first so Testcontainers had memory:

- ML Python suite: **129 passed in 21.06 s**, exit code 0;
- full `mvnw clean verify`: **BUILD SUCCESS**, 12/12 modules, **09:31 min**, exit code 0. Aggregating every `TEST-*.xml` report gives **Surefire 71 classes / 1,379 tests** and **Failsafe 16 classes / 432 tests** — **1,811 tests, 0 failures, 0 errors, 0 skipped**. The 432 Failsafe tests confirm the integration lifecycle stayed enabled;
- merged Compose config re-validated: 15 services, `mem_limit` and `deploy.resources.limits.memory` agreeing on every service, hard limits still totalling exactly **5,100 MiB (4.98 GiB)**, `restart: "no"`;
- cold staged restart: **15/15 healthy, 13/13 endpoints**, every container `restartCount=0` and `OOMKilled=false`;
- settled idle usage **2,876 MiB (2.81 GiB)**; after a full seed plus verify workload **2,919 MiB (2.85 GiB)** against the 5,100 MiB cap;
- `panel-demo.ps1 seed` then `verify` both exited 0, with four scenario payments visible in analytics, saga `RISK_HELD`, an open blocking alert, and an independent re-check of money correctness returning `balanced=true`, `accountsAgreeWithJournal=true`, `differenceMinor=0`, `consistent=true`, `findings=0`, and wallet-ledger `MATCH` with zero difference;
- ML loaded the baked model `fraud_hgb-c9026b7d59cb` (trained 2026-09-20) and warmed it in **6 ms**, executing the trainer zero times at startup.

The tightest container under this profile is `ai-investigation-service` at **90.67 %** of its 352 MiB limit at workload peak, with no OOM kill across two staged boots and a full seed-plus-verify cycle. It is adequate but has the least headroom; if RAG contexts grow, raise it first.
