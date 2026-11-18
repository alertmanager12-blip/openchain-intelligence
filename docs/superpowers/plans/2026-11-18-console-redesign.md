# Console redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the console a prospect-ready look (clear base, level of proof on every finding, honest live strip) through new foundations and five finished demo screens.

**Architecture:** Foundations first (tokens in `src/styles.scss`, light default theme, shared components in `src/app/shared/`), then each demo screen consumes them. Pure functions carry the wording and the mapping rules so that they are unit-tested; components stay thin.

**Tech Stack:** Angular (standalone components, signals), Angular Material, SCSS tokens, Vitest through `npx ng test --watch=false`, Cytoscape (graph).

**Spec:** `docs/superpowers/specs/2026-11-18-console-redesign-design.md`

## Global Constraints

- Fonts: IBM Plex Sans (UI), IBM Plex Mono (addresses, hashes, YAML) only; no new font package (ADR-0012).
- Light tokens: canvas `#FFFFFF`, band `#F2F6F7`, line `#DCE5EA`, ink `#0B2545`, ink-2 `#4D6177`, brand `#0A7381`, confirmed `#1E7457`, inferred text `#8A5D00` / rule `#B7791F`.
- Five level words, exactly: "Observed", "Inferred", "Confirmed", "Predicted", "Simulated". `ANOMALY` and `RISK_SIGNAL` → Inferred; `CONFIRMED_BY_SOURCE` → Confirmed only for a label (the listing itself), Inferred for anything derived from it. The original `evidenceLevel` stays visible as secondary text.
- Sentence case everywhere; no uppercase labels.
- WCAG 2.2 AA contrast for every text/background token pair, both themes; visible focus; `prefers-reduced-motion` respected; no animation in the live strip beyond its data updating.
- No new endpoint, no new dependency; the API as it is.
- Light is the default theme for a new visitor; a stored choice wins; dark stays available.
- Keyboard shortcuts, command palette and navigation keys unchanged.
- Coverage sentence always present on a verdict panel; never "nothing found" without complete coverage.
- Correction to the spec, decision 3: the API gives no "since" date for a network's coverage; the overview sentence names the network, its indexer block and lag, and says earlier activity is not covered.

## Review Focus

1. A network whose provider fails (`healthy: false`, `indexerLag: null`): the live strip says "unreachable", never "live" or "0 behind". → test in Task 4.
2. No open alert at all: the overview shows an invitation to add a wallet to the watchlist, not an empty panel. → test in Task 6.
3. A `CONFIRMED_BY_SOURCE` exposure signal (derived from a confirmed label): shown as Inferred, never Confirmed. → test in Task 2.
4. Dark theme: every new component readable (token pairs contrast-checked in both themes). → test in Task 1.
5. Very long EVM/TRON addresses in evidence lines on a narrow screen: wrap, no horizontal page scroll. → covered by `.mono` word-break in Task 2 and the screenshot check in Task 10.

---

### Task 1: Tokens, contrast and light default

**Files:**
- Modify: `frontend/src/styles.scss` (light and dark token blocks)
- Modify: `frontend/src/app/core/theme.service.ts` (default `'light'`)
- Create: `frontend/src/app/shared/contrast.ts`, Test: `frontend/src/app/shared/contrast.spec.ts`

**Interfaces:**
- Produces: CSS tokens `--canvas --band --line --ink --ink-2 --brand --lvl-observed --lvl-inferred --lvl-inferred-rule --lvl-confirmed --lvl-predicted --lvl-simulated` (existing names kept as aliases where screens use them, e.g. `--bg`, `--surface`, `--accent`); `contrastRatio(hexA: string, hexB: string): number`; `TOKENS: { light: Record<string,string>; dark: Record<string,string> }` exported from `contrast.ts` and kept equal to `styles.scss` values.

- [ ] Step 1: write `contrast.spec.ts`: `contrastRatio('#000000','#FFFFFF')` ≈ 21; for each theme, every text token (`ink`, `ink-2`, `brand`, `lvl-*`) against `canvas` and `band` ≥ 4.5.
- [ ] Step 2: run `npx ng test --watch=false`; expect failure (module missing).
- [ ] Step 3: implement `contrast.ts` (WCAG relative luminance) and the token values; set dark values (navy-based, not near-black) so the test passes; add `theme.service.ts` default `'light'` (stored choice still wins).
- [ ] Step 4: add to `theme.service` spec (or a new `theme.service.spec.ts`): no stored value → `effective()` is `'light'`; stored `'dark'` → `'dark'`.
- [ ] Step 5: run tests and `npx ng build --configuration production`; expect pass.
- [ ] Step 6: commit `feat(console): jetons clair/sombre contrastés, thème clair par défaut`.

### Task 2: Level mapping and the evidence line

