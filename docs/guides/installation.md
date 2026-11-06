# Installation

## Requirements

| Component | Version |
|---|---|
| Docker Engine + Compose v2 | 24+ |
| (build from source) JDK | 21 |
| (build from source) Maven | 3.9+ |
| (build from source) Node.js | 22 LTS |
| RAM / disk (demo) | 2 GB / 2 GB |
| RAM / disk (one live network, 13 months transfers) | 8 GB / 200 GB+ SSD (depends on tracked tokens) |

## Option A — Docker Compose (recommended)

```bash
git clone https://github.com/openchain-intelligence/openchain-intelligence.git
cd openchain-intelligence
cp deploy/.env.example .env      # then edit secrets
docker compose up -d --build
```

Services:

| Service | URL | Notes |
|---|---|---|
| frontend | http://localhost:4200 | nginx serving the Angular build, proxies `/api` to the app |
| app | http://localhost:8080 | API v1 and `/actuator/health` (other actuator endpoints require `keys:admin`) |
| postgres | localhost:5432 | PostgreSQL 17, `pgdata` volume: operator state (API keys, connectors, rules, alerts, deliveries, label reports, users); schema migrated by Flyway when the app starts |

In demo mode (`OCI_DEMO_MODE=true`, the compose default) the app indexes two simulated chains (TRON, Ethereum), loads the community rule pack from `rules/` and prints a generated admin API key **once** in its logs:

```bash
docker compose logs app | grep "demo admin API key"
```

Paste it in the console (Settings, authentication "API key"). A new demo admin key is generated at every start and the previous one is revoked (take the last one logged). `deploy/smoke-test.sh` starts the stack and checks authentication, rules, demo scenarios A–E and the frontend proxy end to end, then restarts the app and checks that a key, a connector, a rule and an acknowledged alert survived.

The compose file passes a **publicly known, dev-only** `OCI_CRYPTO_MASTER_KEY` default so that the demo runs out of the box with PostgreSQL; the application refuses that key (and the dev-only API key pepper) when `OCI_DEMO_MODE=false`.

### Persistence and the `pgdata` volume

- `docker compose restart app`, `docker compose down` + `up`: operator state is kept (it lives in the `pgdata` volume).
- `docker compose down -v` **deletes the volume** and with it every API key, connector, rule change and alert. Back up first.
- Use a fresh database for production: do not reuse a database that served a demo (when demo mode is off, keys created by a previous demo bootstrap are revoked at startup, but demo alerts and rules stay).

### Observability profile (not instrumented yet)

```bash
docker compose --profile observability up -d
```

