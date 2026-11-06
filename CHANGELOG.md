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

- Ingestion of **native transfers** (ETH/BNB/POL of transactions with receipt `status 0x1`, TRX of `TransferContract` / `TriggerSmartContract.call_value` with `contractRet SUCCESS`; zero-value and failed transactions are not flows) as `NATIVE_TRANSFER` events (`network:txHash:native`), feeding exposure, fast-forwarding and drain detection. EVM: `eth_getBlockReceipts` with automatic fallback to `eth_getLogs({blockHash})` + per-transaction `eth_getTransactionReceipt` when the node reports the method as unsupported. Internal transactions are not covered.
- Ingestion of ERC-20/TRC-20 **approvals** (`APPROVAL` events with `approval.owner/spender/amount/unlimited`), registry tokens only unless `oci.chains.<network>.approvals-of-unknown-tokens` is set; they feed `UnusualApprovalDetector`.
- Ingestion of **stablecoin issuer freezes**: Tether `AddedBlackList`/`RemovedBlackList` (Ethereum, TRON), Tether USDT0 `BlockPlaced`/`BlockReleased` (Polygon), Circle `Blacklisted`/`UnBlacklisted` (USDC Ethereum, Polygon), decoded only from the official contracts (`StablecoinFreezeSources`). New event types `STABLECOIN_FREEZE` / `STABLECOIN_UNFREEZE` and rule fields `freeze.*` (`freeze.action` source-backed). A freeze labels the address `ISSUER_FROZEN` (CONFIRMED_BY_SOURCE, scoring weight 0.60, non-decaying), rescores it and re-runs detection for its counterparties; an unfreeze removes the label (`LabelRepository.remove`) and rescores; a reorged freeze/unfreeze is reverted.
- Community rule `rules/generic/issuer-freeze-counterparty.yaml`; demo scenario F (issuer freeze of a counterparty) and a demo unlimited approval to a listed spender; the simulator produces native transfers, approvals and scheduled freezes.
- Native dust thresholds for exposure: ETH 0.005, BNB 0.02, POL 20, TRX 50 (and USDC.e 10).
- Event topic constants verified by `EventTopicsTest` against a test-only Keccak-256 implementation.
- Ingestion of **contract admin changes** as `CONTRACT_ADMIN_CHANGE` events on EVM and TRON: `OwnershipTransferred` (indexed OpenZeppelin layout and Circle's non-indexed layout), EIP-1967 `Upgraded` and `AdminChanged` (topics verified in `EventTopicsTest`). Scope: token registry contracts + `oci.chains.<network>.watched-contracts` (empty by default), or every contract with `admin-changes-of-all-contracts: true`. Fields `admin.{contract,previous,new,role,renounced}` (read by `OwnershipChangeDetector`) and rule fields `contract.{address,event,new_owner}`.
- Approval spender enrichment: `approval.spender_is_contract` (EVM `eth_getCode` at the event block with `latest` fallback, TRON `/wallet/getcontract`; bounded LRU cache `ContractTypeCache` with lookup counters) and `approval.spender_age_days` (days since the platform first saw the spender in an indexed flow; absent when never seen). `approval.spender_verified` stays unpopulated (no verification source). `rules/ethereum/suspicious-approval.yaml` now fires on live data through its age branch.
- **Bounded freeze backfill** (`FreezeBackfill`, `oci.chains.<network>.freeze-backfill.*`, disabled by default): chunked `eth_getLogs` (EVM) / TronGrid contract-events (TRON) scan of the `StablecoinFreezeSources` contracts over finalized blocks, adaptive chunk halving on "too many results / range too large", pause between chunks, resumable and idempotent; found freezes feed the same label/rescore path as live ones (no rule evaluation). Admin endpoints `POST` / `GET /api/v1/admin/backfill/freezes` (`keys:admin`). Flyway migration `V2__freeze_backfill` (`backfill_progress`, `backfill_freeze_events`); stored freezes are re-applied at startup without RPC.

### Changed

- `BlockchainProvider.getBlockActivity(from, to)` (default: token transfers only) is what `BlockIndexer` now reads; `ChainEvent.EventType` gains `STABLECOIN_FREEZE`, `STABLECOIN_UNFREEZE` (rule schema `eventType` enum updated); `LabelRepository` gains `remove(address, category, source)`; `IndexerConfig` gains `approvalsOfUnknownTokens` (old constructor kept). `GET /api/v1/rules/event-types` now reports `NATIVE_TRANSFER`, `APPROVAL` and the freeze types as available.
- `BlockActivity` gains `adminChanges` (4-argument constructor kept); `BlockchainProvider` gains `isContract(address, atBlock)` (default: unknown); `IndexerConfig` gains `watchedContracts` / `adminChangesOfAllContracts` (old constructors kept); `JsonRpcException.rangeTooLarge()`; `Address.isZero()` / `Address.TRON_ZERO`. `GET /api/v1/rules/event-types` reports `CONTRACT_ADMIN_CHANGE` as available; `GET /api/v1/rules/fields` reports `contract.address/event/new_owner` and `approval.spender_is_contract/spender_age_days` as available.

### Fixed

- `OwnershipChangeDetector` recognised only the EVM zero address as a renouncement (regex `0x0+`): a TRON `renounceOwnership` (new owner `T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuWwb`, base58 of `0x41` + 20 zero bytes) was reported as a MEDIUM ownership change instead of INFO. An implementation upgrade to `address(0)` is no longer treated as a renouncement.

### Security

- Risk scores can no longer be raised for free by third parties: unsolicited dust (below 10 USDT/USDC by default, `DetectionConfig.Exposure.dustThresholds`) and transfers of unverified token contracts (fake tokens whose `Transfer` events name a sanctioned sender) no longer create direct or indirect exposure. They produce a `DUSTING` anomaly signal with zero score weight instead. Previously 0.000001 USDT, or a fake-token transfer, from a sanctioned address scored the recipient 80/HIGH.

[Unreleased]: https://github.com/openchain-intelligence/openchain-intelligence/commits/main
