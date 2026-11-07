# ADR-0020: Address history — on-demand backfill through pluggable sources

- Status: accepted
- Date: 2026-11-07
- Numbering: ADR-0013 to ADR-0019 are reserved for the WSO2 API Manager integration.

## Context and problem statement

The indexer processes blocks from its start block onwards. Standard Ethereum JSON-RPC has **no method that lists the
transactions of an address** (`eth_getLogs` filters logs by emitting contract and positional topics only). As a result,
analysing an existing address that ChainSentinel has not indexed (for example `0x0f2897EC295cb6D6776C10Ad16a1D4546C25ec06`)
returns an empty wallet: no transactions, no counterparties, score `UNKNOWN`. Investigating an arbitrary address on demand
is a core use case, so the platform needs a source of per-address history: native transfers, ERC-20 transfers, internal
transactions and approvals, for Ethereum first, then the other EVM networks; TRON history is already available through
TronGrid (`/v1/accounts/{address}/transactions` and `/transactions/trc20`, used by `TronGridProvider`).

Constraints: self-hostable and sovereign by default (positioning), own JSON-RPC/HTTP adapters (ADR-0003), licence policy
(ADR-0012), no data source whose terms would make operators breach them by default.

## Considered options (facts checked on 2026-11-07, sources in the research notes)

| Option | Native | ERC-20 | Internal | Approvals | Networks | Self-hosted | Licence / terms | Cost |
|---|---|---|---|---|---|---|---|---|
| **A. `eth_getLogs` by topic** (existing RPC) | No | Yes | No | Yes | All EVM | Yes | n/a (operator's RPC) | Provider range limits |
| **B. Erigon 3 + Otterscan `ots_` API** (`ots_searchTransactionsBefore/After`, `ots_getInternalOperations`) | Yes | Partial: logs of the transactions found; pure token receipts likely missed (to verify) | Yes | Via receipts / A | Ethereum, Gnosis (not Polygon) | Yes | Erigon LGPL-3.0, called over JSON-RPC (not linked) | Archive node: ~2.25 TB used, 4 TB recommended, 32–64 GB RAM |
| **C. Etherscan API V2** (`txlist`, `tokentx`, `txlistinternal`) | Yes | Yes | Yes | No | Free: Ethereum, Polygon, Arbitrum; BSC/Base/OP paid; no TRON | No | Free plans "Personal & Research" only; commercial display from the Advanced plan; no bulk archiving or relaying; attribution "Powered by Etherscan.io APIs"; non-compete clause | 3 calls/s, 100k/day free |
| **D. Alchemy `alchemy_getAssetTransfers`** | Yes | Yes | Ethereum, Polygon, Base | No | Ethereum and major L2s; TRON via RPC only | No | Internal business use, no resale or mirroring, non-compete | 120 CU per call, 30 M CU/month free |
| E. Blockscout | Yes | Yes | Yes | ? | Hundreds | Heavy (archive node + ~21 TiB PostgreSQL) | **Source-available licence since 2026-04-22** (commercial licence needed to monetise) | Public instances 5–10 rps |
| F. TrueBlocks | Yes | Yes | Yes | Yes | EVM | Yes | GPL-3.0 (run as a separate service) | Archive/tracing node |

## Decision outcome

Introduce an **`AddressHistorySource` SPI** in `oci-core` (`fetch(address, network, fromBlock, toBlock, cursor) → page of
ChainEvent`), and an **on-demand backfill**: when an analyst opens an address that is not covered, the application
fetches its history page by page, feeds it through the normal pipeline (detection, scoring, rules evaluation without
raising alerts for history, like the freeze backfill), stores progress (resumable, idempotent through event ids) and shows
"history loading / covered up to block N" in the console.

Implementations, in this order:

1. **A — `eth_getLogs` by topic** (no new dependency): ERC-20 transfers and approvals where the address is in topic 1 or 2,
   chunked block ranges with the adaptive halving already used by the freeze backfill. Works on every EVM network with the
   RPC already configured. Covers the largest share of stablecoin-related risk.
2. **B — Erigon `ots_` adapter** (sovereign reference for native and internal value on Ethereum/Gnosis), enabled when the
   operator points ChainSentinel at an Erigon archive node with `--http.api=eth,erigon,trace,ots`.
3. **C/D — commercial adapters, opt-in only**: Etherscan V2 and Alchemy, disabled by default, activated by the operator
   with **their own key**, with the terms summarised in the configuration docs and the required attribution shown in the
   console. Never bundled with a shared key.
4. **TRON**: reuse the existing TronGrid account endpoints through the same SPI.

Not adopted: Blockscout self-hosting (licence change and operating cost), TrueBlocks as a default (GPL service + extra
operations; may be added later as an optional adapter).

### Consequences

- Positive: arbitrary addresses become analysable; sovereign by default; commercial sources stay the operator's choice
  and contractual responsibility; one ingestion path, so detectors, scoring and labels behave identically for live and
  historical data.
- Negative: without B (or an opt-in C/D), native and internal transfers of addresses outside the indexed window remain
  invisible — the console must state the coverage explicitly ("ERC-20 history only", "native history: not configured").
  Large addresses (exchanges) need hard caps (max pages, max events) and a visible "truncated" flag.
- Risks to verify before implementation: whether `ots_searchTransactions*` misses pure token receipts; whether it works on
  a non-archive Erigon 3; the 10,000-result cap of Etherscan listings; legal review of non-compete clauses (C, D) if the
  project is ever offered commercially.

## Demo path

To analyse one real Ethereum address immediately, option A needs only an Ethereum JSON-RPC endpoint (operator's node or
provider URL in `oci.chains.ethereum.url`). For native and internal transactions in a demo, option C with a free personal
Etherscan key (`ETHERSCAN_API_KEY`, personal/research use only) is the fastest.
