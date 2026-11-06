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
| postgres | localhost:5432 | started with a `pgdata` volume, **not used yet**: the application keeps all state in memory (see "Current limitations") |

In demo mode (`OCI_DEMO_MODE=true`, the compose default) the app indexes two simulated chains (TRON, Ethereum), loads the community rule pack from `rules/` and prints a generated admin API key **once** in its logs:

```bash
docker compose logs app | grep "demo admin API key"
```

Paste it in the console (Settings, authentication "API key"). `deploy/smoke-test.sh` starts the stack and checks authentication, rules, demo scenarios A–E and the frontend proxy end to end.

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
| `OCI_SECURITY_API_KEY_PEPPER` | — | Secret pepper for API key hashing (≥ 32 bytes). If unset a random one is used and keys are invalidated at every restart. |
| `OCI_SECURITY_OIDC_ISSUER_URI` / `OCI_SECURITY_OIDC_AUDIENCE` | — | OIDC issuer (enables JWT bearer authentication) and optional audience |
| `OCI_CORS_ALLOWED_ORIGINS` | `http://localhost:4200` | Allowed browser origins |
| `OCI_RULES_DIRS` | `rules,../rules` | Rule pack directories (first existing wins); the image ships `/app/rules` |
| `OCI_TELEGRAM_BOT_TOKEN` | — | Enables TELEGRAM connectors (the chat id is given per connector) |
| `OCI_CHAINS_TRON_ENABLED`, `OCI_CHAINS_TRON_PROVIDER=trongrid`, `OCI_CHAINS_TRON_URL`, `OCI_CHAINS_TRON_API_KEY_HEADER`, `OCI_CHAINS_TRON_API_KEY` | off | Live TRON indexing through a TronGrid-compatible API |
| `OCI_CHAINS_ETHEREUM_ENABLED`, `OCI_CHAINS_ETHEREUM_PROVIDER=json-rpc`, `OCI_CHAINS_ETHEREUM_URL`, `OCI_CHAINS_ETHEREUM_START_BLOCK` | off | Live EVM indexing through JSON-RPC (also `bsc`, `polygon`) |

Live providers are implemented but have not been validated against production nodes yet.

### Current limitations

- **No persistence**: rules, alerts, API keys, connectors, profiles and scores live in memory and are lost on restart. PostgreSQL is provisioned for the upcoming persistence layer.
- No rate limiting, idempotency keys or `If-Match` concurrency control yet.
- Notification channels: WEBHOOK, SLACK, DISCORD, TELEGRAM. EMAIL and PUSH need a transport that is not available yet.

## Option B — from source

```bash
mvn -B verify                                        # backend build + tests (JDK 21+, no Docker needed)
cd frontend && npm ci && npm test && npm run build   # Node version from frontend/.nvmrc
cd .. && mvn -pl oci-app spring-boot:run             # demo profile: simulated chains + documented UI key
cd frontend && npm run start:api                     # console on http://localhost:4200 against the local app
```

## Upgrades

- There is no persistent state yet, so upgrading means restarting the containers (state is rebuilt from the chains and the rule pack).
- Read `CHANGELOG.md` for breaking changes; API v1 is additive within the major version.

## Production checklist

See [security-hardening.md](security-hardening.md). Minimum: `OCI_DEMO_MODE=false`, TLS reverse proxy, OIDC configured, `OCI_SECURITY_API_KEY_PEPPER` from a secret store, port 8080 bound to a private interface only.
