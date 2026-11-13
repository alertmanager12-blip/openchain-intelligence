> English translation of [FEATURE_ROADMAP.md](FEATURE_ROADMAP.md) (French original, authoritative). Translated on 2026-11-12; if the two differ, the French version prevails.

# Roadmap

> Complexity: **S** ≤ 3 d · **M** ≤ 2 wk · **L** ≤ 6 wk · **XL** > 6 wk (one experienced person, order of
> magnitude, to be recalibrated after phase 0). Each phase ends with: compilation, green tests (0 skipped),
> smoke test, documentation update, explicit validation before the next phase.

## Overview

| Phase | Objective | Complexity | Depends on |
|---|---|---|---|
| 0 | Audit and consolidation: nothing displayed that does not work | L | — |
| 1 | Reliable intelligence core: persistence, measured noise, real sources | XL | 0 |
| 2 | Graph intelligence: tracing, paths, relationships | L | 1 (persisted flows) |
| 3 | Reproducible autonomous investigation | XL | 1, 2 |
| 4 | Predictive signals and simulation | L | 1, 3 |
| 5 | Agent ecosystem: MCP, SDK | M | 0 (API contract), 3 for the investigation tools |
| 6 | Collaborative intelligence | L | 1 (labels with provenance) |

**Sequencing recommendation**: a **minimal read-only MCP** (portfolio, risk, alerts) can be
delivered as early as the end of phase 1 (complexity S-M), because it is a market prerequisite (five competitors have one)
and it reuses the API. *Decision to be validated.*

---

## Phase 0 — Audit and consolidation

**Goal**: remove any gap between what is displayed and what works; make the real instance operable.

| # | Task | Defect | Modules | Success criterion | Complexity |
|---|---|---|---|---|---|
| 0.1 | Default notification routing: replace `default-webhook` with a configurable connector (`OCI_DEFAULT_CONNECTOR`); failing that, do **not** create a delivery bound to fail and display it clearly (alert "not notified: no connector"); requeue of dead-letters after configuration | D1 | alerting, app, rules | 0 new "unknown connector" dead-letters in production; integration test | S |
| 0.2 | Disable `contract-exploit` by default (it cannot fire) and mark it "requires CONTRACT_CALL"; refuse activation of a rule with a field that is never produced (blocking or explicit warning) | D4 | alerting, rules | no silent active rule; validator test | S |
| 0.3 | EVM Transactions tab: serve transactions from indexed flows + address history instead of the 503; clear message otherwise | D5 | app, frontend | tab working on real Ethereum; test | S |
| 0.4 | TRON without historical balance: show in the UI and the API that `wallet-draining`/RapidDrain are inactive on real TRON; study the current balance as a **marked** approximation (separate ADR if adopted) | D6 | app, frontend, docs | no more unexplained silence | S |
| 0.5 | Real `isContract` (EVM code via `eth_getCode` already cached; TRON `getcontract`) or field removed | D11 | app | no hard-coded value | S |
| 0.6 | Remove Bitcoin/Solana from the UI and the mock (or mark "not supported") | D12 | frontend | no option without a provider | S |
| 0.7 | Deployment defaults: `OCI_DEMO_MODE=false` by default outside `docker compose --profile demo`; publish 8080 on 127.0.0.1; configurable Swagger (disabled in prod by default); mandatory Grafana password | D9 | deploy | adapted smoke test; security checklist up to date | S |
| 0.8 | Wire `FailoverProvider` (list of URLs per chain) | D13 | app | failover tested in integration | S |
| 0.9 | Minimal observability: Micrometer Prometheus (justified dependency: endpoint already expected by the compose stack), indexing metrics (lag, blocks/s, RPC requests, errors), alert and delivery metrics | D10 | app, deploy | `/actuator/prometheus` scraped, Grafana dashboard provided | M |
| 0.10 | Noise measurement: per-rule report (alerts/day, % classified false positive) exposed in the API and the console; pack thresholds reviewed (`fund-splitting`, `suspicious-wallet`, `suspicious-approval`) with documented exclusions | D3 | alerting, app, rules | volume of open alerts/day divided by ≥ 10 on the real instance, without disabling any rule | M |
| 0.11 | Align docs and code: Java 21 compile / 25 runtime, Node, JaCoCo (add or remove the promise), V5 in `data-model.md`, risk example, ids, Swagger; API contract: implement **or** mark "not implemented" rate limiting, Idempotency-Key, If-Match, keyset | D7, D15 | docs, app | 0 gaps in the audit list | M |
| 0.12 | Licenses: blocking check in CI (Maven + npm) | D16 | CI | job red on a forbidden license | S |
| 0.13 | Connectors: wire EMAIL (configurable SMTP); remove PUSH without transport | D14 | alerting, app | SMTP test (GreenMail or local server); no more dead code | M |
| 0.14 | Implement or remove the 6 `SignalType` never emitted | — | intelligence | no dead enumeration | S |

