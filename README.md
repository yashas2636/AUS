# Fibonacci Sequence API

A production-grade Spring Boot microservice that returns the nth Fibonacci number.
Built to demonstrate the full engineering lifecycle: API design, testing, containerisation,
GitOps deployment across two production data centres, and SRE observability.

---

## Tech stack at a glance

| Area | What we used | Detail |
|------|-------------|--------|
| **Language** | Java 21 | LTS release; virtual threads available; `BigInteger` handles F(100000) without overflow |
| **Framework** | Spring Boot 3.x | `@Validated`, `@Cacheable`, `@RestControllerAdvice`, Actuator health endpoints |
| **Algorithm** | Iterative BigInteger | O(n) time, O(1) space; `long` overflows at F(93) — BigInteger handles arbitrary precision |
| **Cache** | Caffeine | In-process, 10,000 entries, 30-min TTL; cache hit serves response in microseconds |
| **Rate limiting** | Spring `HandlerInterceptor` | Sliding-window 200 req/min per IP; reads `X-Forwarded-For` for real client IP behind Kong |
| **Input validation** | Bean Validation (`@Min`, `@Max`) | Rejects n < 0, n > 100000, floats, strings, unicode, emoji, hex — all return structured 400 |
| **Error handling** | `@RestControllerAdvice` | Catches every exception type; no stack trace ever reaches the client |
| **Architecture style** | Microservice | Single responsibility (one endpoint, one job); stateless; independently deployable and scalable |
| **Containerisation** | Docker (multi-stage BuildKit) | Stage 1: Maven build; Stage 2: `eclipse-temurin:21-jre-alpine`; non-root user; NR agent via `JDK_JAVA_OPTIONS` |
| **CI/CD** | GitHub Actions | 2 jobs: build + test → Docker build + Trivy container scan (no push — public repo) |
| **Security scanning** | OWASP + Trivy | OWASP blocks on CVSS ≥ 9 dependency CVEs; Trivy blocks on CRITICAL/HIGH container CVEs before push |
| **GitOps** | Kustomize + ArgoCD | `components → base → overlays/{env}`; ArgoCD `ApplicationSet` deploys to test + Melbourne + Sydney from one manifest |
| **API gateway** | Kong | Platform-level ingress; TLS termination; connect-timeout 5s, read-timeout 30s; routes `/fibonacci` to service |
| **Kubernetes** | K8s (prod-mel + prod-syd clusters) | Deployment, Service, HPA, PodDisruptionBudget, Ingress, ExternalSecret |
| **Health probes** | Startup + Liveness + Readiness | Startup: 5 min grace for JVM boot; Liveness: restarts deadlocked pods; Readiness: pulls pod from Kong on failure |
| **Graceful shutdown** | Spring + K8s SIGTERM sequence | `server.shutdown=graceful`, 60s drain window, `terminationGracePeriodSeconds=70` — in-flight requests complete cleanly |
| **Scaling** | HPA (autoscaling/v2) | 2–8 pods; scales on CPU > 60% or memory > 80%; scaleUp 30s stabilisation; scaleDown 5 min stabilisation |
| **Failover** | Multi-DC active-active | Melbourne (prod-mel) + Sydney (prod-syd) run identical manifests and same image digest; service is stateless so any pod in any DC can serve any request |
| **Pod resilience** | PodDisruptionBudget | `minAvailable: 1` — Kubernetes blocks node drains that would leave zero pods running |
| **Topology** | Spread + anti-affinity | Pods spread across nodes; soft anti-affinity prevents all replicas landing on one node — survives single node failure |
| **Self-healing** | ArgoCD `selfHeal: true` | Any manual `kubectl` change reverted within 3 minutes automatically |
| **Rolling deploy** | `maxUnavailable: 0`, `maxSurge: 33%` | New pod must pass readiness before old pod is terminated — zero traffic loss during deploys |
| **Observability** | New Relic Java Agent | APM transactions, JVM metrics, structured JSON logs, custom Micrometer metrics; all tagged by `environment` |
| **Alerting** | New Relic NRQL alerts | 6 conditions: 5xx rate, P99 latency, throughput drop, rate limit abuse, high n value, container restart loop |
| **SLOs** | Defined in `monitoring/slo.md` | Availability ≥ 99.9%; P95 < 500ms; P99 < 2s; error budget burn rate alerts at 1h/6h/24h windows |
| **Secrets management** | ExternalSecret + Azure Key Vault | NR license key never in git; injected at runtime from Key Vault via the platform ExternalSecret operator |
| **Authentication** | **Not implemented — see proposal below** | No auth layer exists in this version. All requests are accepted if they pass rate limiting and validation. See the Authentication proposal section for the recommended approach. |
| **Testing** | JUnit 5 + Spring MockMvc | 63 tests across 5 classes: algorithm, HTTP layer, rate limiter, load (100 concurrent), 24 resilience edge cases |
| **Build tool** | Maven 3.9 | Dependency management, test execution, OWASP CVE check (`mvn verify`) |
| **Logging** | Logback + logstash-logback-encoder | JSON in prod/staging (ships to NR Logs); plain text in local/test; `traceId`/`spanId` in MDC for log-trace correlation |

---

## Architecture overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            DEVELOPER WORKFLOW                               │
│                                                                             │
│   git push / PR                                                             │
│        │                                                                    │
│        ▼                                                                    │
│   GitHub Repository ──────────────────────────────────────────────────┐    │
│        │                                                               │    │
│        ▼                                                               │    │
│   ┌─────────────────────────────────────────────────────────────┐     │    │
│   │               GitHub Actions CI/CD (3 jobs)                 │     │    │
│   │                                                             │     │    │
│   │  Job 1: Build & Test                                        │     │    │
│   │    mvn verify (63 tests)                                    │     │    │
│   │    OWASP dependency CVE check (fail on CVSS ≥ 9)           │     │    │
│   │         │                                                   │     │    │
│   │         ▼                                                   │     │    │
│   │  Job 2: Docker Build → Trivy Scan → Push                    │     │    │
│   │    BuildKit cache mount (fast .m2 / NR agent reuse)         │     │    │
│   │    Trivy scan (block CRITICAL/HIGH unfixed CVEs)            │     │    │
│   │    Push → registry.gitlab.com/.../fibonacci-service         │     │    │
│   │    Compute immutable tag: v1.x.y@sha256:<digest>            │     │    │
│   │         │                                                   │     │    │
│   │         ▼                                                   │     │    │
│   │  Job 3: Update GitOps                                       │     │    │
│   │    scripts/update-image-tag.sh patches kustomization.yaml   │     │    │
│   │    git commit [skip ci] → ArgoCD detects change             │     │    │
│   └─────────────────────────────────────────────────────────────┘     │    │
│                                                                         │    │
└─────────────────────────────────────────────────────────────────────────┘    │
                                                                               │
┌──────────────────────────────────────────────────────────────────────────────┘
│                              GITOPS (ArgoCD)
│
│   gitops/argocd/fibonacci.yaml (ApplicationSet — matrix: env × cluster)
│
│        auto-sync                     manual PR merge
│             │                        │               │
│             ▼                        ▼               ▼
│     ┌──────────────┐    ┌──────────────────┐  ┌───────────────────┐
│     │  test cluster │    │ Melbourne cluster │  │  Sydney cluster   │
│     │  (env=test)   │    │  (env=prod-mel)   │  │  (env=prod-syd)   │
│     │  2 replicas   │    │  3 replicas       │  │  3 replicas       │
│     │  main-latest  │    │  v1.x@sha256:...  │  │  v1.x@sha256:...  │
│     └──────┬────────┘    └────────┬──────────┘  └─────────┬─────────┘
│            │                      │                        │
└────────────┼──────────────────────┼────────────────────────┼────────────────
             │                      │                        │
             ▼                      ▼                        ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                        RUNTIME (each cluster identical)                    │
│                                                                            │
│   Client Request                                                           │
│        │                                                                   │
│        ▼                                                                   │
│   Kong Gateway (platform-level, ingressClassName: kong)                    │
│   ├── TLS termination                                                      │
│   ├── Connect timeout: 5s / Read timeout: 30s                              │
│   └── Routes /fibonacci → fibonacci-service ClusterIP :8080               │
│        │                                                                   │
│        ▼                                                                   │
│   ┌───────────────────────────────────────────────────────┐               │
│   │                fibonacci-service pod                   │               │
│   │                                                        │               │
│   │  RateLimitInterceptor                                  │               │
│   │  └── Sliding window 200 req/min/IP (X-Forwarded-For)  │               │
│   │            │  429 if exceeded                          │               │
│   │            ▼                                           │               │
│   │  Spring MVC Validation (@Min @Max)                     │               │
│   │            │  400 if bad input                         │               │
│   │            ▼                                           │               │
│   │  FibonacciController                                   │               │
│   │  └── FibonacciService (@Cacheable Caffeine)            │               │
│   │       └── Iterative BigInteger O(n)/O(1)               │               │
│   │            │                                           │               │
│   │            ▼                                           │               │
│   │  GlobalExceptionHandler (catch-all, no 5xx leaks)      │               │
│   │            │                                           │               │
│   │            ▼                                           │               │
│   │  FibonacciMetrics (Micrometer counters + timers)       │               │
│   │                                                        │               │
│   └───────────────────────────────────────────────────────┘               │
│        │                                                                   │
│        ├──▶ New Relic Java Agent (JDK_JAVA_OPTIONS -javaagent)             │
│        │    ├── APM: transactions, error rate, throughput                  │
│        │    ├── JVM: heap, GC, threads                                     │
│        │    ├── Custom metrics: rate_limited, n.value, computation time    │
│        │    └── Logs forwarding (JSON via logstash-logback-encoder)        │
│        │                                                                   │
│        ├──▶ /actuator/health/liveness   → Kubernetes liveness probe        │
│        ├──▶ /actuator/health/readiness  → Kubernetes readiness probe       │
│        └──▶ /actuator/prometheus        → Prometheus scrape                │
│                                                                            │
│   HPA: 2–8 pods | CPU >60% scale-up | PDB: minAvailable=1                 │
└────────────────────────────────────────────────────────────────────────────┘
             │
             ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                           SRE / OBSERVABILITY                              │
│                                                                            │
│   New Relic One                                                            │
│   ├── APM dashboard  — transactions, error rate, P99 latency               │
│   ├── Custom metrics — fibonacci.rate_limited, fibonacci.n.value           │
│   ├── Logs           — JSON structured, environment tagged                 │
│   └── Alerts (monitoring/alerts.yaml)                                      │
│        ├── 5xx error rate > 1%  (5 min)  → critical                        │
│        ├── P99 latency > 2s     (5 min)  → critical                        │
│        ├── Throughput = 0       (5 min)  → critical (service down)         │
│        ├── Rate limit > 20% of traffic   → critical (abuse)                │
│        ├── Avg n > 80,000       (5 min)  → warning  (compute pressure)     │
│        └── Container restarts > 3/10min → critical (OOMKill / crash loop) │
│                                                                            │
│   SLOs (monitoring/slo.md)                                                 │
│   ├── Availability: 99.9%  (43.8 min downtime budget / 30 days)           │
│   ├── P95 latency  < 500ms                                                 │
│   └── P99 latency  < 2s                                                    │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## Input / output examples

### Happy path

```bash
# Spec example 1
curl "http://localhost:8080/api/v1/fibonacci?n=2"
# → 200 OK
{ "n": 2, "result": 1 }

# Spec example 2
curl "http://localhost:8080/api/v1/fibonacci?n=10"
# → 200 OK
{ "n": 10, "result": 55 }

# Base cases
curl "http://localhost:8080/api/v1/fibonacci?n=0"
# → 200 OK
{ "n": 0, "result": 0 }

curl "http://localhost:8080/api/v1/fibonacci?n=1"
# → 200 OK
{ "n": 1, "result": 1 }

# Larger value
curl "http://localhost:8080/api/v1/fibonacci?n=50"
# → 200 OK
{ "n": 50, "result": 12586269025 }

# Maximum allowed — F(100000) has ~20,900 digits
curl "http://localhost:8080/api/v1/fibonacci?n=100000"
# → 200 OK
{ "n": 100000, "result": 354224848179261915075... }  # (truncated for display)
```

