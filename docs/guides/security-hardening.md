# Security hardening

A checklist for running OCI outside a laptop. Threat model summary: [overview.md §10](../architecture/overview.md#10-security-model).

## Must do

- [ ] `OCI_DEMO_MODE=false` (demo mode indexes simulated chains with fictitious labels and prints an all-permissions admin key in the logs).
- [ ] Put OCI behind a TLS-terminating reverse proxy (TLS 1.2+, HSTS). Do not expose `8080` or PostgreSQL publicly.
- [ ] Configure OIDC (`OCI_SECURITY_OIDC_ISSUER_URI`, audience) with your IdP; enforce MFA at the IdP.
- [ ] Generate `OCI_SECURITY_API_KEY_PEPPER` (`openssl rand -base64 48`) and store it in a secret manager (Docker/K8s secrets, Vault, cloud KMS), not in `.env` committed to git. Same for `OCI_TELEGRAM_BOT_TOKEN` and provider API keys.
- [ ] Strong, unique PostgreSQL password (the database is provisioned but not used by this version).
- [ ] Restrict `/actuator/*` to the internal network; only `health` and `info` are exposed and everything except `health`/`info` requires `keys:admin`.
- [ ] Know that all state (rules, alerts, keys, connectors) is in memory in this version and lost on restart: export rules as YAML (`GET /api/v1/rules/export`) and keep them in version control.

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
