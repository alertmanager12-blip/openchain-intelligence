# oci-app

Spring Boot application wiring `oci-blockchain` → `oci-intelligence` → `oci-alerting` behind REST API v1
(`docs/api/README.md`). Operator state is persisted in PostgreSQL when `spring.datasource.url` is set (in memory
otherwise); chain-derived state is always in memory (see "Persistence").

## Run the demo

```bash
mvn -q -pl oci-app -am install -DskipTests
mvn -pl oci-app spring-boot:run          # activates the `demo` profile
```

Demo mode simulates TRON and Ethereum (deterministic chains, time-shifted to "now", one block every 3 s / 12 s),
loads the repository `rules/`, fictional labels, and indexes ~1 h of history at startup.

**Security is always on.** Two keys exist in demo mode:

| Key | Where | Permissions |
|---|---|---|
| demo admin | generated at every startup (the previous one is revoked), printed **once** in the log (`DEMO MODE: demo admin API key ...`), valid 24 h | all |
| Angular dev server | fixed and publicly documented, `demo` profile only: `oci_demo_devui001_DemoOnlyAngularDevServerKeyNotSecret0001` (override with `OCI_DEMO_UI_KEY`) | all `*:read`, `alerts:write`, `rules:write`, `labels:report` — no `webhooks:write`, no `keys:admin` |

Angular: `npx ng serve --configuration development` (proxy to :8080), then in *Settings* choose auth mode
"API key" and paste the dev-server key (it is sent as `X-API-Key`). CORS allows `http://localhost:4200`
(`OCI_CORS_ALLOWED_ORIGINS`).

```bash
curl -H "X-API-Key: $KEY" localhost:8080/api/v1/networks
```

Keys are never accepted in query parameters. Sensitive operations (key create/revoke, rule enable/disable/delete,
webhook create/delete/test, role changes) are written to the `oci.audit` logger, without secrets.

## Persistence

| Mode | When | Stores |
|---|---|---|
| PostgreSQL (JDBC) | `spring.datasource.url` / `SPRING_DATASOURCE_URL` set (Docker Compose) | `Jdbc*Repository`, `JdbcAlertRepository`, `JdbcDeliveryStore` (Spring `NamedParameterJdbcTemplate`, no JPA); schema by Flyway at startup (`src/main/resources/db/migration`) |
| In memory | no datasource URL (`mvn -pl oci-app spring-boot:run`, tests) | `InMemory*` stores; DataSource/Flyway auto-configuration excluded (`PersistenceEnvironmentPostProcessor`) |

The startup log states the mode (`Persistence mode: POSTGRESQL ...` / `Persistence mode: IN-MEMORY ...`, a WARN
outside demo mode). Each store is an interface with an in-memory and a JDBC implementation, selected in
`persistence/PersistenceConfiguration`:

- **Persisted (operator state)**: API keys (`api_keys`: prefix + HMAC hash, scopes, expiry, revocation,
  `last_used_at` written at most once a minute), connectors (`connectors`: target and WEBHOOK secret AES-256-GCM
  encrypted with `OCI_CRYPTO_MASTER_KEY`, masked target in clear), rules (`rules` + immutable `rule_versions`, source
  as submitted, compiled hash, enabled flag, soft delete, optimistic locking), alerts (`alerts` + append-only
  `alert_history` / `alert_comments`, optimistic locking), the delivery outbox (`deliveries`: status, attempts, next
  attempt, dead letter; claimed with `FOR UPDATE SKIP LOCKED` + a lease so that two instances never attempt the same
  delivery), label reports, users/roles. An event's alert, status changes and deliveries are written in one transaction.
- **In memory (chain-derived, rebuilt on restart by re-indexing from the configured start block)**: flows, wallet
  profiles, signals, risk scores, indexer checkpoints, the event history. `AlertService` ignores a replayed
  triggering event that already raised an alert for the rule, so re-indexing does not duplicate alerts or
  notifications. TODO(persistence): PostgreSQL adapters for these.
