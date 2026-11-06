# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html).
Public API stability (REST API v1, rule schema `openchain.io/rule/v1`) is guaranteed from 1.0.0; during 0.x minor versions may contain breaking changes, which are always listed here.

## [Unreleased]

### Added

- Modular monolith skeleton (Maven multi-module, Spring Boot 4.1, Java 21): `oci-core`, `oci-blockchain`, `oci-intelligence`, `oci-alerting`, `oci-app`.
- `oci-core` framework-free domain model and SPI: `Network`, `Address`, `Token`, `TokenTransfer`, `ChainTransaction`, `BlockRef`, `Balance`, `NetworkStatus`, `Page`, `ChainEvent`, `Signal` (with evidence levels), `RiskScore` (with contributions), `BlockchainProvider`.
- Architecture documentation: overview, C4 model (Structurizr DSL + Mermaid), sequence diagrams, data model, rule engine design.
- Architecture Decision Records 0001–0012.
- API v1 contract summary with webhook signature verification examples (Python, Node.js, Java).
- Guides: installation, quickstart (demo mode), writing rules, security hardening; testing strategy; technology choices.
- Docker Compose reference deployment (PostgreSQL 17, app, frontend, optional observability profile with OpenTelemetry Collector, Prometheus, Grafana).
- CI workflow (Maven, frontend, Docker build, licence check placeholder, CodeQL), Dependabot, issue and PR templates.
- OSS governance files: LICENSE (Apache-2.0), NOTICE, CONTRIBUTING (DCO), SECURITY, CODE_OF_CONDUCT (Contributor Covenant 2.1).
- PostgreSQL persistence of operator state (when `SPRING_DATASOURCE_URL` is set; in memory otherwise): API keys, connectors (targets and WEBHOOK secrets AES-256-GCM encrypted with `OCI_CRYPTO_MASTER_KEY`), rules and immutable versions, alerts with history and comments, delivery outbox (retries and dead letters survive a restart, `FOR UPDATE SKIP LOCKED` claiming), label reports, users. Flyway migration `V1__operator_state`; Spring JDBC; optimistic locking on rules and alerts; Testcontainers PostgreSQL 17 tests. Chain-derived state stays in memory and is rebuilt by re-indexing; replayed events do not duplicate alerts.
- `OCI_CRYPTO_MASTER_KEY` (required with PostgreSQL) and `OCI_SECURITY_API_KEY_PEPPER` (required with PostgreSQL outside demo mode): the application fails fast when they are missing or when the dev-only compose defaults are used outside demo mode.

[Unreleased]: https://github.com/openchain-intelligence/openchain-intelligence/commits/main
