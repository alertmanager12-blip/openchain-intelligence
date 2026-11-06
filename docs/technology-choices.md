# Technology choices

Decision legend: **Adopted now** (in 0.1), **Later** (planned, with trigger), **Optional** (operator's choice, not required), **Rejected**. Licences as of late 2026 — always re-check before upgrading. Policy: [ADR-0012](adr/0012-licensing-policy.md).

| Technology | Role | Pros | Cons | Licence | Operational cost | Decision |
|---|---|---|---|---|---|---|
| Java 21 | Backend language/runtime | Records, virtual threads, `BigInteger`/`BigDecimal`, huge ecosystem | Memory footprint | GPLv2 + Classpath Exception (OpenJDK, Temurin) | Low | **Adopted now** ([ADR-0002](adr/0002-java-21-spring-boot.md)) |
| Spring Boot 4.1 | App framework (web, security, actuator, data) | OAuth2 resource server, Actuator/Micrometer, Testcontainers support | Major-version migrations | Apache-2.0 | Low | **Adopted now** |
| Web3j | EVM client library | Complete EVM support | Heavy deps, EVM only, own I/O stack | Apache-2.0 | n/a | **Rejected** for providers; may use `abi` module only ([ADR-0003](adr/0003-own-json-rpc-adapters.md)) |
| Apache Kafka | Durable event log | Replay, partitioned ordering, ecosystem | Ops burden (KRaft cluster, monitoring) | Apache-2.0 | Medium–High | **Later**: multi-consumer scale or external replay ([ADR-0006](adr/0006-event-bus-outbox-kafka-later.md)) |
| Apache Flink | Distributed stateful stream processing | Large windowed state, exactly-once, CEP | Complex ops, JVM cluster | Apache-2.0 | High | **Later**: only if window state exceeds a node |
| PostgreSQL 17 | System of record, transfers, graph adjacency | ACID, partitioning, RLS, recursive CTE, ubiquitous | Columnar analytics weaker | PostgreSQL Licence | Low | **Adopted now** ([ADR-0004](adr/0004-postgresql-first-clickhouse-later.md)) |
| ClickHouse | Analytical store for transfer history | Very fast aggregates, compression | Another datastore, eventual consistency | Apache-2.0 | Medium | **Later**: > 1–2 B rows or p95 > 2 s |
| Neo4j Community | Graph database | Cypher, GDS algorithms | GPLv3 server; clustering only in commercial Enterprise | GPLv3 | Medium | **Optional external adapter**, never bundled ([ADR-0005](adr/0005-graph-store.md)) |
| Memgraph | In-memory graph DB | Fast, Cypher | BSL 1.1 (not OSI); RAM-bound | BSL 1.1 / proprietary EE | Medium | **Rejected** (licence policy) |
| Apache AGE | Graph extension for PostgreSQL | openCypher in Postgres, single DB | Younger; not on all managed Postgres | Apache-2.0 | Low | **Later**: first candidate when CTEs limit us |
| Redis | Cache / rate-limit buckets | Fast, well known | Licence changed from BSD to RSALv2/SSPLv1 (7.4), AGPLv3 added as option in 8.0 | RSALv2 / SSPLv1 / AGPLv3 | Low | **Rejected as default**; use **Valkey** (BSD-3-Clause fork) if a cache is needed — **Optional** |
| Valkey | Cache / distributed rate limiting | Redis-protocol compatible, Linux Foundation, permissive | Extra component | BSD-3-Clause | Low | **Optional** |
| Angular | Frontend framework | Structure, strict TS, DI, testing | Learning curve, bundle size | MIT | Low | **Adopted now** ([ADR-0011](adr/0011-angular-cytoscape-frontend.md)) |
| Cytoscape.js | Graph visualisation | Mature model, layouts, permissive | Canvas limits ~10 k elements | MIT | n/a | **Adopted now** |
| Prometheus | Metrics scraping/storage | Standard, pull model, alerting | Local TSDB retention limits | Apache-2.0 | Low | **Adopted now** (observability profile) |
| Grafana | Dashboards | Rich dashboards, provisioning | AGPL-3.0 | AGPL-3.0 | Low | **Adopted as separate service**: runs as its own container, not linked or redistributed in our artefacts |
| OpenTelemetry | Traces/metrics/logs instrumentation + Collector | Vendor-neutral, standard | Configuration surface | Apache-2.0 | Low | **Adopted now** |
| Keycloak | OIDC identity provider (reference) | Full-featured, self-hostable | JVM service to operate | Apache-2.0 | Medium | **Optional** (any OIDC IdP works, [ADR-0010](adr/0010-authentication-oidc-api-keys.md)) |
| Docker / Compose | Packaging and reference deployment | Universal, simple | Single host | Apache-2.0 (Engine/CLI, Compose) | Low | **Adopted now** |
| Kubernetes | Orchestration at scale | HA, autoscaling, rolling updates | Complexity | Apache-2.0 | Medium–High | **Later / optional** (Helm chart planned) |
| Flyway | DB migrations | Simple SQL migrations | Some features in paid editions | Apache-2.0 (Community) | Low | **Adopted now** |
| Testcontainers | Integration tests | Real Postgres in tests | Needs Docker in CI | MIT | Low | **Adopted now** |
| TimescaleDB | Time-series on Postgres | Convenient | Key features under TSL (non-OSI) | Apache-2.0 / TSL | Low | **Rejected** |
| SpEL / scripting engines | Rule expressions | Flexible | RCE class, unbounded cost | Apache-2.0 / various | n/a | **Rejected** for user rules ([ADR-0007](adr/0007-declarative-rule-dsl-no-scripting.md)) |
