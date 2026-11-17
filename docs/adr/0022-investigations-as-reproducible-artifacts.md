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

## Amendment 3 (2026-11-17): human review of assertions

- Status: proposed (phase 3 of `docs/strategy/FEATURE_ROADMAP.md`: "human validation of assertions (accept/reject,
  comment), logged"; this text awaits review). Builds on amendment 2 (assertions and report).

### Decision

1. **A review is a decision next to an assertion, never a change to it.** An analyst records, on one assertion of one
   investigation, a decision `ACCEPTED`, `REJECTED` or `NEEDS_EVIDENCE`, with an optional comment (at most 2,000
   characters). The assertion, its level, its evidence and the report text are unchanged: the report stays deterministic
   and replayable, and what the data show stays distinct from what a person concluded.
2. **The level never changes.** Accepting an `INFERRED` assertion does not make it `CONFIRMED`: under ADR-0023
   amendment 1 `CONFIRMED` depends on the source only. Review is a third axis, beside level and coverage.
3. **An append-only journal.** Reviews are rows of `assertion_reviews` (investigation, assertion id, decision, comment,
   actor, time, client scope), with forced row-level security and 404 for another scope, as amendment 1. A review is never
   updated or deleted (database grants and a trigger refuse it); a new decision is a new row, the latest one is the
   current decision, and the history stays visible. Reviews are deleted only with their investigation (retention of
   amendment 1). Every review is also written to the audit log.
4. **The report is annotated, not rewritten.** Each sentence shows its current decision, its author and date beside it;
   a rejected assertion stays in the report, marked rejected (removing it would hide what was claimed). Comments are shown
   as human comments and never enter the generated text.
5. **Versions.** Assertion ids are a hash of type, subject, parameters and evidence. A decision taken on version N is
   shown on a later version where the same assertion id appears, marked "decided on version N"; it is not copied. An
   assertion that changed has a new id and no decision.
6. **A STALE investigation keeps its reviews**, with a warning that evidence a decision relied on left the canonical chain.
7. **Permission and separation of duties.** A new permission `investigation:review` (ANALYST and above) is required to
   review; reading reviews needs `investigation:read`. An optional rule per instance, `OCI_INVESTIGATION_FOUR_EYES`
   (off by default), refuses a review by the actor who created the investigation (403).
8. **API**: `POST /api/v1/investigations/{id}/assertions/{assertionId}/reviews` and
   `GET /api/v1/investigations/{id}/reviews`; an assertion that is not in that investigation answers 404.

### Consequences

- One more table (V16) and one permission; nothing changes in the plan, the assertions or the report text.
- Tests required: the report text is identical before and after reviews; the level never changes; a review cannot be
  updated or deleted (application and database); isolation between client scopes (as N1 to N12); a decision on version N
  is shown on version N+1 for the same assertion id and not for a changed one; an unknown assertion answers 404; the
  four-eyes rule when enabled; comments with control characters are escaped in the audit log.

## Amendment 4 (2026-11-17): rule proposals from an investigation

- Status: proposed (phase 3 of `docs/strategy/FEATURE_ROADMAP.md`: "rules proposed from an investigation (YAML draft, never
  enabled automatically)", acceptance "valid draft, tested by dry-run"; this text awaits review). Builds on amendments 2
  (assertions) and 3 (reviews).

### Decision

1. **Deterministic drafts, no AI.** A fixed template per assertion type, versioned `proposal@1`, turns stored assertions
   into rule drafts in the existing format `openchain.io/rule/v1`. The same assertions and reviews give the same YAML,
   byte for byte. Templates use existing fields only.
2. **Templates of `proposal@1`**:
   - `TRACE_ATTRIBUTION` (an address value reached): alert when that address sends funds on (the next hop of the path).
     Kept for the 5 addresses that received the most value (upper bound of the attribution); the others are listed as
     not proposed, so that a dispersion to many small recipients does not drown the useful drafts.
   - `RELATION` with `COMMON_FUNDER`: alert when that funder sends to other addresses.
   - `PATTERN` dispersion or peel chain: the dispersion rule (model of `fund-splitting`) limited to the subject as sender.
   - `DIRECT_EXPOSURE`: alert on a new transfer between the subject and that listed counterparty.
3. **What never becomes a proposal**: an assertion whose current review (amendment 3) is `REJECTED`; coverage and limit
   assertions. Any other assertion can; its current review (accepted, needs evidence, none) is shown with the proposal.
4. **Every draft is checked before it is shown**: valid for the rule loader; embedded examples built from its frozen
   evidence pass: an event the rule must match and one it must not (for the next hop, the evidence transfer brought the
   value in, so it is the counter-example and the expected match is the same transfer sent on; for a dispersion, tests
   with the threshold reached and not reached); a dry run on the in-memory event history
   (`RuleTester`, no notification possible) gives events in scope and triggers, with the time window actually covered,
   which may be short and is said so. A draft that fails any check is not proposed and the reason is listed.
