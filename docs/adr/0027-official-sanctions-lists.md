# ADR-0027: Official sanctions lists, fetched with their provenance

- Status: accepted (2026-11-18, maintainer's go)
- Date: 2026-11-18
- Related: ADR-0008 (explainable scoring), ADR-0012 (licensing), ADR-0023 (epistemic levels), ADR-0026 (client scope)

## Context and problem statement

Production on 2026-11-18: **0 labels loaded** (`oci.labels.files` is empty). The `sanctioned-exposure-signal` rule and the
SANCTIONED / exposure factors of the score therefore see only what the demo or a hand-made file provides: in real mode,
sanctions screening is blind, and nothing on screen says so.

The loader exists (`LabelFileLoader`, CSV/JSON, fail-fast) but no real list is produced, refreshed or dated.

Measured on the OFAC publication of 2026-11-17:

| Export | TRON addresses | EVM addresses |
|---|---|---|
| `SDN.CSV` (remarks column cut at 1,000 characters) | 132 | 100 |
| `SDN_ADVANCED.XML` (full feature list, 129 MB) | **352** | **127** |

A converter built on the CSV would miss about two TRON sanctioned addresses out of three.

## Decision

1. **Source**: the OFAC SDN list, `SDN_ADVANCED.XML` from `sanctionslistservice.ofac.treas.gov` (US government work,
   public domain: compatible with ADR-0012). Only the "Digital Currency Address" features are kept, for the networks
   this instance indexes:
   - TRON: `T…` addresses (34 characters) listed under TRX, USDT or USDC;
   - Ethereum: `0x…` addresses listed under ETH, USDT or USDC. An address listed only under another EVM chain (BSC,
     ARB, ETC) is **not** applied to Ethereum: same key format, but OFAC named another chain.
2. **Label**: category `SANCTIONED`, source `OFAC SDN`, evidence `CONFIRMED_BY_SOURCE`, reliability 1.0,
   `observedAt` = the list's publication date, reference = SDN entry uid, name and programme (e.g. `CYBER2`). Per ADR-0023
   rule 4, only the listing is Confirmed; an exposure derived from it stays Inferred.
3. **Fetch and keep**: the application fetches the list at start and every 24 h (configurable, can be turned off). Each
   accepted publication is stored in PostgreSQL (publication date, fetch time, SHA-256, address count, entries), so a
   restart or an outage of the OFAC site never empties screening: the **last accepted list** stays in force.
4. **Guard against a bad download**: a new publication is refused (the previous one stays, with a warning and a metric)
   if it does not parse, or if its address count drops by more than 20 % at once.
5. **Delisting**: an address no longer in the list loses its label from that publication on. Past alerts stay as they
   were (the address was listed when they were raised); their detail says "delisted on <date>". Nothing is rewritten.
6. **Said on screen**: a "Sanctions lists" status (source, publication date, last check, count, stale after 72 h) in
   Settings and in the coverage sentence of verdicts: "Sanctions screening: OFAC SDN of 17 Nov 2026 (352 TRON, 127 EVM
   addresses)". No list or a stale list is said plainly, never shown as "no sanction found".
7. **Air-gapped instances**: fetch off, the same converter available as a command producing the CSV for
   `oci.labels.files` (existing path).

## Consequences

- Real sanctions screening on TRON and Ethereum, dated and reproducible (the publication used by a verdict is known).
- One outbound HTTPS call a day to a US government site (to be stated in the installation guide).
- 129 MB parsed once a day: streaming parser (StAX), never the whole document in memory.
- Out of scope, next decisions: other official lists (UK OFSI, EU, UN: few crypto addresses), community scam/phishing
  lists (licence review per ADR-0012), exchange attribution.

## Validation before acceptance

- Parser test on a reduced extract of the real XML (TRON, EVM, other chains, an entry with several addresses).
- Tests: refused publication keeps the previous one; delisting; restart without network keeps the stored list.
- In production after deployment: label count equals the converter's count; the three existing
  `sanctioned-exposure-signal` alerts and the 63-alert grid re-read against the list.
