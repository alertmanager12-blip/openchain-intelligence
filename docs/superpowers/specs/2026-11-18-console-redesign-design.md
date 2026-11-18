# Console redesign: a showcase for prospects

- Date: 2026-11-18
- Status: proposed, awaiting the maintainer's approval
- Scope: `frontend/` (Angular console)

## Intent and why

The console is shown to **prospects and clients** (the maintainer's answer). It must, at first sight, look serious and
clear to a compliance officer or an executive, and make visible what sets ChainSentinel apart from Chainalysis or TRM:
every finding says **what is proven and what is inferred, and on which data**. Today the console is a capable analyst
tool with a sober design system (IBM Plex, teal accent, light and dark themes, ~80 CSS tokens, 19 screens, a command
palette), but nothing on the first screen tells that story.

Success: a prospect who sees the overview for ten seconds understands that the product watches chains live, finds
risk, and states its level of proof; the five demo screens look finished and consistent; nothing an analyst relies on
gets lost.

## Goals

1. A visual direction combining three mockups the maintainer reviewed (all three kept, "tout"):
   - **C, "clear bank"**: light, airy base, plain sentences first;
   - **A, "evidence file"**: the level of proof (confirmed / inferred / predicted / simulated) as the visual thread of
     every finding;
   - **B, "watch room"**: a live strip at the top of the overview showing that monitoring runs.
2. New foundations (tokens, typography scale, shared components, navigation shell) that every screen inherits.
3. Five demo screens finished: **overview, wallet, fund flow graph, investigation report, alerts**.

## Non-goals

- Changing framework or UI library: Angular and Angular Material stay.
- Reworking the other 14 screens beyond what the new foundations give them (they are polished later, one by one).
- New backend features or new API fields (the redesign uses what the API returns today).
- A marketing site.

## Anti-goals (would be a failure even if it looks good)

- Mixing levels: an inferred or simulated statement that looks like a confirmed fact; a prediction shown as a finding.
- Hiding coverage or the "not proof of intent / not a finding" limits to make a screen cleaner.
- A live strip that shows invented or decorative activity: it shows only what the indexers really report.
- Regressions for analysts: lost keyboard shortcuts, command palette, density of tables, dark theme, accessibility.
- Template look (identical rounded cards everywhere, gradient washes, uppercase eyebrow labels).

## Constraints

- Licensing (ADR-0012): only the OFL font packages already allowed (IBM Plex Sans variable, IBM Plex Mono). **Call:
  keep IBM Plex Sans** for the UI and Plex Mono for addresses, hashes and YAML; the mockups used Instrument Sans,
  changing font would require amending ADR-0012.
- Accessibility: WCAG 2.2 AA contrast in both themes, visible focus, reduced motion respected (existing behaviour kept).
- Performance: no new heavy dependency; the graph stays lazy-loaded (`@defer`).
- Existing tests (frontend 30 files) keep passing; the epistemic wording tests stay.
- Demo data: the console's mock mode (`environment.development`, `mockApi`) is what a demo without a backend shows; it
  must look right in the new design too.

## Approaches considered

| Approach | Trade-off | Decision |
|---|---|---|
| Theme only (tokens and fonts) | fast, low risk; the first screen still tells no story | rejected: does not serve prospects |
| **Foundations + five demo screens** | the demo path looks finished; the 14 others inherit the tokens and stay consistent enough | **chosen** (maintainer delegated the choice) |
| All 19 screens at once | complete, but long and risky for screens prospects rarely see | rejected for now |

Visual direction: the three mockups A, B and C were shown; the maintainer kept all three, then asked for the best
option; the combination above is the call.

## Design

### Tokens (light theme first; dark theme mapped from them)

| Role | Value | Use |
|---|---|---|
| canvas | `#FFFFFF` | page background |
| band | `#F2F6F7` | navigation, verdict panels, table headers |
| line | `#DCE5EA` | borders, separators |
| ink | `#0B2545` | text, headings |
| ink-2 | `#4D6177` | secondary text |
| brand | `#0A7381` | primary actions, active navigation, live strip ticks (kept from today: brand continuity) |
| confirmed | `#1E7457` | level CONFIRMED |
| inferred | `#8A5D00` text / `#B7791F` rule | level INFERRED |
| predicted | violet, to set for AA contrast | level PREDICTED |
| simulated | ink-2 with a dashed rule | level SIMULATED |
| risk scale | existing `--risk-*` tokens, contrast-checked | scores and severities |

Type scale (IBM Plex Sans): page title 26/32 bold, section 17/24 semibold, body 13–14/20, small 12/18; numbers tabular.
No uppercase labels; sentence case everywhere.

### Shared components

- **Evidence line** (new, replaces ad-hoc badges): a finding sentence with its level, shown as a coloured left rule and
  a level word ("Confirmed", "Inferred", "Predicted", "Simulated"), the source or assertion it cites, and a link to its
  evidence. One component used by overview, wallet, investigation report and alerts.
- **Verdict panel**: one plain sentence, the risk pill, the evidence lines that support it, and the coverage sentence
  (always present).
- **Live strip** (overview only): real indexer state per network (last block, lag, state), a row of recent blocks where
  a tick marks a block that raised a signal; no animation beyond the update itself; honest states ("behind", "paused",
  "gap recorded").
- Existing `oci-*` components (address, network, risk, severity, evidence, state) restyled to the tokens.

### Shell and navigation

Left navigation on the band colour with the active item marked by a brand-colour rule; groups kept ("Investigate",
"Monitor", …); command palette and shortcuts unchanged. Top of page: page title and one-line description (sentence case).

### The five demo screens

- **Overview**: live strip; "What needs your attention" with up to three verdict panels built from the most severe open
  alerts and their signals; three plain figures (open alerts, chains watched, wallets watched). Empty states invite an
  action (add a wallet to the watchlist).
- **Wallet**: verdict panel first (risk, why, coverage), then evidence lines (labels, exposure, relations, signals), then
  the existing detail sections.
- **Fund flow graph**: the canvas restyled (node colours on the risk scale, the brand for the root), the zoom controls
  added on 2026-11-18 kept, the inspector restyled as a side panel.
- **Investigation report**: each sentence as an evidence line with its level and citation; reviews and AI summary sections
  restyled; coverage first.
- **Alerts**: a calmer table with severity, the triggering finding as an evidence line on selection.

## Decisions settled after the builder check (calls, with their basis)

1. **Levels shown by the evidence line: five words, by the rules of ADR-0023 amendments 1 and 2.** Observed, Inferred,
   Confirmed, Predicted, Simulated. Signal and label `evidenceLevel` maps as rule 4 of amendment 1 says: `ANOMALY` and
   `RISK_SIGNAL` → Inferred; `CONFIRMED_BY_SOURCE` → Confirmed only for the listing itself (the label), and an exposure
   derived from it stays Inferred. The original `evidenceLevel` stays visible as secondary text ("anomaly", "risk
   signal", "confirmed by source"), so the distinction kept in the UI today is not lost. Colour per level in the tokens;
   Observed uses ink-2 with a solid rule.
2. **Live strip: front-end only, from what the API returns.** Per network: last indexed block, lag in blocks and time,
   health, from `/networks` and `/dashboard` (polled every 15 s, the console's current rhythm). No per-block row (the API
   has no list of recent blocks and no per-block signal flag); instead the most recent alerts appear as ticks placed by
   their `blockNumber` relative to the head. States shown: "live", "behind (N blocks)", "unreachable". A gap is not
   claimed on the strip (gaps are not exposed there). No new endpoint.
3. **Coverage sentence of the overview and alert panels: the network's indexing coverage**, worded by a template in the
   console ("Based on transfers indexed on TRON since <date>; earlier activity is not covered here.") with the source of
   that date (live coverage start of the network, from the existing status endpoints). On the wallet page, the wallet's
   own coverage (existing). New template sentences get wording tests, like the existing epistemic wording tests.
4. **Light theme becomes the default for new visitors and for the demo**, dark stays one click away and a viewer's
   choice is kept (as today). Reason: the audience is prospects, and direction C is a light base.
5. **"Wallet" means the wallet page** (`/wallets/:network/:address`), which gets the full rework; `/risk` ("Wallet
   risk") inherits the new tokens and is reworked later.

## Left to the builder

- Exact dark-theme values derived from the light tokens, contrast-checked.
- Spacing scale and radius per component (hierarchy: panels 10 px, inputs 6 px, pills full).
- Whether the overview's verdict panels are computed in the console from existing endpoints or need one small read-only
  endpoint (prefer existing endpoints; a new one only if three calls per panel become necessary).
- Order of the five screens (overview first).