starts Prometheus (http://localhost:9090), Grafana (http://localhost:3000, admin password from `GRAFANA_ADMIN_PASSWORD`) and an OpenTelemetry Collector. **The application does not export metrics or traces yet** (no Prometheus registry, no OpenTelemetry SDK): Prometheus finds no target and the Grafana dashboard stays empty. Instrumentation is planned; until then this profile only provisions the tooling.

## Configuration

Settings are `oci.*` properties (see `oci-app/src/main/resources/application.yml`, the authoritative list), settable as environment variables through Spring relaxed binding.

| Variable | Default | Description |
|---|---|---|
| `OCI_DEMO_MODE` | `true` in compose, `false` otherwise | Simulated chains, fictitious labels, generated admin key. **Set `false` in production.** |
| `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | set in compose | PostgreSQL 17 (`jdbc:postgresql://host:5432/db`). **Set = operator state persisted** (Flyway migrates the schema at startup); unset = in-memory mode (startup log `Persistence mode: IN-MEMORY`, a WARN outside demo mode) |
| `OCI_SECURITY_API_KEY_PEPPER` | — | Secret pepper for API key hashing (≥ 32 bytes, `openssl rand -base64 48`). **Required with PostgreSQL outside demo mode** (startup fails otherwise); demo mode with PostgreSQL uses a documented dev-only pepper; in-memory mode uses a random one (keys invalidated at every restart). Changing it invalidates every stored key. |
| `OCI_CRYPTO_MASTER_KEY` | dev-only key in compose (demo only) | base64 of 32 random bytes (`openssl rand -base64 32`): AES-256-GCM key of connector secrets and targets at rest. **Required with PostgreSQL** (startup fails if missing, not 32 bytes, or the compose dev key outside demo mode). Never logged; losing it makes stored connector secrets unrecoverable. |
| `OCI_PERSISTENCE_DELIVERY_CLAIM_LEASE` | `2m` | How long an instance holds a claimed delivery; deliveries claimed by a crashed instance are retried after it |
| `OCI_SECURITY_OIDC_ISSUER_URI` / `OCI_SECURITY_OIDC_AUDIENCE` | — | OIDC issuer (enables JWT bearer authentication) and optional audience |
| `OCI_CORS_ALLOWED_ORIGINS` | `http://localhost:4200` | Allowed browser origins |
| `OCI_RULES_DIRS` | `rules,../rules` | Rule pack directories (first existing wins); the image ships `/app/rules` |
| `OCI_TELEGRAM_BOT_TOKEN` | — | Enables TELEGRAM connectors (the chat id is given per connector) |
| `OCI_CHAINS_TRON_ENABLED`, `OCI_CHAINS_TRON_PROVIDER=trongrid`, `OCI_CHAINS_TRON_URL`, `OCI_CHAINS_TRON_API_KEY_HEADER`, `OCI_CHAINS_TRON_API_KEY` | off | Live TRON indexing through a TronGrid-compatible API |
| `OCI_CHAINS_ETHEREUM_ENABLED`, `OCI_CHAINS_ETHEREUM_PROVIDER=json-rpc`, `OCI_CHAINS_ETHEREUM_URL`, `OCI_CHAINS_ETHEREUM_START_BLOCK` | off | Live EVM indexing through JSON-RPC (also `bsc`, `polygon`) |

Live providers are implemented but have not been validated against production nodes yet.

### Current limitations

- **Operator state is persisted** in PostgreSQL (API keys, connectors, rules and their versions, alerts with history and comments, the delivery outbox with retries and dead letters, label reports, users/roles) when `SPRING_DATASOURCE_URL` is set. **Chain-derived state is not persisted yet**: flows, wallet profiles, signals, risk scores, indexer checkpoints and the event history stay in memory and are rebuilt on restart by re-indexing from the configured start block (`OCI_CHAINS_*_START_BLOCK`; demo chains are regenerated). Re-indexing does not duplicate alerts: an event that already raised an alert for a rule is recognised. Alert details derived from the event history (address, transaction hash, matched fields) are only available for events re-indexed since the start.
- Single instance: rules and connectors are loaded in memory at startup; outbox claiming is multi-instance safe (`FOR UPDATE SKIP LOCKED` + lease), but running several app instances is not supported yet.
- No rate limiting, idempotency keys or `If-Match` concurrency control yet.
- Notification channels: WEBHOOK, SLACK, DISCORD, TELEGRAM. EMAIL and PUSH need a transport that is not available yet.

## Option B — from source

```bash
mvn -B verify                                        # backend build + tests (JDK 21+); PostgreSQL tests use Docker (Testcontainers), skipped with a message without it
cd frontend && npm ci && npm test && npm run build   # Node version from frontend/.nvmrc
cd .. && mvn -pl oci-app spring-boot:run             # demo profile, in memory: simulated chains + documented UI key
# with PostgreSQL: SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/oci SPRING_DATASOURCE_USERNAME=oci \
#   SPRING_DATASOURCE_PASSWORD=oci-dev-only OCI_CRYPTO_MASTER_KEY=$(openssl rand -base64 32) mvn -pl oci-app spring-boot:run
cd frontend && npm run start:api                     # console on http://localhost:4200 against the local app
```

## Upgrades

- **Back up before upgrading**: `docker compose exec -T postgres pg_dump -U oci -Fc oci > oci-$(date +%F).dump` (restore with `pg_restore`). Keep `OCI_CRYPTO_MASTER_KEY` and `OCI_SECURITY_API_KEY_PEPPER` with the backup, in your secret store: a dump without the master key cannot decrypt connector secrets, and without the pepper no stored API key authenticates.
- **Flyway migrates the schema automatically at startup** (`oci-app/src/main/resources/db/migration`, `flyway_schema_history` table). Migrations are forward-only: to roll back an upgrade, restore the backup taken before it. A failed migration stops the application before it serves traffic.
- Chain-derived state is rebuilt by re-indexing after the restart; repository rules (`rules/`) that changed are added as new rule versions (enabled flags kept), unchanged ones are left alone, and rules deleted by an operator are not re-imported.
- Read `CHANGELOG.md` for breaking changes; API v1 is additive within the major version.

## Production checklist

See [security-hardening.md](security-hardening.md). Minimum: `OCI_DEMO_MODE=false`, TLS reverse proxy, OIDC configured, `OCI_SECURITY_API_KEY_PEPPER` and `OCI_CRYPTO_MASTER_KEY` from a secret store, a strong `POSTGRES_PASSWORD`, regular database backups, port 8080 bound to a private interface only.
