> English translation of [CHAINSENTINEL_VISION.md](CHAINSENTINEL_VISION.md) (French original, authoritative). Translated on 2026-11-12; if the two differ, the French version prevails.

# ChainSentinel — Vision and positioning

> Status: **proposal to be validated** (2026-11-09). Based on the code audit at `44c0990`, real-world operation of the
> production instance (Ethereum mainnet + TRON) and the sourced market study
> ([market-research-sources.md](market-research-sources.md)).

## 1. Positioning

**ChainSentinel — The Open Intelligence Engine for Blockchain.**
*Understand. Predict. Investigate. Act.*

An Open Source (Apache-2.0), **self-hostable** platform that monitors stablecoin flows in real time
(USDT/USDC on TRON and EVM), explains every risk it flags, keeps the evidence for every conclusion and is
driven entirely through API, declarative rules and agents.

### What we are not

- Not a clone of Chainalysis, TRM or Elliptic: their main asset is a proprietary attribution database
  (« 1B+ labeled », « 2.2B+ attributed », « hundreds of clustering heuristics ») that we do not have and cannot
  rebuild in the short term.
- Not an academic forensics tool: GraphSense (MIT, self-hostable, TRON + USDT, built-in MCP) already occupies that
  ground; it does not, however, do real-time monitoring, alerting or risk scoring (public docs).

## 2. The demonstrable competitive advantage: an intersection, not a feature

The market study is unsparing: **no element taken in isolation is unique**.

| Element | Already covered by |
|---|---|
| Explainable score | Elliptic (logged factors), Blockaid (amounts/categories/%), Chainalysis (direct/indirect exposure) |
| MCP server / agents | TRM (Jul. 2026), GraphSense (June), Arkham (Oct.), Elliptic (public), Breadcrumbs |
| Open source + self-hosted | GraphSense (MIT) |
| Community intelligence | TRM Beacon, Arkham Intel Exchange, Breadcrumbs Network, GraphSense TagPacks |
| TRON coverage | all seven players |

What exists at **none** of the players studied is the combination:

1. **Open source and self-hosted** — none of the five SaaS offerings (Chainalysis, TRM, Elliptic, Arkham, Blockaid)
   documents self-hosting; the only commercial on-premise offering found (Breadcrumbs) costs 380,000 $/year.
2. **Real-time monitoring and alerting** — absent from the existing open source offering (GraphSense).
3. **"Stablecoin rails" specialization** — TRON-first + EVM, with issuer events (USDT/USDC freezes) as
   first-class signals; no player positions itself this way in the sources studied.
4. **Open, versioned rules, testable in CI** — competing engines are configured through closed
   interfaces.
5. **Reproducible, evidenced investigations** — every conclusion tied to its data, its parameters and the
   engine version; every probabilistic relationship presented as such.

Timely argument, verified at the source: Chainalysis stated on 2026-10-30 that « names, usernames, password
hashes and live tokens for all Chainalysis users were exposed », then shut down KYT (interface and API) from 2026-11-04 to
2026-11-07. For a PSP, depending on a single SaaS for compliance monitoring is a documented operational risk;
a self-hosted engine is an alternative or an **independent safety net**. We must not
exploit this incident in a disparaging way: it is an architecture argument, not aggressive marketing.

## 3. First target market (hypothesis to be validated)

**Fintechs, PSPs, mid-sized exchanges and companies that handle stablecoins** and want to monitor
their incoming/outgoing flows on their own infrastructure, integrate the decision into their systems (API, webhooks,
rules as code) and keep control of their data.

Why this segment:

- TRON/USDT and EVM concentrate stablecoin payment flows and, according to Elliptic, the majority of the
  laundering addresses from the Binance hack of 2026-10-29 (« Most are Tron and EVM (0x) addresses »).
