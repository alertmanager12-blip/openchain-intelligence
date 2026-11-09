# ADR-0023: Epistemic levels for every assertion (observed, inferred, predicted, confirmed)

- Status: proposed
- Date: 2026-11-09

## Context and problem statement

`EvidenceLevel` (ANOMALY, RISK_SIGNAL, CONFIRMED_BY_SOURCE) qualifies signals and rules, but wallet relations,
predictions and simulations need a stricter distinction, so that a probabilistic association is never shown as an
identity and a prediction never as a fact.

## Decision (proposed)

Introduce in `oci-core` an `Assertion` type: subject, predicate, value, **level** ∈ {OBSERVED (read from the chain),
INFERRED (derived by a heuristic, with confidence), PREDICTED (hypothesis with confidence, signals, limits and an expiry),
CONFIRMED (backed by a named reliable source, e.g. issuer freeze, OFAC), SIMULATED (what-if, never stored as an
observation)}, confidence, evidence references (tx hashes, blocks, labels), sources, engine@version, created/expires.
Signals, scores, relations, predictions and investigation outputs are exposed as assertions or carry their level.
The existing `EvidenceLevel` maps onto it (ANOMALY/RISK_SIGNAL → INFERRED, CONFIRMED_BY_SOURCE → CONFIRMED).

## Consequences

- Positive: one vocabulary across API, UI, MCP and reports; enables prediction evaluation (expiry + outcome).
- Negative: API additions on many DTOs (additive, non-breaking in v1).