### Negative / error scenarios

| Input | HTTP status | Response body | Why |
|-------|-------------|---------------|-----|
| `?n=-1` | 400 | `{"error":{"code":"VALIDATION_ERROR","message":"n must be >= 0"}}` | Below minimum |
| `?n=100001` | 400 | `{"error":{"code":"VALIDATION_ERROR","message":"n must be <= 100000 to prevent excessive computation"}}` | Above maximum |
| `?n=abc` | 400 | `{"error":{"code":"TYPE_MISMATCH","message":"Parameter 'n' must be an integer, got: 'abc'"}}` | Not an integer |
| `?n=3.14` | 400 | `{"error":{"code":"TYPE_MISMATCH","message":"Parameter 'n' must be an integer, got: '3.14'"}}` | Float |
| `?n=1e5` | 400 | `{"error":{"code":"TYPE_MISMATCH","...}}` | Scientific notation parsed as float |
| `?n=0x0A` | 400 | `{"error":{"code":"TYPE_MISMATCH","...}}` | Hex string |
| `?n=٧` | 400 | `{"error":{"code":"TYPE_MISMATCH","...}}` | Unicode digit (Arabic-Indic) |
| `?n=😀` | 400 | `{"error":{"code":"TYPE_MISMATCH","...}}` | Emoji |
| `?n=2147483648` | 400 | `{"error":{"code":"TYPE_MISMATCH","...}}` | Beyond `Integer.MAX_VALUE` (2³¹ − 1) |
| (no `n` param) | 400 | `{"error":{"code":"MISSING_PARAMETER","message":"Required parameter 'n' is missing"}}` | Param omitted |
| `POST /api/v1/fibonacci?n=5` | 405 | `{"error":{"code":"METHOD_NOT_ALLOWED","message":"HTTP method 'POST' is not supported. Use GET."}}` | Wrong HTTP method |
| `GET /api/v1/unknown` | 404 | `{"error":{"code":"NOT_FOUND","message":"The requested endpoint does not exist"}}` | Unknown path |
| 201st request/min from same IP | 429 | `{"error":{"code":"RATE_LIMIT_EXCEEDED","message":"Too many requests. Max 200 requests per minute per IP."}}` | Rate limit hit |
| Accept: application/xml | 406 | (empty body) | Service only produces JSON |

```bash
# curl examples for negative cases

# n out of range
curl "http://localhost:8080/api/v1/fibonacci?n=-1"
# → 400 {"error":{"code":"VALIDATION_ERROR","message":"n must be >= 0"}}

# wrong type
curl "http://localhost:8080/api/v1/fibonacci?n=hello"
# → 400 {"error":{"code":"TYPE_MISMATCH","message":"Parameter 'n' must be an integer, got: 'hello'"}}

# missing param
curl "http://localhost:8080/api/v1/fibonacci"
# → 400 {"error":{"code":"MISSING_PARAMETER","message":"Required parameter 'n' is missing"}}

# wrong method
curl -X POST "http://localhost:8080/api/v1/fibonacci?n=5"
# → 405 {"error":{"code":"METHOD_NOT_ALLOWED","message":"HTTP method 'POST' is not supported. Use GET."}}

# unknown path
curl "http://localhost:8080/api/v1/unknown"
# → 404 {"error":{"code":"NOT_FOUND","message":"The requested endpoint does not exist"}}
```

---

## Workflow 1 — API request lifecycle

How a single request moves through the system from client to response.

```
CLIENT
  │
  │  GET /fibonacci?n=10
  │  Host: fibonacci.connective.com.au
  ▼
┌─────────────────────────────────────────────────────────────────────┐
│  KONG GATEWAY  (platform-level ingress)                             │
│                                                                     │
│  ┌─ TLS termination ──────────────────────────────────────────────┐ │
│  │  ┌─ Route match: path prefix /fibonacci ─────────────────────┐ │ │
│  │  │  Strip path prefix → forward to fibonacci-service:8080   │ │ │
│  │  │  connect-timeout: 5s │ read-timeout: 30s                  │ │ │
│  │  │                                                           │ │ │
│  │  │  If pod doesn't respond in time:                          │ │ │
│  │  │    → 504 Gateway Timeout to client                        │ │ │
│  │  └───────────────────────────────────────────────────────────┘ │ │
│  └─────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
  │
  │  X-Forwarded-For: <real-client-ip> header added by Kong
  ▼
┌─────────────────────────────────────────────────────────────────────┐
│  SPRING MVC  (fibonacci-service pod)                                │
│                                                                     │
│  ① RateLimitInterceptor  (preHandle — runs before controller)       │
│  │                                                                  │
│  │  Read X-Forwarded-For → resolve real client IP                   │
│  │  Look up sliding window for this IP (last 60s of timestamps)     │
│  │  Evict timestamps older than 60s                                 │
│  │                                                                  │
│  │  window_size >= 200?                                             │
│  │    YES → 429 {"error":{"code":"RATE_LIMIT_EXCEEDED",...}}        │
│  │           metrics.fibonacci.rate_limited.total++                 │
│  │           request stops here                                     │
│  │    NO  → add timestamp to window, continue                       │
│  │                                                                  │
│  ② Spring MVC binding + @Validated                                  │
│  │                                                                  │
│  │  Can ?n be parsed as int?                                        │
│  │    NO  → MethodArgumentTypeMismatchException                     │
│  │           → GlobalExceptionHandler → 400 TYPE_MISMATCH          │
│  │                                                                  │
│  │  Is ?n present?                                                  │
│  │    NO  → MissingServletRequestParameterException                 │
│  │           → GlobalExceptionHandler → 400 MISSING_PARAMETER      │
│  │                                                                  │
│  │  Is 0 ≤ n ≤ 100000?                                              │
│  │    NO  → ConstraintViolationException (@Min / @Max)              │
│  │           → GlobalExceptionHandler → 400 VALIDATION_ERROR       │
│  │           metrics.fibonacci.validation.errors++                  │
│  │                                                                  │
│  ③ FibonacciController.fibonacci(n)                                 │
│  │                                                                  │
│  │  Start computation timer (Micrometer)                            │
│  │  Call FibonacciService.compute(n)                                │
│  │                                                                  │
│  │  ┌─ Caffeine cache lookup ────────────────────────────────────┐  │
│  │  │                                                            │  │
│  │  │  Cache HIT?                                                │  │
│  │  │    YES → return cached BigInteger  (O(1), no computation)  │  │
│  │  │                                                            │  │
│  │  │  Cache MISS?                                               │  │
│  │  │    → Iterative BigInteger loop  (O(n) time, O(1) space)    │  │
│  │  │    → Store result in cache (max 10,000 entries, 30min TTL) │  │
│  │  │    → Return result                                         │  │
│  │  └────────────────────────────────────────────────────────────┘  │
│  │                                                                  │
│  │  Stop computation timer                                          │
│  │  metrics.fibonacci.requests.total++                              │
│  │  metrics.fibonacci.n.value.record(n)                             │
│  │                                                                  │
│  │  Any unexpected exception?                                       │
│  │    → GlobalExceptionHandler catch-all                           │
│  │    → 500 {"error":{"code":"INTERNAL_ERROR",...}}                 │
│  │    → Stack trace logged at ERROR level (never sent to client)    │
│  │                                                                  │
│  ④ Response serialisation                                           │
│                                                                     │
│  200 OK  Content-Type: application/json                             │
│  {"n": 10, "result": 55}                                            │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
  │
  ▼
┌─────────────────────────────────────────────────────────────────────┐
│  NEW RELIC JAVA AGENT  (javaagent in JVM, invisible to code)        │
│                                                                     │
│  Captures every transaction automatically:                          │
│  ├── duration, HTTP status, error flag                              │
│  ├── JVM heap, GC pause, thread pool snapshot                       │
│  ├── traceId / spanId added to log MDC (log ↔ trace correlation)    │
│  └── Custom metrics forwarded: fibonacci.requests, n.value, etc.    │
└─────────────────────────────────────────────────────────────────────┘
  │
  ▼
CLIENT receives response
```

**Error code reference:**

| Condition | Status | `error.code` |
|-----------|--------|--------------|
| n < 0 or n > 100000 | 400 | `VALIDATION_ERROR` |
| n is not an integer (abc, 3.14, 1e5) | 400 | `TYPE_MISMATCH` |
| n param missing | 400 | `MISSING_PARAMETER` |
| Rate limit exceeded | 429 | `RATE_LIMIT_EXCEEDED` |
| Wrong HTTP method | 405 | `METHOD_NOT_ALLOWED` |
| Unknown path | 404 | `NOT_FOUND` |
| Unexpected / unhandled error | 500 | `INTERNAL_ERROR` |

---

## Workflow 2 — Kubernetes reliability and failover

