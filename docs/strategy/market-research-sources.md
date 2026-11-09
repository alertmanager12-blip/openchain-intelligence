# ChainSentinel — Competitive Research: Blockchain Intelligence / KYT Vendors

- **Access date for every URL in this report:** 2026-11-09
- **Scope:** Chainalysis, TRM Labs, Elliptic, Arkham Intelligence, Blockaid, Breadcrumbs, GraphSense (Iknaio / AIT / CSH)
- **Method:** Official product pages, docs, GitHub and official blogs were read first. Third-party sources are used only where noted and are marked **[secondary]**. Anything not stated by a source is marked **(inferred)**. A capability not found is reported as "not found in public docs" — this does not mean the capability is absent.
- **Source IDs** in square brackets (for example [C1]) point to the "Sources" list at the end.

## Market context (Oct–Nov 2026)

- **Chainalysis security incident.** The incident was disclosed on 2026-10-30. An external actor "using offensive agentic techniques" gained access to internal systems. Chainalysis says "names, usernames, password hashes and live tokens for all Chainalysis users were exposed". Reactor and Data Solutions were taken offline on 2026-11-02, and KYT (UI and API) plus Address Screening on 2026-11-04. Chainalysis reports Reactor, KYT and Address Screening back online on 2026-11-07 [C6]. This is a documented example of how much customers depend on a single SaaS vendor for KYT.
- **Binance hack, 2026-10-29.** Elliptic's headline describes "$6.4 billion stolen from Binance" laundered by North Korea [E1]. Elliptic published a free blocklist and says "most are Tron and EVM (0x) addresses" [E6]. TRM attributes USD 7.4B of DPRK theft to 2026 [T10]. TRON/EVM stablecoin rails are central to current laundering flows, which supports ChainSentinel's TRON + EVM focus.

---

## 1. Chainalysis

**Main product lines**
- **KYT (Know-Your-Transaction)** — real-time transaction monitoring [C1]
- **Address Screening** — wallet screening with risk scores [C3]
- **Reactor** — investigations and graph [C2]
- **Data Solutions (DS)** — SQL/Python access, bulk exports, custom APIs [C9]
- **Hexagate** — Web3 threat detection and mitigation, acquired Dec 2024 [C7]
- **Alterya** — AI fraud detection, acquired 2025-01-13 [C8]
- **Chainalysis agents ("Chain")** — AI agents, in beta [C4][C5]

**Strengths (documented)**
- "Hundreds of clustering heuristics" and data "backing court-tested blockchain intelligence" [C1]. The agents page claims it is "the only blockchain intelligence proven under the Daubert standard in U.S. federal court" [C4].
- KYT "generates alerts within seconds of a transaction occurring on-chain" [C1].
- Indirect exposure: "going back as many hops as needed until an identified service is hit" [C1].
- 1,500+ customers [C9]. Named logos include Coinbase, Kraken, Bybit, BNY and others [C1].

**Documented limits**
- Pricing is not public. Product pages only offer "Request a demo" [C1][C3].
- Availability limit: agents are "currently in beta" and "available to customers with access to Chainalysis Data Solutions (DS)" [C4].
- The page for the free on-chain sanctions oracle is titled "Chainalysis oracle for sanctions screening – Deprecated" [C10].
- Documented service outage 2026-11-02 → 2026-11-07, with credentials/tokens exposed [C6].
- Coverage figures differ by product: Reactor claims "27+ blockchains and 40 million+ assets" [C2], while the KYT FAQ claims "400+ networks and 50M+ tokens" [C1]. Buyers should verify per product.

**Investigation capabilities**
- Reactor traces "across 27+ blockchains… 325M+ swaps, 300+ bridges and DEXs, and the popular mixers within a single investigative workflow" [C2].
- KYT alerts can be opened in Reactor [C1].
- Agents "generate investigation graphs, dashboards, reports" and "summary reports generated on demand" [C4][C5].

**Behavioural analysis**
- KYT "behavioral alerts… specific transaction patterns indicative of suspicious behavior" [C1].
- Clustering heuristics [C1].
- Alterya: AI fraud detection "that identifies scammers before they meet their victims" [C8].

**Coverage**
- KYT: 400+ networks and 50M+ tokens (KYT FAQ; the page was modified 2026-11-05) [C1].
- Reactor: 27+ chains and 40M+ assets (current page) [C2].
- DS "onboards new blockchains automatically supporting all tokens that follow widely adopted standards" [C9].
- Sui automatic token support was announced in March 2026 [C11].
- TRON support was not explicitly confirmed on the pages I fetched. It is very likely given the 400+ figure **(inferred)**.

**Business model / pricing**
- Enterprise SaaS; contact sales.
- No public price list or free tier found for KYT, Address Screening or Reactor.
- Address Screening: "we do not charge for rescreens" [C3].

**Integration**
- KYT API [C1]; Address Screening APIs ("high-reliability, low-latency APIs") [C3].
- DS: SQL, Python, bulk exports, custom APIs [C9].
- Third-party integrations exist, e.g. DFNS, Fireblocks, Persona **[secondary]** [X3].
- No public MCP server found. The agents page says agents can use models "from Google DeepMind, Anthropic, OpenAI, and others", but external MCP access is not mentioned [C4]. A third-party survey dated 2026-08-03 also reports "no official MCP server found" **[secondary]** [X2].

**Open source:** None found in public docs.

**2025–2026 developments**
- Alterya acquired (2025-01-13) [C8].
- Blockchain intelligence agents announced 2026-03-31, with rollout "over the summer" [C5]. Agents are now in beta for DS customers [C4]. Agent workflows are described as "deterministic… same inputs, same outputs" and "auditable", with inputs, data sources and actions recorded [C4].
- Security incident and outage, 2026-10-30 → 2026-11-07 [C6].

