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
| app | http://localhost:8080 | API v1, `/actuator/health`, `/actuator/prometheus` |
| postgres | localhost:5432 | data volume `pgdata` |

With observability:

```bash
docker compose --profile observability up -d
```

adds Prometheus (http://localhost:9090), Grafana (http://localhost:3000, admin password from `GRAFANA_ADMIN_PASSWORD`) and the OpenTelemetry Collector (OTLP gRPC 4317 / HTTP 4318).

## Configuration

All settings are environment variables (Spring relaxed binding).

| Variable | Default | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://postgres:5432/oci` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `oci` / from `.env` | DB credentials |
| `OCI_DEMO_MODE` | `true` in compose | Simulated chain + demo tenant/key. **Set `false` in production.** |
| `OCI_ROLES` | `api,worker` | Process roles |
| `OCI_NETWORKS_TRON_ENABLED` | `false` | Enable TRON live indexing |
| `OCI_NETWORKS_TRON_PROVIDERS_0_URL` | — | TronGrid-compatible base URL (e.g. `https://api.trongrid.io` or own node) |
| `OCI_NETWORKS_TRON_PROVIDERS_0_API_KEY` | — | TronGrid API key |
| `OCI_NETWORKS_ETHEREUM_ENABLED` | `false` | Enable Ethereum |
| `OCI_NETWORKS_ETHEREUM_PROVIDERS_0_URL` | — | JSON-RPC URL |
| `OCI_SECURITY_OIDC_ISSUER_URI` | — | OIDC issuer (enables JWT auth) |
| `OCI_SECURITY_API_KEY_PEPPER` | — | Secret pepper for API key hashing (≥ 32 bytes) |
| `OCI_CRYPTO_MASTER_KEY` | — | Base64 256-bit key for connector secret encryption |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | — | e.g. `http://otel-collector:4318` |

> Variable names for networks and security are the reference contract; check `oci-app/src/main/resources/application.yml` of your release for the authoritative list.

## Option B — from source

```bash
mvn -B verify                         # backend build + tests (needs Docker for Testcontainers)
cd frontend && npm ci && npm run build
java -jar oci-app/target/oci-app-*.jar --spring.profiles.active=local
```

## Upgrades

- Database migrations run automatically at start-up (Flyway). Back up PostgreSQL first (`pg_dump -Fc`).
- Read `CHANGELOG.md` for breaking changes; API v1 is additive within the major version.

## Production checklist

See [security-hardening.md](security-hardening.md). Minimum: `OCI_DEMO_MODE=false`, TLS reverse proxy, OIDC configured, strong DB password, `OCI_CRYPTO_MASTER_KEY` and `OCI_SECURITY_API_KEY_PEPPER` from a secret store, backups, monitoring enabled.
