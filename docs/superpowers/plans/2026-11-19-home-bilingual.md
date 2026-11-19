# Bilingual console and "watch post" overview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the console bilingual EN/FR (shell, overview and the shared pieces it shows) and replace the overview with a reliability band plus "handle now" verdicts.

**Architecture:** The dashboard endpoint gains four facts it does not expose today (last indexed block time, indexing gaps, unrouted notifications, the most severe open alerts and a 24 h split by rule). The console gets a signal-based `I18nService` with typed `en`/`fr` dictionaries and `Intl` formatting; the overview is rebuilt around a pure `reliability()` function and per-rule `alertSentence()` builders, both language-aware.

**Tech Stack:** Spring Boot 4 / Java 21 (Maven), PostgreSQL; Angular 22 (signals, OnPush, standalone), Vitest.

**Spec:** `docs/superpowers/specs/2026-11-19-home-bilingual-design.md`

## Global Constraints

- Languages: exactly `en` and `fr`; default = stored choice (localStorage key `oci.lang`, read/written in try/catch) else `navigator.language` starting with `fr` → `fr`, else `en`; switch without reload; `<html lang>` follows.
- No new runtime dependency (frontend or backend); formatting via `Intl` only.
- A key present in `en` and missing in `fr` must fail compilation (fr typed against en).
- "Open" = API status `OPEN` = engine `DETECTED`, `PENDING`, `NOTIFIED` (`AlertFacade.apiStatus`), everywhere on the overview.
- Gaps affecting the band: detected in the last **7 days**; delay threshold **15 min**; sanctions list stale after **72 h** (state `STALE` from the API).
- Band verdict: *Yes* only if every check positively passed; *No* if a network is unhealthy, its delay unknown, or no sanctions list; *Partly* otherwise. A fact the caller cannot read is "not visible with your role", never a pass.
- New dashboard fields are optional/additive; sections the caller cannot read go to `omittedSections`: `notifications` needs `rules:read` and `webhooks:read`; `alerts.*` needs `alerts:read`; `coverage` and `networks[].lastIndexedBlockTime` need only the dashboard's `wallet:read`.
- Translated sentences carry the same facts and the same hedging as the English ones; unknown rule or missing field → the engine's own `summary`/`evidenceSummary`, never an invented sentence.
- Light/dark themes, 16 px gutter at phone width, keyboard and screen-reader access as today.
- Commits: no Claude attribution lines (maintainer rule); French commit subjects in the repo's style (`feat(...)`, `fix(...)`).
- Tests: backend `mvn -q -pl <module> -am test -Dtest=<Class>` (add `-Dsurefire.failIfNoSpecifiedTests=false`); frontend `cd frontend && npx vitest run <path>` for one file, `npx ng test --watch=false` for all.

## Review Focus

1. A network whose head is unknown (provider failing): delay must read "unknown" and the band *No*, not a delay of 0 → test in Task 9.
2. The caller lacks `alerts:read` or `rules:read`/`webhooks:read`: band lines say "not visible with your role" and the verdict cannot be *Yes* → test in Task 9.
3. `lastIndexedBlockTime` null right after a restart: delay falls back to blocks, never a guessed time, and is not counted as passing → test in Task 9.
4. A gap older than 7 days only: band stays *Yes* if all else passes, and the gap is reachable via history → test in Task 9.
5. Language switched while the overview is open: every visible string and number re-renders without reload (OnPush) → test in Task 10.

---

### Task 1: Last indexed block time per network

**Files:**
- Modify: `oci-blockchain/src/main/java/io/openchain/blockchain/indexer/BlockIndexer.java:43,145` (`PollResult`)
- Modify: `oci-app/src/main/java/io/openchain/app/chain/ChainIngestionService.java:80-110,330-335` (`NetworkRuntime`)
- Modify: `oci-app/src/main/java/io/openchain/app/api/Dto.java:93` (`NetworkDto`), `oci-app/src/main/java/io/openchain/app/api/InvestigationController.java:185,192`
- Test: `oci-blockchain/src/test/java/io/openchain/blockchain/indexer/BlockIndexerTest.java`, `oci-app/src/test/java/io/openchain/app/DashboardAndRoutingApiTest.java`

