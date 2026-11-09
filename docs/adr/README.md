# Architecture Decision Records

Format: [MADR](https://adr.github.io/madr/). New ADRs: copy the structure of an existing one, next number, status `proposed`, open a PR.

| # | Title | Status |
|---|---|---|
| [0001](0001-modular-monolith-and-workers.md) | Modular monolith with worker role | accepted |
| [0002](0002-java-21-spring-boot.md) | Java 21 and Spring Boot | accepted |
| [0003](0003-own-json-rpc-adapters.md) | Own HTTP/JSON-RPC adapters instead of Web3j or vendor SDKs | accepted |
| [0004](0004-postgresql-first-clickhouse-later.md) | PostgreSQL first, ClickHouse later | accepted |
| [0005](0005-graph-store.md) | Graph store: Postgres CTE (MVP) vs Neo4j vs Memgraph vs Apache AGE | accepted |
| [0006](0006-event-bus-outbox-kafka-later.md) | In-process bus + outbox; Kafka later; Flink when needed | accepted |
| [0007](0007-declarative-rule-dsl-no-scripting.md) | Declarative rule DSL, no scripting | accepted |
| [0008](0008-explainable-risk-scoring.md) | Explainable risk scoring | accepted |
| [0009](0009-webhook-security.md) | Webhook security: HMAC and SSRF | accepted |
| [0010](0010-authentication-oidc-api-keys.md) | Authentication: OIDC + API keys | accepted |
| [0011](0011-angular-cytoscape-frontend.md) | Angular frontend + Cytoscape.js | accepted |
| [0012](0012-licensing-policy.md) | Licensing policy | accepted |
| [0020](0020-address-history-sources.md) | Address history: on-demand backfill through pluggable sources | accepted |
| [0021](0021-stablecoin-rail-focus.md) | First market: self-hosted monitoring of stablecoin rails (TRON + EVM) | proposed |
| [0022](0022-investigations-as-reproducible-artifacts.md) | Investigations as persistent, resumable, replayable artifacts | proposed |
| [0023](0023-epistemic-levels-for-assertions.md) | Epistemic levels for every assertion | proposed |
| [0024](0024-persist-chain-derived-state.md) | Persist chain-derived state in PostgreSQL | proposed |
| [0025](0025-mcp-as-api-adapter.md) | MCP server as a thin adapter of the REST API | proposed |
