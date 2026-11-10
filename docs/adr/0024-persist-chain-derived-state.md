# ADR-0024: Persist chain-derived state in PostgreSQL

- Status: accepted (2026-11-09; step 1 implemented; step 2 implemented but off by default, does not scale yet; step 3a implemented, not sufficient on its own: see the 2026-11-10 measurement; step 3b implemented: hard caps and compact accumulators)
- Date: 2026-11-09

## Context and problem statement

Operator state (keys, rules, alerts, connectors, deliveries), freeze backfill and address history are persisted, but
flows, wallet profiles, signals, risk scores, indexer checkpoints (`InMemoryCheckpointStore` in
`ChainIngestionService`), the event history used by rule dry-runs and the label catalogue are in memory. A restart
loses the intelligence and re-indexes from the start block. Measured volume: ~290k events in 3 days on two chains with
partial coverage.

## Decision (proposed)

Implement JDBC stores behind the existing interfaces (CheckpointStore, flow store, profile, signal and risk-score
repositories, event history), with monthly partitioning of flows/events and configurable retention. Keep the in-memory
implementations for tests and the demo profile. ClickHouse (ADR-0004) is reconsidered only past a measured threshold
(ingestion rate or query latency target missed).

## Consequences

- Positive: restarts without loss; dry-runs and investigations over real history; prerequisite for ADR-0022.
- Negative: write amplification on hot paths (batching required); migration effort; storage cost.

## Implementation

- **Step 1 (done, 2026-11-09):** indexer checkpoints of real chains in `indexer_checkpoints` (V8, `JdbcCheckpointStore`):
  resume without gap after a restart, guarded by `max-resume-gap` (7200 blocks by default; a larger gap restarts near the
  head and logs the skipped range). The reorg window is stored as block references only (one small upsert per block):
  reorgs are detected after a restart, but events of blocks indexed before it are not retracted.
- **Step 2 (done, 2026-11-09):** flows, signals and risk scores journaled write-behind (`value_flows`, `signals`,
  `risk_scores`, V9) through decorators of the in-memory stores, so detectors keep reading memory; reloaded in the
  background at start-up within `intelligence-retention` (7 days), flows rebuilding wallet profiles without detection;
  daily purge; drops counted (`oci_journal_dropped_total`). Not persisted: the event history behind rule dry-runs and the
  rule engine windows. **Off by default** (`oci.persistence.intelligence-journal`): measured in production with Ethereum
  mainnet + TRON at the head, ~4.8 million flows and ~950,000 signals per day, and the reload took 316 s for 50,000 flows.
- **Step 3a (implemented 2026-11-09, production validation in progress):** the in-memory working set is trimmed every
  10 minutes. Flows older than 24 h and profiles (with their scores) of wallets without a flow for 7 days are removed;
  ages are measured per network against the latest flow time (event time), so a catch-up keeps what it indexes and a
  stalled network does not block the others. Signals are removed only when older than 48 h **and** their wallet left the
  working set, because risk scores are recomputed from the stored signals (180-day half-life): removing them earlier would
  lower scores. HIGH/CRITICAL signals, the watchlist and addresses with a loaded history are always kept; non-transfer
  event ids are capped at 500,000. A flow racing the eviction of its wallet goes to a fresh profile (retired
  accumulators). Trade-offs for other wallets: detectors see 24 h of flows (circular flows, unusual-amount baseline,
  indirect exposure), a wallet back after 7 days idle restarts its profile. `OCI_INGESTION_WORKING_SET_TRIM_INTERVAL=0`
  disables it.
  Measured on 2026-11-10 (Ethereum mainnet + TRON, app alone in a 4 GB container, 3 GB heap, all retentions set to 2 h):
  each trim takes 0.3 to 2.4 s and removes 9,000 to 64,000 flows, yet after 3 hours the working set still grows
  (962,000 flows, 301,000 signals, 650,000 profiles) and the old generation reaches 2.9 GB of 3 GB, with 269 full GCs
  (7.5 min of cumulated pauses). Step 3a slows the growth but does not bound memory at chain-wide volume: step 3b is needed.
- **Step 3b (implemented 2026-11-10, production measurement pending):** hard caps and compact accumulators.
  - Caps applied after the time windows (`OCI_INGESTION_WORKING_SET_MAX_FLOWS`, `_MAX_PROFILES`, `_MAX_SIGNALS`; defaults
    400,000 / 300,000 / 150,000, sized for a 3 GB heap): beyond a cap the oldest flows, the least recently active profiles
    (with scores and tracking) and the oldest signals of wallets without a profile go, HIGH/CRITICAL included (raised
    alerts are in PostgreSQL). Items sharing the boundary instant go together, so a cap always holds. Kept addresses are
    neither removed nor counted.
  - Wallet accumulators no longer grow with the number of transfers: the latest 4,096 event ids and transaction hashes for
    de-duplication, exact activity times for the latest 2,048 instants (older ones folded into first time, end of span
    and longest gap), the 1,024 heaviest counterparties, distinct contracts counted up to 1,024. Totals and counts stay
    exact; the approximations only concern replays older than the latest 4,096 events of a wallet, out-of-order events
    older than its folded span and the tail of its counterparties.
  - Consequence: under sustained chain-wide volume the caps, not the time windows, set the effective look-back of detectors
    for unwatched wallets. Watch a wallet or load its history to keep its full working set.
- **Step 3c (needed for the journal):** journal only the watchlist scope plus compact wallet-profile snapshots and the
  latest score per address, so that the reload is proportional to the number of wallets, not to the number of transfers.

