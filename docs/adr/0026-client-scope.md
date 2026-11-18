# ADR-0026: Client scope — deep, persisted analysis on the watchlist; light screening elsewhere

- Status: **proposed** (principle approved on 2026-11-10; design to be validated before any implementation)
- Date: 2026-11-10
- Replaces, if accepted: ADR-0024 step 2 (chain-wide journal) and step 3c (selective persistence)
- Related: ADR-0020 (address history), ADR-0021 (stablecoin rails), ADR-0023 (epistemic levels), ADR-0024 (bounded working set)

## Context and problem statement

Production facts (Ethereum mainnet + TRON at the head):

| Fact | Value | Source |
|---|---|---|
| Value flows | about 4.8 million per day | measurement 2026-11-09 |
| Signals | about 950,000 per day | measurement 2026-11-09 |
| Chain-wide journal reload | 316 s for 50,000 flows | measurement 2026-11-09 (ADR-0024 step 2, now off) |
| Working set, time windows only (2 h) | old generation 2.9 GB of 3 GB after 3 h, 269 full GCs | measurement 2026-11-10 |
| Working set with caps (400k flows, 300k profiles) | memory held, 0 full GC (run in progress), but **about 2 h of flow look-back** at steady state | measurement 2026-11-10, `docs/ADR-0024-STEP-3B-VALIDATION.md` |
| Behavioural rules chain-wide | noisy even after tuning (`fund-splitting` −83 %, still noisy) | phase 0, task 0.10 |
| Production watchlist | **0 address** | database, 2026-11-10 |

Analysing the whole chain in depth is neither affordable (memory, storage, reload time) nor useful (noise): a PSP needs
depth on **its own wallets and the wallets they deal with**, and a fast, honest check on any other address. Today the
deep analysis covers no wallet at all (empty watchlist) while the chain-wide analysis has a 2-hour memory.

## Usage scenarios (they size the design; the figures marked "assumed" must be measured during the pilot)

1. **PSP accepting USDT on TRON**: 2,000 deposit addresses (one per merchant), 3 hot wallets, 1 treasury. Needs: is an
   incoming payment linked to sanctioned, frozen or flagged funds? Did a deposit address receive funds structured to
   avoid thresholds? Volume: assumed 5,000 incoming transfers per day, 20,000 distinct payers per month.
2. **Mid-size exchange**: 50 hot and cold wallets with very high activity: assumed 50,000 transfers per day and 300,000
   distinct counterparties per month on the hot wallets.
3. **Screening request on an unknown address** (any client): "can I accept funds from / send funds to X?", answered in
   under 300 ms (p95) from stored data, with what was checked and what was not.

## Decision (proposed)

### 1. Three depths of analysis