- These players do not always have the budget for a Chainalysis/TRM contract (« contact sales »); Breadcrumbs charges
  499 $/month for TRON + risk score, Arkham Enterprise 1,500 $/month.
- The existing code is already oriented this way: real TRON + Ethereum indexing, USDT/USDC, issuer freezes,
  approvals, YAML rules, webhook/Slack/Discord/Telegram connectors.

What this segment requires and we do not yet have (see [PRODUCT_GAP_ANALYSIS.md](PRODUCT_GAP_ANALYSIS.md)):
persistence of indexed state, a measured low false-positive rate, real sanctions lists, a decision API
(pre-transaction screening), minimal high availability.

## 4. Non-negotiable principles

1. **Observed ≠ inferred ≠ predicted ≠ confirmed.** Every assertion carries its epistemic level
   (see proposed ADR-0023). A probabilistic association is never presented as an identity.
2. **No opaque score.** Every score exposes its contributions, its sources, its method and the engine version
   (already the case: `RiskScoringEngine`, model `oci-risk-1`).
3. **Reproducibility.** An investigation records its inputs, its sources, its parameters, the engine version
   and can be replayed.
4. **Determinism first.** AI is used for correlation, assistance and drafting, never to produce a risk
   conclusion on its own.
5. **Honesty about data limitations.** Non-archive node, absence of internal transactions, limited
   attribution: shown to the analyst, not hidden.
6. **No displayed feature that does not work.** (See the defects identified in phase 0.)
7. **Security and verification are never paid** (see [MONETIZATION_STRATEGY.md](MONETIZATION_STRATEGY.md)).

## 5. Answer to the mission's final question

> *How do we make ChainSentinel an Open Source blockchain intelligence platform that does not merely
> detect risks, but understands behaviours, automates investigations, anticipates threats and
> turns blockchain data into actionable and verifiable intelligence?*

**By making evidence the product, and by starting where no one is open: self-hosted real-time monitoring of
stablecoin rails.** Concretely:

1. **Make it reliable before extending it (phase 0-1).** Today the real instance produces 4,065 open alerts, most
   of them from three noisy rules, and 5,675 notifications lost to a non-existent default connector;
   all indexed state is lost on restart. An intelligence platform that cannot be trusted has no
   value. We measure the noise, we persist the state, we load real lists (OFAC, issuer freezes, TagPacks).
2. **Understand behaviours with open, measured detectors (phase 1-2).** The 10 existing detectors
   rely on raw data, without an attribution database: this is compatible with self-hosting. We publish
   their thresholds and their false-positive rates measured on real data, and we make them tunable per rule.
3. **Automate investigation as a verifiable artefact (phase 3).** An investigation is a persistent object:
   inputs, sources, parameters, engine version, graph, typed assertions (observed/inferred/predicted/confirmed),
   exportable, replayable report, which a human can validate. This is our main difference from competitors'
   « agents »: theirs are closed; ours are auditable line by line.
4. **Anticipate without over-promising (phase 4).** A prediction is a dated hypothesis, with signals, confidence,
   limitations and an expiry date, evaluated after the fact (published realization rate). Simulation builds on
   what exists (`RuleTester` dry-run): "what would have happened if this rule had run over the last 30 days?".
5. **Make the whole thing drivable by agents within the client's perimeter (phase 5).** MCP and SDK generated from
   the OpenAPI, with the same per-operation scopes as the API. This is not a differentiator (everyone has one),
   it is a prerequisite — but an MCP **on one's own data, on one's own premises** remains rare.
6. **Open up intelligence without diluting it (phase 6).** Interoperable format (TagPacks), provenance, reputation,
   revocation; unverified community information is never displayed as a fact.

The advantage lies in the combination **open + self-hosted + real-time + stablecoin rails + rules as code +
evidenced investigations**. The main risk — the leaders' attribution depth — is partially offset
by importing open sources and by the possibility of using ChainSentinel **alongside** a commercial
provider, not necessarily in its place.
