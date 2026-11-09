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