**Interfaces:**
- Produces: `PollResult(long head, long lastBlock, int blocksIndexed, int eventsEmitted, int eventsRetracted, int reorgs, Instant lastBlockTime)` (+ the old 6-arg constructor delegating with `null`); `NetworkRuntime.lastIndexedBlockTime(): Instant` (nullable); `NetworkDto.lastIndexedBlockTime: Instant` (nullable, JSON `lastIndexedBlockTime`, last component).

- [ ] **Step 1: Failing tests.** `BlockIndexerTest.pollReportsTheTimestampOfTheLastIndexedBlock`: index blocks 1..3 whose `BlockRef.timestamp` are T0+1s..T0+3s → `r.lastBlockTime()` equals T0+3s; a poll with an empty window returns `null`. `DashboardAndRoutingApiTest.networksCarryTheLastIndexedBlockTime`: after the demo has indexed, `$.networks[0].lastIndexedBlockTime` is an ISO instant.
- [ ] **Step 2: Run, expect FAIL** (no such accessor / JSON path missing).
- [ ] **Step 3: Implement.** `lastBlockTime` = `window.isEmpty() ? null : window.peekLast().block().timestamp()`; `pollLocked` stores it in a `volatile Instant lastIndexedBlockTime`; both `NetworkDto` constructions pass it (`null` for not-enabled networks).
- [ ] **Step 4: Run both tests and the module suites** `mvn -q -pl oci-blockchain,oci-app -am test` → PASS.
- [ ] **Step 5: Commit** `feat(api): heure du dernier bloc indexé par réseau`.

### Task 2: Alert repository queries for the overview

**Files:**
- Modify: `oci-alerting/src/main/java/io/openchain/alerting/alert/AlertRepository.java`
- Modify: `oci-app/src/main/java/io/openchain/app/persistence/JdbcAlertRepository.java`
- Test: `oci-alerting/src/test/java/io/openchain/alerting/alert/AlertRepositoryDefaultsTest.java` (create), `oci-app/src/test/java/io/openchain/app/persistence/JdbcRepositoriesPersistenceTest.java`

**Interfaces:**
- Produces:
  - `default List<Alert> findTopOpen(Set<AlertStatus> open, int limit)`: alerts whose status is in `open`, ordered severity CRITICAL→INFO, then `createdAt` desc, then id desc; at most `limit`.
  - `record RuleOpenAndRecent(String ruleId, long open, long createdSince)` and `default List<RuleOpenAndRecent> openAndRecentByRule(Set<AlertStatus> open, Instant since)`: per rule, alerts with status in `open`, and alerts created at or after `since` (any status); rules with both 0 omitted.
  - JDBC overrides with one SQL query each (severity order via `case severity when 'CRITICAL' then 0 ... end`).

- [ ] **Step 1: Failing tests.** Defaults test on an in-memory repo with 6 alerts (2 HIGH open old/new, 1 CRITICAL open, 1 CRITICAL RESOLVED, 1 MEDIUM ACKNOWLEDGED, 1 LOW open): `findTopOpen(OPEN, 3)` → [CRITICAL open, HIGH new, HIGH old]; `openAndRecentByRule` counts per rule as constructed. Same two assertions in `JdbcRepositoriesPersistenceTest.topOpenAndPerRuleCountsInSql`.
- [ ] **Step 2: Run, expect FAIL** (methods missing).
- [ ] **Step 3: Implement** defaults (stream over `findAll()`) and the JDBC overrides.
- [ ] **Step 4: Run** both tests → PASS; `mvn -q -pl oci-alerting,oci-app -am test` → PASS.
- [ ] **Step 5: Commit** `feat(alerts): alertes ouvertes les plus graves et décompte par règle`.

### Task 3: Which rules notify nobody

**Files:**
- Create: `oci-app/src/main/java/io/openchain/app/alerts/NotificationRouting.java`
- Test: `oci-app/src/test/java/io/openchain/app/alerts/NotificationRoutingTest.java`

