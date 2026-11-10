# Frontend UI/UX redesign (fourth pass)

Date: 2026-11-10. Scope: the ChainSentinel console (`frontend/`, Angular 22 + Angular Material 22). The landing page on `/`
keeps its own palette (see "Remaining work"). No business feature, API contract or data model was changed; no mock data was
added to real screens; no dependency was added except the two font packages that replace the previous two.

## 1. Initial state

The console already had a token-based design system (third pass, Linear/Vercel family, `src/styles.scss`):

- Flat neutral surfaces, light theme by default (dark followed the operating system), neutral near-black dark canvas
  (`#0b0b0c`), one desaturated teal accent.
- Inter for all text, JetBrains Mono for addresses and hashes.
- Shared building blocks: `oci-page-header` (sticky bar, breadcrumbs, ⌘K search), `oci-state` (loading skeleton, empty,
  error with retry), severity / evidence / risk chips, `oci-gauge`, tables with sticky headers, expandable rows, toasts,
  confirmation dialog, command palette, keyboard shortcuts.

Problems found while walking every route (overview, explorer, wallet, risk, fund flow graph, alerts, rule builder, rules as
code, simulator, watchlist, community labels, integrations, settings) at 1440 px and 390 px, dark and light:

| Area | Problem |
|---|---|
| Identity | Generic look: neutral black, default typeface, nothing specific to an analyst tool. |
| Hierarchy | Critical alerts had the same weight as every other figure on the overview. |
| Colour use | The activity chart was entirely in the accent colour; HIGH and CRITICAL were not the only saturated colours on a page. |
| Explorer | The empty page was a small form and four lines of text; the network selector showed an empty value. |
| Wallet | Identity (address, labels) and risk reading floated on the canvas without a frame. |
| Fund flow graph | Every edge was grey: incoming, outgoing and risky relations could not be told apart (mission requirement). |
| Alerts inbox | Severity was a coloured dot only; nothing readable without colour in the row itself. |
| Mobile | Breadcrumb separators overlapped on narrow screens; explorer controls did not use the full width. |
| Truncation | Some truncated titles and summaries had no way to read the full text without opening the item. |

## 2. Design system choices

Direction: an analyst's instrument for blockchain intelligence. Quiet surfaces, precise type, colour reserved for meaning.

**Colour** (tokens in `src/styles.scss`, light in `:root`, dark in the `dark-tokens` mixin):

| Token | Dark | Light | Role |
|---|---|---|---|
| `--bg` | `#080d12` | `#f4f6f8` | Blue-black anthracite canvas (not neutral black). |
| `--surface` / `--surface-2` / `--well` | `#0d141b` / `#142029` / `#0a1117` | `#fff` / `#edf0f3` / `#f3f5f7` | Panels, raised fills, readouts. |
| `--rail` | mix of surface and canvas | same | Sidebar, half a step above the canvas. |
| `--accent` | `#3cc6d4` | `#0a7381` | Signal cyan: primary action, focus, active navigation, links. Used sparingly. |
| `--risk-*` | green / amber / orange / red | darker equivalents | Product meaning only. HIGH and CRITICAL are the only saturated colours on a page. |
| `--flow-in` / `--flow-out` | `#95a3ff` / `#3cc6d4` | `#4f5fd0` / `#0a7381` | Fund flow direction in the graph; never a risk colour. |

All text colours keep WCAG AA contrast (4.5:1) on `--bg`, `--surface` and `--well` in both themes; text field outlines
keep 3:1. Severity is never conveyed by colour alone: every coloured dot or edge comes with a label or a legend entry.

**Theme**: dark is the default (`public/theme-init.js`, `ThemeService`); light and "system" remain available in
Settings > Appearance, and an existing stored preference is respected.

**Type**: IBM Plex Sans (variable) for all UI text and figures, IBM Plex Mono for addresses, hashes and YAML. Plex was
drawn for engineering interfaces, has tabular figures and a mono companion that matches it. Base size 13.5 px, page titles
22 px. Only the Latin subsets ship (`src/theme/fonts.css`, three WOFF2 files, about 75 KB), OFL-1.1, recorded in `NOTICE`,
ADR-0012 (revision 2026-11-10) and the CI licence allow-list.