How the service stays up through crashes, node failures, bad deploys, and traffic spikes.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  POD STARTUP SEQUENCE                                                       │
│                                                                             │
│  ArgoCD deploys new pod                                                     │
│         │                                                                   │
│         ▼                                                                   │
│  Container starts (JVM init ~20–40s)                                        │
│         │                                                                   │
│         │  startupProbe: GET /actuator/health                               │
│         │  Every 10s, up to 30 attempts (= 5 min grace period)              │
│         │                                                                   │
│         │  Pod NOT in Service endpoints yet — no live traffic               │
│         │                                                                   │
│         │  Probe fails (JVM still loading)?                                 │
│         │    → Wait 10s, retry. No restart yet.                             │
│         │                                                                   │
│         │  Probe fails 30 consecutive times?                                │
│         │    → Pod killed and rescheduled (crashed before ready)            │
│         │                                                                   │
│         │  Probe succeeds?                                                  │
│         ▼    → startupProbe hands off to liveness + readiness probes        │
│  ─────────────────────────────────────────────────────────────────────────  │
│  RUNTIME HEALTH CHECKS  (running continuously on every pod)                 │
│                                                                             │
│  livenessProbe: GET /actuator/health/liveness  (every 10s, timeout 5s)     │
│  │                                                                          │
│  │  Passes?  → Do nothing. Pod stays running.                               │
│  │  Fails 3× in a row?                                                      │
│  │    → Kubernetes kills and restarts the container                         │
│  │    → Use case: JVM thread deadlock, OutOfMemoryError, hung state         │
│  │    → NR alert fires if restarts > 3 in 10 min                           │
│  │                                                                          │
│  readinessProbe: GET /actuator/health/readiness  (every 5s, timeout 2s)    │
│  │                                                                          │
│  │  Passes?  → Pod is in Service endpoints, Kong routes traffic to it       │
│  │  Fails 2× in a row?                                                      │
│  │    → Pod removed from Service endpoints                                  │
│  │    → Kong stops sending new requests to this pod                         │
│  │    → In-flight requests still complete (graceful drain)                  │
│  │    → Pod NOT killed — just quarantined. Recovers when probe passes again │
│  │    → Use case: pod is alive but not ready (config reload, GC pause)      │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│  GRACEFUL SHUTDOWN SEQUENCE  (SIGTERM received — rolling deploy or drain)   │
│                                                                             │
│  t=0s   Kubernetes sends SIGTERM to pod                                     │
│         │                                                                   │
│         ├── readinessProbe fails immediately → pod removed from Kong        │
│         │   No new requests routed to this pod                              │
│         │                                                                   │
│         ├── Spring Boot receives SIGTERM → enters graceful shutdown mode    │
│         │   server.shutdown=graceful (configured in application.properties) │
│         │   Tomcat stops accepting new connections                          │
│         │   In-flight requests continue processing                          │
│         │                                                                   │
│  t=60s  spring.lifecycle.timeout-per-shutdown-phase=60s elapses            │
│         → Spring Boot exits even if some requests are still in flight       │
│         → JVM shuts down                                                    │
│         │                                                                   │
│  t=70s  terminationGracePeriodSeconds=70 elapses                           │
│         → Kubernetes sends SIGKILL if process is still running              │
│         → 10s gap between Spring's 60s and k8s 70s ensures Spring          │
│            always exits cleanly before SIGKILL                              │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│  ZERO-DOWNTIME ROLLING DEPLOY  (new image tag pushed to gitops repo)        │
│                                                                             │
│  Initial state: 3 pods running v1.0.0                                       │
│  [pod-1: READY] [pod-2: READY] [pod-3: READY]                               │
│                                                                             │
│  ArgoCD detects new image tag in overlay → applies new Deployment spec      │
│                                                                             │
│  maxUnavailable: 0   ← never remove an old pod until a new one is ready     │
│  maxSurge: 33%       ← add at most 1 extra pod during rollout               │
│                                                                             │
│  Step 1: Start new pod-4 (v1.1.0)                                           │
│  [pod-1: READY] [pod-2: READY] [pod-3: READY] [pod-4: STARTING]            │
│                                                                             │
│  Step 2: pod-4 passes readinessProbe → joins Service endpoints              │
│  [pod-1: READY] [pod-2: READY] [pod-3: READY] [pod-4: READY]               │
│                                                                             │
│  Step 3: Terminate pod-1 (SIGTERM → 60s drain → exit)                       │
│  [pod-2: READY] [pod-3: READY] [pod-4: READY]  ← 3 pods always serving     │
│                                                                             │
│  Steps 4-6: Repeat for pod-2 and pod-3                                      │
│                                                                             │
│  Final state: 3 pods running v1.1.0, zero downtime                          │
│                                                                             │
│  If new pod NEVER passes readinessProbe?                                    │
│    → Old pods are never terminated (maxUnavailable: 0 guarantees this)      │
│    → Rollout stalls — ArgoCD reports Degraded                               │
│    → SRE investigates — no traffic loss                                      │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│  HORIZONTAL SCALING  (HPA — autoscaling/v2)                                 │
│                                                                             │
│  Normal load: 2 pods (minReplicas)                                          │
│                                                                             │
│  Traffic spike detected:                                                    │
│    CPU  > 60% average across pods  OR                                       │
│    Memory > 80% average across pods                                         │
│         │                                                                   │
│         ▼                                                                   │
│  scaleUp: stabilizationWindow 30s                                           │
│    → Wait 30s to confirm spike is real (not a momentary burst)              │
│    → Add up to 2 pods per 60s                                               │
│    → HPA ceiling: 8 pods                                                    │
│                                                                             │
│  Traffic normalises:                                                        │
│    CPU  < 60% AND memory < 80%                                              │
│         │                                                                   │
│         ▼                                                                   │
│  scaleDown: stabilizationWindow 300s (5 min)                                │
│    → Wait 5 min to confirm load has genuinely dropped                       │
│    → Remove at most 1 pod per 60s (gentle scale-down)                       │
│    → Floor: 2 pods (minReplicas)                                            │
│                                                                             │
│  HPA ceiling hit (8 pods, CPU still > 60%)?                                 │
│    → NR alert fires: "HPA at max replicas"                                  │
│    → Human decision required to raise maxReplicas or investigate load       │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│  NODE FAILURE / DRAIN  (PodDisruptionBudget)                                │
│                                                                             │
│  Scenario: platform team drains node-2 for maintenance                     │
│                                                                             │
│  Running pods (topology-spread across nodes):                               │
│  node-1: [pod-A: READY]                                                     │
│  node-2: [pod-B: READY]  ← node being drained                               │
│  node-3: [pod-C: READY]                                                     │
│                                                                             │
│  PodDisruptionBudget: minAvailable=1                                        │
│                                                                             │
│  kubectl drain node-2:                                                      │
│    → Kubernetes evicts pod-B                                                 │
│    → Checks PDB: after eviction, 2 pods remain (A + C) ≥ minAvailable(1)   │
│    → Eviction allowed. pod-B terminated gracefully.                         │
│    → Scheduler places new pod-D on node-1 or node-3                         │
│    → No service interruption                                                 │
│                                                                             │
│  What if only 1 pod is running (e.g. HPA scaled down to min=1)?            │
│    → Eviction would leave 0 pods < minAvailable(1)                          │
│    → Kubernetes BLOCKS the drain until another pod becomes available        │
│    → Operator notified — must resolve before drain can proceed              │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│  MULTI-DATACENTRE FAILOVER                                                  │
│                                                                             │
│  prod-mel (Melbourne)    prod-syd (Sydney)                                  │
│  3 replicas              3 replicas                                         │
│  Same image digest       Same image digest                                  │
│                                                                             │
│  Both DCs are active simultaneously (active-active).                        │
│  DNS / load balancing at the platform level routes between DCs.             │
│                                                                             │
│  Melbourne cluster goes down:                                               │
│    → Platform-level health check detects Melbourne unresponsive             │
│    → DNS failover routes 100% of traffic to Sydney                          │
│    → Sydney HPA scales up to absorb the additional load                     │
│    → No config change needed — both clusters run identical manifests        │
│                                                                             │
│  Service is stateless (no shared DB, no sticky sessions) →                  │
│  any request can be served by any pod in any DC                             │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│  POD CRASH AND OOM RECOVERY                                                 │
│                                                                             │
│  Two distinct failure modes — both handled automatically.                   │
│                                                                             │
│  ── Scenario A: Application crash (unhandled exception / JVM error) ──────  │
│                                                                             │
│  Pod crashes (process exits with non-zero code)                             │
│         │                                                                   │
│         ▼                                                                   │
│  Kubernetes detects container exit immediately                              │
│  restartPolicy: Always (default) → container restarted                     │
│  restartCount increments                                                    │
│         │                                                                   │
│         ▼                                                                   │
│  startupProbe takes over (5 min grace for JVM to boot)                      │
│  Once healthy → liveness + readiness probes resume                          │
│  Pod rejoins Service endpoints → Kong routes traffic to it again            │
│         │                                                                   │
│  restartCount > 3 in 10 min?                                                │
│    → NR alert "Container Restart Loop" fires → SRE paged                    │
│    → SRE checks NR logs for ERROR at boot time to find root cause           │
│                                                                             │
│  ── Scenario B: Out Of Memory (OOM) ───────────────────────────────────────│
│                                                                             │
│  Two layers of protection prevent OOMKill from happening silently:         │
│                                                                             │
│  Layer 1 — JVM heap cap (prevent breach of container limit)                 │
│    -XX:MaxRAMPercentage=65.0                                                │
│    Container memory limit: 512Mi                                            │
│    JVM heap ceiling:        332Mi  (65% of 512Mi)                           │
│    Non-heap headroom:       180Mi  (thread stacks, Metaspace, NR agent,     │
│                                     Caffeine off-heap)                      │
│    → JVM heap exhaustion hits BEFORE Kubernetes OOMKill                     │
│    → JVM stays within container limits under normal load                    │
│                                                                             │
│  Layer 2 — Fail fast on heap exhaustion                                     │
│    -XX:+ExitOnOutOfMemoryError                                              │
│    → When JVM heap is fully exhausted, JVM exits immediately                │
│    → Clean exit (not a hung/degraded state serving errors)                  │
│    → Kubernetes sees container exit → restarts it (same as Scenario A)     │
│    → Last ERROR log line before exit identifies the cause                   │
│                                                                             │
│  Layer 3 — HPA scales out before OOM is reached                             │
│    Memory HPA trigger: 80% of 512Mi = 409Mi                                 │
│    → HPA adds pods when avg memory climbs toward the limit                  │
│    → Load distributed across more pods → per-pod memory drops               │
│    → OOM never triggered under normal traffic growth                        │
│                                                                             │
│  Full OOM recovery sequence:                                                │
│                                                                             │
│  Memory grows (high n values, cache pressure, traffic spike)                │
│         │                                                                   │
│         ▼                                                                   │
│  Memory > 80% average across pods                                           │
│    → HPA adds pod (scaleUp: max 2 pods per 60s)                             │
│    → New pod shares load → memory pressure drops                            │
│    → No OOM in most cases                                                   │
│         │                                                                   │
│  If memory keeps growing past HPA response speed:                           │
│         │                                                                   │
│         ▼                                                                   │
│  JVM heap at 332Mi ceiling → -XX:+ExitOnOutOfMemoryError triggers          │
│    → Container exits cleanly                                                │
│    → Kubernetes restarts pod (exponential backoff: 0s, 10s, 20s, 40s...)   │
│    → Remaining pods absorb traffic (PDB ensures minAvailable=1)             │
│    → Restarted pod comes back healthy via startupProbe                      │
│         │                                                                   │
│         ▼                                                                   │
│  restartCount > 3 in 10 min                                                 │
│    → NR alert "Container Restart Loop" fires                                │
│    → SRE investigates: check NR logs for OOMKill / large n values           │
│    → Fix: raise memory limit in overlay (Melbourne/Sydney config.env)       │
│           or lower @Max(100000) cap to reduce worst-case heap usage         │
│           raise PR → ArgoCD deploys → new pods with higher limit            │
│                                                                             │
│  Additional JVM flags in production overlays:                               │
│    -XX:+UseG1GC              low-pause GC, handles heap well under load     │
│    -XX:+UseStringDeduplication reduces heap for repeated string values      │
│    -XX:ActiveProcessorCount=1 tells JVM it has 1 vCPU (avoids              │
│                               over-provisioning thread pools)               │
│    -XX:InitialRAMPercentage=65.0 start at full heap to avoid GC at boot     │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Capacity and throughput

How many requests the service can handle per second, and what limits apply at each layer.

### Rate limits (enforced)

| Layer | Limit | Scope | What happens when exceeded |
|-------|-------|-------|---------------------------|
| Kong (platform gateway) | 50 RPS per route | All clients combined | 429 from Kong before request reaches pod |
| In-process rate limiter | 200 req/min per IP | Per client IP, per pod | 429 `RATE_LIMIT_EXCEEDED` from the service |
| Tomcat thread pool | 200 concurrent threads | Per pod | Requests queue (up to 500) then Kong times out at 30s |

> **Note on the in-process rate limiter:** it is per-pod. With 3 replicas, Kong load-balances across pods, so a single client can effectively reach 3 × 200 = 600 req/min before hitting the pod-level limit. For true distributed rate limiting, back the limiter with Redis (currently acknowledged but not implemented).

---

### Throughput estimates per pod

Throughput depends heavily on whether the result is already cached.

| Scenario | Latency per request | Estimated RPS per pod |
|----------|--------------------|-----------------------|
| Cache **hit** (n already computed) | < 1ms | ~2,000–5,000 RPS |
| Cache **miss**, small n (n ≤ 1,000) | 1–5ms | ~200–1,000 RPS |
| Cache **miss**, medium n (n ≤ 10,000) | 5–50ms | ~20–200 RPS |
| Cache **miss**, large n (n ≤ 100,000) | up to ~500ms | ~2–20 RPS |

In practice, after warm-up the cache hit rate climbs quickly. The Caffeine cache holds 10,000 entries with a 30-min TTL — any n value requested more than once is served from memory.

---

### Cluster-wide throughput at scale

| Pod count (HPA) | Tomcat threads total | Estimated RPS (warm cache) | Estimated RPS (cold, mixed n) |
|-----------------|---------------------|---------------------------|-------------------------------|
| 2 pods (min) | 400 | ~4,000–10,000 | ~400–2,000 |
| 4 pods | 800 | ~8,000–20,000 | ~800–4,000 |
| 8 pods (max) | 1,600 | ~16,000–40,000 | ~1,600–8,000 |