---

## 2. TRM Labs

**Main product lines**
- **Wallet Screening** [T7]
- **Transaction Monitoring** [T1]
- **Forensics**, with Signatures® and the Co-Case Agent AI assistant [T2]
- **Triage** [T6]
- **BLOCKINT API** [T5]
- **Sanctions API** and **Chainabuse API** [T5]
- **TRM MCP** [T3]
- **Beacon Network** — real-time crime response network [T4]
- **Chainabuse** — community scam reporting [T9]

**Strengths (documented)**
- Transaction Monitoring supports multi-condition rules: "inputs including transaction amounts, aggregation to a category, velocity, blockchain, counterparty risk indicators" [T1].
- Daily rescreening and a "risk engine audit log" [T1].
- Signatures®: an ML engine that "automatically uncovers suspicious behavioral patterns… that would not trigger individual rule-based alerts" [T8].
- Standardised chain onboarding, with "automatic cross-chain swap tracing" on every Standard chain [T6].

**Documented limits**
- Pricing is not public (demo request) [T7].
- Capability depth varies by chain: there are 150 chains in total, of which "55+ chains with Standard support for tracing and alert triage" (Jan 2026) [T6].
- TRM MCP access is gated: "please reach out to your account team" [T3].
- No rate limits were found on the docs landing page [T5]. That is "no documented limit found", not proof that none exist.

**Investigation capabilities**
- Forensics provides "automatic cross-chain tracing", custom graph elements with off-chain data, case management that consolidates "graphs and notes in one place", and export [T2].
- Forensics also includes victim reports ("largest database of scam and fraud victim reports") and the Co-Case Agent AI assistant [T2].

**Behavioural analysis**
- Signatures® ML pattern detection (peeling chains, layering) [T8][T11].
- Behavioural and exposure-based rules [T1].
- A risk score "derived from its counterparty exposure — not just a binary flag" [T8].

**Coverage**
- "150 blockchains… 55+ chains with Standard support… 200 million+ assets" (Jan 2026) [T6].
- Earlier figures: "90+ blockchains" (May 2025) [T12] and "100+" (Oct 2025) [T13].
- TRON is explicitly covered [T8]. A Sumsub integration page lists TRON with token support **[secondary]** [X4].

**Business model / pricing**
- Enterprise; contact sales.
- Free entry points:
  - Beacon Network "Basic membership is free for verified exchanges and crypto service providers" [T4].
  - Chainabuse is a free public reporting site [T9].
  - A Sanctions API is documented [T5]. Free status was not confirmed on the docs landing page.

**Integration**
- APIs for Wallet Screening, Transaction Monitoring, Sanctions, Chainabuse and BLOCKINT [T5].
- TRM MCP works with "any MCP-compatible client, including Claude, Cursor, and VS Code" [T3].
- Webhooks were not mentioned on the Transaction Monitoring page [T1]. The page does mention API integration and "Continuous alerting".

**Open source:** None found in public docs.

**2025–2026 developments**
- Coverage expanded through 2025 to 150 chains (Jan 2026) [T6].
- **TRM MCP** launched **2026-07-06**. It lets an AI agent "triage the queue, investigate exposure paths, and dismiss or escalate the alert" [T3].
- Beacon Network has industry members listed on its page (Binance, Coinbase, Kraken, PayPal, Stripe and others) [T4].

---

## 3. Elliptic

**Main product lines** [E2]
- **Lens** — transaction monitoring and wallet screening in one workspace [E3]
- **Discovery** — VASP due diligence
- **Investigator** — forensics [E4]
- **Ecosystem Monitoring** — for token and stablecoin issuers [E5]
- **Data Fabric** [E1]
- **Elliptic Copilot** [E2]

**Strengths (documented)**
- Explainability is a stated selling point: "consistent, explainable risk score", and "every screen and alert is logged with the risk factors behind it, so your team can show examiners exactly why a decision was made" [E3].
- A single case queue across screening and monitoring [E3].
- "1B+ crypto addresses labeled" and "99%" of global trading volume [E1].
- Ecosystem Monitoring lets issuers "freeze or block risky wallets before exposure builds", with "alerts delivered via webhook" [E5].

**Documented limits**
- Pricing is not public. A "free trial" offer appears in a search snippet of the homepage [E1]; terms were not verified.
- No other documented limit found.

**Investigation capabilities**
- Investigator offers "automatic graph generation" (claimed to cut graph-building time "by up to 60%") and "one-click cross-chain tracing" through bridges.
- Evidence can be exported ("graphs, timestamps and labels in formats built for regulators, auditors and courts") [E4].

**Behavioural analysis**
- Lens mentions continuous monitoring and an "event detection set" [E3].
- No explicit behavioural/ML typology product was found on the pages fetched. Classification: "Present, to verify depth".

**Coverage**
- Lens: "60+ blockchains and 250+ bridges" [E3].
- Homepage: "65+ blockchains, bridges and tokens" [E1].
- TRON is supported, per the Sumsub list **[secondary]** [X5] and Elliptic's own blocklist page [E6].

**Business model / pricing:** Enterprise; contact sales or demo. A free trial is referenced [E1].

**Integration**
- REST API with official SDKs "for Node.js, PHP, and Python" [E7].
- Webhook delivery is documented for Ecosystem Monitoring alerts [E5].
- **Public MCP server** at `https://public.mcp.elliptic.co/mcp`: "It's free, needs no account or API key". It was documented for the Binance-hack blocklist (2026-10/11) [E6]. Whether the full product is exposed via MCP was not verified.

