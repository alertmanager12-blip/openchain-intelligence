# ADR-0005: Graph store — PostgreSQL adjacency with recursive CTE in the MVP

- Status: accepted (revisit at 0.3)
- Date: 2026-11-06

## Context and problem statement

Investigations need counterparty graphs (who sent to whom, how much, over which token and time range) and exposure (shortest/any path to labelled addresses within N hops). Queries are depth ≤ 3 in practice; deeper traversal through exchange hot wallets explodes combinatorially and is rarely meaningful. The project is Apache-2.0 and must remain distributable without copyleft or source-available obligations in its core.

## Considered options

| Option | Licence | Notes |
|---|---|---|
| **A. PostgreSQL adjacency (`address_edges`) + recursive CTE** | PostgreSQL Licence (permissive) | No new component; good up to depth 3 with fan-out caps |
| B. Neo4j Community Edition | **GPLv3** (server); Enterprise is commercial | Mature Cypher, GDS library; GPL server used over network is acceptable to run, but it cannot be bundled/embedded in an Apache-2.0 distribution without care; clustering only in Enterprise |
| C. Memgraph | **BSL 1.1** (converts to Apache-2.0 after the change date), enterprise features under a proprietary licence | Fast in-memory, Cypher; BSL is not OSI-approved and restricts some commercial uses |
| D. Apache AGE (PostgreSQL extension) | **Apache-2.0** | openCypher inside PostgreSQL; keeps one database; younger project, extension must be installed (not on all managed Postgres offerings) |

## Decision outcome

Chosen option: **A** for the MVP. `address_edges` aggregates transfers per `(network, from, to, token)`; traversal is a recursive CTE with depth limit, cycle detection (path array), per-node fan-out cap (top-K by amount), time and token filters, and a statement timeout. Results are flagged `truncated` when caps apply.

Evaluated next step when depth/fan-out needs grow: **D (Apache AGE)** first, because it preserves the licence policy and single-database operations. Neo4j Community may be supported as an **optional, external** adapter behind a `GraphRepository` interface (talking Bolt over the network, never bundled), clearly documented as GPLv3 software operated by the user. Memgraph is not adopted because BSL conflicts with ADR-0012 for anything we ship.

### Pros

- Zero additional operational components; transactional consistency with transfers.
- Licence-clean.

### Cons

- Recursive CTEs degrade beyond depth 3–4 and on hub nodes; no graph algorithms (PageRank, community detection) out of the box.

## Consequences

- A `GraphRepository` port is defined in the application; the CTE implementation is the default.
- Known hubs (exchange hot wallets, bridges) are labelled and traversal stops at them by default (configurable).
- Benchmarks on the demo dataset are part of the test suite to detect regressions.