HPA scales out automatically when:
- CPU > 60% average across pods, **or**
- Memory > 80% average across pods

Scale-up adds up to 2 pods per 60 seconds (stabilisation window: 30s).

---

### What limits throughput in practice

```
Request throughput is bounded by whichever of these is hit first:

1. Kong rate limit      50 RPS hard ceiling at the gateway
                        ↓ raise in Kong config if needed

2. In-process limiter   200 req/min per IP per pod
                        ↓ tune rate.limit.requests-per-minute in config.env

3. Computation cost     Large n values (>10,000) are CPU-intensive on cache miss
                        ↓ Caffeine cache eliminates cost on repeat requests
                        ↓ n capped at 100,000 to bound worst case

4. JVM heap             Caffeine holds up to 10,000 entries
                        Large n results (n=100,000 → ~20,900 digit BigInteger)
                        consume significant heap
                        ↓ JVM heap capped at 65% of 512Mi = 332Mi
                        ↓ HPA scales out when memory hits 80%

5. HPA ceiling          8 pods maximum
                        ↓ raise maxReplicas in hpa.yaml for sustained
                          high-traffic events
```

---

### SLO latency targets

| Percentile | Target | What it means |
|------------|--------|---------------|
| P95 | < 500ms | 95 out of 100 requests complete in under 500ms |
| P99 | < 2s | 99 out of 100 requests complete in under 2s |

P99 > 2s for 5 consecutive minutes triggers the **High P99 Latency** alert. Common causes:
- Sudden spike in large n requests (no cache warmth)
- CPU throttling (check HPA — may need to scale out)
- G1GC full-collection pause (check NR JVM metrics)

---

## Workflow 3 — End-to-end system: code to production

How a change flows from a developer's laptop to both production data centres.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  DEVELOPER                                                                  │
│                                                                             │
│  git push → opens PR on GitHub                                              │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  GITHUB ACTIONS CI  (.github/workflows/ci.yml)                              │
│                                                                             │
│  ┌─ Job 1: Build & Test ──────────────────────────────────────────────────┐ │
│  │  mvn verify                                                            │ │
│  │  ├── 59 unit + integration + resilience tests must all pass            │ │
│  │  └── OWASP dependency check: any CVE with CVSS ≥ 9 → CI FAILS         │ │
│  └──────────────────────────────────────────────┬─────────────────────────┘ │
│                                                 │ passes                    │
│  ┌─ Job 2: Docker Build → Scan → Push ──────────▼─────────────────────────┐ │
│  │  docker buildx build                                                   │ │
│  │  ├── BuildKit cache: .m2 deps + NR agent cached across CI runs         │ │
│  │  ├── Stage 1 (build): mvn package -DskipTests                          │ │
│  │  └── Stage 2 (runtime): eclipse-temurin:21-jre-alpine, non-root user   │ │
│  │                                                                        │ │
│  │  Trivy scan — container CVE scan                                       │ │
│  │  └── CRITICAL or HIGH unfixed CVE found? → CI FAILS, image not pushed  │ │
│  │                                                                        │ │
│  │  Push to GitLab registry                                               │ │
│  │  └── Tag: v1.x.y@sha256:<digest>  (immutable — digest from build       │ │
│  │         action output, NOT docker inspect which was a prior bug)       │ │
│  └──────────────────────────────────────────────┬─────────────────────────┘ │
│                                                 │ passes                    │
│  ┌─ Job 3: Update GitOps (test overlay only) ───▼─────────────────────────┐ │
│  │  scripts/update-image-tag.sh                                           │ │
│  │  └── Patches test/kustomization.yaml with new image digest             │ │
│  │  git commit [skip ci] → push to main                                   │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  ARGOCD  (ApplicationSet — matrix: env × cluster)                           │
│                                                                             │
│  Watches the gitops repo every 3 minutes (or webhook on push)               │
│                                                                             │
│  Detects change in test/kustomization.yaml                                  │
│         │                                                                   │
│         ▼                                                                   │
│  ┌──────────────────┐                                                        │
│  │  test cluster    │  auto-sync                                             │
│  │  2 replicas      │──────────────────── rolling deploy → pods updated     │
│  │  main-latest tag │  selfHeal: true (manual kubectl changes reverted)     │
│  └──────────────────┘                                                        │
│                                                                             │
│  For production: engineer raises manual PR                                  │
│    └── Updates melbourne/ and sydney/ overlays to the same digest tag       │
│         │                                                                   │
│         ▼                                                                   │
│  PR reviewed → merged                                                        │
│         │                                                                   │
│         ▼                                                                   │
│  ┌───────────────────┐    ┌───────────────────┐                             │
│  │  prod-mel cluster │    │  prod-syd cluster  │                            │
│  │  3 replicas       │    │  3 replicas        │  both auto-sync on merge   │
│  │  v1.x@sha256:...  │    │  v1.x@sha256:...   │                            │
│  └───────────────────┘    └───────────────────┘                             │
│                                                                             │
│  Deploy health check:                                                        │
│    ArgoCD watches pod readiness during rollout                               │
│    New pods fail readinessProbe? → rollout stalls, old pods keep serving    │
│    ArgoCD reports Degraded → Teams SRE alert fires                           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  RUNTIME OBSERVABILITY  (New Relic)                                         │
│                                                                             │
│  Every request captured by NR Java agent:                                   │
│  ├── APM: error rate, throughput, P50/P95/P99 latency                       │
│  ├── JVM: heap, GC, thread pool                                             │
│  ├── Custom metrics: rate_limited, n.value, computation.seconds             │
│  └── Logs: structured JSON with traceId for request correlation             │
│                                                                             │
│  Alert conditions (all notify teams-sre channel):                           │
│                                                                             │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │ Alert                     │ Fires when            │ Severity         │   │
│  │ ─────────────────────────────────────────────────────────────────── │   │
│  │ 5xx error rate            │ > 1% for 5 min        │ critical         │   │
│  │ P99 latency               │ > 2s for 5 min        │ critical         │   │
│  │ Throughput drop           │ 0 req/min for 5 min   │ critical         │   │
│  │ Rate limit abuse          │ > 20% requests 429'd  │ critical         │   │
│  │ Container restart loop    │ > 3 restarts / 10 min │ critical         │   │
│  │ High avg n value          │ avg n > 80k / 5 min   │ warning          │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  SLO burn rate alerts:                                                      │
│  ├── 1h window  burn > 14.4x → page immediately (budget gone in 2h)        │
│  ├── 6h window  burn > 6x    → page (budget gone in 5 days)                │
│  └── 24h window burn > 3x   → ticket (budget gone in 10 days)              │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## CI/CD pipeline detail

```
┌──────────────┐     ┌────────────────────────────────┐     ┌──────────────────┐
│              │     │   Job 2: Docker Build & Scan    │     │  Job 3: GitOps   │
│  Job 1:      │     │                                 │     │  Update          │
│  Build &     │────▶│  docker buildx build            │────▶│                  │
│  Test        │     │  (BuildKit cache: .m2, NR agent)│     │  yq patch        │
│              │     │         │                       │     │  kustomization   │
│  mvn verify  │     │         ▼                       │     │  .yaml with      │
│  (59 tests)  │     │  Trivy scan                     │     │  digest tag      │
│         │    │     │  CRITICAL/HIGH → fail CI        │     │         │        │
│         ▼    │     │         │ pass                  │     │         ▼        │
│  OWASP CVE  │     │         ▼                       │     │  git commit      │
│  check      │     │  Push to GitLab registry         │     │  [skip ci]       │
│  CVSS≥9 →   │     │  tag: v1.x@sha256:<digest>      │     │         │        │
│  fail CI    │     │  (digest from build output,      │     │         ▼        │
│             │     │   NOT from docker inspect)       │     │  ArgoCD watches  │
└──────────────┘     └────────────────────────────────┘     │  → auto-sync     │
                                                             │    test cluster  │
                                                             └──────────────────┘
```

---

## Request flow

```
Client
  │
  │  GET /api/v1/fibonacci?n=10
  ▼
Kong Gateway
  ├── TLS termination
  ├── connect-timeout: 5s, read-timeout: 30s
  └── 504 to client if pod doesn't respond in time
  │
  ▼
RateLimitInterceptor (sliding window, per IP)
  ├── Reads X-Forwarded-For (real client IP behind Kong)
  ├── 200/min/IP — evicts timestamps older than 60s
  ├── Increments fibonacci.rate_limited.total metric on reject
  └── 429 {"error":{"code":"RATE_LIMIT_EXCEEDED",...}} if over limit
  │
  ▼
Spring MVC binding + @Validated
  ├── @Min(0) / @Max(100000) — ConstraintViolationException → 400
  ├── type mismatch (?n=abc) — MethodArgumentTypeMismatchException → 400
  ├── missing param — MissingServletRequestParameterException → 400
  └── increments fibonacci.validation.errors metric on reject
  │
  ▼
FibonacciController
  ├── Starts computation timer
  └── calls FibonacciService.compute(n)
        │
        ├── Cache HIT  → return cached BigInteger instantly
        │                (Caffeine, 10k entries, 30 min TTL)
        │
        └── Cache MISS → iterative BigInteger loop O(n) time / O(1) space
                         populate cache → return result
  │
  ▼
Record metrics:
  ├── fibonacci.requests.total++
  ├── fibonacci.n.value.record(n)
  └── stop computation timer → fibonacci.computation.seconds
  │
  ▼
200 OK {"n": 10, "result": 55}
  │
  ▼
NR Java Agent captures transaction
  ├── duration, status code, error flag
  └── JVM heap, GC pause, thread pool snapshot
```

---

## Quick start

### Prerequisites
- Java 21+, Maven 3.9+
- Docker (for Compose and image builds)

### Run locally — fastest

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

`local` profile: rate limit disabled, DEBUG logging, New Relic agent inactive.  
Starts on `http://localhost:8080`.

### Run with Docker Compose

```bash
docker-compose up
```

Pass `NEW_RELIC_LICENSE_KEY=<key>` as an env var to activate APM.

### Apply to local Kubernetes (minikube / kind)

```bash
kubectl apply -k gitops/applications/fibonacci/overlays/local
```

No ArgoCD, no ExternalSecret needed — uses a stub secret.

### Run all tests

```bash
mvn test -Dspring.profiles.active=test
```

Expected output:

```
[INFO] Tests run: 59, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Run a specific test class

```bash
# API layer only
mvn test -Dtest=FibonacciControllerTest -Dspring.profiles.active=test

# Resilience / edge cases
mvn test -Dtest=FibonacciResilienceTest -Dspring.profiles.active=test

# Algorithm correctness only
mvn test -Dtest=FibonacciServiceTest -Dspring.profiles.active=test

# Rate limiter
mvn test -Dtest=RateLimitInterceptorTest -Dspring.profiles.active=test

# Load test (100 concurrent requests)
mvn test -Dtest=FibonacciLoadTest -Dspring.profiles.active=test
```

### Manual testing with curl (service must be running)

Start the service first:
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
# Starts on http://localhost:8080
```

**Happy path:**
```bash
curl -s "http://localhost:8080/api/v1/fibonacci?n=2"
# Expected: {"n":2,"result":1}

curl -s "http://localhost:8080/api/v1/fibonacci?n=10"
# Expected: {"n":10,"result":55}

curl -s "http://localhost:8080/api/v1/fibonacci?n=0"
# Expected: {"n":0,"result":0}

curl -s "http://localhost:8080/api/v1/fibonacci?n=50"
# Expected: {"n":50,"result":12586269025}
```

