# ADR-0021: First market — self-hosted monitoring of stablecoin rails (TRON + EVM)

- Status: proposed (requires product validation)
- Date: 2026-11-09

## Context and problem statement

ChainSentinel cannot match the incumbents' proprietary attribution (Chainalysis, TRM, Elliptic, Arkham) nor out-feature
them on chain coverage. The competitive research (docs/strategy/market-research-sources.md, sources accessed 2026-11-09)
found no public self-hosting option at Chainalysis, TRM, Elliptic, Arkham or Blockaid; the only commercial on-premises
offer found (Breadcrumbs) costs USD 380k/year; the open-source player (GraphSense, MIT) documents no real-time
monitoring, alerting or risk scoring. No vendor positions itself as TRON- or stablecoin-first, while Elliptic reports
that most addresses of the 2026-10-29 Binance hack laundering set are TRON and EVM. The code base already indexes TRON
and Ethereum in real time, with USDT/USDC transfers, approvals and issuer freezes.

## Decision (proposed)

Focus the product on **self-hosted, real-time monitoring of stablecoin rails (USDT/USDC on TRON first, then EVM)** for
fintechs, payment providers, mid-size exchanges and companies handling stablecoins. New chains are added only when a
pilot needs them. Bitcoin and Solana stay out of scope (not in the UI) until then.

## Consequences

- Positive: builds on what works; addresses a documented gap (open + self-hosted + real-time + stablecoin rails).
- Negative: smaller addressable market than a generalist; attribution remains the main weakness (mitigated by open
  sources: OFAC, issuer freezes, TagPacks, partner lists).
- To validate: 10-15 customer interviews and 2-3 pilots (docs/strategy/MONETIZATION_STRATEGY.md § 6).