**Open source**
- SDKs only, under Apache-2.0: `elliptic-python` on PyPI and `elliptic-sdk` on npm [E7][E8].
- The platform itself is closed.

**2025–2026 developments**
- Copilot and "agentic AI… inside your regulated workflows" messaging [E1].
- Public free MCP and CSV blocklist for the Binance hack of 2026-10-29 [E6].

---

## 4. Arkham Intelligence

**Main product lines** [A1]
- Intel platform features: Visualizer, TracerV2, Alerts, Risk Scoring, Case Management, Automated Reporting and Multi-Seat Access
- **Arkham Enterprise** (paid tier)
- **API**, including the "Ultra" address-matching engine [A2]
- **MCP server** [A3]
- **Intel Exchange**, a bounty marketplace using the ARKM token [A4][A6]

**Strengths (documented)**
- Large attribution set: "3.80B+" total addresses, "2.2B+" attributed and "1.63B+" with entity [A1].
- Transparent pricing and a usable free tier [A1].
- Risk scoring "0–100 by exposure to illicit on-chain activity" [A2].

**Documented limits**
- "16 Supported Chains" [A1][A2]. This is a narrower chain set than TRM, Chainalysis or Elliptic.
- Free tier gets "a limited number of email and Telegram alerts". Enterprise adds "higher alert limits" [A1].
- MCP requires "an Arkham API Key", and only "some of the small ones are free" [A3].
- API rate limits: the docs have a "Rate limits" section [A5], but the figures could not be extracted. Treat this as "documented, value not captured".

**Investigation capabilities**
- TracerV2: "create, edit, and save visual diagrams showing how funds travel".
- Case management: "bringing addresses, transactions, and entities into one case".
- Automated reports "covering risk assessment, holdings, fund flow analysis, and counterparty relationships" [A1].

**Behavioural analysis**
- Attribution via the Ultra address-matching engine [A2].
- Risk scoring by exposure [A1].
- No behavioural/anomaly typology detection found in public docs.

**Coverage**
- 16 chains (as of 2026-11) [A1][A2].
- TRON appears in third-party API listings **[secondary]** [X6]. It was not confirmed on an Arkham page fetched.

**Business model / pricing**
- **Free tier:** explorer pages, Visualizer, tags, predicted labels and limited alerts.
- **Arkham Enterprise: "$1,500 USD per month"**, with a **14-day trial** [A1].

**Integration**
- REST API with Intelligence and Alerts endpoint groups (e.g. `POST /user/alerts`) **[secondary listing]** [X6] [A2].
- Email and Telegram alerts [A1].
- MCP server (announced 2026-10-06) for "Claude, Claude Code, Codex or any other MCP-capable tool" [A3].

**Open source:** None found in public docs.

**2025–2026 developments**
- Arkham MCP server announced 2026-10-06 [A3].
- The API now also covers HyperCore and Polymarket data [A2].

---

## 5. Blockaid

**Main product lines** [B1]
- **End User Protection** — transaction, token and address scanning for wallets [B2]
- **Onchain Monitoring**
- **Crypto Fraud Prevention** — e.g. pig butchering
- **Cosigner** — independent co-signer for MPC/multisig, simulating every transaction before signing [B3][B9]
- **Bridge Sentinel** — LayerZero DVN
- **Risk Exposure** — compliance suite launched 2026-05-12 [B4][B5]

**Strengths (documented)**
- Pre-transaction simulation and validation at scale: "5.9B" transactions scanned, "527M" attacks prevented and "$13.1B" theft prevented [B1]. Customer logos include Coinbase, MetaMask, Kraken, Safe and OKX [B2].
- Cosigner "simulated and validated every withdrawal… before it was signed". It supports Fireblocks, BitGo, Safe and others [B9].
- Risk Exposure claims to "block interactions with every exposed address in <300ms". It returns "a clear verdict and full address exposure breakdown: USD amounts, risk categories, percentage of transaction value… Audit-ready output" [B4].

**Documented limits**
- Pricing is not public; no free tier found.
- Cosigner "runs on Blockaid infrastructure, separate from the customer's" [B9]. This is a design choice, but it does mean no self-hosting.
- The compliance page's chain statement is limited to "Ethereum Mainnet as well 6+ other EVM chains" [B4]. TRON Risk Exposure was announced separately (2026-06-25) [B6].

**Investigation capabilities**
- Cosigner shows "the full transaction flow in an interactive graph" for a single transaction [B3].
- No multi-hop investigation graph or case management found in public docs.

**Behavioural analysis**
- "Advanced machine learning and AI, clustering algorithms to detect similarities in patterns, bytecode, and other onchain activity associated with threats" [B1].
- Onchain Monitoring on Tron can "detect anomalies and respond as activity happens" [B6].

**Coverage**
- Ethereum, Solana, Base, Tempo, Linea, Blast, Optimism and Bitcoin are shown on the transaction-security page [B2].
- TRON Onchain Monitoring + Risk Exposure since 2026-06-25. Blockaid notes Tron is "a primary rail for USDT transfers globally" [B6].
- No single chain count was found.

**Business model / pricing:** Enterprise; contact sales. No public pricing found.

**Integration**
- REST API with an OpenAPI schema [B10].
- Official Node client (Stainless-generated, **Apache-2.0**) [B8].
- "Automated alerts and response workflows" [B6].
- No MCP server found in public docs.

**Open source:** Client SDK only (Apache-2.0) [B8]. The engine is closed.

