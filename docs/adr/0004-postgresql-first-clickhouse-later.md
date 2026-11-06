# ADR-0004: PostgreSQL first, ClickHouse later

- Status: accepted
- Date: 2026-11-06

## Context and problem statement

We store operational data (tenants, rules, alerts, outbox, audit) requiring transactions, and chain data (token transfers) that grows quickly and serves analytical queries (exposure, baselines, graph). Self-hosters need a simple footprint.

## Considered options

1. **PostgreSQL only in the MVP** (monthly range partitions for transfers, BRIN + B-tree indexes), ClickHouse added when needed.
2. PostgreSQL + ClickHouse from day one.
3. TimescaleDB.
4. A single NoSQL store (Cassandra/ScyllaDB).

## Decision outcome

Chosen option: **1**.

Trigger to add ClickHouse (Apache-2.0): sustained > 1–2 B transfer rows, or investigation queries p95 > 2 s after index tuning, or need for multi-year history with heavy aggregations. ClickHouse would then hold the transfer history and aggregates (fed from the outbox/Kafka); PostgreSQL remains the system of record for operational data.

### Pros

- One database to operate, back up and secure; ACID for alert + delivery outbox.
- Partitioning gives cheap retention (drop partition) and pruning.
- Recursive CTEs suffice for MVP graph depth (see ADR-0005).

### Cons

- Columnar analytics (long-range aggregates) are slower than in ClickHouse.
- Write amplification of indexes at high ingest rates.

## Consequences

- Repository interfaces for transfer queries are designed so that a ClickHouse implementation can be swapped in.
- Amounts stored as `numeric(78,0)`; ClickHouse equivalent would be `UInt256`/`Decimal256`.

## Pros and cons of the other options

- **Both from day one**: best analytics, but double operational burden and dual-write consistency for a not-yet-proven need.
- **TimescaleDB**: convenient, but key features are under the Timescale License (TSL, not OSI-approved) — conflicts with our licensing policy (ADR-0012) for the core distribution.
- **Cassandra/Scylla**: scale, but no transactions and poor ad-hoc querying for investigations.
