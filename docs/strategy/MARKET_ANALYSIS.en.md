> English translation of [MARKET_ANALYSIS.md](MARKET_ANALYSIS.md) (French original, authoritative). Translated on 2026-11-12; if the two differ, the French version prevails.

# Competitive analysis

> Summary in French of [market-research-sources.md](market-research-sources.md) (full report, ~60 official sources
> consulted on 2026-11-09, exact quotations, inferences marked). Rule applied: a capability that was not
> found is recorded as "not found in the public documentation", never "absent". No competitor limitation has
> been invented.
>
> Two facts were re-verified directly at the source before writing: the Chainalysis security incident
> (official post of 2026-10-30, updated on 2026-11-07) and the Elliptic blocklist for the Binance hack of
> 2026-10-29 ("Most are Tron and EVM (0x) addresses").

## 1. Market context (October-November 2026)

- **Chainalysis incident**: "names, usernames, password hashes and live tokens for all Chainalysis users were
  exposed"; Reactor and Data Solutions shut down from 2026-11-02, KYT (UI and API) and Address Screening shut down on
  2026-11-04, restored on 2026-11-07.
- **Binance hack (2026-10-29)**: Elliptic publishes a free blocklist, no account required, also exposed through
  a public MCP server; the majority of the addresses are TRON and EVM.
- **MCP is becoming the norm**: TRM (2026-07-06), GraphSense (26.06), Arkham (2026-10-06), Elliptic (public),
  Breadcrumbs.

## 2. Competitor profiles (essentials)

| Player | Products | Documented strengths | Documented limitations | Coverage (stated) | Model | Open source |
|---|---|---|---|---|---|---|
| **Chainalysis** | KYT, Address Screening, Reactor, Data Solutions, Hexagate, Alterya, agents (beta) | "hundreds of clustering heuristics", alerts within seconds, multi-hop indirect exposure, 1,500+ customers, agents "deterministic… auditable" | prices not public; agents restricted to DS customers; sanctions oracle deprecated; incident 2026-10-30 → 11-07 | KYT 400+ networks; Reactor 27+ chains | enterprise, quote-based | none found |
| **TRM Labs** | Wallet Screening, Transaction Monitoring, Forensics, Signatures®, Beacon Network | multi-condition rules, ML (Signatures), Beacon network (free base tier), MCP | prices not public | 150 chains, 55+ in standard support | enterprise; Beacon free (verified VASPs) | none found |
| **Elliptic** | Lens, Investigator, Navigator, Ecosystem Monitoring, Copilot | logged decisions "with the risk factors behind it", 1B+ labeled addresses, free public MCP | prices not public | 60+ | enterprise | Apache-2.0 SDK, closed platform |
| **Arkham** | Intel Platform, Tracer, Visualizer, Intel Exchange, alerts | 2.2B+ attributed addresses, free offering, MCP | — | 16 chains | **free + Enterprise 1,500 $/month** | none found |
| **Blockaid** | pre-transaction simulation, Cosigner, Risk Exposure (May 2026), Onchain Monitoring | prevention of drainers/approvals, threat ML, TRON since June 2026 | Cosigner runs at Blockaid | — | quote-based | Node SDK Apache-2.0 |
| **Breadcrumbs** | Investigation Tool, AutoTrace, Monitoring, Network, API | complete public price list, community network, MCP | **TRON and risk score restricted to the 499 $/month plan**; on-premise 380,000 $/year + 30,000 $ | — | public, tiered | none found |
| **GraphSense / Iknaio** | Pathfinder 2, REST API, `graphsense-lib`, CLI, TagPacks, clustering; Iknaio: hosted, CaseConnect, QuickLock | **MIT, self-hosted**, TRON "full mode" + USDT, academic publications, monthly releases, built-in MCP (26.06) | heavy infrastructure (Spark + Cassandra) — Iknaio's reason for being; EVM "Lite" with reduced features | 14 chains (6 Full, 8 Lite EVM) | free OSS; Iknaio quote-based | **yes (MIT)** |

## 3. Comparison matrix

**P** = present (documented) · **V** = present, depth to be verified · **A** = not found in the public
documentation · **CS** = ChainSentinel today (verified in the code, `44c0990`).