**Negative / validation scenarios:**
```bash
# n below minimum
curl -s "http://localhost:8080/api/v1/fibonacci?n=-1"
# Expected 400: {"error":{"code":"VALIDATION_ERROR","message":"n must be >= 0"}}

# n above maximum
curl -s "http://localhost:8080/api/v1/fibonacci?n=100001"
# Expected 400: {"error":{"code":"VALIDATION_ERROR","message":"n must be <= 100000 to prevent excessive computation"}}

# n is not an integer
curl -s "http://localhost:8080/api/v1/fibonacci?n=abc"
# Expected 400: {"error":{"code":"TYPE_MISMATCH","message":"Parameter 'n' must be an integer, got: 'abc'"}}

# n is a float
curl -s "http://localhost:8080/api/v1/fibonacci?n=3.14"
# Expected 400: {"error":{"code":"TYPE_MISMATCH","...}}

# n is scientific notation (looks like 100000 but is a float string)
curl -s "http://localhost:8080/api/v1/fibonacci?n=1e5"
# Expected 400: {"error":{"code":"TYPE_MISMATCH","...}}

# n param missing entirely
curl -s "http://localhost:8080/api/v1/fibonacci"
# Expected 400: {"error":{"code":"MISSING_PARAMETER","message":"Required parameter 'n' is missing"}}

# Wrong HTTP method
curl -s -X POST "http://localhost:8080/api/v1/fibonacci?n=5"
# Expected 405: {"error":{"code":"METHOD_NOT_ALLOWED","message":"HTTP method 'POST' is not supported. Use GET."}}

# Unknown path
curl -s "http://localhost:8080/api/v1/unknown"
# Expected 404: {"error":{"code":"NOT_FOUND","message":"The requested endpoint does not exist"}}

# Path traversal attempt
curl -s "http://localhost:8080/api/v1/fibonacci/../../../etc/passwd"
# Expected 404: {"error":{"code":"NOT_FOUND","...}}

# Client requests XML (service only produces JSON)
curl -s -H "Accept: application/xml" "http://localhost:8080/api/v1/fibonacci?n=5"
# Expected 406 Not Acceptable
```

**Rate limit test** (local profile has limit of 200/min — use a low limit for quick testing):
```bash
# Set rate.limit.requests-per-minute=5 in application-local.properties, then:
for i in $(seq 1 6); do
  curl -s "http://localhost:8080/api/v1/fibonacci?n=$i"
  echo
done
# Requests 1-5: 200 OK with results
# Request 6:    429 {"error":{"code":"RATE_LIMIT_EXCEEDED","...}}
```

**Health / observability endpoints:**
```bash
curl -s "http://localhost:8080/actuator/health"
# Expected 200: {"status":"UP",...}

curl -s "http://localhost:8080/actuator/health/liveness"
# Expected 200: {"status":"UP"}

curl -s "http://localhost:8080/actuator/health/readiness"
# Expected 200: {"status":"UP"}

# These are intentionally NOT exposed (security)
curl -s "http://localhost:8080/actuator/env"
# Expected 404

curl -s "http://localhost:8080/actuator/heapdump"
# Expected 404
```

---

## Testing with Postman

### Setup

1. Open Postman
2. Create a new Collection — name it `Fibonacci Service`
3. Set a Collection Variable:
   - Variable: `baseUrl`
   - Initial value: `http://localhost:8080`
   - Change to your deployed URL when testing against a live environment
4. Start the service locally before running requests:
   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=local
   ```

---

### Happy path requests

#### F(2) = 1 — spec example

```
Method:  GET
URL:     {{baseUrl}}/api/v1/fibonacci
Params:  n = 2
```

In Postman: **Params** tab → Key `n`, Value `2` → **Send**

```json
200 OK
{ "n": 2, "result": 1 }
```

---

#### F(10) = 55 — spec example

```
Method:  GET
URL:     {{baseUrl}}/api/v1/fibonacci?n=10
```

```json
200 OK
{ "n": 10, "result": 55 }
```

---

#### F(0) = 0 — base case (zero, not an error)

```
Method:  GET
URL:     {{baseUrl}}/api/v1/fibonacci?n=0
```

```json
200 OK
{ "n": 0, "result": 0 }
```

---

#### F(1) = 1 — base case

```
Method:  GET
URL:     {{baseUrl}}/api/v1/fibonacci?n=1
```

```json
200 OK
{ "n": 1, "result": 1 }
```

---

#### F(50) — larger value

```
Method:  GET
URL:     {{baseUrl}}/api/v1/fibonacci?n=50
```

```json
200 OK
{ "n": 50, "result": 12586269025 }
```

---

#### F(100000) — maximum allowed (result is ~20,900 digits)

```
Method:  GET
URL:     {{baseUrl}}/api/v1/fibonacci?n=100000
```

```json
200 OK
{ "n": 100000, "result": 354224848179261915075... }
```

---

### Negative / error scenario requests

#### n below minimum

```
Method:  GET
URL:     {{baseUrl}}/api/v1/fibonacci?n=-1
```

```json
400 Bad Request
{ "error": { "code": "VALIDATION_ERROR", "message": "n must be >= 0" } }
```

---

#### n above maximum

```
Method:  GET
URL:     {{baseUrl}}/api/v1/fibonacci?n=100001
```

```json
400 Bad Request
{ "error": { "code": "VALIDATION_ERROR", "message": "n must be <= 100000 to prevent excessive computation" } }
```

---

#### n is a string

```
Method:  GET
URL:     {{baseUrl}}/api/v1/fibonacci?n=hello
```

```json
400 Bad Request
{ "error": { "code": "TYPE_MISMATCH", "message": "Parameter 'n' must be an integer, got: 'hello'" } }
```

---

#### n is a float

```
Method:  GET
URL:     {{baseUrl}}/api/v1/fibonacci?n=3.14
```

```json
400 Bad Request
{ "error": { "code": "TYPE_MISMATCH", "message": "Parameter 'n' must be an integer, got: '3.14'" } }
```

---

#### n param missing entirely

```
Method:  GET
URL:     {{baseUrl}}/api/v1/fibonacci
         (no params at all)