**Deliverables**: defects D1-D16 closed or explicitly documented; before/after noise report; CHANGELOG.

### Progress (2026-11-09)

| Task | Status | Deviation from the plan, and why |
|---|---|---|
| 0.1 | done | `SKIPPED` (migration V6) instead of a dead-letter queue; no automatic replay of the 5,675 old ones (spam of stale alerts) |
| 0.2 | done | generic reachability analysis (`RuleReachability`); the tester and the simulator also evaluate a disabled rule |
| 0.3 | done | transfers known to the platform, each in its own unit (`kind: TRANSFER`) |
| 0.4 | done | limitations exposed per network (`limitations`); no approximation of the TRON balance |
| 0.5 | done | `isContract` nullable: unknown rather than false; not resolved on the graph (RPC cost) |
| 0.6 | done | — |
| 0.7 | done | the demo **remains** the default (startup in 5 minutes) but demo + real chain is refused at startup; Grafana password unchanged (local port only) |
| 0.8 | done | failover **in the HTTP transport**; `FailoverProvider` removed (it would have lost balances, approvals, freezes, history) |
| 0.9 | done | metrics available only; Grafana panels without data removed |
| 0.10 | partial | noise report + `fund-splitting` 1.2.0 (−83% measured) + observation period for the age fields. **Finding**: on an entire chain, the behavioral rules remain noisy; the answer is a watchlist (the client's wallets), to be done at the start of phase 1 |
| 0.11 | done | documentation marked "not implemented" rather than features added in a rush |
| 0.12 | done | — |
| 0.13 | partial | PUSH removed; EMAIL postponed (SMTP transport in its own right) |
| 0.14 | done | the 6 types removed; they will come back with their detector |

**Risks**: 0.10 may reveal that some rules of the pack are structurally noisy on mainnet → remove them
from the "active by default" pack rather than hiding the noise.
**Tests**: unit tests for each fix; integration (Testcontainers) for 0.1, 0.3; extended smoke test
(notification routing, transactions tab).

---

## Phase 1 — Reliable intelligence core

> **Done (2026-11-09):** watchlist (API, page, wallet button, rule fields, pack rules). **Priority added after phase 0: watchlist.** The client's wallets (deposit addresses, treasury)
> and their direct counterparties; `wallet.watchlisted` / `counterparty.watchlisted` populated; the pack's behavioral
> rules target this scope by default. This is what makes the alerts actionable for a PSP.

| Feature | Modules | Acceptance criterion | Complexity |
|---|---|---|---|
| Persistence of derived state (checkpoints, flows, profiles, signals, scores, history for dry-run) — ADR-0024 | app, intelligence, blockchain | restart without loss or re-indexing; resume at the next block; restart test in CI | XL |
| Labels with full provenance (source, reliability, evidence, expiration, revocation) + hot import (admin API) | intelligence, app | each label displayed with its source and date; effective revocation | M |
| Real sources: OFAC SDN (crypto addresses), issuer freezes (already), GraphSense TagPacks, partner lists (CSV) | app | sanctions set updated daily, update date displayed | M |
| Engine version and evidence level on **all** outputs (signal, alert, score) | core, intelligence, alerting | field present and tested everywhere | S |
| "Screening" decision API: `POST /screening` (address or transaction → verdict + reasons + limitations), target latency p95 < 300 ms on data in the database | app | OpenAPI contract + local load test | M |
| Real rate limiting and Idempotency-Key | app | 429 + headers; idempotent replay | M |
| OIDC login in the console (PKCE) + effective roles | frontend, app | no more key in `localStorage` in OIDC mode; VIEWER role cannot write | M |
| Ground-truth dataset: labeled cases (freezes confirmed by the issuer, OFAC addresses, public incidents) to measure precision/recall of the rules | intelligence, docs | quality dashboard published | L |

**Risks**: PostgreSQL data volume (partitioning), cost of label sources, license of the lists (check the
reuse conditions of each source).

---

## Phase 2 — Graph intelligence

| Feature | Acceptance criterion | Complexity |
|---|---|---|
| Upstream/downstream fund tracing on persisted flows, > 3 hops with a budget (nodes, time) and stop on an identified service | paginated result, explicit `truncated`, bounded time | L |
| Path search between two addresses | paths with amounts and dates, sources | M |
| Relationships between wallets (co-funding, common fan-in/fan-out, reuse of counterparties) as **inferences** with confidence and justification | each relationship carries its `INFERRED` level and its evidence | L |
| Interactive visualization (existing Cytoscape): time/token filters, on-demand expansion, export (existing PNG + JSON) | usable on 500 nodes | M |

**Risk**: false links (service addresses, routers) → exclusions via service labels; never identity.

---

## Phase 3 — Autonomous investigation (ADR-0022)

| Feature | Acceptance criterion | Complexity |
|---|---|---|
| Persistent investigation object, background execution, interruption/resumption | resumption after restart tested | L |
| Deterministic investigation plan (history → counterparties → paths → signals → labels → correlation) | same input + same data = same result (replayability test) | L |
| Structured report (JSON + PDF/HTML): typed assertions, evidence, sources, limitations, version | no statement without provenance | M |
| Human validation of assertions (accept/reject, comment) | logged | M |
| Rules proposed from an investigation (YAML draft, never activated automatically) | valid draft, tested by dry-run | M |
| *Optional* AI assistance: summary written from the assertions only, citing their identifiers | no claim outside the assertions (test) | M |

---

## Phase 4 — Predictive signals and simulation

| Feature | Acceptance criterion | Complexity |
|---|---|---|
| Precursor signals: unusual dispersion, awakening after dormancy, multiplication of new counterparties, change of strategy, coordinated movements | each signal = `PREDICTED` hypothesis with confidence, source signals, limitations, **expiration** | L |
| A posteriori evaluation of predictions (realized / not realized at expiration) | rate published per type | M |
| Rule simulation on persistent history (extension of `RuleTester`): impact of a rule change | before/after report without side effects | M |
| Graph what-if: "who would be exposed if X were labeled sanctioned?" | result marked `SIMULATED`, never mixed with facts | M |

---

## Phase 5 — Agent ecosystem (ADR-0025)

| Feature | Acceptance criterion | Complexity |
|---|---|---|
| MCP server (read, then write), same scopes as the API | per-tool authorization test | M |
| Java / Python / TypeScript SDKs generated from the OpenAPI, published | generated contract tests | M |
| Rules and alerts "as code": full configuration export/import (rules, connectors without secrets) | lossless round trip | S |
| Webhooks: versioned event catalog (already signed) | docs + verification examples | S |

---

## Phase 6 — Collaborative intelligence

| Feature | Acceptance criterion | Complexity |
|---|---|---|
| Moderation of reports (queue, evidence, logged decision) | no report published without a decision | M |
| Exchange of labels/IOCs in TagPack format (import/export) with provenance | interoperable with GraphSense | M |
| Contributor reputation and revocation | revoked label removed everywhere | M |
| Sharing of investigations (signed export) and rules (versioned community pack) | signature verification | M |

**Rule**: unverified community information is always displayed as such.
