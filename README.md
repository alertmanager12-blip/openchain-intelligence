# OpenChain Intelligence

**Open source blockchain investigation, risk intelligence and programmable monitoring.**

OpenChain Intelligence (OCI) indexes token flows on TRON and EVM chains, detects explainable risk signals, scores wallets with traceable contributions, and lets you declare monitoring rules in YAML that raise alerts and call your systems through signed webhooks — all self-hostable with `docker compose up`.

> **Status: 0.1.0 — unreleased MVP.** APIs and schemas may change before 1.0. See the module status table below.

Licence: [Apache-2.0](LICENSE).

## Why OCI

- **Explainable by design** — every risk point and every alert links to evidence: transactions, rule version, source, timestamp.
- **Honest about evidence** — signals are tagged `ANOMALY`, `RISK_SIGNAL` or `CONFIRMED_BY_SOURCE`. OCI never labels an address as criminal.
- **Programmable, safely** — rules are declarative data (`openchain.io/rule/v1`): filters, sliding windows, group-by, dedup, cooldown. No scripting, no code execution.
- **Reorg-aware** — confirmations are first-class; alerts depending on orphaned blocks are retracted (risk signals are not rolled back yet).
- **Exact amounts** — `BigInteger`/`BigDecimal` end to end.
- **Multi-chain** — one provider SPI with retries, circuit breaker and client-side rate limiting; TRON and EVM (Ethereum, BSC, Polygon) first.
- **Open and portable** — Apache-2.0, any OIDC provider, a single Docker Compose stack.

## Features

| Area | Capabilities |
|---|---|
| Investigation | Wallet analysis (profile, counterparties, signals, labels), transaction analysis, counterparty graph explorer (depth ≤ 3) |
| Risk intelligence | Detectors for transaction patterns (structuring, fast forwarding, unusual amounts…), wallet patterns (dormant reactivation, rapid drain…), exposure (sanctioned/flagged, direct and indirect); explainable 0–100 score |
| Monitoring | YAML/JSON rules, DRY_RUN on history, versioning, alert lifecycle (acknowledge, assign, comment, resolve, false positive) |
| Notifications | Webhooks (HMAC-SHA256, SSRF-guarded, retries, dead letter), Slack, Discord, Telegram. Email/push: *planned* (no transport yet) |
| Platform | REST API v1 (problem+json, cursor pagination), API keys (hashed, scoped, expiring, revocable) and optional OIDC, per-endpoint permissions, audit log stream (`oci.audit`). *Planned*: persistence (PostgreSQL), idempotency keys, rate limiting, tenant isolation, Prometheus metrics, OpenTelemetry traces |

## Architecture

Target architecture (PostgreSQL persistence is not implemented yet: the MVP keeps state in memory):

```mermaid
flowchart LR
  subgraph Providers
    TG[TronGrid / java-tron]
    RPC[EVM JSON-RPC]
  end
  subgraph OCI[OpenChain Intelligence — modular monolith]
    IDX[Indexer<br/>checkpoints · reorgs]
    DET[Detection<br/>signals]
    SC[Scoring<br/>explainable]
    RE[Rule engine<br/>windows · dedup · cooldown]
    AL[Alerts]
    DLV[Delivery<br/>outbox · HMAC · retry]
    API[REST API v1]
  end
  PG[(PostgreSQL)]
  FE[Angular UI<br/>Cytoscape.js]
  EXT[Webhooks / Slack / Discord / Telegram]

  TG & RPC --> IDX -->|ChainEvent| DET -->|signals| SC
  IDX -->|ChainEvent| RE
  SC -->|risk changes| RE
  DET -->|signals| RE
  RE --> AL --> DLV --> EXT
  IDX & DET & SC & AL --> PG
  FE --> API --> PG
```