```

```json
400 Bad Request
{ "error": { "code": "MISSING_PARAMETER", "message": "Required parameter 'n' is missing" } }
```

---

#### Wrong HTTP method

```
Method:  POST
URL:     {{baseUrl}}/api/v1/fibonacci?n=5
```

```json
405 Method Not Allowed
{ "error": { "code": "METHOD_NOT_ALLOWED", "message": "HTTP method 'POST' is not supported. Use GET." } }
```

---

#### Unknown endpoint

```
Method:  GET
URL:     {{baseUrl}}/api/v1/unknown
```

```json
404 Not Found
{ "error": { "code": "NOT_FOUND", "message": "The requested endpoint does not exist" } }
```

---

#### Rate limit exceeded (429)

To trigger quickly, lower the limit first.  
In `src/main/resources/application-local.properties` set `rate.limit.requests-per-minute=3`, restart, then send the same request 4 times in quick succession.

```
Method:  GET
URL:     {{baseUrl}}/api/v1/fibonacci?n=5
(send 4 times rapidly)
```

Requests 1–3:
```json
200 OK
{ "n": 5, "result": 5 }
```

Request 4:
```json
429 Too Many Requests
{ "error": { "code": "RATE_LIMIT_EXCEEDED", "message": "Too many requests. Max 3 requests per minute per IP." } }
```

---

### Health and observability endpoints

#### Overall health

```
Method:  GET
URL:     {{baseUrl}}/actuator/health
```

```json
200 OK
{ "status": "UP" }
```

---

#### Kubernetes liveness probe

```
Method:  GET
URL:     {{baseUrl}}/actuator/health/liveness
```

```json
200 OK
{ "status": "UP" }
```

---

#### Kubernetes readiness probe

```
Method:  GET
URL:     {{baseUrl}}/actuator/health/readiness
```

```json
200 OK
{ "status": "UP" }
```

---

#### Sensitive actuator endpoints — must be blocked

```
Method:  GET
URL:     {{baseUrl}}/actuator/env
```

```
404 Not Found   ← intentional, secrets would be exposed
```

```
Method:  GET
URL:     {{baseUrl}}/actuator/heapdump
```

```
404 Not Found   ← intentional, full JVM memory dump
```

---

### Postman quick-reference

| # | Method | URL | Param | Expected |
|---|--------|-----|-------|----------|
| 1 | GET | `/api/v1/fibonacci` | `n=2` | 200 `{"result":1}` |
| 2 | GET | `/api/v1/fibonacci` | `n=10` | 200 `{"result":55}` |
| 3 | GET | `/api/v1/fibonacci` | `n=0` | 200 `{"result":0}` |
| 4 | GET | `/api/v1/fibonacci` | `n=1` | 200 `{"result":1}` |
| 5 | GET | `/api/v1/fibonacci` | `n=50` | 200 `{"result":12586269025}` |
| 6 | GET | `/api/v1/fibonacci` | `n=100000` | 200 large number |
| 7 | GET | `/api/v1/fibonacci` | `n=-1` | 400 `VALIDATION_ERROR` |
| 8 | GET | `/api/v1/fibonacci` | `n=100001` | 400 `VALIDATION_ERROR` |
| 9 | GET | `/api/v1/fibonacci` | `n=hello` | 400 `TYPE_MISMATCH` |
| 10 | GET | `/api/v1/fibonacci` | `n=3.14` | 400 `TYPE_MISMATCH` |
| 11 | GET | `/api/v1/fibonacci` | *(none)* | 400 `MISSING_PARAMETER` |
| 12 | POST | `/api/v1/fibonacci` | `n=5` | 405 `METHOD_NOT_ALLOWED` |
| 13 | GET | `/api/v1/unknown` | — | 404 `NOT_FOUND` |
| 14 | GET | `/api/v1/fibonacci` | `n=5` ×4 rapid | 429 `RATE_LIMIT_EXCEEDED` |
| 15 | GET | `/actuator/health` | — | 200 `{"status":"UP"}` |
| 16 | GET | `/actuator/health/liveness` | — | 200 `{"status":"UP"}` |
| 17 | GET | `/actuator/health/readiness` | — | 200 `{"status":"UP"}` |
| 18 | GET | `/actuator/env` | — | 404 blocked |
| 19 | GET | `/actuator/heapdump` | — | 404 blocked |

---

## Test scenario matrix

| # | Class | Scenario | Input | Expected |
|---|-------|----------|-------|----------|
| 1 | ServiceTest | F(0) base case | n=0 | 0 |
| 2 | ServiceTest | F(1) base case | n=1 | 1 |
| 3 | ServiceTest | Spec example | n=2 | 1 |
| 4 | ServiceTest | Spec example | n=10 | 55 |
| 5 | ServiceTest | Large value (BigInteger) | n=100 | 354224848179261915075 |
| 6 | ServiceTest | Cache hit consistency | n=50 twice | same result both calls |
| 7 | ControllerTest | Happy path n=2 | n=2 | 200, result=1 |
| 8 | ControllerTest | Happy path n=10 | n=10 | 200, result=55 |
| 9 | ControllerTest | Float rejected | n=3.14 | 400 TYPE_MISMATCH |
| 10 | ControllerTest | String rejected | n=hello | 400 TYPE_MISMATCH |
| 11 | ControllerTest | Boolean rejected | n=true | 400 TYPE_MISMATCH |
| 12 | ControllerTest | SQL injection rejected | n=1;DROP TABLE | 400 TYPE_MISMATCH |
| 13 | ControllerTest | XSS rejected | n=\<script\> | 400 TYPE_MISMATCH |
| 14 | ControllerTest | Wrong method | POST ?n=5 | 405 METHOD_NOT_ALLOWED |
| 15 | ControllerTest | Unknown path | GET /unknown | 404 NOT_FOUND |
| 16 | ControllerTest | Missing param | GET /fibonacci | 400 MISSING_PARAMETER |
| 17 | RateLimitTest | Within limit | 5 requests (limit=5) | all 200 |
| 18 | RateLimitTest | Over limit | 6th request (limit=5) | 429 RATE_LIMIT_EXCEEDED |
| 19 | RateLimitTest | 429 body is valid JSON | 6th request | parseable JSON body |
| 20 | LoadTest | 1,000 valid concurrent requests | n=1..100 mixed | all 200, zero 5xx |
| 21 | LoadTest | 1,000 bad-data requests | n=abc | all 4xx, zero 5xx |
| 22 | LoadTest | 1,000 mixed requests | valid + invalid | zero 5xx |
| 23 | ResilienceTest | Integer.MAX_VALUE | n=2147483647 | 400 VALIDATION_ERROR |
| 24 | ResilienceTest | Beyond int range | n=2147483648 | 400 TYPE_MISMATCH |
| 25 | ResilienceTest | Scientific notation | n=1e5 | 400 TYPE_MISMATCH |
| 26 | ResilienceTest | Hex string | n=0x0A | 400 TYPE_MISMATCH |
| 27 | ResilienceTest | Leading zeros | n=007 | 200, result=13 (F(7)) |
| 28 | ResilienceTest | Null byte | n=%00 | 400 |
| 29 | ResilienceTest | URL-encoded space | n=%205 | 400 |
| 30 | ResilienceTest | Plus sign | n=+5 | 400 |
| 31 | ResilienceTest | Unicode digit | n=٧ | 400 TYPE_MISMATCH |
| 32 | ResilienceTest | Emoji | n=😀 | 400 TYPE_MISMATCH |
| 33 | ResilienceTest | Duplicate param | n=5&n=10 | 200, result=5 (first value) |
| 34 | ResilienceTest | Extra params | n=3&foo=bar | 200, result=2 |
| 35 | ResilienceTest | Maximum n | n=100000 | 200, ~20900-digit result |
| 36 | ResilienceTest | Large n cache consistency | n=50000 twice | identical responses |
| 37 | ResilienceTest | Cache stampede | 50 threads, same n | all consistent, zero errors |
| 38 | ResilienceTest | Actuator env hidden | GET /actuator/env | 404 |
| 39 | ResilienceTest | Actuator beans hidden | GET /actuator/beans | 404 |
| 40 | ResilienceTest | Actuator heapdump hidden | GET /actuator/heapdump | 404 |
| 41 | ResilienceTest | Actuator health exposed | GET /actuator/health | 200 |
| 42 | ResilienceTest | XML accept header | Accept: application/xml | 406 |
| 43 | ResilienceTest | Wildcard accept header | Accept: */* | 200 JSON |
| 44 | ResilienceTest | Path traversal | /../etc/passwd | 404 |
| 45 | ResilienceTest | Trailing slash | /fibonacci/ | 404 |
| 46 | ResilienceTest | Cache eviction | 500 unique n values | all 200, zero errors |
| 47 | ResilienceTest | n=0 returns 0 (not falsy error) | n=0 | 200, result=0 |
| 48 | ResilienceTest | Bad flood then valid | 20 bad + 1 valid | valid returns 200 |

---

## API

### `GET /api/v1/fibonacci?n={n}`

Returns the nth Fibonacci number. F(0) = 0, F(1) = 1, F(n) = F(n-1) + F(n-2).

**Valid range:** `0 ≤ n ≤ 100000`

**Spec examples:**

```
GET /api/v1/fibonacci?n=2   →  200 OK  { "n": 2,  "result": 1  }
GET /api/v1/fibonacci?n=10  →  200 OK  { "n": 10, "result": 55 }
```

**Error response shape** — all errors share this structure:

```json
{ "error": { "code": "VALIDATION_ERROR", "message": "n must be >= 0" } }
```

| Condition | Status | `error.code` |
|-----------|--------|--------------|
| `n` negative | 400 | `VALIDATION_ERROR` |
| `n` > 100000 | 400 | `VALIDATION_ERROR` |
| `n` not an integer (`abc`, `3.14`, `1e5`, `٧`) | 400 | `TYPE_MISMATCH` |
| `n` missing | 400 | `MISSING_PARAMETER` |
| Wrong HTTP method | 405 | `METHOD_NOT_ALLOWED` |
| Unknown path | 404 | `NOT_FOUND` |
| Unexpected error | 500 | `INTERNAL_ERROR` |

### Observability endpoints

| Endpoint | Purpose |
|----------|---------|
| `GET /actuator/health` | Overall health |
| `GET /actuator/health/liveness` | Kubernetes liveness probe |
| `GET /actuator/health/readiness` | Kubernetes readiness probe |
| `GET /actuator/metrics` | JVM + HTTP + cache metrics |
| `GET /actuator/prometheus` | Prometheus scrape endpoint |

`/actuator/env`, `/actuator/beans`, `/actuator/heapdump` are **not exposed** (security).

---

## Implementation

### Algorithm

Iterative O(n) time, O(1) space using `BigInteger`. `long` overflows at F(93). A naive recursive implementation has exponential time — O(2ⁿ) — and stack-overflows around n=10,000.

### Caching

`@Cacheable` backed by Caffeine (10,000 entries, 30-min TTL, stats recording enabled).  
Cache hit rate is visible at `/actuator/metrics/cache.gets`.

### Rate limiting

Two independent layers:

| Layer | Limit | Where |
|-------|-------|-------|
| Kong (platform) | 50 RPS per route, burst ×3 | Before request reaches pod |
| `RateLimitInterceptor` (in-process) | 200 req/min per IP | Inside Spring MVC |

The in-process limiter uses a **sliding window** (not fixed window) keyed by client IP from `X-Forwarded-For`. In a multi-pod deployment the per-pod limit applies — for true distributed rate limiting, back with Redis.

### Input validation

`@Min(0)` and `@Max(100000)` reject out-of-range values before the service layer.  
`@Validated` on the controller activates constraint checking for `@RequestParam`.

---

## Test suite — 63 tests across 5 classes

| Class | Tests | What it covers |
|-------|-------|----------------|
| `FibonacciServiceTest` | 16 | Algorithm — base cases, spec examples, parametrized values F(0)–F(30) (8 cases), F(50) BigInteger overflow, F(100), negative input |
| `FibonacciControllerTest` | 18 | HTTP layer — happy path, floats, strings, booleans, SQL injection, XSS, wrong method, 404, missing param |
| `RateLimitInterceptorTest` | 3 | Within-limit passes; over-limit returns 429; response body is valid JSON |
| `FibonacciLoadTest` | 2 | 100 concurrent requests: all-valid → all 200; bad-data flood → zero 5xx |
| `FibonacciResilienceTest` | 24 | Integer boundary (MAX\_VALUE, beyond int range), scientific notation, hex (0x0A accepted), leading zeros, URL-encoded chars, unicode digit (accepted), emoji, plus-sign prefix, duplicate params, extra params, large response (n=100000), cache consistency, actuator endpoint security, content negotiation, path traversal, trailing slash, bad-data flood then valid request |

---

## Production deployment

### Containerisation

`Dockerfile` uses a two-stage build with **BuildKit cache mounts**:

```
Stage 1 (build)
  ├── --mount=type=cache,target=/root/.m2   ← Maven deps cached across CI builds
  ├── mvn package -DskipTests
  └── --mount=type=cache,target=/tmp/nr-cache  ← NR agent cached by version
       └── download only if newrelic-{version}.jar not in cache

Stage 2 (runtime)
  ├── eclipse-temurin:21-jre-alpine (~120 MB — JRE only, no compiler)
  ├── Non-root user (appuser:appgroup)
  ├── HEALTHCHECK → wget /actuator/health (for docker-compose / standalone)
  └── ENTRYPOINT ["java", "-jar", "app.jar"]
       NR agent activated via JDK_JAVA_OPTIONS (from k8s ConfigMap)
       not hardcoded in image — same image works in all environments
```

### GitOps structure (Kustomize)

```
gitops/applications/fibonacci/
├── components/     Shared: Deployment, Service, HPA, newrelic.yml ConfigMap
├── base/           Service: probes, Kong Ingress, ExternalSecret, image ref
└── overlays/
    ├── test/       Test cluster — main-latest tag, 2 replicas
    ├── melbourne/  Melbourne DC (prod-mel) — pinned v1.x@sha256:..., 3 replicas
    ├── sydney/     Sydney DC (prod-syd)    — pinned v1.x@sha256:..., 3 replicas
    └── local/      Minikube/kind — stub secret, 1 replica, no NR agent
```

### ArgoCD

`gitops/argocd/fibonacci.yaml` — `ApplicationSet` with matrix generator:

| Overlay | ArgoCD cluster label | Data centre | Sync |
|---------|---------------------|-------------|------|
| `test` | `env=test` | Test cluster | Automatic (CI updates tag) |
| `melbourne` | `env=prod-mel` | Melbourne DC | Automatic after gitops PR merge |
| `sydney` | `env=prod-syd` | Sydney DC | Automatic after gitops PR merge |

`selfHeal: true` — any manual `kubectl` change is reverted within 3 minutes.  
Teams notifications fire on deploy/health-degraded for prod clusters only.

### CI/CD

Three jobs — **Job 2 must pass Trivy scan before image is pushed**:

1. **Build & Test** — `mvn verify` (63 tests) + OWASP dependency CVE check (`CVSS ≥ 9` = fail)
2. **Docker Build → Trivy Scan** — BuildKit build, container CVE scan (CRITICAL/HIGH reported), image loaded locally for scan (not pushed — public repo has no registry configured)

> **CD note:** In a production deployment this pipeline would add a third job to push the image to a registry and update the GitOps overlay. That design is documented in the Architecture overview and Workflow 3 sections. The public GitHub repo omits the push because it has no target registry or Kubernetes cluster.

Production promotion: manual PR updating `melbourne/` and `sydney/` overlays to the same immutable digest tag.

### Kong (API Gateway)

Platform-level — not in this repo. Service registers via `Ingress` with `ingressClassName: kong`:
- `konghq.com/connect-timeout: 5000` (5s TCP)
- `konghq.com/read-timeout: 30000` (30s response)
- `konghq.com/strip-path: true`

### Monitoring and logging

**New Relic Java Agent** (activated via `JDK_JAVA_OPTIONS=-javaagent:/opt/newrelic/newrelic.jar`):
- APM: transactions, error rates, throughput, P50/P95/P99 latency
- JVM: heap, GC pause time, thread pool saturation
- Logs: JSON forwarding with `traceId`/`spanId` MDC fields
- Config: `newrelic.yml` mounted as ConfigMap volume — 404/429 excluded from error collector, `expected_status_codes: 400,401,403`
- License key: ExternalSecret from Azure Key Vault — never in git

**Custom business metrics** (`FibonacciMetrics.java`, exported via Micrometer):

| Metric | What it tells you |
|--------|-------------------|
| `fibonacci.requests.total` | Baseline throughput for burn rate calculations |
| `fibonacci.rate_limited.total` | Abuse / misconfigured client indicator |
| `fibonacci.validation.errors` | Bad client detector |
| `fibonacci.n.value` | Distribution of n — are clients requesting cheap or expensive values? |
| `fibonacci.computation.seconds` | Actual compute latency (excludes cache hits) |

All metrics tagged with `application=fibonacci-service` and `environment=prod-mel/prod-syd/test` for NR dashboard filtering.

**Structured logging** — `logback-spring.xml`:
- `prod`/`staging` profiles: JSON via `logstash-logback-encoder` (ships to NR Logs)
- `local`/`test` profiles: plain text for human readability

**Health probes** (`gitops/applications/fibonacci/base/probes.yaml`):

| Probe | Endpoint | Timing | Effect |
|-------|----------|--------|--------|
| Startup | `/actuator/health` | Every 10s, max 30 retries (5 min) | Prevents liveness from killing pod during JVM startup |
| Liveness | `/actuator/health/liveness` | Every 10s, fail after 3 | Restarts pod if JVM is deadlocked |
| Readiness | `/actuator/health/readiness` | Every 5s, fail after 2 | Removes pod from Kong backend — no traffic during rolling deploy |

### SLOs and alerting

Defined in `monitoring/`:

| SLO | Target | Error budget (30 days) |
|-----|--------|------------------------|
| Availability | ≥ 99.9% | 43.8 min |
| P95 latency | < 500ms | 5% of requests |
| P99 latency | < 2s | 1% of requests |

Alert conditions (`monitoring/alerts.yaml`):

| Alert | Fires when |
|-------|-----------|
| High 5xx error rate | > 1% for 5 min |
| High P99 latency | > 2s for 5 min |
| Throughput drop | Zero requests for 5 min |
| Rate limit abuse | > 20% of requests rate-limited |
| High n value | Avg n > 80,000 for 5 min |
| Container restart loop | > 3 restarts in 10 min (OOMKill) |

### Scaling

| Layer | Configuration |
|-------|---------------|
| HPA | 2–8 replicas. Scale-up: CPU >60% or memory >75%, 30s window. Scale-down: 5 min window |
| Caffeine cache | 10k entries, 30-min TTL — hot values free after first call |
| Input cap | `n ≤ 100,000` — bounds worst-case computation |
| Rate limiting | Kong (50 RPS) + in-process (200/min/IP) |
| Rolling deploy | `maxUnavailable: 0`, `maxSurge: 33%`, `terminationGracePeriodSeconds: 70` |
| Topology spread | Pods spread across nodes + soft anti-affinity |
| PodDisruptionBudget | `minAvailable: 1` — survives node drains and cluster upgrades |

---

## Authentication — not implemented (proposal)

> **Status: not implemented in this version.**
> The service currently has no authentication or authorisation layer.
> Any caller that passes rate limiting and input validation receives a response.
> This section documents what authentication would look like and how it would be added.

---

### Why it was not added

The assignment spec does not require authentication, and adding a half-wired auth layer
(with no identity provider, no token issuance, no key management) would produce code
that cannot be meaningfully tested or validated. The more honest approach is to document
the gap clearly and propose the correct production implementation.

---

### Current behaviour without authentication

| Scenario | Current response | Expected response with auth |
|----------|-----------------|----------------------------|
| Valid request, no credentials | `200 OK` | `401 Unauthorized` |
| Valid request, invalid/expired token | `200 OK` | `401 Unauthorized` |
| Valid request, valid token, insufficient scope | `200 OK` | `403 Forbidden` |
| Valid request, valid token, correct scope | `200 OK` | `200 OK` (no change) |

---

### Proposed approach: API key authentication (simplest) or JWT (recommended)

#### Option 1 — API key (simple, suitable for server-to-server)

```
Client sends:  GET /api/v1/fibonacci?n=10
               Authorization: ApiKey <key>

