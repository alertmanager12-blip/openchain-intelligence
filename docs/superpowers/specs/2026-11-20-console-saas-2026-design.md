# Console look: a 2026 SaaS finish on the existing foundations

- Date: 2026-11-20
- Status: approach delegated by the maintainer ("prend la meilleure proposition"); short design, no new backend work
- Scope: `frontend/` (console shell, tokens, shared components, overview; every other screen inherits)

## Intent and why

The maintainer wants the console to read as a current, high-end SaaS product rather than a clean internal tool. The
2026-11-18 redesign gave it sound foundations (light base, IBM Plex, five levels of proof, reliability band). What it
lacks is depth, a signature moment and the small finishing details that separate a product from a prototype.

## Goals

1. Layered surfaces: a cool tinted canvas, white panels that float by tone rather than by heavy borders, a radius
   hierarchy (panels 12, controls 8, chips 6, overlays 14) and ink-tinted shadows.
2. A shell with presence: the rail on the band tone with a tinted active pill, a translucent top bar with the command
   search as a pill, a page title set tighter and larger.
3. One signature element: on the overview, a live network strip (one instrument strip with a tile per network: name,
   state, lag) placed above the reliability band. Only data the API already returns; a pulse only when the network is
   live, none under reduced motion.
4. A dark theme as finished as the light one: deeper blue-black canvas, luminous teal, hairlines at low opacity.
5. One orchestrated reveal on the overview (band, strip, first verdicts rise in once); no hover theatre elsewhere.

## Non-goals and anti-goals

- No new font (ADR-0012), no new dependency, no new endpoint, no screen rewrite beyond the overview.
- Anti-goals from the 2026-11-18 spec stand: no level mixing, no decorative live activity, no analyst regression
  (shortcuts, ⌘K, table density, AA contrast), no template look (identical cards, gradient washes, uppercase eyebrows).

## Approaches considered

| Approach | Trade-off | Decision |
|---|---|---|
| Tokens only | cheap, but the first screen still has no signature | rejected |
| **Tokens + shell + overview instrument** | every screen inherits the finish; one screen gets the memorable element | **chosen** |
| Full screen-by-screen rework | weeks; most screens are rarely shown | rejected |

## Tokens (light / dark)

| Role | Light | Dark |
|---|---|---|
| canvas | `#F4F6F8` | `#090F1A` |
| surface | `#FFFFFF` | `#0F1829` |
| surface-2 / well / band | `#EDF1F4` | `#151F33` |
| line / line-soft | `#DFE5EA` / `#E9EEF2` | `#22304A` / `#1B2740` |
| ink / ink-2 | `#0B1F3A` / `#4F627A` | `#E8EEF6` / `#A7B6C9` |
| brand | `#0A7381` (kept) | `#46CFDB` |

Type: h1 26/32 weight 600 tracking -0.02em; lede 14 ink-2; the rest unchanged.
Checked in `shared/contrast.ts` tests (AA on both themes).

## Left to the builder

Exact pulse timing, strip breakpoints, whether the side "Networks" card stays once the strip exists (call: it goes,
the strip replaces it; "Last 24 hours" stays).