**2025–2026 developments**
- **Risk Exposure** compliance suite (2026-05-12) [B5].
- **TRON** Onchain Monitoring and Risk Exposure (2026-06-25) [B6].
- AI-powered "Recommended Policies for Cosigner" (2026-07-30) [B7].
- New Onchain Monitoring capabilities (2026-09-09) [B7].

Blockaid moved from wallet-security simulation into compliance and exposure screening in 2026. It is now a direct entrant into KYT-adjacent territory.

---

## 6. Breadcrumbs

**Main product lines**
- **Investigation Tool**, with AutoTrace [BC1][BC4]
- **Monitoring** and **Search** tools (included in plans) [BC2]
- Compliance offering at comply.breadcrumbs.app (page blocked by Cloudflare, content not verified)
- Breadcrumbs Academy training
- **"Breadcrumbs One" API** [BC4]
- **MCP** ("Connect to Claude") [BC2][BC4]

**Strengths (documented)**
- Community-powered attribution: "Investigators from around the world actively contribute reports and attribution data to the Breadcrumbs Network". The site reports "1B+ data points", "50K+ Investigators" and "3K+ Law Enforcement users" [BC1].
- Fully public price list, including an on-prem option [BC2][BC3].
- The "Address Verification Service" shares "the source of our Attribution data" on higher law-enforcement tiers [BC2]. This is a form of provenance transparency.

**Documented limits**
- TRON is a "Premium Blockchain". On retail plans, TRON and the risk score are only included in **Retail Biz at $499/month**. Retail Free ($0) and Retail Pro ($249/month) do not include premium chains or risk score [BC3].
- CSV export is limited to 10 rows on free plans [BC2][BC3].
- Search API: "10 credits ($0.01) per page" [BC4].

**Investigation capabilities**
- Multi-chain graph with AutoTrace ("automatically finds all the endpoints that funds went to").
- API endpoints for relationship finder, smart expand and AutoTrace [BC1][BC4].
- PDF report export [BC2].

**Behavioural analysis**
- Attribution and risk endpoints exist (`/risk`, `/attribution`, `/sanctioned_address`) [BC4].
- No behavioural/anomaly detection found in public docs.

**Coverage**
- The homepage claims "21 Blockchains supported" [BC1].
- The API docs list ETH, BTC, MATIC, RON, SOL, TRX, BNB, CHZ, OP, AA, BASE, DOGE, LTC, BLAST, SCROLL, XRP, ARB, HEMI, SOPH, XPL and MORPH [BC4].

**Business model / pricing (public)** [BC2][BC3]

| Segment | Plan | Price |
|---|---|---|
| Retail | Free | $0 |
| Retail | Pro | $249/month |
| Retail | Biz | $499/month |
| Law enforcement | LE Free | $0 |
| Law enforcement | LE Grant | $0 (requires application) |
| Law enforcement | LE Pro | $12,000/year |
| On-prem | **Data On Premises** | **$380,000/year + $30k one-time setup**, "User's servers", 5 licences |

**Integration**
- REST API (credit-based) [BC4].
- MCP via a downloadable `breadcrumbs.mcpb` extension, or remote `https://mcp.breadcrumbs.one/mcp` with an auth header [BC4].
- Webhooks were not found in the API docs captured.

**Open source:** None found in public docs.

**2025–2026 developments**
- API "One V2" and MCP installation docs [BC4]. The exact launch date was not found.
- The pricing page shows "Last Build 10.27.2026" [BC2].

---

## 7. GraphSense (Complexity Science Hub / AIT origin; Iknaio as commercial partner)

**Main product lines**
- **GraphSense**, open-source platform (MIT): dashboard (Pathfinder 2), REST API, Python library `graphsense-lib`, CLI, TagPacks/TagStore, and a clustering engine [G1][G2][G4].
- **Iknaio** (commercial):
  - Hosted Pathfinder
  - **CaseConnect** ("collaborative intelligence by connecting related cases")
  - **QuickLock** (reporting)
  - CSAM Checker
  - REST API + **MCP**
  - Dark Web Monitor (operated by CFLW)

  These are sold in "Professional and Institutional packages" [G3].

**Strengths (documented)**
- "The complete software stack is open source and MIT licensed". The site emphasises "full data sovereignty, algorithmic transparency" [G1].
- Self-hostable; "Built on Apache Spark and Cassandra for horizontal scalability" [G1].
- Academic pedigree: a published system paper [G6].
- **TRON is supported in "Full mode"**, alongside BTC, BCH, LTC, ZEC and ETH. USDT is supported [G1][G3].
- Active release cadence: monthly releases through 2026 [G2].
- Interoperable attribution format: TagPacks (public or private). The public TagPack repo accepts community PRs [G1][G5].

**Documented limits**
- Infrastructure burden is stated by the project itself. Iknaio was founded "after realizing that many users cannot run the required infrastructure themselves" [G1]. The REST API needs Cassandra + PostgreSQL TagStore [G4].
- API bounds (26.08): "10,000 items per bulk list, 8 MB request body, 5,000 items per page, depth 7" [G2].
- "Lite" EVM networks (BSC, Arbitrum, Base, Optimism, Polygon, Avalanche, Gnosis, Robinhood Chain) are "served by an external GraphSense-compatible backend with a reduced feature set" [G2][G3].
- Pathfinder 1.0 (cluster-centric) was retired in 26.08 [G2].

**Investigation capabilities**
- Pathfinder 2 address-by-address tracing, "so every step can be verified and reproduced" [G2].
- Path finding [G1].
- PDF/PNG graph export and CSV export (26.01) [G2].
- CaseConnect 3.0 for cross-case links (26.07) [G2].
- Swap and bridge decoding, including THORChain (25.09) and CoW Protocol swaps (26.09) [G2].