Details: [architecture overview](docs/architecture/overview.md) · [C4 model](docs/architecture/c4/) · [sequences](docs/architecture/sequences.md) · [data model](docs/architecture/data-model.md) · [rule engine](docs/architecture/rule-engine.md) · [ADRs](docs/adr/).

## Quickstart

Requirements: Docker with Compose v2.

```bash
git clone https://github.com/openchain-intelligence/openchain-intelligence.git
cd openchain-intelligence
docker compose up -d --build
# UI:  http://localhost:4200
# API: http://localhost:8080/api/v1  · health: http://localhost:8080/actuator/health
```

This starts in **demo mode** (`OCI_DEMO_MODE=true`) on a simulated chain — no provider keys required. Walk through it in the [quickstart guide](docs/guides/quickstart.md). For a real deployment read [installation](docs/guides/installation.md) and [security hardening](docs/guides/security-hardening.md).

A rule looks like this:

```yaml
apiVersion: openchain.io/rule/v1
id: usdt-burst-outflow
version: 1.0.0
name: USDT burst outflow
author: your-team
scope:
  networks: [tron]
  eventTypes: [TOKEN_TRANSFER]
conditions:
  logic: AND
  rules:
    - { field: transaction.token.symbol, operator: EQUALS, value: USDT }
aggregation:                      # one aggregate per rule: SUM over a sliding 1h event-time window per sender
  window: 1h
  groupBy: [transaction.from]
  function: SUM
  field: transaction.amount
  operator: GREATER_THAN_OR_EQUAL
  threshold: "50000"
severity: HIGH
cooldown: 6h
actions:
  - { type: WEBHOOK, connectorId: ops-webhook }
```

## Module status

| Module | Purpose | Status |
|---|---|---|
| `oci-core` | Framework-free domain model + `BlockchainProvider` SPI | **MVP** — implemented, tested |
| `oci-blockchain` | TRON (TronGrid-compatible) and EVM JSON-RPC providers, failover, indexer, reorg handling | **MVP** — in progress |
| `oci-intelligence` | Detectors, explainable risk scoring, graph exposure | **MVP** — in progress |
| `oci-alerting` | Rule DSL v1, evaluator (windows, dedup, cooldown), alert lifecycle, connectors | **MVP** — in progress |
| `oci-app` | Spring Boot app: REST API v1, API-key/OIDC security, demo mode (persistence *planned*) | **MVP** — in progress |
| `frontend` | Angular UI: analysis, graph explorer, rules, alerts | **MVP** — in progress |
| Bitcoin / Solana providers | UTXO and slot-based chains | *planned* |
| Kafka transport, ClickHouse analytics, Apache AGE graph | Scale-out options | *planned* ([ADR-0004](docs/adr/0004-postgresql-first-clickhouse-later.md), [0005](docs/adr/0005-graph-store.md), [0006](docs/adr/0006-event-bus-outbox-kafka-later.md)) |
| Helm chart | Kubernetes deployment | *planned* |

## Documentation

- [API v1 contract](docs/api/README.md) — endpoints, auth, errors, pagination, webhook verification
- [Writing rules](docs/guides/writing-rules.md)
- [Technology choices](docs/technology-choices.md) and [licensing policy](docs/adr/0012-licensing-policy.md)
- [Testing strategy](docs/testing-strategy.md)

## Build from source

```bash
mvn -B verify                                     # JDK 21+, no Docker needed
cd frontend && npm ci && npm test && npm run build # Node version from frontend/.nvmrc
bash deploy/smoke-test.sh                         # optional: Docker Compose end-to-end smoke test
```

## Contributing

Contributions of code, rules and docs are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) (DCO sign-off required) and the [Code of Conduct](CODE_OF_CONDUCT.md). Security issues: [SECURITY.md](SECURITY.md).

## Disclaimer

OCI outputs are risk indicators derived from public blockchain data and attributed third-party sources. They are not legal determinations, do not identify any person, and must be reviewed by a qualified human before any decision affecting someone.