**Interfaces:**
- Consumes: `RuleRegistry.list()` (`StoredRule.enabled()`, `current().rule().actions()[].connectorId()`), `ConnectorRegistry.find(String)`, `DeliveryService.PLACEHOLDERS`, `OciProperties.getNotifications().getDefaultConnector()`.
- Produces: Spring bean `NotificationRouting` with `List<String> unroutedRules()` (sorted rule ids). An action resolves when its connector id exists; a placeholder id resolves to the default connector when that is set and exists, else to a connector of the same id. A rule is unrouted when enabled and no action resolves. Demo mode is not skipped.

- [ ] **Step 1: Failing tests** `NotificationRoutingTest`: (a) placeholder only, no default → unrouted; (b) placeholder only, default set to an existing connector → routed; (c) default set to a missing connector → unrouted; (d) one placeholder action + one action to an existing connector → routed; (e) disabled rule → not listed.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** `NotificationRouting`.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(alerts): règles dont les alertes ne parviennent à personne`.

### Task 4: Dashboard sections for the overview

**Files:**
- Modify: `oci-app/src/main/java/io/openchain/app/api/DashboardController.java`
- Test: `oci-app/src/test/java/io/openchain/app/DashboardAndRoutingApiTest.java`

**Interfaces:**
- Consumes: Task 1 `NetworkDto.lastIndexedBlockTime`; Task 2 `findTopOpen`, `openAndRecentByRule`; Task 3 `unroutedRules()`; the `IndexingCoverage.Store` bean, `List<IndexingCoverage.Gap> gaps(Network n)` (`Gap(network, fromBlock, toBlock, cause, detectedAt)`), called for each enabled network.
- Produces (JSON, all additive):
  - `coverage: { gaps: [{ network, fromBlock, toBlock, blocks, cause, detectedAt }] }` (all recorded gaps of enabled networks, newest `detectedAt` first);
  - `notifications: { unroutedRules: string[], openAlertsUnrouted: number }` or `null` + `"notifications (needs rules:read and webhooks:read)"` in `omittedSections`;
  - `alerts.attention: AlertDto[]` = `findTopOpen(AlertFacade OPEN set, 5)`;
  - `alerts.last24h: { total: number, byRule: [{ ruleId, ruleName, count }] }` (count desc).

- [ ] **Step 1: Failing tests** in `DashboardAndRoutingApiTest`: admin key → the four paths exist with the right shapes; with a demo rule routed to the placeholder and no default connector, `notifications.unroutedRules` contains it and `openAlertsUnrouted` equals its open alerts; a key without `rules:read` → `notifications` null and listed in `omittedSections`; `alerts.attention` sorted by severity.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** in `DashboardController` (inject `NotificationRouting` and the coverage store; `ruleName` from `RuleRegistry`).
- [ ] **Step 4: Run** the test class, then `mvn -q -pl oci-app -am test` → PASS.
- [ ] **Step 5: Commit** `feat(api): tableau de bord — trous, notifications, alertes à traiter, 24 h par règle`.

### Task 5: Translation foundation

**Files:**
- Create: `frontend/src/app/core/i18n/i18n.service.ts`, `frontend/src/app/core/i18n/en.ts`, `frontend/src/app/core/i18n/fr.ts`, `frontend/src/app/core/i18n/format.ts`
- Test: `frontend/src/app/core/i18n/i18n.service.spec.ts`, `frontend/src/app/core/i18n/format.spec.ts`

**Interfaces:**
- Produces:
  - `type Lang = 'en' | 'fr'`; `en` = `const` object of entries; `type Key = keyof typeof en`; `fr: { [K in Key]: Entry }` where `type Entry = string | ((p: Params, lang: Lang) => string)`, `type Params = Record<string, string | number>`.
  - `@Injectable({ providedIn: 'root' }) class I18nService { readonly lang: Signal<Lang>; set(l: Lang): void; t(key: Key, params?: Params): string }` — string entries replace `{name}` with `params.name` (numbers formatted with `fmtNumber`).
  - `format.ts`: `fmtNumber(n: number, lang: Lang, maxFraction = 2): string`, `fmtDate(iso: string, lang: Lang): string` (day month year), `fmtRelative(iso: string, lang: Lang, now = Date.now()): string` (`Intl.RelativeTimeFormat`, numeric 'auto'), `fmtDuration(ms: number, lang: Lang): string` ("1 h 20 min", "27 min", "45 s"; same output in both languages), `plural(n: number, lang: Lang, forms: { one: string; other: string }): string` (`Intl.PluralRules`; French counts 0 and 1 as `one`).

- [ ] **Step 1: Failing tests.** `i18n.service.spec`: default from `navigator.language = 'fr-FR'` → `fr`; stored `oci.lang = 'en'` wins; `set('en')` stores it and sets `document.documentElement.lang`; `t('overview.title')` changes with the language; a throwing `localStorage` does not break construction. A dictionary test: every `en` key has a non-empty `fr` entry, and no rendered `fr`/`en` string still contains `{`. `format.spec`: `fmtNumber(1385,'fr')` → `'1 385'` (narrow no-break space U+202F), `'en'` → `'1,385'`; `fmtDuration(4_800_000,'fr')` → `'1 h 20 min'`; `plural(0,'fr',…)` → one form, `plural(0,'en',…)` → other.
- [ ] **Step 2: Run** `npx vitest run src/app/core/i18n` → FAIL.
- [ ] **Step 3: Implement** the service (signal initialised once; storage in try/catch), dictionaries with the `shell.*` and `overview.*` keys needed by Tasks 6–10 (later tasks add their keys to both files), and `format.ts`.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(console): socle de traduction EN/FR`.

### Task 6: Shell in both languages

**Files:**
- Modify: `frontend/src/app/app.ts` (header popover near the theme toggle, `:94`), `frontend/src/app/layout/navigation.ts`, `frontend/src/app/layout/command-palette.ts`, `frontend/src/app/layout/shortcut-help.ts`, `frontend/src/app/shared/page-header.ts`, `frontend/src/app/core/i18n/en.ts`, `fr.ts`
- Test: `frontend/src/app/layout/shell.spec.ts`, `frontend/src/app/layout/command-palette.spec.ts`

**Interfaces:**
- Consumes: Task 5 `I18nService`.
- Produces: `NavItem.labelKey: Key` and `NavGroup.titleKey: Key` replacing `label`/`title` strings (keywords stay English plus French synonyms appended, e.g. `'dashboard home health accueil santé'`); a visible `FR | EN` switch (two toggle buttons, `aria-pressed`, group label "Language"/"Langue") in the top bar, present at phone width.

- [ ] **Step 1: Failing tests:** in French the rail shows "Vue d'ensemble", "Alertes", "Réglages"; clicking `EN` switches them to "Overview", "Alerts", "Settings" without navigation; the palette finds "Alertes" by typing "alertes"; the switch has `aria-pressed` on the active language.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the two specs, then `npx ng test --watch=false` → PASS.
- [ ] **Step 5: Commit** `feat(console): cadre de la console en français et en anglais`.

### Task 7: Shared overview pieces in both languages

**Files:**
- Modify: `frontend/src/app/shared/levels.ts`, `shared/verdict-panel.ts`, `shared/evidence-line.ts`, `shared/coverage-text.ts`, `shared/sanctions-text.ts`, `shared/explain.ts` (signal type names `:9-23`, category names `:27-29`), `shared/format.ts` (`sentence()` of severities/statuses), `core/i18n/en.ts`, `fr.ts`
- Test: the existing specs of each file (`levels.spec.ts`, `verdict-panel.spec.ts`, `evidence-line.spec.ts`, `coverage-text.spec.ts`, `sanctions-text.spec.ts`)

**Interfaces:**
- Consumes: Task 5.
- Produces (pure functions gain a trailing `lang: Lang = 'en'` parameter, existing callers unchanged): `levelWord(l, lang)`, `evidenceDetail(e, lang)`, `networkCoverageSentence(s, lang)`, `sanctionsSentence(l, lang)`, `signalTypeName(type: string, lang: Lang): string | null` (null for an unknown type), `severityName(s: Severity, lang)`, `statusName(s: AlertStatus, lang)`. Components read `I18nService.lang()` for their fixed words ("Coverage", "Limit", "Show detail"…).

- [ ] **Step 1: Failing tests:** for each function, the current English output unchanged with `lang` omitted, and the French output for one case each, e.g. for a CURRENT list `{ source: 'OFAC SDN', issued: '2026-11-19', tron: 351, ethereum: 128 }`, `sanctionsSentence(l,'fr')` → `'Liste de sanctions : OFAC SDN du 19 nov. 2026 (351 adresses TRON et 128 adresses Ethereum).'`; `sanctionsSentence(undefined,'fr')` → `'État de la liste de sanctions inconnu.'`; `signalTypeName('INDIRECT_EXPOSURE','fr')` → `'Exposition indirecte à une adresse listée'`; `networkCoverageSentence(unknownHead,'fr')` says the head is unknown ("tête de chaîne inconnue").
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the specs and the full suite → PASS.
- [ ] **Step 5: Commit** `feat(console): verdicts, niveaux de preuve et couverture en français`.

### Task 8: Alert sentences from structured fields

**Files:**
- Create: `frontend/src/app/shared/testing/alert-fields.fixture.ts`, `frontend/src/app/shared/alert-sentence.ts`
- Modify: `frontend/src/app/core/i18n/en.ts`, `fr.ts`
- Test: `frontend/src/app/shared/alert-sentence.spec.ts`

**Interfaces:**
- Consumes: Task 5 (`fmtNumber`, `plural`), Task 7 (`signalTypeName`, category names).
- Produces: `alertSentence(a: Pick<Alert, 'ruleId' | 'ruleName' | 'summary' | 'evidenceSummary' | 'matchedEvent' | 'occurrenceCount'>, lang: Lang): string` and `ruleDisplayName(ruleId: string, fallback: string, lang: Lang): string` (shipped rules translated, others keep `fallback`).

- [ ] **Step 1: Build the fixture (Claude, read-only on production):** for each of the 10 shipped rules present in the database, the `subject_fields` of up to 3 alerts (`select rule_id, subject_fields from alerts where rule_id = … order by first_seen desc limit 3`), written as `export const ALERT_FIELDS: Record<string, Record<string, unknown>[]>`. Only public on-chain values (addresses, hashes, amounts, tokens, block numbers); no comment, assignee or user data. Rules absent from production get no entry.
- [ ] **Step 2: Failing tests** per rule present in the fixture, both languages, asserting the facts (amount, token, counterparty short form, listed category), e.g. wallet-draining FR contains `'Portefeuille vidé'` and the amount formatted `'248,37 ETH'`; listed-address-exposure FR contains `'gelée par l\'émetteur'` and never `'sanction'`; sanctioned-exposure-signal FR contains `'sanctionnée'`; a fixture with the amount field removed → the engine's `evidenceSummary`; an unknown rule id → `summary`.
- [ ] **Step 3: Run, expect FAIL.**
- [ ] **Step 4: Implement** one small builder per rule reading only keys seen in the fixture; return the fallback when a required key is missing.
- [ ] **Step 5: Run** → PASS; full suite → PASS.
- [ ] **Step 6: Commit** `feat(console): phrases d'alerte en français et en anglais depuis les champs de l'alerte`.

### Task 9: Reliability band

**Files:**
- Modify: `frontend/src/app/core/models.ts` (`Dashboard`, `NetworkStatus`), `frontend/src/app/core/mock/mock-db.ts:550` (`dashboard()` returns the new fields)
- Create: `frontend/src/app/features/overview/reliability.ts`
- Test: `frontend/src/app/features/overview/reliability.spec.ts`

**Interfaces:**
- Consumes: Task 4 JSON (`coverage.gaps`, `notifications`, `alerts.attention`, `alerts.last24h`, `networks[].lastIndexedBlockTime`), Task 5 formatting, `SanctionsList` (existing), Task 7 sentences.
- Produces:
  - `type Check = 'gaps' | 'delay' | 'notifications' | 'sanctions' | 'health'`; `interface BandLine { check: Check; state: 'fail' | 'warn' | 'pass' | 'unknown'; text: string; link?: { path: string; query?: Record<string, string> } }`;
  - `reliability(d: Dashboard, sanctions: SanctionsList | undefined | null, lang: Lang, now = Date.now()): { verdict: 'yes' | 'partly' | 'no'; lines: BandLine[]; olderGaps: number }` — lines ordered fail, unknown, warn, pass; delay per enabled network = `now - lastIndexedBlockTime` (fallback to blocks text, state `unknown`, when null); threshold 15 min; gaps counted only when `detectedAt` is within 7 days (`olderGaps` = the rest); `sanctions === null` (request failed or not permitted) → `unknown`;
  - `BandComponent` (`oci-reliability-band`, input `d`, `sanctions`) rendering the verdict word, the lines, links, and collapsing passes to one line when more than two pass.

- [ ] **Step 1: Failing tests** (`reliability.spec.ts`), fixtures built from the 2026-11-19 production figures: all good → `yes`; the 19/11 case (gap of 29,640 TRON blocks detected yesterday, 1,385 unrouted, ETH 1 h 20 behind, list CURRENT) → `partly` with lines in the order gaps/notifications/delay/sanctions; unhealthy network → `no`; head unknown (`indexerLag: null`) → `no` and line `unknown` (Review Focus 1); `notifications: null` with `omittedSections` → line `unknown` "not visible with your role" and verdict not `yes` (Review Focus 2); `lastIndexedBlockTime: null` → blocks text, state `unknown` (Review Focus 3); only a 10-day-old gap → `yes`, `olderGaps: 1` (Review Focus 4); `sanctions.state === 'NONE'` → `no`; `STALE` → `partly`; French and English texts for the 19/11 case.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** models (optional fields), mock (values that exercise a `partly` band), `reliability()` and the component.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(overview): bandeau « ma surveillance est-elle fiable ? »`.

### Task 10: New overview page

**Files:**
- Modify: `frontend/src/app/features/overview/overview.ts`, `overview.html`, `overview.scss`, `attention.ts`
- Delete: `frontend/src/app/features/overview/live-strip.ts`, `live-strip.spec.ts`, `activity-chart.ts` (used only by the overview)
- Test: `frontend/src/app/features/overview/overview.spec.ts`, `attention.spec.ts`

**Interfaces:**
- Consumes: Tasks 5–9.
- Produces: `attention(d: Dashboard, lang: Lang, max = 5): Attention[]` built from `d.alerts.attention` (fallback to the old filtering of `d.alerts.recent` when the field is absent); each `Attention.sentence = alertSentence(a, lang)`; each supporting `VerdictLine.text = signalTypeName(s.type, lang) ?? s.title`, with the engine's `s.summary` in `detail` (spec §4). Layout per spec §4: band, then two columns "Handle now" (≤ 5 verdicts, "→ all N open alerts" link to `/alerts?status=OPEN`) and a side column (last 24 h total + labelled split by rule with numbers; one line per enabled network: name, health, delay). `emptyState` semantics kept.

- [ ] **Step 1: Failing tests** (`overview.spec.ts` with the mock backend): in French the page shows "Ma surveillance est-elle fiable ?", "À traiter maintenant", "24 dernières heures" and no element of the removed blocks (no live strip, no networks table, no recent-signals table); at most 5 verdicts; the open-alerts link targets `status=OPEN`; switching to EN re-renders headings, numbers (`1,385`) and verdict sentences without navigation (Review Focus 5); `attention.spec`: top-5 order taken from `alerts.attention`, signal line text is the translated type name with the engine summary as detail.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** and delete the unused files.
- [ ] **Step 4: Run** `npx ng test --watch=false` and `npm run build -- --configuration production` → PASS / build OK; check the page in the browser (mock backend) at 1280 px and 375 px, light and dark.
- [ ] **Step 5: Commit** `feat(overview): page d'accueil « poste de veille »`.

### Task 11: Whole-branch checks and docs

**Files:**
- Modify: `CHANGELOG.md` (Unreleased: Added bilingual console, Changed overview, Added dashboard fields), `docs/api/` page of `GET /dashboard` if it lists fields (`git grep -l "omittedSections" docs`)

- [ ] **Step 1:** `mvn -q -pl oci-app -am test` and `cd frontend && npx ng test --watch=false && npm run build -- --configuration production` → all PASS.
- [ ] **Step 2:** Update CHANGELOG and the API doc.
- [ ] **Step 3: Commit** `docs: console bilingue et nouvelle page d'accueil`.