**Behavioural analysis**
- New UTXO clustering engine, also released as the open-source `graphsense-clustering` package (26.08) [G2].
- Coinjoin and change-address heuristics; mixing detection (26.04) [G2].
- Cross-chain tag derivation via public-key reuse (25.11) [G2].
- No risk scoring, real-time monitoring or alerting found in public docs [G1].

**Coverage**
- Iknaio: "fourteen blockchains", of which 6 are Full and 8 Lite EVM (as of 2026-09/11) [G3][G2].

**Business model / pricing**
- The open source is free.
- Iknaio offers hosted packages; no public prices found [G3].

**Integration**
- REST API (FastAPI module since 26.03), Python library, and a `graphsense` CLI (26.06) [G2][G4].
- **MCP interface built into the GraphSense API** (26.06, demonstrated with Mistral Le Chat) [G2].

**Open source:** github.com/graphsense, MIT [G1][G4][G5].

**2025–2026 developments**

| Release | Date | Main additions |
|---|---|---|
| 25.09 | 2025-09-04 | Services layer |
| 25.11 | 2025-11-06 | Cross-chain tags |
| 26.01 | 2026-01-30 | Exports |
| 26.03 | 2026-03-12 | FastAPI |
| 26.04 | 2026-04-10 | Coinjoin heuristics |
| 26.05 | 2026-05-09 | Transaction inspection, cross-chain list |
| 26.06 | 2026-06-01 | **MCP** + CLI |
| 26.07 | 2026-07-13 | CaseConnect 3.0 |
| 26.08 | 2026-09-02 | New clustering engine |
| 26.09 | 2026-09-30 | 8 Lite EVM chains |

Source: [G2].

---

## 8. Comparison matrix

Values used: **P** = "Present (documented)", **V** = "Present, to verify depth", **A** = "Absent or not found in public docs". Each cell gives the value, a short note and the source ID.

| Capability | Chainalysis | TRM Labs | Elliptic | Arkham | Blockaid | Breadcrumbs | GraphSense / Iknaio |
|---|---|---|---|---|---|---|---|
| Real-time transaction monitoring (KYT) | **P** — KYT alerts "within seconds" [C1] | **P** — Transaction Monitoring, multi-condition rules [T1] | **P** — Lens [E3] | **V** — real-time alerts, not a KYT product [A1] | **V** — Risk Exposure + Onchain Monitoring, launched 2026 [B4][B6] | **V** — "Monitoring" tool in plans [BC2] | **A** — no monitoring/alerting found [G1] |
| Wallet screening / risk scores | **P** — Address Screening [C3] | **P** — Wallet Screening [T7] | **P** — Lens [E3] | **P** — 0–100 score [A2] | **P** — Risk Exposure verdict [B4] | **P** — `/risk`, Biz plan risk score [BC3][BC4] | **A** — no risk score found [G1] |
| Explainability of scores (factors exposed) | **P** — direct/indirect exposure by category [C3] | **V** — "what triggered the alert", exposure-based score [T1][T8] | **P** — "logged with the risk factors behind it" [E3] | **V** — score "by exposure"; breakdown not verified [A2] | **P** — "USD amounts, risk categories, percentage" [B4] | **A** — score explanation not found; attribution sources shared on LE tiers [BC2] | **A** — no score (open algorithms) [G1] |
| Attribution / entity DB & clustering | **P** — hundreds of heuristics [C1] | **P** — labels + clustering [T11] | **P** — 1B+ labeled [E1] | **P** — 2.2B+ attributed [A1] | **V** — threat-pattern clustering [B1] | **P** — 1B+ community data points [BC1] | **P** — clustering engine + TagPacks [G1][G2] |
| Multi-hop tracing / investigation graph | **P** — Reactor [C2] | **P** — Forensics [T2] | **P** — Investigator [E4] | **P** — TracerV2 / Visualizer [A1] | **A** — single-tx flow graph only [B3] | **P** — Investigation Tool / AutoTrace [BC1] | **P** — Pathfinder 2 [G2] |
| Case management & reports | **P** — KYT cases; agent reports [C1][C5] | **P** — cases + notes [T1][T2] | **P** — case queue; evidence export [E3][E4] | **P** — Case Mgmt, Automated Reporting [A1] | **A** — not found | **V** — PDF report export [BC2] | **V** — CaseConnect, QuickLock (Iknaio), PDF export [G2][G3] |
| Behavioural / predictive analytics | **P** — behavioural alerts; Alterya [C1][C8] | **P** — Signatures® ML [T8] | **V** — "event detection" only [E3] | **A** — not found | **P** — ML/AI threat detection, anomalies [B1][B6] | **A** — not found | **V** — coinjoin/change heuristics, not risk behaviour [G2] |
| Pre-transaction simulation / threat prevention | **V** — Hexagate (acquired) [C7] | **A** — no simulation found; pre-authorisation screening only [T7] | **A** — not found | **A** — not found | **P** — simulation, Cosigner [B2][B3] | **A** — not found | **A** — not found |
| Community / collaborative intelligence | **V** — "professional community"; no data-sharing network found [C1] | **P** — Beacon Network (free tier), Chainabuse [T4][T9] | **A** — not found; vendor-published free blocklist [E6] | **P** — Intel Exchange bounties [A4] | **A** — not found | **P** — Breadcrumbs Network, LE Network [BC1][BC2] | **P** — public TagPacks via PR; CaseConnect [G5][G3] |
| Self-hosting / on-prem | **A** — DS bulk export only [C9] | **A** — not found | **A** — not found | **A** — not found | **A** — Cosigner on Blockaid infra [B9] | **P** — "Data On Premises" $380k/yr [BC2] | **P** — self-hosted [G1] |
| Open source | **A** — not found | **A** — not found | **A** — platform closed; SDKs Apache-2.0 [E7] | **A** — not found | **A** — engine closed; Node SDK Apache-2.0 [B8] | **A** — not found | **P** — MIT [G1] |
| API / SDK / webhooks | **P** — KYT & screening APIs [C1][C3] | **P** — documented APIs [T5] | **P** — SDKs; webhooks [E5][E7] | **P** — API, alerts endpoints [A2] | **P** — API + SDK [B8][B10] | **P** — API (credit-based) [BC4] | **P** — REST, Python lib, CLI [G2][G4] |
| AI agent / MCP integration | **V** — in-platform agents (beta, DS customers); no MCP found [C4] | **P** — TRM MCP 2026-07-06 [T3] | **V** — free public MCP (blocklist scope); Copilot [E6][E2] | **P** — MCP 2026-10-06 [A3] | **A** — no MCP found | **P** — MCP docs [BC4] | **P** — MCP in API, 26.06 [G2] |
| USDT-on-TRON focus | **V** — TRON not confirmed on fetched pages (inferred supported) | **V** — TRON covered; generalist [T8] | **V** — TRON covered; generalist [X5][E6] | **V** — TRON via secondary listing only [X6] | **V** — TRON since 2026-06; cites USDT [B6] | **V** — TRON is a premium chain, gated to $499/mo [BC3] | **V** — TRON full mode; USDT [G1][G3] |
| Pricing transparency | **A** — contact sales | **A** — contact sales; Beacon basic free [T4] | **A** — contact sales; free trial mentioned [E1] | **P** — free tier; $1,500/mo [A1] | **A** — contact sales | **P** — full public price list [BC2][BC3] | **P** — free OSS; Iknaio prices not public [G3] |

