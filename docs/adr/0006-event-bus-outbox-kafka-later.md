# ADR-0006: In-process event bus + transactional outbox; Kafka later; Flink only when needed

- Status: accepted
- Date: 2026-11-06

## Context and problem statement

Events flow indexer → detection → scoring → rule engine → alerts → notifications. Notifications must not be lost or duplicated in a way receivers cannot handle. Self-hosters should not need a message broker for a single-node deployment, but the design must scale out later.

## Considered options

1. **In-process bus** (per-key ordered dispatch, bounded queues) + **PostgreSQL transactional outbox** for durable side effects; Kafka relay added later.
2. Kafka from day one.
3. RabbitMQ / NATS.
4. Apache Flink for all stream processing.

## Decision outcome

Chosen option: **1**.

- Every `ChainEvent` has a deterministic `eventId`; all consumers are idempotent on it.
- Anything that must survive a crash (deliveries, domain events intended for external consumers) is written to the outbox in the same transaction as the state change.
- **Kafka (Apache-2.0)** is introduced when we run multiple independent consumers at scale or need replay for external systems: an outbox relay publishes to topics partitioned by `network:address`. No consumer code changes beyond the transport adapter.
- **Flink (Apache-2.0)** is introduced only when windowed state exceeds a single node (e.g. hundreds of millions of live groups, windows > 7 d) or when exactly-once cross-partition CEP is needed. The declarative rule DSL (ADR-0007) is designed to be translatable to Flink jobs.

### Pros

- No broker to operate in the MVP; simple local development.
- Exactly-once *effects* through idempotency + outbox, without distributed transactions.

### Cons

- In-memory window state must be rebuilt on restart (replay from `token_transfers`, bounded by the largest window).
- Horizontal scaling of evaluation requires partition leases (per network/address range) until Kafka is introduced.

## Consequences

- An `EventPublisher`/`EventSubscriber` port abstracts the bus.
- Outbox rows carry `topic`, `key`, `payload`, `headers` to be Kafka-ready.
- Metrics: queue depth, outbox backlog, processing lag.