| Capability | Chainalysis | TRM | Elliptic | Arkham | Blockaid | Breadcrumbs | GraphSense | **CS** |
|---|---|---|---|---|---|---|---|---|
| Real-time monitoring (KYT) | P | P | P | V | V | V | A | **P** (TRON + Ethereum, ~1 min behind the head) |
| Screening / risk score | P | P | P | P | P | P | A | **P** (without a real sanctions list) |
| Score explainability | P | V | P | V | P | A | A (no score) | **P** (contributions, method, version) |
| Attribution / clustering | P | P | P | P | V | P | P | **A** (no clustering, no real label set) |
| Multi-hop tracing / graph | P | P | P | P | A | P | P | **V** (BFS ≤ 3 hops over in-memory flows) |
| Cases and reports | P | P | P | P | A | V | V | **A** (no case or report) |
| Behavioral / predictive analysis | P | P | V | A | P | A | V | **V** (10 detectors, nothing predictive) |
| Pre-transaction simulation | V | A | A | A | P | A | A | **A** (rule dry-run only) |
| Community intelligence | V | P | A | P | A | P | P | **V** (reports stored, never moderated) |
| Self-hosting | A | A | A | A | A | P (380 k$/year) | P | **P** |
| Open source | A | A | A (SDK) | A | A (SDK) | A | P | **P** (Apache-2.0) |
| API / SDK / webhooks | P | P | P | P | P | P | P | **V** (REST API, signed webhooks; no SDK) |
| AI agents / MCP | V | P | V | P | A | P | P | **A** |
| USDT-on-TRON focus | V | V | V | V | V | V (paid) | V | **P** (issuer freezes included) |
| Pricing transparency | A | A | A | P | A | P | P | **P** (free) |

## 4. Honest reading

1. **Explainability is not a differentiator** — Elliptic, Blockaid and Chainalysis have it. What can be one:
   **open, portable, CI-testable** rules, and **replayable** investigations.
2. **MCP is a prerequisite, not a differentiator** — five players have it; GraphSense already has it self-hosted.
3. **Real-time self-hosting is the real gap in the market** — GraphSense is open but does not monitor;
   the SaaS products monitor but cannot be self-hosted; commercial on-premise costs 380 k$/year.
4. **TRON/stablecoin specialization is a positioning choice, not a capability gap** — everyone
   covers TRON; no one positions themselves on it first.
5. **The main risk is attribution** — we have neither clustering nor an entity database. A PSP wants to know whether
   the counterparty is an exchange, a mixer or a sanctioned address; without labels, our alerts remain
   behavioral.

## 5. Differentiation opportunities (ranked)

| # | Opportunity | Strength | Main counter-argument |
|---|---|---|---|
| O1 | **Self-hosted real-time KYT for stablecoin rails** (TRON + EVM) | strong | GraphSense covers open analysis; weak attribution |
| O3 | **TRON-first, issuer events (USDT/USDC freezes) as signals** | medium | easy to copy; Elliptic already handles freezes on the issuer side |
| O2 | **Open, versioned, CI-testable declarative rules** | medium | explainability exists elsewhere; narrow angle |
| O6 | **Independent fallback engine / second opinion** alongside a commercial KYT | medium | only one documented incident; cost of running two systems |
| O5 | **Bring-your-own-intelligence** (TagPacks, OFAC, partner lists, internal labels) | medium | lower attribution quality; GraphSense already reads TagPacks |
| O8 | **Behavioral detectors without an attribution database, published and measured** | medium | TRM Signatures, Blockaid ML exist; only differentiates if they are public and benchmarked |
| O4 | **Transparent pricing** (free when self-hosted, paid support) | weak on its own | several free offerings already exist |
| O7 | **MCP over one's own data, within one's own perimeter** | weak | GraphSense already does it self-hosted |

**Conclusion**: we invest in O1 + O3 + O2 + O5, we treat O6 as a sales argument, O7 as a technical
prerequisite, O8 as a quality requirement (published benchmark).

## 6. What this analysis does not allow us to assert

- Competitors' actual customer volumes, false-positive rates or attribution quality: not public.
- The absence of a capability at a competitor: only its absence from the public documentation consulted.
- The target segment's actual willingness to pay: to be validated through interviews (see
  [MONETIZATION_STRATEGY.md](MONETIZATION_STRATEGY.md), section "Validation").
