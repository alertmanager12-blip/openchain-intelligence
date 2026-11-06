# Testing strategy

Principle: a feature is done when its tests run green in CI. Pyramid: many fast unit tests on the framework-free domain, focused integration tests on real infrastructure, a few end-to-end scenarios on the demo chain.

> **Status (0.1.0).** Implemented and run on every build: unit tests in every module, adapter tests against a local HTTP server with hand-written JSON fixtures (not yet recorded from real nodes), API tests with Spring MockMvc (security, problem+json, rules, alerts, connectors), an in-process end-to-end test (`EndToEndPipelineTest`: rule → simulated transfer → detection → alert → signed webhook), a frontend/backend rule contract test, Vitest frontend tests, CodeQL, and the Docker Compose smoke test (`deploy/smoke-test.sh`). **Planned** (rows below that need them): property-based tests (jqwik), WireMock, database integration tests (there is no persistence yet), tenant isolation, OpenAPI diff, load tests.

| Level | Scope | Tools | Where | Runs |
|---|---|---|---|---|
| Unit | Domain model, scoring maths, rule compiler/evaluator, window structures, SSRF guard, HMAC | JUnit 5, AssertJ | each module `src/test` | every build |
| Property-based | Window aggregates vs. brute-force, amount parsing round-trips, score bounds/monotonicity | jqwik (EPL-2.0) | `oci-alerting`, `oci-intelligence` | every build |
| Contract (providers) | Adapters vs. recorded real responses (TronGrid, JSON-RPC), error classification (429, 5xx, malformed) | WireMock (Apache-2.0), JSON fixtures | `oci-blockchain` | every build |
| Contract (API) | OpenAPI document vs. implementation; problem+json shapes; pagination | Spring MockMvc / RestAssured, OpenAPI diff in CI | `oci-app` | every build |
| Integration | Repositories, Flyway migrations, partitions, RLS isolation, outbox leasing (`SKIP LOCKED`) | Testcontainers PostgreSQL 17 | `oci-app` (`*IT`) | `mvn verify` |
| End-to-end | Demo chain → alert → webhook received and signature verified | Testcontainers + compose, a sink container | `oci-app` e2e profile | nightly + release |
| Frontend | Components/services, headless | Angular CLI `ng test` (Vitest + jsdom) | `frontend` | every build |
| Load | API throughput, evaluation latency per event, delivery throughput | k6 (AGPL-3.0, used as an external tool, not shipped) or Gatling (Apache-2.0) | `perf/` (planned) | before release |
| Security | SAST (CodeQL), dependency CVEs, licence policy, SSRF bypass corpus, authz matrix | CodeQL, Dependabot, OWASP dependency-check, licence check | CI | every PR |

## Critical scenarios (must have tests)

### Deduplication and idempotency
- The same `ChainEvent` delivered twice (same `eventId`) produces one window contribution and one alert.
- Two matches with the same dedup key while an alert is active → one alert, `occurrences = 2`, one notification within cooldown, a second after cooldown.
- Delivery retried after a timeout uses the same `Idempotency-Key`/`X-OCI-Delivery-Id`.

### Windows
- Sliding window boundaries: events at exactly `t − size` excluded/included per spec (documented as half-open `(t − size, t]`).
- Out-of-order events within lateness are counted; beyond lateness are dropped and metered.
- Group cardinality cap evicts the oldest group and emits a metric.
- Exact decimal sums (e.g. `0.1 + 0.2 = 0.3` in USDT 6-decimal units).

### Reorg
- Simulated chain with a 2-block reorg: orphaned transfers marked removed, checkpoint rewound, canonical events re-applied, signals retracted, alerts moved to `RETRACTED` when no longer satisfied.
- Reorg deeper than `maxReorgDepth` halts the network indexer and raises an operational alert.
- Re-indexing the same range twice is idempotent.

### Provider resilience
- Primary returns 429 with `Retry-After` → backoff then failover to secondary; circuit opens after N failures and half-opens.
- Lagging secondary never moves the checkpoint backwards.
- Provider outage during wallet analysis returns cached data with `dataFreshness: STALE`.

### Security
- Tenant A cannot read/modify tenant B's rules, alerts, connectors (404), both via repositories and via RLS with a forged `tenant_id`.
- SSRF corpus: `127.0.0.1`, `0x7f000001`, `2130706433`, `[::1]`, `[::ffff:127.0.0.1]`, `169.254.169.254`, DNS name resolving to private IP, redirect to private IP, rebinding between check and connect.
- Rule DSL: oversized documents, deep nesting, YAML anchors/aliases bombs, unknown fields, type confusion — all rejected with 422, no evaluation.
- API key: only hash stored; revoked/expired keys rejected; scope enforcement matrix.

### Kafka resilience (when Kafka is introduced)
- Outbox relay crash between publish and mark-sent → duplicate publish, consumers dedup on `eventId`.
- Consumer rebalance mid-window → state restored from changelog/replay; no duplicate alerts.
- Broker unavailable → outbox backlog grows, metric/alert fires, no data loss.

## Commands

```bash
mvn -B verify                                  # all modules, unit + integration (Docker required)
mvn -B -pl oci-alerting -am test               # one module
cd frontend && npm ci && npm test            # ng test --watch=false (Vitest, jsdom)
```

## Coverage

Coverage is a signal, not a target: JaCoCo report per module; rule engine and scoring are expected above 85 % line coverage, with mutation testing (PIT, Apache-2.0) on the evaluator planned.
