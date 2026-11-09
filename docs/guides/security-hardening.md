# Security hardening

A checklist for running OCI outside a laptop. Threat model summary: [overview.md §10](../architecture/overview.md#10-security-model).

## Must do

- [ ] `OCI_DEMO_MODE=false` (demo mode indexes simulated chains with fictitious labels and prints an all-permissions admin key in the logs).
- [ ] Put OCI behind a TLS-terminating reverse proxy (TLS 1.2+, HSTS). Do not expose `8080` or PostgreSQL publicly.
- [ ] Configure OIDC (`OCI_SECURITY_OIDC_ISSUER_URI`, audience) with your IdP; enforce MFA at the IdP.
- [ ] Generate `OCI_SECURITY_API_KEY_PEPPER` (`openssl rand -base64 48`) and store it in a secret manager (Docker/K8s secrets, Vault, cloud KMS), not in `.env` committed to git. Same for `OCI_TELEGRAM_BOT_TOKEN` and provider API keys. With PostgreSQL the application refuses to start without it outside demo mode (an ephemeral pepper would invalidate every stored key at the next restart). Keep it stable: changing it invalidates all API keys (re-issue them).
- [ ] Generate `OCI_CRYPTO_MASTER_KEY` (`openssl rand -base64 32`, exactly 32 bytes) and keep it in the secret manager. It encrypts connector secrets at rest (AES-256-GCM, fresh 96-bit nonce per value, associated data binding each ciphertext to its connector and column; the key id stored with each value detects a wrong key). Required with PostgreSQL; the dev-only default of `docker-compose.yml` is refused when `OCI_DEMO_MODE=false`. It is never logged. **Back it up separately from the database**: a database dump without it cannot decrypt connector secrets; a dump plus the key can. Master key rotation is not supported yet (changing the key makes the application refuse to start until the connectors are recreated).
- [ ] Strong, unique PostgreSQL password; PostgreSQL reachable only from the application (compose binds it to `127.0.0.1`). Enable TLS to the database (`?sslmode=verify-full` in `SPRING_DATASOURCE_URL`) when it runs on another host.
- [ ] Database role: the application runs Flyway migrations at startup, so its role owns the schema (CREATE/ALTER on its schema). For a stricter setup, run migrations with a separate owner role (`SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD`) and give the application role only `SELECT, INSERT, UPDATE, DELETE` on the tables of its schema (no superuser, no `CREATEDB`, no access to other databases).
- [ ] What is stored: API keys as prefix + `HMAC-SHA256(pepper, secret)` (never the secret); connector targets (Slack/Discord/webhook URLs, Telegram chat ids) and WEBHOOK signing secrets encrypted (only a masked `scheme://host/…` form in clear); alerts, comments and label reports in clear (they may contain personal data: restrict database access and backups accordingly).
- [ ] Restrict `/actuator/*` to the internal network. `health` and `info` are public; `prometheus` answers without a key only to loopback/private source addresses (the host, the compose network) and refuses public ones; everything else requires `keys:admin`. The compose file publishes port 8080 on 127.0.0.1 only.
- [ ] Keep `OCI_DEMO_MODE=false`: the application refuses to start when demo mode and a real chain are both enabled (demo mode uses public development secrets).
- [ ] OpenAPI / Swagger UI are off outside demo mode unless `OCI_API_DOCS_ENABLED=true`.
- [ ] Run with PostgreSQL (`SPRING_DATASOURCE_URL`): without it all operator state (rules, alerts, keys, connectors) is in memory and lost on restart (the startup log says `Persistence mode: IN-MEMORY` with a WARN). Back the database up regularly (`pg_dump`), test restores, and still export rules as YAML (`GET /api/v1/rules/export`) into version control.
- [ ] Never reuse a demo database in production (demo keys are revoked when demo mode is off, but demo alerts and data stay).

## Should do

- [ ] Egress control: allow outbound only to chain providers, IdP and webhook destinations (or route webhooks via an egress proxy). OCI's SSRF guard is a second layer.
- [ ] There is no rate limiting or quota yet: put a rate-limiting reverse proxy or API gateway in front of the API.
- [ ] API keys: short expiry for CI usage, one key per integration, minimal scopes; revoke unused keys (`last_used_at`).
- [ ] Webhook secrets cannot be rotated in place yet: create a new connector, switch the rules to it, delete the old one.
- [ ] Run containers read-only with a tmpfs `/tmp`, non-root (the image already uses UID 10001), `no-new-privileges`, drop all capabilities.
- [ ] Ship logs to a central store; alert on `401/403` spikes and on the `oci.audit` logger (API key creation/revocation, rule enable/disable/delete, connector create/delete/test). The audit trail is a log stream, not a database table, in this version.
- [ ] Keep images up to date (Dependabot PRs, rebuild on base image updates); scan images (e.g. Trivy).

## Chain data providers

- Use authenticated endpoints and keep provider API keys secret (they are quotas you pay for).
- Prefer your own node for production volumes; treat all provider data as untrusted input (OCI validates hash linkage and parses numbers strictly).

## Data protection

- Labels and alerts about addresses can affect people: restrict who can export them, keep the source and evidence level attached, and process disputes (`/labels/reports`).
- Configure retention (see [data-model.md](../architecture/data-model.md#retention)) according to your legal obligations.

## Reporting vulnerabilities

See [SECURITY.md](../../SECURITY.md).