Notes on the matrix:
- **USDT-on-TRON focus.** Every vendor covers TRON. None positions itself as TRON-first or stablecoin-rail-first in the sources reviewed. Blockaid comes closest in messaging [B6]. The cells reflect coverage, not specialisation.
- **AI agent / MCP.** MCP became widespread in 2026: TRM (July), GraphSense (June), Arkham (October), Elliptic (public, October/November) and Breadcrumbs (date unknown).

---

## 9. Candidate differentiation opportunities for an open-source, self-hosted player

Each opportunity lists the supporting evidence, the counter-evidence and an honest assessment.

### O1. Self-hosted, data-sovereign real-time KYT for stablecoin rails (TRON + EVM)

**Evidence**
- No self-hosting option was found for Chainalysis, TRM, Elliptic, Arkham or Blockaid. Blockaid's Cosigner explicitly runs on Blockaid infrastructure [B9].
- The only commercial on-prem offer found is Breadcrumbs' "Data On Premises" at $380,000/year plus $30k setup [BC2].
- The Chainalysis incident (2026-10-30 → 11-07) exposed "live tokens for all Chainalysis users" and took the KYT API offline for days [C6]. This gives a concrete argument for keeping monitoring in-house, or for running a vendor-independent fallback.

**Counter-evidence / risk**
- GraphSense is already MIT-licensed, self-hostable and supports TRON and USDT [G1][G3]. However, it documents no real-time monitoring, alerting or risk scoring [G1]. The gap that remains is monitoring and alerting, not self-hosted analytics in general.
- A self-hosted product lacks the incumbents' proprietary attribution, which is their core asset: TRM 150 chains, Arkham 2.2B+ attributed addresses, Elliptic 1B+ labeled.
- Regulated buyers may still require a recognised data vendor **(inferred)**.

### O2. Open, versioned, declarative rule logic (YAML-as-code) as the explainability story

**Evidence**
- Incumbent rule engines are configured inside closed UIs (TRM "multi-condition rules" [T1]; Chainalysis KYT "custom groups", thresholds [C1]).
- Open rules can be reviewed, diffed, tested and audited by an examiner, line by line **(inferred)**.

**Counter-evidence / risk**
- **Explainability itself is not a differentiator.**
  - Elliptic logs every decision "with the risk factors behind it" [E3].
  - Blockaid returns "USD amounts, risk categories, percentage of transaction value" [B4].
  - Chainalysis shows direct and indirect exposure by category [C3].
  - Chainalysis agents now advertise "deterministic, code-based workflows… every run records its inputs, data sources, and actions" [C4].
  - GraphSense already markets "algorithmic transparency" [G1].
- The defensible angle is narrow: rules that are open-source, portable and testable in CI, not "explainable scores".

### O3. Stablecoin-rail specialisation: TRON-first, with issuer events (USDT freezes/blacklists) as first-class signals

**Evidence**
- No vendor in the sources reviewed positions as TRON or USDT-first. Most treat TRON as one chain among 16–400+.
- Breadcrumbs gates TRON behind its $499/month Biz plan [BC3].
- Blockaid only added TRON monitoring and Risk Exposure in June 2026 [B6].
- Elliptic says the Binance-hack laundering set is "mostly Tron and EVM" [E6].
- I found no documented competitor feature that ingests stablecoin freeze events as a monitoring signal **(not found in public docs; do not claim absence)**.

**Counter-evidence / risk**
- Elliptic Ecosystem Monitoring already supports issuer-side "freeze or block" workflows with webhooks [E5]. Its target is issuers rather than receiving fintechs.
- All seven vendors cover TRON. Specialisation is a positioning choice, not a capability gap.
- Freeze-event indexing is straightforward to copy **(inferred)**.

