# Bilingual console (EN/FR) and a new overview: "can I trust what I see?"

- Date: 2026-11-19
- Status: proposed, awaiting the maintainer's approval
- Scope: `frontend/` (Angular 22 console) and the dashboard endpoint of `oci-app`
- Related: `2026-11-18-console-redesign-design.md` (design system, verdict panel, evidence lines), ADR-0023 (levels of
  proof), ADR-0027 (OFAC list)

## Intent and why

The maintainer works in French; the console is English only. The overview (`/`, `features/overview/`) stacks seven
blocks (live strip, "what needs your attention", six counters, recent alerts with risk distribution and statuses, a
networks table, a 24 h chart, recent signals). Network health appears three times and recent alerts repeat the
attention block. Above all, the page never answers the question that decides whether anything else on it can be
believed: **is monitoring working?** Measured in production on 2026-11-19, none of these is visible on the overview:

- 29,640 TRON blocks never analysed (`indexer_gaps`, 18 and 19 Nov, about 25 h of transfers);
- 1,385 open alerts that reach nobody (8 enabled rules route to the placeholder `default-webhook`, no
  `OCI_DEFAULT_CONNECTOR`);
- the indexing delay as time (shown only as blocks: 414 Ethereum blocks is about 1 h 20, 536 TRON blocks about 27 min);
- which OFAC publication is in force (only inside each verdict's coverage sentence).

The maintainer asked to rework the overview "substance and form" and to make the console bilingual EN/FR, and chose:

1. **Direction A, "watch post"** (mockup reviewed 2026-11-19): first a reliability band, then the alerts to handle as
   verdicts, everything else compact.
2. **Option C for engine sentences**: the interface is translated; the sentences most visible on the overview (verdicts
   and alert summaries) are rebuilt in the console from structured fields in both languages; other engine sentences
   (signal summaries in other screens, evidence descriptions, rule names in rule screens) stay as the engine wrote them
   for now.

Success: opening the console in French, in ten seconds the maintainer knows whether monitoring can be trusted right
now and what to handle first, without one English sentence in the overview's visible text (the engine's original
wording stays reachable in expandable details); switching to English changes every word without reloading.

## Goals

1. **Bilingual foundation** usable by every screen: language switch EN/FR in the shell, no reload, remembered; dates,
   numbers, plurals and relative times formatted for the language.
2. **Shell and overview fully bilingual**, including the shared components the overview shows (verdict panel, evidence
   lines, coverage and sanctions sentences, severity and status names).
3. **New overview, direction A**:
   - **Reliability band** "Is monitoring reliable?" with a one-word verdict (*Yes* / *Partly* / *No*) and one line per
     check, each problem linking to where it is fixed or detailed:
     - indexing gaps (blocks never analysed, per network, with dates and approximate duration). Only gaps **detected in
       the last 7 days** affect the band verdict; older gaps are listed behind a "history" link and never turn the
       band amber on their own (`indexer_gaps` is append-only and has no "resolved" state);
     - indexing delay per enabled network, as time (and blocks in a tooltip); unknown when the head is unknown;
     - notifications: number of open alerts whose rule has no destination (definition in §3); links to Settings;
     - sanctions list: source, publication date, address count, stale after 72 h (ADR-0027), or "no list";
     - network health (provider degraded) when a network is unhealthy.
     When every check passes, the band collapses to one green line.
   - **"Handle now"**: at most 5 open alerts, most severe first then most recent, each as a verdict (sentence, risk,
     supporting signal lines, coverage); the total number of open alerts with a link to the alert list.
   - **Side column**: last 24 hours (alert count and split by rule) and one line per enabled network.
4. **Verdict and alert sentences in both languages** for the shipped rules (wallet-draining, large-usdt-transfer,
   fund-splitting, issuer-freeze-counterparty, sanctioned-exposure-signal, listed-address-exposure, suspicious-wallet,
   contract-exploit, dormant-wallet-reactivation, suspicious-approval), built from the alert's matched-event fields.

## Non-goals