- **Secrets**: with PostgreSQL, `OCI_CRYPTO_MASTER_KEY` (base64, 32 bytes) and, outside demo mode,
  `OCI_SECURITY_API_KEY_PEPPER` are required: the application fails at startup with an explicit message otherwise.
  Demo mode accepts the dev-only master key of `docker-compose.yml` and falls back to a documented dev-only pepper;
  both are refused when demo mode is off.
- **Demo bootstrap is idempotent**: repository rules are imported once per distinct source (a changed file adds a
  version, an operator deletion is respected), the generated demo admin key of the previous start is revoked (older
  ones purged), demo users are inserted only if absent, demo scenario checks also count deliveries persisted by the
  previous process.

Tests: `JdbcRepositoriesPersistenceTest` (every repository, migration, optimistic locking, SKIP LOCKED claim,
encryption at rest, wrong master key) and `PersistenceRestartTest` (two application contexts on the same database)
run against PostgreSQL 17 with Testcontainers; they are skipped, with a message, when Docker is not available.

## Configuration

See `src/main/resources/application.yml`: `OCI_DEMO_MODE`, `SPRING_DATASOURCE_URL` (+ `_USERNAME`, `_PASSWORD`),
`OCI_CRYPTO_MASTER_KEY`, `OCI_SECURITY_API_KEY_PEPPER` (HMAC pepper for key hashes; required with PostgreSQL outside
demo mode, random and ephemeral in memory mode when unset), `OCI_SECURITY_OIDC_ISSUER_URI` (JWT resource server enabled only when
set; roles from the `oci_roles` claim), `oci.security.keys` (provisioned keys, hashed at startup),
`oci.chains.*` (real TronGrid / EVM JSON-RPC providers, off by default), `OCI_RULES_DIRS`.

## Demo scenarios (fictitious)

In demo mode five FICTITIOUS stories are injected into the simulated TRON ledger and processed by the real pipeline
(indexer → detection → risk → rule engine → alert → notification sink): A large transfer, B 2-hop exposure to a
(demo-)sanctioned address, C wallet draining, D fund splitting, E dormant wallet reactivation.
`GET /api/v1/demo/scenarios` lists each one with its events, signals, risk scores, alerts and simulated notifications
(`checks` must all be true); alerts carry `demoScenario`. Addresses are generated and labelled `demo-scenarios`.

The demo ledger (`DemoLedgerProvider`) funds every simulated wallet from a fictional faucet and drops simulated
transfers that would overdraw their sender, so balances and `wallet.balance` are consistent (no negative balances,
no balance ratios > 1). Alerts per rule on a fresh demo start, before → after: `wallet-draining` 122 → 2,
`suspicious-wallet` 40 → 42 (now the largest: every demo wallet is < 30 days old), total 162 → 51.

## Not implemented yet (TODO)

- **Persistence of chain-derived state** (transfers, profiles, signals, risk scores, checkpoints, event history);
  master key rotation; connector secret rotation; multi-instance cache invalidation for rules/connectors.
- **Idempotency** store (`Idempotency-Key`, 24 h replay, 409 on body mismatch).
- **Rate limiting** (token bucket, `RateLimit-*` headers). The `429 rate-limited` problem type and its
  `Retry-After` mapping exist, but nothing raises it yet.
- **ETag / If-Match** on `PUT /rules/{id}` (412). `GET /rules/{id}` sends an ETag but it is not enforced.
- **Kafka** event bus (ADR-0006): the pipeline is called in-process; the delivery outbox is a PostgreSQL table.
- `token` / `direction` / `from` / `to` filters on `GET /wallets/{a}/transactions`.
- Catalog fields/event types never produced yet (`transaction.amount_usd`, `wallet.watchlisted`, `contract.*`,
  `approval.*`, `CONTRACT_*`, `APPROVAL`, `RISK_SCORE_CHANGED`, ...): flagged `available=false` by `/rules/fields`
  and `/rules/event-types`, and marked "not yet available" in the Alert Builder.
- Multi-tenancy (`oci_tenant`), audit_log table, Slack/Discord/Telegram/email connector management,
  label report moderation, alert re-open (the alerting lifecycle makes RESOLVED/FALSE_POSITIVE terminal).