### O4. Transparent, accessible pricing for SMB fintechs and PSPs (free self-host; optional paid support)

**Evidence**
- Chainalysis, TRM, Elliptic and Blockaid publish no prices (contact sales).
- Arkham Enterprise is $1,500/month [A1]. Breadcrumbs risk scoring plus TRON is $499/month [BC3].

**Counter-evidence / risk**
- Free tiers already exist:
  - Arkham free tier (limited alerts) [A1]
  - Breadcrumbs Retail Free [BC3]
  - TRM Beacon Network basic membership, free for verified VASPs [T4]
  - Elliptic's free public MCP and blocklist [E6]
  - GraphSense, fully free [G1]
- "Free" alone does not differentiate. The combination of free + self-hosted + real-time KYT + TRON is the actual gap.

### O5. Bring-your-own intelligence and interoperability (TagPacks, sanctions lists, vendor blocklists, internal labels)

**Evidence**
- GraphSense TagPacks are an open, documented attribution format with a public, PR-driven repository [G5].
- Elliptic publishes free CSV blocklists [E6].
- TRM promotes "bring your own data" enrichment (Oct 2025) [T13].
- A self-hosted engine that ingests TagPacks, OFAC SDN crypto addresses, issuer freeze lists and partner blocklists would partly offset the attribution gap **(inferred)**.

**Counter-evidence / risk**
- Attribution quality and coverage remain far below the incumbents.
- Blocklists are event-specific (e.g. "The Binance hack of October 2026 only" [E6]).
- GraphSense already consumes TagPacks natively.

### O6. Hybrid deployment: open-source core as a vendor-independent fallback or second opinion beside a commercial KYT

**Evidence**
- The Chainalysis outage removed KYT UI and API access for roughly three days, 2026-11-04 → 11-07 [C6].
- Many vendors already ship APIs and SDKs [C1][T5][E7][B8]. A connector-based design could run ChainSentinel rules alongside a vendor's score **(inferred)**.

**Counter-evidence / risk**
- This is one documented incident; no comparable incidents were researched for the others.
- Buyers may see dual-running as extra cost **(inferred)**.
- The positioning concedes that ChainSentinel is complementary rather than a replacement.

### O7. Self-hosted, agent-ready API (MCP over your own data, no data leaving your perimeter)

**Evidence**
- Vendor MCPs are tied to their clouds and accounts: TRM via account team [T3]; Arkham requires an API key with partly paid queries [A3].

**Counter-evidence / risk — strong**
- MCP is now table stakes. TRM, Arkham, Breadcrumbs, Elliptic (public) and GraphSense all ship one.
- GraphSense's MCP is built into its self-hostable MIT API [G2], so "self-hosted MCP" is already covered by an open-source player.
- This is a necessary feature, not a differentiator.

### O8. Behavioural detectors without a proprietary attribution database (e.g. draining, peel/velocity, approval abuse)

**Evidence**
- Attribution-light behavioural detection can run entirely on raw chain data, which suits self-hosting **(inferred)**.
- GraphSense's documented heuristics are forensic (coinjoin/change) rather than monitoring detectors [G2].

**Counter-evidence / risk**
- TRM Signatures® [T8], Chainalysis behavioural alerts [C1] and Blockaid ML threat detection [B1] are established.
- Blockaid also covers approval and drainer prevention pre-transaction [B1][B2].
- Detectors only become a differentiator if they are open, tunable and published, ideally with benchmarks.

**Overall honest read**
- The defensible combination is: open-source (Apache-2.0) + self-hosted + real-time monitoring and alerting + TRON/USDT and EVM stablecoin focus + open declarative rules.
- No single element is unique. GraphSense owns "open source + self-hosted + TRON analytics". Incumbents own "explainable risk + KYT + attribution". MCP is commoditised.
- The gap is the **intersection** of these elements, and the largest risk is attribution data depth.

---

## Sources

All accessed 2026-11-09. Entries marked **[secondary]** are not vendor primary sources.

**Chainalysis**
- [C1] KYT product page — https://www.chainalysis.com/product/kyt/
- [C2] Reactor product page — https://www.chainalysis.com/product/reactor/
- [C3] Address Screening — https://www.chainalysis.com/product/address-screening/
- [C4] Chainalysis agents — https://www.chainalysis.com/ai-agents/
- [C5] "Chainalysis Introduces the First Blockchain Intelligence Agents" (2026-03-31) — https://www.chainalysis.com/blog/introducing-first-blockchain-intelligence-agents-2026/
- [C6] "Updated Nov 7: Chainalysis Security Incident Information" (published 2026-10-30) — https://www.chainalysis.com/blog/security-incident-october-30/
- [C7] Hexagate acquisition — https://www.chainalysis.com/blog/chainalysis-hexagate-announcement/
- [C8] Alterya acquisition (2025-01-13) — https://www.chainalysis.com/blog/chainalysis-alterya-announcement/
- [C9] Data Solutions — https://www.chainalysis.com/product/ds/
- [C10] Oracle for sanctions screening (page titled "Deprecated") — https://go.chainalysis.com/chainalysis-oracle-docs.html
- [C11] Sui automatic token support (March 2026) — https://www.chainalysis.com/blog/sui-automatic-token-support-march-2026/