- Translating the other screens (alerts, wallet, graph, investigations, rules, settings…): later, screen by screen, on
  the foundation built here. Until then they stay English inside a French shell.
- Translating stored engine text (signal summaries, evidence descriptions, alert history) or changing how the engine
  writes it (option B was not chosen).
- Languages other than EN and FR; right-to-left.
- Per-user language stored on the server.
- Changing what counts as an alert, a gap or a delay: the overview shows existing facts, it computes no new risk.

## Anti-goals (failure even if it works)

- A reassuring overview while monitoring is degraded: a missing fact (endpoint failed, permission missing, network
  head unknown) is shown as **unknown**, never as a pass. The band never says *Yes* unless every check positively
  passed.
- A French sentence that says more than the English one or than the evidence (ADR-0023): a translated verdict keeps
  the same level of proof and the same "a signal is a reason to look closer, not proof" stance.
- A missing translation that silently shows a raw key or a blank: the build or the tests fail instead.
- Hiding open alerts behind "nothing to handle" (the 2026-11-18 review finding kept in `emptyState`).

## Constraints

- Angular 22, signals, OnPush, standalone components, Vitest (`npx ng test --watch=false`); most templates are inline
  in `.ts` files (93 source files, 7 `.html`).
- One image per deploy; the deploy procedure (host build, nginx-only image) must not change.
- No new runtime dependency unless it removes real work (none needed: `Intl` covers formatting and plurals).
- Permissions: the dashboard already omits sections the caller may not read (`omittedSections`); the band must say
  "not visible with your role" for those, not "OK".
- Light and dark themes, phone width (16 px gutter, no horizontal scroll), keyboard and screen-reader access as today.
- Production facts come from live state; nothing on the overview is sample data.

## Approaches considered

**Translation mechanism**

1. *Angular built-in i18n (`@angular/localize`)*: compile-time, one build and one URL per language, a reload to switch.
   Rejected: two bundles and nginx routing change the deploy; switching reloads.
2. *Third-party runtime library (Transloco, ngx-translate)*: runtime switch, JSON files. Rejected: a dependency for
   what a small typed service does, and untyped keys hide missing translations until runtime.
