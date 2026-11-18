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

## Amendment 2 (2026-11-18): SIMULATED and PREDICTED, with their own rules

- Status: proposed (phase 4 of `docs/strategy/FEATURE_ROADMAP.md`: "what-if graph" marked `SIMULATED`, "precursor
  signals" as `PREDICTED` with expiry and a measured realisation rate; acceptance criteria of phase 4 in
  `docs/strategy/ACCEPTANCE_CRITERIA.md`). Lifts the reservation of rule 1 of amendment 1 for these two levels only, under
  the rules below; everything else in amendment 1 is unchanged.

### Decision

1. **`SIMULATED` is the result of an explicit hypothesis** ("if address X were listed as category C"). It is computed on
   request and **never stored** as a fact: no signal, alert, label, score, assertion of an investigation or rule input is
   created from it, and no detector, rule or score reads it. Every simulated item carries the level `SIMULATED` and the
   hypothesis it depends on, in the same response; simulated results come from their own endpoints, never mixed into a
   response that holds observed or inferred facts. Coverage applies as for any result (amendment 1, rule 2): "nobody would
   be exposed" is said only with `COMPLETE` coverage.
2. **`PREDICTED` is a hypothesis about a future observable event** of a subject: a type, the subject, a confidence, the
   signals it rests on (ids), its limits, an issue date and a **mandatory expiry**, and the **observable condition** that
   would realise it, written so that a machine can evaluate it (for example "an outgoing transfer of the subject to more
   than N new counterparties before the expiry"). It is never `CONFIRMED`, never raises a risk score or opens an alert by
   itself, and is shown as a hypothesis with its expiry.
3. **Every prediction is evaluated.** It stays `OPEN` until its condition is observed (`REALISED`, with the evidence that
   realised it) or its expiry passes (`NOT_REALISED`); a prediction whose subject lost coverage before the expiry is
   `UNDETERMINED`, never counted as not realised. The evaluation is deterministic, uses observed events only, and is stored.
   The **realisation rate per type** (realised over realised plus not realised, with the counts and the period) is
   published; a type is not described as predictive before enough evaluated predictions exist (threshold stated with the
   rate).
4. **Storage.** Predictions and their evaluations are stored append-only (an evaluation is a new row), client-scoped where
   they concern a client's watched wallets, with row-level security, as the artifacts of ADR-0022. Simulated results are
   not stored; an export of one carries its hypothesis and level.
5. **Order of work** (each step reviewed on its own): the what-if (no storage); then predictions, their storage and
   evaluation; then the precursor detectors one by one (dispersion, wake-up after dormancy, burst of new counterparties,
   change of strategy, coordinated movements); rule simulation on history last, limited to the history actually held
   (in memory until decision D5 provides a persisted journal) and saying so.

### Consequences

- New levels in the API and the console, each with its own wording; additive for the v1 API.
- Tests required: a simulated result never reaches a signal, alert, label, score or investigation; a simulated response
  always carries its hypothesis and level; a prediction without expiry or observable condition is refused; a prediction
  never changes a risk score; evaluation is deterministic (same events, same outcome); loss of coverage gives
  `UNDETERMINED`; the realisation rate counts only evaluated predictions.
