# ADR-0024: Persist chain-derived state in PostgreSQL

- Status: accepted (2026-11-09; step 1 implemented; step 2 implemented but off by default, does not scale yet)
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
- **Step 3 (needed):** bound the in-memory working set (the same volume makes memory grow by millions of flows per day,
  pre-existing), journal only the watchlist scope plus compact wallet-profile snapshots and the latest score per address,
  so that the reload is proportional to the number of wallets, not to the number of transfers.

