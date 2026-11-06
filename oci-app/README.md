# oci-app

Spring Boot application wiring `oci-blockchain` → `oci-intelligence` → `oci-alerting` behind REST API v1
(`docs/api/README.md`). Everything is in memory in this version (demo MVP).

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
| demo admin | generated at startup, printed **once** in the log (`DEMO MODE: demo admin API key ...`), valid 24 h | all |
| Angular dev server | fixed and publicly documented, `demo` profile only: `oci_demo_devui001_DemoOnlyAngularDevServerKeyNotSecret0001` (override with `OCI_DEMO_UI_KEY`) | all `*:read`, `alerts:write`, `rules:write`, `labels:report` — no `webhooks:write`, no `keys:admin` |

Angular: `npx ng serve --configuration development` (proxy to :8080), then in *Settings* choose auth mode
"API key" and paste the dev-server key (it is sent as `X-API-Key`). CORS allows `http://localhost:4200`
(`OCI_CORS_ALLOWED_ORIGINS`).

```bash
curl -H "X-API-Key: $KEY" localhost:8080/api/v1/networks
```

Keys are never accepted in query parameters. Sensitive operations (key create/revoke, rule enable/disable/delete,
webhook create/delete/test, role changes) are written to the `oci.audit` logger, without secrets.

## Configuration

See `src/main/resources/application.yml`: `OCI_DEMO_MODE`, `OCI_SECURITY_API_KEY_PEPPER` (HMAC pepper for key
hashes; random and ephemeral when unset), `OCI_SECURITY_OIDC_ISSUER_URI` (JWT resource server enabled only when
set; roles from the `oci_roles` claim), `oci.security.keys` (provisioned keys, hashed at startup),
`oci.chains.*` (real TronGrid / EVM JSON-RPC providers, off by default), `OCI_RULES_DIRS`.

## Not implemented yet (TODO)

- **Persistence**: PostgreSQL + Flyway for rules/versions, alerts, API keys, users, label reports, webhook
  connectors (secrets encrypted with `OCI_CRYPTO_MASTER_KEY`), checkpoints, flows. Everything is lost on restart.
- **Idempotency** store (`Idempotency-Key`, 24 h replay, 409 on body mismatch).
- **Rate limiting** (token bucket, `RateLimit-*` headers). The `429 rate-limited` problem type and its
  `Retry-After` mapping exist, but nothing raises it yet.
- **ETag / If-Match** on `PUT /rules/{id}` (412). `GET /rules/{id}` sends an ETag but it is not enforced.
- **Outbox / Kafka** event bus (ADR-0006): the pipeline is called in-process.
- Multi-tenancy (`oci_tenant`), audit_log table, Slack/Discord/Telegram/email connector management,
  label report moderation, alert re-open (the alerting lifecycle makes RESOLVED/FALSE_POSITIVE terminal).