**Shape and depth**: radii by role (chip 4, control 6, well 8, panel 10, overlay 12 px). Panels: 1 px line plus a
hairline top highlight in dark (`--shadow-panel`), no glow. Only overlays (menus, dialogs, popovers, toasts) cast a shadow.

**Motion**: unchanged and short (120 to 180 ms), only in answer to an action (hover, open, expand); all motion is disabled
under `prefers-reduced-motion`.

## 3. Pages and components improved

| Page / component | Change |
|---|---|
| Shell (`app.scss`) | Sidebar on its own `--rail` tone; active page marked by a tinted row and a 2 px accent edge. |
| Page header | Taller bar (52 px); on phones only the current breadcrumb shows, so crumbs no longer overlap the actions. |
| Overview | Attention bar when critical alerts are open, with the action that answers it ("Review critical alerts", filtered link); severity rail on HIGH/CRITICAL recent alerts; activity bars in a neutral tone so the alert marks stand out; "Confidence 65%" without the stray gap; full text on hover for truncated titles and summaries. |
| Explorer | The lookup form is the page's centre: 44 px fields, live detection chip ("Address" / "Transaction") with the reason, a network selector that says what it will do ("Detect from the format", "Detect (Ethereum)", "No enabled network matches"); accepted formats as a reference grid; transaction status as a status dot plus text; full-width controls on phones. |
| Wallet | Identity card: address, network, account type and labels on the left, the risk reading on its own well on the right, a 3 px severity edge when the wallet is HIGH or CRITICAL. |
| Fund flow graph | Edges coloured by direction relative to the root (into the root, out of the root, between other wallets); a dashed orange edge when the counterparty is scored HIGH or CRITICAL, worded in the legend as "not proof of shared intent"; neutral edges recede (55 % opacity). Legend extended with the four edge kinds. |
| Alerts inbox | HIGH and CRITICAL rows carry a readable severity chip next to the status; titles and summaries show the full text on hover. |
| Risk, Watchlist, Community labels, Integrations, Settings, Rule builder, Rules as code, Simulator | Inherit the new tokens, type, panels and states; checked visually, no structural change needed. |

## 4. Reusable pieces added to the design system

- `.attention` (and `.attention--warn`): one line saying what needs a decision now, with the action that answers it.
- `.sev-rail`, `.sev-rail--high`, `.sev-rail--critical`: a 2 px edge for rows and panels whose subject is HIGH or CRITICAL.
- Tokens `--rail`, `--shadow-panel`, `--r-overlay`, `--flow-in`, `--flow-out`.

Existing shared components (`oci-state` skeleton / empty / error, chips, gauge, page header, confirm dialog, toasts,
tooltips, command palette) were kept and restyled through the tokens rather than duplicated.

## 5. Verification

- `ng build` (production): passes, initial bundle 523 kB (133 kB transferred).
- `ng test`: 25 files, 142 tests, all passing (before and after the changes).
- Every route rendered with the mock backend in headless Chrome at 1440 px and 390 px in dark, and the main routes in
  light; interactive states checked: alert selected (detail pane), command palette, loaded fund flow graph. No console error.
- Contrast of the new token pairs checked against WCAG AA as listed above.
- Authentication, permissions and API calls are untouched (no change under `core/` except the theme default).

## 6. Remaining work

- **Landing page palette**: the public landing (`features/landing`) uses emerald (`#3ddc97`) as its accent, the console
  uses cyan, because green is the console's LOW risk colour. Aligning both on one accent is a brand decision (the Open
  Graph image is emerald).
- The alerts inbox and the fund flow graph were checked at 390 px but remain dense on phones; a dedicated mobile layout
  for the graph inspector would help.
- Tables wider than the screen scroll horizontally on small screens (by design); a card layout for the longest tables
  (community labels, wallet transactions) is not done.
- No automated visual regression test exists; the screenshots used for this pass are not committed.
- Prettier is configured but not enforced in CI, and the codebase does not currently pass it; changed files follow the
  surrounding style.
