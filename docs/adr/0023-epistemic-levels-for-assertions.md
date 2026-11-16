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

## Amendment 1 (2026-11-16): three levels now, coverage as a separate axis

- Status: proposed (option C of `docs/ADR_0023_REVIEW.md` and decision D3 of `docs/FUND_FLOW_TRACE_DECISIONS.md`,
  validated in principle by the maintainer on 2026-11-16; this text awaits review before the ADR is accepted)
- Replaces the "Decision (proposed)" section above where they differ.

### Decision

1. **Three levels are implemented**: `OBSERVED` (read from the chain or from an indexed event, nothing derived),
   `INFERRED` (derived by a heuristic or a detector, with a confidence), `CONFIRMED` (see rule 3). `PREDICTED` and
   `SIMULATED` are **reserved, not implemented**: they are added, with their own rules, the day a producer exists. No
   API, screen or report may show them before that.
2. **Coverage is a separate axis, never folded into the level.** An assertion read from partial data stays `OBSERVED`;
   what the data covers is said by a coverage state attached to the result: `COMPLETE`, `PARTIAL` (missing sub-ranges
   listed), `TRUNCATED` (a limit was reached; the range actually covered is given), `UNAVAILABLE`, `NOT_YET_INDEXED`
   (definitions in `docs/FUND_FLOW_TRACE_P0_B_COVERAGE.md` § 2.1). **"No result" may only be stated with `COMPLETE`
   coverage**; otherwise the wording is "no result in the covered data, from … to …".
3. **`CONFIRMED` only through** (a) a named source that is itself reliable for that fact (label or event with
   `EvidenceLevel.CONFIRMED_BY_SOURCE`: issuer freeze event, sanctions list), with the source, its version and its date
   recorded in the assertion; or (b) a human validation that is identified (who) and dated (ADR-0022). A heuristic, a
   score or a number of signals never makes an assertion `CONFIRMED`.
4. **Mapping of the existing `EvidenceLevel`**, fixed and tested: `ANOMALY` and `RISK_SIGNAL` → `INFERRED`;
   `CONFIRMED_BY_SOURCE` → `CONFIRMED` only when rule 3 (a) holds for the asserted fact, else `INFERRED` (for example an
   exposure derived from a confirmed label is `INFERRED`; the label itself is `CONFIRMED`). `evidenceLevel` stays in the
   v1 JSON, read-only, next to the new `level`; there is no second vocabulary to choose from.
5. **Evidence references** (`EvidenceRef`): network, transaction hash, block number and log index when known, `eventId`,
   provenance (indexer, history source, label source with version), and a `finalized` flag (false while the block is
   not final). A reference without a block is allowed only for sources that do not give one, and says so.
6. **Projection at read time.** Assertions are built when a signal, score, relation or trace is read or exported; no new
   object is stored per signal in the in-memory working set (memory measured: `Signal$Evidence` already 1.34 M instances,
   43 MB). Results that are persisted (traces, investigations) store their assertions in PostgreSQL, not in memory.

### Consequences

- Additive for the v1 API: `level`, `coverage` and `evidence[]` fields; nothing removed.
- Every producer of a "no result" sentence (exposure, trace, screening) needs a coverage state first: P0-B comes before
  any such sentence is shown.
- Tests required before the amendment is considered implemented: the mapping of rule 4; rule 3 refuses a heuristic or a
  score as `CONFIRMED`; "no result" without `COMPLETE` coverage is never produced; `PREDICTED`/`SIMULATED` never appear
  in an API response.