**TRM Labs**
- [T1] Transaction Monitoring — https://www.trmlabs.com/crypto-compliance/transaction-monitoring
- [T2] Forensics — https://www.trmlabs.com/forensics
- [T3] "TRM MCP Day 1" (2026-07-06) — https://www.trmlabs.com/resources/blog/trm-mcp-day-1-resolve-alerts-with-your-ai-agent
- [T4] Beacon Network — https://www.trmlabs.com/beacon-network
- [T5] Documentation — https://docs.trmlabs.com/
- [T6] January 2026 Product Highlights — https://www.trmlabs.com/resources/blog/january-product-highlights-accelerating-investigations-with-intuitive-workflows
- [T7] Wallet Screening — https://www.trmlabs.com/crypto-compliance/wallet-screening
- [T8] Glossary: Transaction monitoring — https://www.trmlabs.com/glossary/transaction-monitoring
- [T9] Chainabuse — https://chainabuse.com/en/
- [T10] DPRK USD 12.8B blog — https://www.trmlabs.com/resources/blog/north-korea-has-stolen-usd-128-billion-in-crypto-usd-74-billion-of-it-in-2026
- [T11] "How to choose the best transaction monitoring solution" / tracing fundamentals — https://www.trmlabs.com/resources/blog/how-to-choose-the-best-transaction-monitoring-solution-for-your-compliance-program ; https://www.trmlabs.com/resources/blog/the-fundamentals-of-cryptocurrency-transaction-tracing
- [T12] May 2025 Product Highlights (90+ chains) — https://www.trmlabs.com/resources/blog/may-2025-product-highlights-leveling-up-blockchain-intelligence-workflows
- [T13] October 2025 Product Highlights (100+ chains, bring your own data) — https://www.trmlabs.com/resources/blog/october-2025-product-highlights-expanding-coverage-and-empowering-your-data

**Elliptic**
- [E1] Homepage — https://www.elliptic.co/
- [E2] Products — https://www.elliptic.co/products/
- [E3] Lens — https://www.elliptic.co/products/lens/
- [E4] Investigator — https://www.elliptic.co/products/investigator/
- [E5] Ecosystem Monitoring — https://www.elliptic.co/products/ecosystem-monitoring/
- [E6] Binance Hack 2026 Blocklist + public MCP — https://www.elliptic.co/binance-hack-2026-blocklist/
- [E7] SDK quick start — https://developers.elliptic.co/docs/quick-start-sdks ; https://pypi.org/project/elliptic-python/
- [E8] npm elliptic-sdk — https://www.npmjs.com/package/elliptic-sdk

**Arkham**
- [A1] Homepage — https://arkm.com/
- [A2] API — https://arkm.com/api
- [A3] "The Arkham MCP Server" (2026-10-06) — https://info.arkm.com/announcements/the-arkham-mcp-server
- [A4] Intel Exchange announcement — https://info.arkm.com/announcements/arkham-intel-exchange
- [A5] API docs — https://arkm.com/api/docs
- [A6] Guide: How to use Arkham Intel — https://info.arkm.com/research/how-to-use-arkham-intel-guide-explained

**Blockaid**
- [B1] Homepage — https://blockaid.io/
- [B2] Transaction security — https://blockaid.io/transaction-security
- [B3] Cosigner — https://blockaid.io/cosigner
- [B4] Compliance / Risk Exposure — https://blockaid.io/compliance
- [B5] "Introducing Blockaid Risk Exposure" (2026-05-12) — https://blockaid.io/blog/compliance-cant-wait-a-minute-introducing-blockaid-risk-exposure
- [B6] "Blockaid Expands Onchain Monitoring Support for Tron" (2026-06-25) — https://blockaid.io/blog/blockaid-expands-onchain-monitoring-support-for-tron
- [B7] Blog index — https://blockaid.io/blog
- [B8] blockaid-client-node (Apache-2.0) — https://github.com/blockaid-official/blockaid-client-node
- [B9] Bitget hot wallet drain post — https://blockaid.io/blog/350m-bitget-hot-wallet-drain-how-blockaids-cosigner-and-onchain-monitoring-can-protect-exchange-hot-wallets
- [B10] API docs (OpenAPI) — https://docs.blockaid.io/reference/openapi-schema

**Breadcrumbs**
- [BC1] Homepage — https://www.breadcrumbs.app/
- [BC2] Pricing (government/LE) — https://www.breadcrumbs.app/pricing
- [BC3] Pricing (retail) — https://www.breadcrumbs.app/pricing/retail
- [BC4] API reference + MCP — https://docs.breadcrumbs.app/

**GraphSense / Iknaio**
- [G1] GraphSense homepage — https://graphsense.org/
- [G2] News & Releases — https://graphsense.org/news.html
- [G3] Iknaio platform — https://iknaio.com/platform/
- [G4] graphsense-lib — https://github.com/graphsense/graphsense-lib
- [G5] graphsense-tagpacks — https://github.com/graphsense/graphsense-tagpacks
- [G6] Haslhofer et al., "GraphSense: A General-Purpose Cryptoasset Analytics Platform" — https://arxiv.org/abs/2102.13613

**Secondary sources**
- [X2] **[secondary]** Didit, "Which Identity Vendors Have an MCP Server? 2026 Comparison" — https://didit.me/blog/identity-vendors-mcp-server-comparison/
- [X3] **[secondary]** DFNS Chainalysis integration — https://docs.dfns.co/integrations/aml-kyt/chainalysis
- [X4] **[secondary]** Sumsub TRM Labs chain list — https://docs.sumsub.com/docs/trm-labs
- [X5] **[secondary]** Sumsub Elliptic chain list — https://docs.sumsub.com/docs/elliptic
- [X6] **[secondary]** PublicAPIs.io Arkham API listing — https://publicapis.io/arkham-api
