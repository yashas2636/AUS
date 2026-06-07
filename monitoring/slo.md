# fibonacci-service — SLI / SLO Definitions

## Service overview

fibonacci-service is a stateless compute API. Its SLOs are defined around three signals:
availability (it responds), latency (it responds fast enough), and correctness (it never
returns wrong data). Correctness is guaranteed structurally — the algorithm is deterministic
and `BigInteger` cannot overflow — so it is not a runtime SLO.

---

## SLI definitions

### SLI-1: Availability
**Definition:** Percentage of requests that receive a non-5xx HTTP response.  
4xx responses (validation errors, rate limiting) are client errors — they count as *available*.

```sql
-- NR NRQL
SELECT percentage(count(*), WHERE httpResponseCode < 500)
FROM Transaction
WHERE appName = 'fibonacci-service'
SINCE 30 days ago
```

### SLI-2: Latency
**Definition:** Percentage of requests that complete within 500ms (P95 target).

```sql
SELECT percentage(count(*), WHERE duration < 0.5)
FROM Transaction
WHERE appName = 'fibonacci-service'
SINCE 30 days ago
```

P99 latency (for dashboards):
```sql
SELECT percentile(duration, 50, 95, 99)
FROM Transaction
WHERE appName = 'fibonacci-service'
TIMESERIES 5 minutes
SINCE 1 hour ago
```

### SLI-3: Error rate
**Definition:** Percentage of requests that result in an unhandled 5xx response.

```sql
SELECT percentage(count(*), WHERE httpResponseCode >= 500)
FROM Transaction
WHERE appName = 'fibonacci-service'
SINCE 30 days ago
```

---

## SLO targets

| SLO | Target | Error budget (30 days) |
|-----|--------|----------------------|
| Availability ≥ 99.9% | 99.9% | 43.8 minutes downtime |
| P95 latency < 500ms | 95% of requests | 5% of requests may exceed |
| P99 latency < 2s | 99% of requests | 1% of requests may exceed |
| Error rate < 0.1% | < 0.1% 5xx responses | 43.8 minutes of full outage equivalent |

---

## Error budget burn rate

A burn rate of 1x means the budget is being consumed at exactly the rate that depletes it
by end of 30 days. Alert thresholds (see `alerts.yaml`):

| Window | Burn rate | Meaning | Action |
|--------|-----------|---------|--------|
| 1 hour | > 14.4x | Budget exhausted in 2 hours | Page immediately (SEV-1) |
| 6 hours | > 6x | Budget exhausted in 5 hours | Page (SEV-2) |
| 24 hours | > 3x | Budget exhausted in 10 days | Ticket (SEV-3) |

---

## Key business metrics (not SLOs — monitoring only)

| Metric | What it tells you | Alert if |
|--------|-------------------|----------|
| `fibonacci.rate_limited.total` | Clients hitting rate limit — abuse or misconfigured consumer | Rate > 10% of total requests |
| `fibonacci.n.value` mean | Average n value requested — spikes mean expensive computations | Mean n > 80,000 over 5 min |
| `fibonacci.computation.seconds` P99 | Computation latency excluding cache | P99 > 1s over 5 min |
| Cache hit rate (`cache.gets[result=hit]`) | Fraction of requests served from cache | Hit rate < 50% over 1 hour |