5. **Provenance and limits in the YAML**: `enabled: false`; `author: investigation:<id>@v<version>`; tags `proposed`,
   `investigation`; a documentation block citing the assertion ids and stating that addresses are inferred and that an
   alert is not a finding. Ids are deterministic (`inv-<hash>-<template>`).
6. **Nothing is stored.** Proposals are computed on request from the stored assertions:
   `POST /api/v1/investigations/{id}/rule-proposals` (`investigation:read`; a POST like `POST /rules/test`, since it
   computes a dry run on request, but it stores nothing and needs no write permission). Adopting one is the existing
   `POST /api/v1/rules` (`rules:write`): the rule is created **disabled**; enabling it is the separate, audited
   `POST /rules/{id}/enable`. Nothing in this amendment can enable a rule.
7. **Console**: "Propose rules" on an investigation lists each draft with its YAML, the assertion and its review, the dry
   run result, and "Create as disabled rule".

### Consequences

- No table, no permission; one read endpoint and templates in the intelligence module.
- Tests required: same assertions give the same YAML; every template gives a draft valid for the loader whose examples
  pass; a rejected assertion gives no proposal; a proposal adopted through `POST /rules` is disabled; the dry run never
  notifies; another client's investigation answers 404.

## Amendment 5 (2026-11-17): an optional AI summary, checked against the assertions

- Status: proposed (phase 3 of `docs/strategy/FEATURE_ROADMAP.md`: "optional AI assistance: summary written from the
  assertions only, citing their identifiers", acceptance "no claim outside the assertions (test)"; this text awaits
  review). Builds on amendments 2 (assertions, report) and 3 (reviews). Principle 4 of `CHAINSENTINEL_VISION.md`: AI
  drafts, it never concludes on risk.

### Decision

1. **Optional and off by default.** `OCI_AI_SUMMARY_ENABLED=false`; the operator also provides `ANTHROPIC_API_KEY`. Off or
   without a key, nothing is sent and the console shows no summary action. The deterministic report stays the reference
   and is never replaced or rewritten.
2. **Provider and integration.** The Claude API through the official Java SDK (`com.anthropic:anthropic-java`), no
   hand-written HTTP client. Default model `claude-opus-5-5` at low effort (constrained drafting, not long reasoning),
   configurable (`OCI_AI_SUMMARY_MODEL`); server-side refusal fallbacks enabled. A refusal or an API error is a failed
   summary, stored with its reason.
3. **What is sent, and only that.** The assertions of the one investigation (type, level, parameters, ids), as JSON data.
   No raw transfers, no frozen evidence, nothing from another investigation or client scope. The documentation states
   that this data leaves the instance for the provider when the feature is enabled.
4. **On-chain data is untrusted (prompt injection).** Token symbols and label texts are filtered (allowed characters,
   bounded length; otherwise replaced by a neutral placeholder such as "token 0x…"); assertions are sent as delimited JSON
   data, never as instructions; the system prompt says so. The output can only take effect through the checks of point 5.
5. **Structured output, then a deterministic check** (the test "no claim outside the assertions"). The response follows
   a JSON schema (`output_config.format`): a list of sentences, each with the assertion ids it relies on. The server
   refuses the summary when a sentence cites no id or an id not in the investigation; when an address, amount or hash in
   a sentence does not appear in the parameters of the assertions it cites; when it says "confirmed" while none of its
   assertions is `CONFIRMED`; when it states identity, ownership, intent or guilt (a fixed list of terms). When coverage
   is not `COMPLETE`, the coverage sentence of the report (amendment 2) is put first. A refused summary is never shown.
6. **Stored, never regenerated silently.** A language model is not deterministic: each summary is stored once, append-only
   (`investigation_summaries`, V17, client scope, forced row-level security), with the model id, the prompt version
   `summary@1`, a hash of the assertions sent, the check result and its reasons. Asking again creates a new record. The
   console labels it "written by AI, checked against the assertions" and shows it apart from the report, each sentence
   with its citations.
7. **Permission and budget.** Requesting a summary needs `investigation:write` (a paid call); a daily cap per client scope
   (default 20). Reading summaries needs `investigation:read`.

### Consequences

- One dependency (the SDK, under the licence policy), one table (V17), one setting group; nothing changes in the plan,
  the assertions, the reviews or the report.
- Tests required (no network: the API client is replaced by a stub in tests): the checker refuses each kind of invented
  claim (unknown id, unsupported address or amount, "confirmed" without a confirmed assertion, identity or intent terms);
  the coverage sentence is imposed when coverage is partial; a filtered symbol never reaches the request; nothing is sent
  when the feature is off; another client's summary answers 404; the daily cap answers 429.