**Files:**
- Create: `frontend/src/app/shared/levels.ts`, `frontend/src/app/shared/evidence-line.ts`
- Test: `frontend/src/app/shared/levels.spec.ts`, `frontend/src/app/shared/evidence-line.spec.ts`

**Interfaces:**
- Produces: `type Level = 'OBSERVED' | 'INFERRED' | 'CONFIRMED' | 'PREDICTED' | 'SIMULATED'`; `levelWord(l: Level): string`; `levelOfSignal(e: EvidenceLevel, isLabel: boolean): Level`; component `oci-evidence-line` with inputs `level: Level`, `text: string`, `detail?: string | null` (secondary text, e.g. original evidence level), `cite?: string | null`, output `citeClick: string`.

- [ ] Step 1: `levels.spec.ts`: `levelOfSignal('ANOMALY', false)` → `'INFERRED'`; `levelOfSignal('RISK_SIGNAL', false)` → `'INFERRED'`; `levelOfSignal('CONFIRMED_BY_SOURCE', true)` → `'CONFIRMED'`; `levelOfSignal('CONFIRMED_BY_SOURCE', false)` → `'INFERRED'` (Review Focus 3); `levelWord` returns the five exact words.
- [ ] Step 2: `evidence-line.spec.ts` (TestBed): renders the level word, the text, the detail when given; `cite` renders a button that emits `citeClick` with the id.
- [ ] Step 3: run tests; expect failure.
- [ ] Step 4: implement; the line is a left rule coloured by `--lvl-*` (Simulated: dashed rule), level word first, text, optional detail in ink-2, optional cite button; addresses inside text use `.mono` with `word-break: break-all`.
- [ ] Step 5: run tests; expect pass. Commit `feat(console): niveaux de preuve et ligne de preuve partagée`.

### Task 3: Coverage wording

**Files:**
- Create: `frontend/src/app/shared/coverage-text.ts`, Test: `frontend/src/app/shared/coverage-text.spec.ts`

**Interfaces:**
- Produces: `networkCoverageSentence(s: NetworkStatus): string`.

- [ ] Step 1: tests, exact copy:
  - healthy, `lastIndexedBlock` 88105040, `indexerLag` 3, network tron → `"Based on the transfers this instance indexes on TRON (block 88,105,040, 3 blocks behind). Activity before monitoring started is not covered."`
  - `indexerLag` null → `"Based on the transfers this instance indexes on TRON; the chain head is unknown right now. Activity before monitoring started is not covered."`
  - never contains "no " + "found" / "nothing".
- [ ] Step 2: run; fail. Step 3: implement with `netLabel()` from `shared/format.ts` and `toLocaleString('en-US')`. Step 4: run; pass. Commit `feat(console): phrase de couverture par réseau`.

### Task 4: Live strip

**Files:**
- Create: `frontend/src/app/features/overview/live-strip.ts`, Test: `frontend/src/app/features/overview/live-strip.spec.ts`

**Interfaces:**
- Consumes: `NetworkStatus`, `Alert` (`blockNumber`, `severity`, `network`).
- Produces: `stripState(s: NetworkStatus): { state: 'live' | 'behind' | 'unreachable'; label: string }`; `ticks(head: number, alerts: Alert[], span: number): { pos: number; severity: Severity }[]` (pos in 0..1, alerts outside `[head-span, head]` dropped); component `oci-live-strip` inputs `networks: NetworkStatus[]`, `alerts: Alert[]`.

- [ ] Step 1: tests: lag 0..20 → `live`; lag > 20 → `behind` with label `"Behind (N blocks)"`; `healthy: false` or `indexerLag` null → `unreachable` (Review Focus 1); `ticks(1000, [{blockNumber: 990}, {blockNumber: 10}, {blockNumber: null}], 100)` → one tick at pos 0.9.
- [ ] Step 2: run; fail. Step 3: implement (no CSS animation; state colour from tokens; per network: name, last indexed block, state label). Step 4: run; pass. Commit `feat(console): bandeau de surveillance en direct, honnête`.

### Task 5: Verdict panel

**Files:**
- Create: `frontend/src/app/shared/verdict-panel.ts`, Test: `frontend/src/app/shared/verdict-panel.spec.ts`

**Interfaces:**
- Consumes: `oci-evidence-line` (Task 2), `oci-risk` (existing).
- Produces: component `oci-verdict` inputs `sentence: string` (required), `risk: RiskLevel | null`, `lines: { level: Level; text: string; detail?: string | null }[]`, `coverage: string` (required), `limit?: string | null` (e.g. "This is not proof of intent.").

- [ ] Step 1: tests: renders sentence, risk pill, each line, and the coverage text; an empty `coverage` throws at render (required input) or shows a visible "Coverage unknown" text — choose the latter and test it.
- [ ] Step 2–4: fail, implement (band background, radius 10 px, sentence 17 px semibold), pass. Commit `feat(console): panneau de verdict`.