| Depth | Who | What is kept | What runs |
|---|---|---|---|
| **W: watched** | addresses in the client's watchlist | every flow involving them (persisted), full profile (persisted snapshot), signals, score history; past activity loaded on enrolment (ADR-0020) | all detectors and rules, with their full configured windows (24 h structuring, 7 days circular flow, 180 days dormancy) |
| **C: direct counterparties** | addresses that exchanged value with a W address | the flows **with W** only (already stored as W's flows) and a **compact profile**: labels, latest score, first/last interaction with W, counts and totals with W | label, sanction, freeze and direct-exposure checks; their own activity is **not** followed |
| **G: everything else** | the rest of the chain | the bounded in-memory working set of ADR-0024 (time windows + caps) | light screening: labels, sanctions, issuer freezes, direct exposure to a labelled address; chain-wide behavioural detectors only within the working-set look-back, and **never presented as complete** |

There is **no automatic expansion beyond one hop.** Indirect exposure (2+ hops) is computed only on demand (screening
with `depth > 1` or an investigation), with explicit budgets, and its result is stored with its coverage.

### 2. Bounds on counterparty expansion (requirement 1)

| Bound | Default | Why |
|---|---|---|
| C entries per W address | 10,000 most recent (by last interaction) | an exchange hot wallet has far more counterparties (scenario 2, assumed 300,000/month); a fixed budget per W keeps memory proportional to the size of the watchlist, not to the activity of the chain |
| C entries per client (global) | 500,000 | compact profile in memory, estimated ≤ 300 bytes each → ≤ 150 MB; **to be confirmed by the heap histogram of the ADR-0024 run** |
| W addresses per client | 50,000 (configurable) | scenario 1 needs 2,006; scenario 2 needs 50; a hard limit makes the cost predictable |
| On-demand indirect exposure | ≤ 3 hops, ≤ 500 nodes, as the current exposure and graph services; a time budget is to be added | the hop and node budgets are already in production; a truncated result says so |
| Evicted C entry | removed from memory; its persisted rows (flows with W) stay | the history with W is what matters for audit |

Every bound has a metric (`oci_scope_size{depth}`, `oci_scope_evictions_total{depth}`) and a log line when it binds.

### 3. Isolation between clients (requirement 2)

- **Default deployment model: one instance per client** (self-hosted, ADR-0021). Isolation is physical: separate
  database, separate memory. This is the model to sell first.
- **Within an instance, every scoped record carries a `scope_id`** from the first migration of this ADR: watchlist,
  W and C state, persisted flows of W, verdicts, alerts on W, investigations. A single default scope exists until a
  multi-client deployment is needed (a PSP serving several merchants, or a hosted offer).
- API keys and console users are bound to one `scope_id`; every repository query filters on it (enforced in the
  repositories, not the controllers) and an authorisation test per endpoint proves a key of scope A never reads scope B.
- **Shared, scope-neutral data**: public chain data and public labels (sanctions, issuer freezes, community labels).
  **Never shared**: anything derived from a scope (its watchlist, C profiles, verdicts). A screening by scope B must not
  become deeper or faster because scope A watches the same address: depth is decided by B's own scope only.

### 4. Persistence, retention and rebuild (requirement 3)

| Data | Persisted | Default retention | Rebuild after restart or loss |
|---|---|---|---|
| Watchlist (W) | yes (existing table, + `scope_id`) | until removed | — |
| Flows involving W | yes, partitioned by month | 13 months (configurable) | reloaded per W address; beyond retention, re-fetched from the address history sources (ADR-0020) and **marked as re-fetched** |
| W profile | snapshot every 10 min and at shutdown | latest snapshot only | snapshot + flows after the snapshot; if no snapshot, rebuilt from persisted flows |
| C compact profile | yes | while the C entry is within budget | from W's persisted flows |
| Signals and score history of W | yes | 13 months | — |
| Verdicts (screening, section 5) | yes, immutable | **5 years** (FATF Recommendation 11 record keeping; configurable for the operator's jurisdiction) | never rebuilt: a verdict is a record of what was known then |
| Chain-wide working set (G) | no (ADR-0024 steps 3a/3b) | — | starts empty |

Size estimate to validate during the pilot: a flow row ≈ 300 bytes with indexes (assumed); scenario 2 (50,000
transfers/day) ≈ 15 MB/day ≈ 5.5 GB/year; scenario 1 ≈ 0.5 GB/year.

### 5. Verdicts: traceable, explainable, never overstated (requirements 4 and 5)

Every answer about an address (screening, wallet page, MCP) is a **verdict record** with two separate axes:

- **Coverage** (what was actually examined): `DEEP` (W, all detectors with their full windows), `COUNTERPARTY` (C),
  `SCREENED` (G: lists and direct exposure only), `NOT_COVERED` (unsupported network or source unavailable). It lists the
  checks performed, the block range and time range analysed, each source with its version and last update date, the
  detectors and rule pack with their versions, and the known limitations (for example "native transfers not covered").
- **Outcome**: `RISK_FOUND` (with the assertions, their level OBSERVED / INFERRED / CONFIRMED per ADR-0023, and their
  evidence: tx hashes, blocks, label sources), `NO_RISK_FOUND`, or `INCONCLUSIVE`.

Rule: **`NO_RISK_FOUND` exists only with coverage `DEEP` or `COUNTERPARTY` and every declared check completed.** With
coverage `SCREENED`, the outcome is `NO_LISTED_EXPOSURE` (no sanctioned, frozen or flagged link found in the lists
checked), which the API, console and reports word as "no signal found in the checks performed", never as "no risk".

A verdict stores its inputs, engine version, rule pack and source versions, and the ids of the evidence it used, so it
can be explained and audited later without recomputing it.

## Consequences

- Positive: memory and storage proportional to the client's perimeter, not to the chain; detectors keep their full
  windows where the client needs them; no "safe" answer that hides a partial analysis; clean basis for `POST /screening`,
  investigations (ADR-0022) and the MCP (ADR-0025).
- Negative: an address outside the scope gets a shallow answer (by design, and said so); onboarding requires the client
  to declare its wallets (an empty watchlist means no deep analysis, as today in production); a new migration and
  `scope_id` plumbing across repositories; partitioned tables to operate.
- Behaviour change: chain-wide behavioural alerts (outside W) become informational or are disabled by default (they are
  noisy, phase 0.10, and their look-back is about 2 hours).

## Validation before acceptance

1. Heap histogram and per-item memory cost from the ADR-0024 run, to confirm or correct the C budget.
2. Counterparty distribution of real wallets close to the scenarios (one public TRON hot wallet and one deposit address,
   loaded through the address history), to confirm the 10,000-per-W budget.
3. Review of the verdict wording (section 5) against one real compliance use case.
4. The open questions below.

## Open questions

- Default retention of W flows (13 months proposed) versus the record-keeping obligations of the target clients.
- Should C addresses that are themselves high risk be promoted to W automatically (bounded), or only suggested?
- Multi-scope instance: needed for the first pilots, or one instance per client is enough?

## Amendment 1 (2026-11-18): the persisted journal of watched addresses (decision D5, option d)

- Status: proposed (option d of decision D5 in `docs/FUND_FLOW_TRACE_DECISIONS.md`; implements the "W: watched" depth of
  this ADR for flows only; this text awaits review).

### Decision

1. **A journal of the transfers of watched addresses.** Every live transfer whose sender or receiver is on the watchlist
   is written to `flow_journal` (network, event id, transaction, time, sender, receiver, token, exact raw amount). The
   chain-wide journal (V9) stays off. Address histories loaded on enrolment (ADR-0020) are written to it as well.
2. **Partitions by month, retention 13 months**, purged by dropping partitions (never a large `DELETE`). Indexes by
   (network, sender, time) and (network, receiver, time).
3. **Public data once, interest apart.** Transfers are public chain data, stored once whoever watches them. Which address
   is watched since when is kept apart (`journal_coverage`); with several client scopes (decision D1) it carries the
   scope and row-level security, like the watchlist itself will.
4. **Coverage.** For each watched address the journal records since when it is complete (enrolment time, or the start
   of a loaded history); indexing gaps (P0-B) apply as everywhere. A reader never says "no transfer" for a period the
   journal does not cover.
5. **Write-behind, never blocking indexing**: a bounded queue drained in batches; a full queue drops and counts. Metrics:
   written, dropped, queue size.
6. **Read on demand, no reload at startup** (no memory cost): traces, investigations, exposure and coverage read the
   journal for watched addresses beyond what memory holds (step 2).
7. **Steps**, each reviewed: (1) tables and writer; (2) readers and coverage; (3) monthly purge; (4) console. Then, as
   separate steps: wake-up after dormancy and strategy change on watched addresses, long histories.

### Consequences

- Disk proportional to the watchlist (estimated 1.3 to 12 GB over 13 months for the scenarios of this ADR); a 48-hour
  measurement precedes any longer retention promise. Production watchlist today: one address.
- Not in this amendment: compact profiles of direct counterparties (depth C), rule simulation on the journal (rules
  replay events, not only transfers).
