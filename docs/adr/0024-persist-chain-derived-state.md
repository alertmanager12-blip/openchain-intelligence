# ADR-0024: Persist chain-derived state in PostgreSQL

- Status: proposed
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
