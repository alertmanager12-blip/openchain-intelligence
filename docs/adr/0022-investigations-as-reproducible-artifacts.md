# ADR-0022: Investigations as persistent, resumable, replayable artifacts

- Status: proposed
- Date: 2026-11-09

## Context and problem statement

Today an "investigation" is a set of stateless reads (wallet analysis, exposure BFS ≤ 3 hops, graph BFS ≤ 500 nodes)
over in-memory flows. Nothing is saved, nothing can be resumed, replayed or reviewed, and there is no report. The target
(autonomous investigation engine) requires results an analyst, an auditor or a regulator can verify later.

## Decision (proposed)

Add an `oci-investigation` module (plain Java, like the other domain modules). An investigation is a persisted object:
input (address/transaction), parameters (depth, window, budgets), deterministic plan steps, status, the engine version,
data-source coverage (e.g. "node serves only recent blocks"), the frozen graph, the typed assertions it produced
(ADR-0023) and human validation decisions. Execution is asynchronous, resumable and idempotent (same mechanics as the
address history backfill, ADR-0020). Reports (JSON, then HTML/PDF) are generated only from stored assertions. Optional
AI assistance may summarise assertions, citing their ids; it never adds facts.

## Consequences

- Positive: reproducibility becomes testable (same inputs + same data ⇒ same result); auditable reports.
- Negative: requires persisted flows (ADR-0024); storage growth per investigation (bounded by budgets).

## Amendment 1 (2026-11-16): the trace artifact first

- Status: proposed (option C of `docs/ADR_0022_REVIEW.md` and decision D2 of `docs/FUND_FLOW_TRACE_DECISIONS.md`; this
  text awaits review before the ADR is accepted)
- Replaces the "Decision (proposed)" section above where they differ.

### Decision

1. **First step: the trace artifact only.** An artifact stores one run of the trace engine (Fund Flow Trace P0-D):
   the request (seed, direction, window, token, method, limits, stop categories, minimum share), the engine version
   (`trace@1`), the coverage of every address it read (P0-B), the **frozen flows** it read (each with its event id,
   aliases, transaction, time, parties, token and raw amount), and the result (nodes, edges, attributions, stops,
   findings, status). The generic step plan, human validation of assertions, HTML/PDF reports and AI summaries come in
   later amendments, each reviewed on its own.
2. **Replayable means explainable from the frozen evidence.** Running the engine again on the frozen flows must give the
   same result byte for byte (tested). Running it on newer data is a **new version** of the artifact, linked to the
   previous one with the `TraceDiff` between them (evidence added, removed, moved; attributions changed). A stored version
   is never modified.
3. **`STALE` after a reorganisation.** When a frozen flow is retracted by the chain (reorganisation), the artifacts that
   contain it are marked `STALE`, with the event ids concerned; they stay readable, and a new version can be run.
4. **Client scope on every table** (decision D1): `client_scope_id` not null, every read and write takes the scope as a
   required parameter, row-level security on these tables from their creation, and the negative tests N1 to N12 of
   `docs/FUND_FLOW_TRACE_TENANT_ISOLATION.md` are part of "done". Another scope's artifact answers 404, like an unknown one.
5. **Placement.** The engine stays pure in `oci-intelligence` (no persistence, no clock). Storage, asynchronous execution
   (a bounded queue, resumable after a restart like the address history backfill), budgets and the API are in `oci-app`.
   No new module until the step plan exists.
6. **Budgets.** Every run is bounded by its limits (depth ≤ 8, nodes ≤ 5,000, flows read ≤ 200,000); in addition a per-scope
   cap on concurrent runs and on runs per day protects provider quotas. A run never adds anything to the in-memory working
   set of ingestion.
7. **Retention** (proposed, to confirm): artifacts and their frozen flows kept 13 months per scope (as the client-scope
   journal of decision D5), then deleted with their exports; configurable per instance. Exports are reads that are
   audited, and their links are not guessable (the security of the data never rests on that).

### Consequences

- Storage per run is bounded by its limits (estimated ~14 MB for 20,000 flows, to be measured on real runs).
- Until the client-scope journal (D5) exists, a trace reads what memory and loaded histories hold; its coverage says so,
  and the frozen flows keep it explainable later.
- Tests required before this amendment is considered implemented: replay on frozen flows gives the same result;
  a new version links its `TraceDiff`; `STALE` after a retraction; N1-N12; budgets refuse a run beyond them.

## Amendment 2 (2026-11-17): a deterministic investigation plan and a report made only of assertions

- Status: proposed (phase 3 of `docs/strategy/FEATURE_ROADMAP.md`: "deterministic investigation plan" and "structured
  report"; this text awaits review). Builds on amendment 1 (trace artifacts) and ADR-0023 amendment 1 (levels, coverage).

### Decision

1. **An investigation runs a fixed plan on one subject** (an address on a network), versioned as `plan@1`. The steps run in
   this order, each on evidence frozen at the start of the investigation, each producing typed assertions:
   1. *Coverage* of the subject (P0-B): which blocks the answers rest on. One assertion, its state, never "complete" unless
      `AddressCoverage` says so.
   2. *Labels* of the subject (source, version, date).
   3. *Direct exposure* (`ExposurePaths`): one assertion per listed counterparty, with the transfers and amounts per token;
      transfers ignored (dust, unverified token) are stated as ignored.
   4. *Traces* out and in (two trace artifacts of amendment 1, linked to the investigation): value attributed per wallet,
      the method stated, intervals kept, stops listed.
   5. *Patterns* found by the traces (dispersion, consolidation, circulation, peel chain).
   6. *Relations* with other wallets (`WalletRelations`).
   7. *Signals* already detected on the subject by the detectors, with their own evidence.
2. **An assertion** is: a type, a subject, parameters (addresses, amounts as exact strings, counts), a level (`OBSERVED`,
   `INFERRED`, `CONFIRMED` under the rules of ADR-0023 amendment 1), the evidence (event ids, label source and version),
   the step and plan version that produced it, and a deterministic id (hash of type, subject, parameters and evidence). It
   carries **no free text**: wording comes from a fixed template per type.
3. **The report is generated only from the stored assertions**: every sentence is a template filled with one assertion
   and cites its id. JSON first; HTML and PDF later. Its first section states the coverage and the limits; a missing step
   (budget, provider failure) is a stated limit, never silently skipped. Wording rules: no identity ("may be related"),
   "no exposure found in blocks X to Y" only with `COMPLETE` coverage, a method of attribution always named as a convention.
4. **Determinism**: the same subject, plan version, engine versions and frozen evidence give the same assertions, the same
   ids and the same report text (tested). Running it again on newer data is a new version with the assertions added and
   removed.
5. **Storage and isolation** as amendment 1: client scope on every row, row-level security, 404 for another scope; the
   investigation stores its assertions and the ids of its trace artifacts; a STALE trace makes the investigation STALE.
6. **Not in this amendment**: human validation of assertions, rule proposals, AI summaries (each its own amendment).

### Consequences

- One investigation costs two traces (within the trace budgets) and in-memory reads; it adds nothing to the ingestion
  working set.
- Tests required: replay gives identical assertions and report; each assertion type has a template and a level rule
  test (an inference is never CONFIRMED); "no exposure" without COMPLETE coverage never appears; a STALE trace propagates.