### Task 6: Shell and overview

**Files:**
- Modify: `frontend/src/app/layout/shell.ts`, `frontend/src/app/layout/navigation.ts` (styles only, items unchanged)
- Modify: `frontend/src/app/features/overview/overview.ts`, `overview.html`, `overview.scss`
- Create: `frontend/src/app/features/overview/attention.ts`, Test: `attention.spec.ts`

**Interfaces:**
- Consumes: Tasks 2–5.
- Produces: `attention(d: Dashboard, max = 3): { sentence: string; risk: RiskLevel | null; lines: {...}[]; network: NetworkId }[]` built from open recent alerts, most severe first, each line from the alert's summary and its linked signals (`levelOfSignal`).

- [ ] Step 1: `attention.spec.ts`: picks the 3 most severe open alerts; an empty `recent` → `[]`; `alerts: null` (omitted section) → `[]`.
- [ ] Step 2: overview test (existing `pages.spec.ts` style): with no open alert the page shows the text "Add a wallet to the watchlist to see what needs your attention here." and a link to `/watchlist` (Review Focus 2).
- [ ] Step 3: fail; implement: live strip on top; heading "What needs your attention"; verdict panels with `networkCoverageSentence`; three figures (open alerts, chains watched = enabled networks, wallets watched = `totals.walletsTracked`); existing sections below kept but restyled; shell navigation on band with brand rule on the active item.
- [ ] Step 4: run all tests (`shell.spec.ts`, `command-palette.spec.ts` unchanged and passing); build. Commit `feat(console): coque et vue d'ensemble redessinées`.

### Task 7: Wallet page

**Files:**
- Modify: `frontend/src/app/features/wallet/wallet.ts`, `wallet.html`, `wallet.scss`
- Create: `frontend/src/app/features/wallet/wallet-verdict.ts`, Test: `wallet-verdict.spec.ts`

**Interfaces:**
- Produces: `walletVerdict(w: WalletAnalysis): { sentence; risk; lines; coverage }` — sentence from the score and the strongest signal type, lines from labels (label → Confirmed when `CONFIRMED_BY_SOURCE`) and signals (Inferred), coverage from the existing wallet coverage text.

- [ ] Step 1: tests: a wallet with a sanctions label and one exposure signal → one Confirmed line (the label) and one Inferred line (the exposure); a wallet with nothing → sentence "No risk indicator in the data covered." with the coverage text.
- [ ] Step 2–4: fail, implement (verdict panel first, existing sections below), pass; build. Commit `feat(console): page portefeuille avec verdict et preuves`.

### Task 8: Investigation report and alerts

**Files:**
- Modify: `frontend/src/app/features/investigations/investigations.ts`, `frontend/src/app/features/alerts/*.ts` (detail pane only)
- Test: extend `investigations.spec.ts`; `frontend/src/app/features/alerts/alert-lines.spec.ts`

**Interfaces:**
- Consumes: `oci-evidence-line`; report sentences carry `level` (`OBSERVED|INFERRED|CONFIRMED`) and `assertionId`.
- Produces: `alertLines(a: Alert): { level: Level; text: string; detail: string }[]` (from its signals' `evidenceLevel`).

- [ ] Step 1: tests: a report sentence of level `INFERRED` renders as an evidence line with "Inferred" and a cite button with its assertion id; `alertLines` maps `RISK_SIGNAL` to Inferred with detail "risk signal".
- [ ] Step 2–4: fail, implement (report sentences, review buttons and AI summary kept, coverage section first), pass. Commit `feat(console): rapport d'investigation et alertes en lignes de preuve`.

### Task 9: Graph restyle

**Files:**
- Modify: `frontend/src/app/features/investigation/graph-canvas.ts` (stylesheet colours via tokens), `investigation.ts` (inspector panel styles)

- [ ] Step 1: no logic change; keep the zoom controls and keys of 2026-11-18.
- [ ] Step 2: run the headless interaction check (`scratchpad/cdp/graph-check.mjs` against `ng serve --port 4300`) in both themes; expect all interactions `true`.
- [ ] Step 3: commit `style(console): graphe aux nouvelles couleurs`.

### Task 10: Visual verification

- [ ] Step 1: `npx ng serve --port 4300` (mock data); headless Chrome screenshots of overview, wallet, graph, investigations, alerts at 1440 px and 390 px, light and dark; review each for overlap, horizontal scroll (Review Focus 5) and level words.
- [ ] Step 2: run all frontend tests and the production build; expect pass.
- [ ] Step 3: update `CHANGELOG.md`; commit `docs: refonte de la console (vitrine)`.
