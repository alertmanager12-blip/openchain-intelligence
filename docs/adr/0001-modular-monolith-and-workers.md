# ADR-0001: Modular monolith with worker role

- Status: accepted
- Date: 2026-11-06
- Deciders: core maintainers

## Context and problem statement

OCI needs indexing, detection, scoring, rule evaluation, notification delivery and an API. The team is small, the domain boundaries are still evolving, and the hardest correctness problems (reorg retraction, idempotency, outbox) benefit from a single transactional database. Self-hosters want a deployment they can run on one machine.

## Considered options

1. Microservices from day one (indexer, detection, alerting, API as separate services over Kafka).
2. Classic monolith (single module, no enforced boundaries).
3. **Modular monolith** (Maven modules with enforced dependency direction) deployable in `api` and `worker` roles from one image.

## Decision outcome

Chosen option: **3**. Modules `oci-core` (framework-free domain + SPI), `oci-blockchain`, `oci-intelligence`, `oci-alerting` (each depends only on `oci-core`), and `oci-app` (Spring Boot wiring). Roles are selected with `OCI_ROLES`.

### Pros

- One build, one image, one database transaction for alert + outbox.
- Boundaries enforced by the compiler (Maven dependency graph), so extraction to services stays possible.
- Simple self-hosting (`docker compose up`).

### Cons

- A single process failure affects API and workers when co-located (mitigated by running roles separately).
- Shared database becomes a coupling point if module ownership of tables is not respected.

## Consequences

- Each module owns its tables (prefix/convention), and no module reads another module's tables directly.
- Workers coordinate exclusively through PostgreSQL leases and the outbox, never in-memory singletons, so the worker role can scale horizontally.
- Architecture tests (ArchUnit) in `oci-app` verify the dependency rules.

## Pros and cons of the other options

- **Microservices**: independent scaling and deployment, but distributed transactions/sagas for alert+delivery, operational cost (Kafka, service mesh) unjustified at this stage.
- **Classic monolith**: fastest start, but boundaries erode and later extraction is costly.