3. **In-house typed dictionaries with a signal-based service (chosen, Claude's call, stated to the maintainer)**:
   `en.ts` is the source of keys; `fr.ts` is typed against it so a missing key fails compilation; a `lang` signal makes
   every template that reads a translation update on switch, without reload.

**Engine sentences** (maintainer's choice: C)

- A: interface only, engine sentences stay English. Fast, mixed screen.
- B: the engine stores structured facts, every language renders them. Complete, heavy (engine and schema).
- **C (chosen)**: A everywhere, plus the overview's verdict and alert sentences rebuilt in the console from structured
  fields (`matchedEvent`), with the engine's English text as fallback when a field is missing or the rule is unknown.

**Overview layout** (maintainer's choice: A among three mockups)

- **A, watch post (chosen)**: reliability first, then action.
- B, daily brief: a written analyst note first. Kept as an idea for the band's first line later, not built now.
- C, tight dashboard: four key figures, split by rule, short list.

## Design

### 1. Translation foundation (`frontend/src/app/core/i18n/`)

- `I18nService`: `lang = signal<'en' | 'fr'>`, initial value = stored choice (localStorage `oci.lang`, read in
  try/catch) else `navigator.language` starting with `fr` → `fr`, else `en`. Setting it stores the choice and updates
  `<html lang>`.
- Dictionaries `en.ts` (source of truth) and `fr.ts` typed `Record<keyof typeof en, …>`: a key in `en` missing from
  `fr` does not compile. Keys are namespaced by area (`shell.*`, `overview.*`, `verdict.*`, `severity.*`, …).
- Entries are strings with named parameters (`'{count} open alerts'`) or functions for grammar the template cannot do
  (plural via `Intl.PluralRules`, gender/elision in French).
- Access: a `t(key, params?)` function (and a pure-false pipe or template helper, builder's choice) that reads the
  `lang` signal, so OnPush templates re-render on switch.
- Formatting helpers replace the hard-coded `'en-US'`: numbers (`Intl.NumberFormat`), dates and times
  (`Intl.DateTimeFormat`), relative times (`Intl.RelativeTimeFormat`, replacing `timeAgo`'s English), durations
  ("1 h 20 min" / "1 h 20 min").
- Language switch `FR | EN` in the shell header (visible on phone width), with an accessible name.
- Tests: every key of `en` present and non-empty in `fr`; no `{param}` left unreplaced; a sample of formatted values in
  both languages.

### 2. Shell and shared components

- `layout/` (navigation, command palette labels, shortcut help, toasts) and the shared components the overview uses
  (`verdict-panel`, `evidence-line`, `coverage-text`, `sanctions-text`, `levels`, `page-header`, state/skeleton texts,
  severity and status names) go through `t()`.
- Other screens keep their English text; their navigation labels are translated (they live in the shell).

### 3. Dashboard additions (`oci-app`, `GET /api/v1/dashboard`)

Added fields, all optional so an older console still reads the response:

- `networks[].lastIndexedBlockTime`: timestamp of the last block processed for that network (kept in memory by the
  ingestion path; null until a block is processed after start). The console shows delay = now − that time; when null it
  falls back to blocks only, never to a guess.
- `coverage.gaps[]`: per network, the recorded `indexer_gaps` (from, to, cause, detected at) with the total of blocks
  never analysed. Read from `JdbcIndexingCoverageStore`.
- `notifications`: `{ unroutedRules: string[], openAlertsUnrouted: number }`. A rule is **unrouted** when it is enabled
  and none of its actions resolves to an existing connector, where a placeholder (`DeliveryService.PLACEHOLDERS`)
  resolves to `OCI_DEFAULT_CONNECTOR` when that is set and exists, else to a connector of the same id if one exists.
  Unlike `Bootstrap.warnAboutNotificationRouting`, demo mode is not skipped and a rule with one working action is
  routed. `openAlertsUnrouted` = open alerts (definition below) of unrouted rules. Out of scope: connectors that exist
  but keep failing (dead letters), a later check.
- **"Open" everywhere** = the API status `OPEN` (engine DETECTED, PENDING, NOTIFIED), as `totals.openAlerts` and the
  alert list filter `status=OPEN` already use; ACKNOWLEDGED and INVESTIGATING alerts have an owner and are not "to
  handle now". The count on the overview, the "→ all open alerts" link and `openAlertsUnrouted` use this one set.
- `alerts.attention[]`: the 5 open alerts of highest severity, most recent first within a severity; today's console
  only sees the latest alerts of any status, so with 1,385 open alerts the most severe ones can be missed.
- `alerts.last24h`: count and split by rule of alerts created in the last 24 hours.
- The sanctions status is read from the existing `GET /api/v1/labels/sanctions-lists`.
- Each section obeys the existing permission model and is listed in `omittedSections` when the caller cannot read it:
  `notifications` mirrors the rules and connectors endpoints (`rules:read` and `webhooks:read`), `coverage.gaps` and
  `lastIndexedBlockTime` the networks data (`wallet:read`, like the dashboard), `alerts.*` `alerts:read`.

### 4. Overview page (`features/overview/`)

```
┌ ChainSentinel ───────────────────────────────── [FR | EN] ┐
│ Is monitoring reliable?   Partly                           │
│  ⚠ 1,385 open alerts reach nobody            → Settings    │
│  ⚠ 29,640 TRON blocks never analysed (18–19 Nov, ~25 h)    │
│  ⚠ Ethereum 1 h 20 min behind · TRON 27 min                │
│  ✓ OFAC SDN list of 19 Nov in force (479 addresses)        │
├──────────────────────────────────┬─────────────────────────┤
│ Handle now                       │ Last 24 hours           │
│  verdict (risk, sentence,        │ 135 alerts              │
│  supporting signals, coverage)   │ split by rule           │
│  … at most 5                     │ Networks: 1 line each   │
│  → all 1,385 open alerts         │                         │
└──────────────────────────────────┴─────────────────────────┘
```

- **Band verdict**: *Yes* when every check passes; *No* when a network is unhealthy or its delay is unknown, or when no
  sanctions list is in force; *Partly* otherwise (gaps, delay above threshold, unrouted alerts, stale list). Delay
  threshold: 15 min (configurable constant), matching the supervision suggested in `docs/ops/MIGRATION_SERVEUR.md`
  (≈ 50 Ethereum blocks).
- **Order of band lines**: failures first (red), then warnings (amber), then passes (green, collapsed to one line when
  more than two pass).
- **Handle now**: verdict panel per alert (existing `oci-verdict`), sentence from §5, supporting lines = recent signals
  of the same wallet (existing behaviour), coverage = network coverage + sanctions sentence (existing). Each supporting
  line is written in the console from structured parts: the signal **type's name** from the dictionary (the
  engine's set of signal types is fixed, `SignalType`), its level of proof and its confidence; the engine's English
  summary moves into the line's expandable detail ("engine's wording"). An unknown signal type shows the engine's
  title. Empty states
  keep `emptyState` semantics (open alerts hidden vs none open vs no permission).
- **Side column**: last 24 hours (count, split by rule as a labelled bar with numbers), one line per enabled network
  (name, health, delay).
- Removed from the overview (still available in their screens): recent alerts list, risk distribution, alerts by
  status, networks table, 24 h activity chart, recent signals table, the six counters, the live strip (its role, "is
  it running", is now the band).

### 5. Verdict and alert sentences (option C)

- `shared/alert-sentence.ts`: `alertSentence(alert, lang)` builds one sentence per shipped rule from
  `alert.matchedEvent` fields (amount and token, counterparty, wallet balance, occurrences, `signal.type`,
  `signal.listed_category`, `freeze.*`), e.g. wallet-draining: FR "Portefeuille vidé : 248,4 ETH sur 248,4 envoyés à
  une seule adresse", EN "Wallet drained: 248.4 ETH of 248.4 sent to a single address".
- Same facts, same hedging in both languages; no fact the matched event does not carry.
- Fallback: unknown rule or missing field → the engine's `summary` / `evidenceSummary` as written (English), never an
  invented sentence.
- Rule display names for the shipped rules come from the dictionary; a user-created rule keeps its own name.
- Tests per rule, both languages, plus the fallback, on a fixture of real matched-event fields: Claude extracts, read
  only, the `subject_fields` of a few production alerts per rule (public on-chain data: addresses, amounts, tokens,
  hashes) into `frontend/src/app/shared/testing/alert-fields.fixture.ts` before the sentences are written, so the
  sentence builder relies on keys the rules really fill.

## Decided by the maintainer

- Bilingual EN/FR console; overview first, other screens later.
- Option C for engine sentences.
- Overview direction A.
- The proposal played back on 2026-11-19 (band content, at most 5 verdicts, removed blocks, language default,
  scope) was answered "go".

## Claude's calls (stated, correctable)

- In-house typed dictionaries, no new dependency.
- Default language from the browser, remembered locally.
- Delay threshold 15 min; band verdict rules in §4.
- Only gaps detected in the last 7 days affect the band (older ones in a history link).
- "Open" = API status `OPEN` only, everywhere on the overview.
- "Unrouted" definition in §3 (no dead-letter check yet).
- Signal lines under a verdict: type name, level and confidence in the console's language; engine wording in details.
- Real-alert fixture extracted by Claude from production (read-only) for the sentence tests.
- Dashboard additions in §3 rather than several new endpoints (one call, one permission model).
- The live strip is removed: the band replaces it.

## Left to the builder

- Exact key naming and file split of the dictionaries; pipe vs function in templates.
- Visual details within the existing design system (tokens, typography, verdict panel).
- How the ingestion path records `lastIndexedBlockTime` (in-memory per network is enough; no schema change).
- Mock API (`core/mock`) updates so the console still runs without a backend.