Kong Gateway validates key:
  Key absent or unrecognised  →  401 Unauthorized
  Key valid, quota exceeded   →  429 Too Many Requests
  Key valid                   →  forward to fibonacci-service
```

Kong has a built-in API key plugin — no code changes needed in the service.
Keys are stored in Kong's database and issued per consumer (team or application).

#### Option 2 — JWT Bearer token (recommended for user-facing or multi-tenant)

```
Client sends:  GET /api/v1/fibonacci?n=10
               Authorization: Bearer <jwt>

Kong validates JWT signature + expiry:
  Token absent                →  401 Unauthorized
                                 {"error":{"code":"UNAUTHORIZED",
                                           "message":"Authentication required."}}

  Token invalid / expired     →  401 Unauthorized
                                 {"error":{"code":"UNAUTHORIZED",
                                           "message":"Token is invalid or has expired."}}

  Token valid, scope missing  →  403 Forbidden
                                 {"error":{"code":"FORBIDDEN",
                                           "message":"Your token does not have permission
                                                       to access this resource."}}

  Token valid, scope present  →  forward to fibonacci-service → 200 OK
```

```
Implementation steps:
  1. Add Kong JWT plugin to the fibonacci-service route
  2. Issue JWTs from your identity provider (Azure AD / Auth0 / Keycloak)
     with a scope claim e.g. fibonacci:read
  3. Kong verifies: signature (RS256 public key), expiry, audience
  4. Kong strips the Authorization header (optional) before forwarding to the pod
  5. No Spring Security changes needed — auth is enforced at the gateway,
     not inside the application
```

#### Option 3 — Spring Security inside the service (defence in depth)

If you need auth enforcement even for internal cluster traffic (bypassing Kong):

```java
// Add to pom.xml:
// <dependency>
//   <groupId>org.springframework.boot</groupId>
//   <artifactId>spring-boot-starter-security</artifactId>
// </dependency>
// <dependency>
//   <groupId>org.springframework.boot</groupId>
//   <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
// </dependency>

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**").permitAll()  // k8s probes — no auth
                .requestMatchers("/actuator/prometheus").permitAll() // Prometheus scrape
                .requestMatchers("/api/v1/fibonacci").hasAuthority("SCOPE_fibonacci:read")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
```

```yaml
# application.properties additions
spring.security.oauth2.resourceserver.jwt.issuer-uri=https://your-idp.com
spring.security.oauth2.resourceserver.jwt.audiences=fibonacci-service
```

With this in place the error responses become:

```
No token          →  401  {"error":{"code":"UNAUTHORIZED","message":"Authentication required."}}
Expired token     →  401  {"error":{"code":"UNAUTHORIZED","message":"Token is invalid or has expired."}}
Wrong scope       →  403  {"error":{"code":"FORBIDDEN","message":"Your token does not have permission to access this resource."}}
```

These would be handled by extending `GlobalExceptionHandler` with:

```java
@ExceptionHandler(AccessDeniedException.class)
public ResponseEntity<Map<String, Object>> handleForbidden(AccessDeniedException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(error("FORBIDDEN", "Your token does not have permission to access this resource."));
}

@ExceptionHandler(AuthenticationException.class)
public ResponseEntity<Map<String, Object>> handleUnauthorised(AuthenticationException ex) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(error("UNAUTHORIZED", "Authentication required."));
}
```

---

### Recommendation

| Layer | What to add | Why |
|-------|-------------|-----|
| Kong | JWT plugin on the fibonacci route | Single enforcement point for all services on the platform; no app code changes |
| Spring Security | `oauth2-resource-server` inside the pod | Defence in depth — enforces auth even for internal cluster traffic that bypasses Kong |
| Actuator endpoints | Remain unauthenticated | Kubernetes probes cannot send tokens; health and prometheus endpoints must stay open |
| Secrets | JWT public key via ExternalSecret from Azure Key Vault | Never in git; rotated without redeployment |

---

## SRE proposal: what I would automate next

> **Status: proposal — not yet implemented.**
> This section describes how I would evolve this service's operational posture as an SRE,
> moving from reactive monitoring to a fully automated detect → diagnose → fix → review → deploy loop.

---

### The problem with the current setup

What we have today is good observability: New Relic alerts fire, logs are structured, health
probes restart crashed pods. But the response to every alert still requires a human to:

1. Read the alert
2. Open NR, query logs, find the root cause
3. Write a fix
4. Open a PR
5. Wait for review
6. Merge and deploy

For a 24/7 production service this means on-call engineers are woken at 3am to do work
that is largely pattern-matching — the same OOM fix, the same config change, the same
dependency bump. The goal is to automate the routine so humans only handle the novel.

---

### Proposal 1: AI-powered SRE monitoring agent

An autonomous agent that watches the service continuously, diagnoses problems, and raises
a PR with a proposed fix — without waking anyone up for the routine cases.

```
┌─────────────────────────────────────────────────────────────────────┐
│                    SRE AGENT LOOP (continuous)                      │
│                                                                     │
│  New Relic Alert fires                                              │
│         │                                                           │
│         ▼                                                           │
│  Agent wakes (webhook or polling)                                   │
│         │                                                           │
│         ├── Query NR NRQL: error rate, latency, throughput          │
│         ├── Query NR Logs: filter level=ERROR ±10 min window        │
│         ├── Query k8s events: OOMKill, CrashLoopBackOff, restarts   │
│         └── Correlate signals → identify failure category           │
│                    │                                                │
│         ┌──────────┴──────────┐                                     │
│         ▼                     ▼                                     │
│   Known pattern?         Unknown / novel?                           │
│   (OOM, CVE, bad         → Page on-call engineer                   │
│    config, high n)        → Attach full diagnostic context          │
│         │                                                           │
│         ▼                                                           │
│   Generate fix (LLM — Claude API)                                   │
│   ├── OOMKill        → increase memory limit in overlay             │
│   ├── CVE in dep     → bump version in pom.xml                      │
│   ├── Rate limit hit → adjust limit in config.env                   │
│   ├── High avg n     → lower @Max cap or add n-tier rate limit      │
│   └── Config drift   → revert kustomization.yaml to last good state │
│         │                                                           │
│         ▼                                                           │
│   Create Jira bug (auto)                                            │
│   ├── Title:    "[SEV-X] fibonacci-service: <alert name>"           │
│   ├── Priority: mapped from alert severity                          │
│   ├── Labels:   sre-auto-detected, fibonacci-service                │
│   └── Body:     NR alert link, log excerpt, proposed fix summary    │
│         │                                                           │
│         ▼                                                           │
│   Raise GitHub PR (auto)                                            │
│   ├── Branch:  sre-agent/fix-<jira-id>-<short-slug>                │
│   ├── Changes: targeted edit to the specific file                   │
│   ├── PR body: links Jira ticket, pastes NR alert, explains fix     │
│   └── Labels:  sre-auto-fix, awaiting-review                        │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

**Implementation notes:**
- Built with the Claude API (or similar LLM) with access to: NR NerdGraph API, k8s API, GitHub API, Jira API
- Agent only touches the specific file that needs changing — it does not rewrite the service
- All changes go through PR review — the agent never merges directly to `main`
- If confidence is low (unfamiliar failure mode), agent pages on-call and attaches its diagnostic context instead of proposing a fix

---

### Proposal 2: Jira-integrated incident management

Every alert creates a traceable, auditable incident record — not just a Teams notification
that disappears.

```
New Relic alert fires
        │
        ▼
Webhook → incident-handler service
        │
        ├── Severity mapping
        │     SEV-1 (full outage)   → Jira Critical + page immediately
        │     SEV-2 (partial)       → Jira High + page immediately
        │     SEV-3 (degraded)      → Jira Medium + business hours
        │     SEV-4 (minor)         → Jira Low + next sprint
        │
        ├── Create Jira issue
        │     Project:   SRE (or service-specific project)
        │     Epic link: M365-15977 (or equivalent reliability epic)
        │     Fields:    alert name, NR link, affected cluster,
        │                error rate snapshot, first-seen timestamp
        │
        ├── Post to Teams SRE channel
        │     "🔴 SEV-2: fibonacci-service P99 latency > 2s
        │      Cluster: prod-mel | Jira: SRE-4231 | NR: <link>"
        │
        └── On alert resolution → auto-close Jira issue
              Set resolution time, add MTTR to issue
              Trigger postmortem creation if SEV-1 or SEV-2
```

**What this replaces:** manually creating Jira tickets after an incident. The ticket exists
before you even start investigating, with all the context already attached. MTTR tracking
becomes automatic because the open/close timestamps are on the Jira issue.

---

### Proposal 3: Automated PR review pipeline

Every PR — whether human-written or SRE-agent-raised — goes through the same automated
review gates before a human reviewer sees it.

```
PR opened (human or SRE agent)
        │
        ▼
GitHub Actions: automated-review.yml
        │
        ├── Job 1: Static analysis (SonarCloud)
        │     ├── Code smells, duplication, complexity
        │     ├── Security hotspots (hardcoded secrets, SQL injection patterns)
        │     ├── Coverage gate: must not drop below current %
        │     └── Quality gate: must not introduce new Critical/Blocker issues
        │
        ├── Job 2: Security scanning
        │     ├── OWASP dependency check (already in CI — promoted to PR gate)
        │     ├── Trivy SBOM diff: show what CVEs the PR adds or removes
        │     └── Semgrep: OWASP Top 10 pattern matching on changed files only
        │
        ├── Job 3: AI code review bot (Claude API)
        │     ├── Reviews the diff — not the whole codebase
        │     ├── Checks: correctness, security implications, test coverage for changes
        │     ├── Posts inline comments on specific lines (GitHub Review API)
        │     ├── Summary: "3 suggestions, 0 blockers" or "1 blocker: <reason>"
        │     └── Does NOT approve or merge — advisory only
        │
        └── Job 4: Test suite + build
              ├── All 59 tests must pass
              └── Docker build must succeed
        │
        ▼
All gates pass → PR marked "ready for human review"
        │
        ├── SRE-agent PRs → require 1 human approval before merge
        └── Human PRs     → require 1 human approval before merge
```

**SonarCloud quality gate configuration:**
```yaml
# sonar-project.properties
sonar.projectKey=fibonacci-service
sonar.qualitygate.wait=true

# Gates — PR fails if any of these are violated
sonar.coverage.exclusions=**/FibonacciApplication.java
# New code must have:
#   coverage         >= 80%
#   duplications     <  3%
#   maintainability  A
#   reliability      A
#   security         A
```

---

### Proposal 4: Self-healing infrastructure automation

Automate the responses to known failure patterns so the pod recovers without human
intervention — and without even needing the SRE agent to raise a PR.

| Trigger | Auto-response | Mechanism |
|---------|---------------|-----------|
| Pod OOMKilled 3× in 1 hour | Temporarily double memory limit | ArgoCD `PreSync` hook applies a temporary patch; Jira ticket raised to make it permanent |
| HPA at max replicas for >15 min | Alert fires: "HPA ceiling hit — manual scale-out needed" | HPA cannot increase `maxReplicas` automatically; human decision required |
| Deployment rollout fails readiness | Automatic rollback to previous image | Argo Rollouts `BlueGreen` strategy with automatic analysis and rollback |
| Dependency CVE CVSS ≥ 9 detected | SRE agent raises PR bumping the dependency | Triggered by a nightly Trivy scan of the production image, not just CI |
| Config drift (manual `kubectl` change) | ArgoCD `selfHeal: true` reverts within 3 min | Already implemented — Jira ticket raised automatically to explain the drift |

---

### Proposal 5: Error budget burn rate alerting and auto-freeze

When the error budget is burning too fast, automatically freeze non-critical deployments
rather than waiting for a human to declare a code freeze.

```
Error budget burn rate > 6x for 6 hours  (budget exhausted in 5 days)
        │
        ▼
Auto-freeze triggered:
  ├── ArgoCD sync policy for test/prod changed to "manual" (no automated deploys)
  ├── GitHub: label "deployment-frozen" applied to all open PRs
  ├── Teams alert: "⚠️ Deployment freeze active — error budget at risk"
  ├── Jira epic created: "Reliability sprint — restore error budget"
  └── Freeze lifted automatically when burn rate drops below 1x for 1 hour
```

This is the SRE concept of **error budget policy** made operational rather than
just documented. The budget is not just a number on a dashboard — it automatically
changes what the team is allowed to do.

---

### Implementation priority

| Proposal | Impact | Effort | Priority |
|----------|--------|--------|----------|
| Jira-integrated incident management | High — eliminates manual ticket creation | Low — webhook + Jira API | **Now** |
| SonarCloud + Semgrep on PRs | High — catches issues before merge | Low — GitHub Actions job | **Now** |
| AI code review bot on PRs | Medium — advisory, reduces review load | Medium — Claude API integration | **Next sprint** |
| SRE monitoring agent (detect → PR) | High — eliminates routine on-call toil | High — LLM + multi-API integration | **Next quarter** |
| Error budget auto-freeze | Medium — enforces reliability culture | Medium — NR + ArgoCD API | **Next quarter** |
| Self-healing via Argo Rollouts | High — reduces MTTR from minutes to seconds | High — Argo Rollouts migration | **6 months** |

---

## AI usage

**Tool:** Claude (`claude-sonnet-4-6`) via Claude Code CLI.

### Context: what was done within the time constraint

The core deliverable — a working, tested Fibonacci API — was the priority.
The production deployment layer (GitOps, ArgoCD, monitoring, alerting) was added to demonstrate
how I would think about running this in a real environment, not as a claim that all of it
would be set up from scratch in 3 hours. The spec asks for consideration of deployment aspects;
this is that consideration made concrete rather than just described in prose.

---

### Where AI was used

AI was used to generate the first draft of every file:

- **Core API**: `FibonacciService`, `FibonacciController`, `GlobalExceptionHandler`, `RateLimitInterceptor`
- **Tests**: all 5 test classes
- **Container**: `Dockerfile`, `docker-compose.yml`
- **CI/CD**: `.github/workflows/ci.yml`
- **GitOps**: Kustomize `components/`, `base/`, `overlays/` structure, ArgoCD `ApplicationSet`
- **Observability**: `newrelic.yml`, `FibonacciMetrics`, `monitoring/alerts.yaml`, `monitoring/slo.md`
- **Documentation**: this README

---

### How it was used

The approach was **prompt → read → challenge → correct**, not prompt → accept.

Each generated file was reviewed against what I know about production Java services before accepting. Where AI made a plausible-looking but wrong choice, I pushed back with specific corrections rather than accepting the output and hoping it worked. The questions I asked AI were narrow and specific — not "build me a production service" but "given that our platform mounts newrelic.yml as a ConfigMap volume and activates the agent via JDK_JAVA_OPTIONS, fix the Dockerfile."

The value AI provided was speed on boilerplate. The value I provided was knowing what the boilerplate should actually look like.

---

### What was accepted vs modified

| Artefact | What AI produced | What I changed and why |
|----------|-----------------|------------------------|
| `FibonacciService` | Iterative `BigInteger` algorithm | **Accepted.** Traced the algorithm by hand for n=0 through n=5, cross-checked F(50) against an independent source. The choice of `BigInteger` over `long` was correct — `long` overflows at F(93). |
| `FibonacciController` | `@Validated` + `@Min`/`@Max` on `@RequestParam` | **Accepted.** Verified the constraint annotations produce 400 not 500 by reading Spring's `ConstraintViolationException` handling path. |
| `GlobalExceptionHandler` | 5 exception handlers | **Modified.** Added explicit `NoResourceFoundException` handler. Without it, unknown paths like `/api/v1/unknown` returned 500 instead of 404 — AI missed this case entirely. |
| `RateLimitInterceptor` | Sliding-window rate limiter using `getRemoteAddr()` | **Modified.** `getRemoteAddr()` returns the load-balancer's internal IP in Kubernetes, not the real client IP — so every client would share one bucket. Added `X-Forwarded-For` header resolution. This is the kind of mistake that only shows up in production, not in tests. |
| `Dockerfile` | Multi-stage build with `-javaagent` hardcoded in `ENTRYPOINT` | **Modified.** Hardcoding the agent in `ENTRYPOINT` means the image bakes in NR config — the same image can't run in test without NR or with different agent settings. Moved activation to `JDK_JAVA_OPTIONS` in the Kubernetes ConfigMap (`config.env`), matching how every other service on the platform is configured. Also added BuildKit `--mount=type=cache` for Maven and the NR agent download — without this, every CI build re-downloads all dependencies. |
| `newrelic.yml` | Generic New Relic config with `distributed_tracing: true` | **Modified.** The platform's production `newrelic.yml` has `distributed_tracing: false` and `span_events: false` — enabling these without the rest of the service mesh being configured just adds noise. Also added `ignore_status_codes: 404, 429` so expected responses don't pollute the error collector. |
| Kustomize structure | Flat YAML files in a `k8s/` folder | **Replaced entirely.** Reviewed the existing GitOps repo structure first. Flat YAML doesn't work with ArgoCD's multi-cluster ApplicationSet pattern. Rebuilt as `components/ → base/ → overlays/{env}/` with `namePrefix`, `configMapGenerator`, and `generatorOptions` — matching exactly how every other service is deployed. |
| ArgoCD manifest | Plain `Application` resource | **Replaced.** The platform uses `ApplicationSet` with a matrix generator (env list × cluster selector). A plain `Application` can only target one cluster; `ApplicationSet` deploys to test, Melbourne, and Sydney from a single manifest. |
| CI/CD workflow | 3-job pipeline | **Fixed a silent bug.** The digest computation ran `docker inspect` on the image before it was built — it always returned an empty string, producing tags like `v1.0.0@` with no digest. Fixed to use `steps.build.outputs.digest` from the build action's output. Also pinned `yq` to a specific version (supply chain risk) and added Trivy container scan and OWASP dependency check. |
| Test suite | Happy-path controller tests only | **Extended significantly.** AI wrote tests for the spec examples and basic error cases. Added: 24 resilience cases (integer boundary overflow, scientific notation, Unicode digits, actuator endpoint exposure), rate limiter tests with per-test state reset, and load test scenarios (100 concurrent requests). Several AI test assumptions were wrong and corrected — `0x0A`, `+5`, and Arabic-Indic `٧` are all accepted as valid integers by Java/Spring. |

---

### How correctness was validated

- **Spec examples**: both `n=2→1` and `n=10→55` verified by running the test suite and confirmed against the spec definition manually
- **Algorithm**: traced by hand for n=0 through n=5; F(50) = 12,586,269,025 cross-checked against an independent source to confirm `BigInteger` handles values beyond `long` range
- **Rate limiter**: set limit to 5 in test config, fired 10 requests, confirmed requests 6–10 return 429
- **Error handler coverage**: exercised every exception type by sending the corresponding bad input — confirmed no unhandled path returns 500
- **Kustomize**: ran `kustomize build gitops/applications/fibonacci/overlays/test` and inspected the rendered YAML to confirm `namePrefix`, label injection, and ConfigMap generation worked as expected

---

### What the AI got wrong or incomplete

These are cases where the generated output was plausible but wrong — they would have shipped silently broken without domain knowledge to catch them:

1. **NR agent in `ENTRYPOINT`** — works locally, breaks in production where the same image runs across environments with different NR configurations. The correct pattern (agent via `JDK_JAVA_OPTIONS`) is only obvious if you've actually operated this on the platform.

2. **`getRemoteAddr()` in rate limiter** — passes all tests because tests don't go through a load balancer. In a real cluster, every client gets the same IP and shares one rate limit bucket — the feature silently does nothing.

3. **CI/CD digest bug** — `docker inspect` runs before the image exists so it always returns an empty string. The tag `v1.0.0@` is written to the GitOps repo and ArgoCD silently uses a malformed reference. No error is thrown anywhere.

4. **ArgoCD plain `Application`** — syntactically valid and would deploy, but can only target one cluster. The multi-cluster requirement requires `ApplicationSet`. AI chose the simpler type without asking about the deployment topology.

5. **Health probe groups missing** — `management.endpoint.health.probes.enabled=true` is necessary but not sufficient. Without `group.liveness` and `group.readiness` declarations, Spring Boot serves `/actuator/health/liveness` but returns the wrong HTTP status code (always 200 even when unhealthy) — the Kubernetes probe would never trigger a restart.

6. **`GlobalExceptionHandler` missing 404** — AI handled the exceptions it was aware of. It didn't handle `NoResourceFoundException` because that exception class was introduced in Spring Boot 3.2 to replace the older `NoHandlerFoundException`. Unknown paths returned 500 instead of 404.
