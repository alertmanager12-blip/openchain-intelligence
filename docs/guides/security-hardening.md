# Security hardening

A checklist for running OCI outside a laptop. Threat model summary: [overview.md §10](../architecture/overview.md#10-security-model).

## Must do

- [ ] `OCI_DEMO_MODE=false` (demo mode creates a demo tenant/key and allows `http://` webhooks).
- [ ] Put OCI behind a TLS-terminating reverse proxy (TLS 1.2+, HSTS). Do not expose `8080` or PostgreSQL publicly.
- [ ] Configure OIDC (`OCI_SECURITY_OIDC_ISSUER_URI`, audience) with your IdP; enforce MFA at the IdP.
- [ ] Generate `OCI_CRYPTO_MASTER_KEY` (`openssl rand -base64 32`) and `OCI_SECURITY_API_KEY_PEPPER` (`openssl rand -base64 48`); store them in a secret manager (Docker/K8s secrets, Vault, cloud KMS), not in `.env` committed to git.
- [ ] Strong, unique PostgreSQL password; app DB role without superuser; `audit_log` UPDATE/DELETE revoked.
- [ ] Restrict `/actuator/*` except `health` to the internal network (prometheus scrape only from the monitoring network).
- [ ] Backups: daily `pg_dump`/PITR, tested restore.

## Should do

- [ ] Egress control: allow outbound only to chain providers, IdP and webhook destinations (or route webhooks via an egress proxy). OCI's SSRF guard is a second layer.
- [ ] Set per-tenant quotas (rules, connectors, API rate, DRY_RUN budget).
- [ ] API keys: short expiry for CI usage, one key per integration, minimal scopes; revoke unused keys (`last_used_at`).
- [ ] Rotate webhook secrets periodically (two active secrets during rotation).
- [ ] Rotate `OCI_CRYPTO_MASTER_KEY` by adding a new key id; the re-encryption job rewraps connector secrets.
- [ ] Run containers read-only with a tmpfs `/tmp`, non-root (the image already uses UID 10001), `no-new-privileges`, drop all capabilities.
- [ ] Ship logs to a central store; alert on `401/403` spikes, API key creation, rule changes, connector changes (all in `audit_log`).
- [ ] Keep images up to date (Dependabot PRs, rebuild on base image updates); scan images (e.g. Trivy).

## Chain data providers

- Use authenticated endpoints and keep provider API keys secret (they are quotas you pay for).
- Prefer your own node for production volumes; treat all provider data as untrusted input (OCI validates hash linkage and parses numbers strictly).

## Data protection

- Labels and alerts about addresses can affect people: restrict who can export them, keep the source and evidence level attached, and process disputes (`/labels/reports`).
- Configure retention (see [data-model.md](../architecture/data-model.md#retention)) according to your legal obligations.

## Reporting vulnerabilities

See [SECURITY.md](../../SECURITY.md).
