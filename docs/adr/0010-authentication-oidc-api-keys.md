# ADR-0010: Authentication — OIDC (any provider) and API keys

- Status: accepted
- Date: 2026-11-06

## Context and problem statement

Human users need SSO with their organisation's identity provider; machine clients need long-lived credentials with narrow scopes. We must not build a password database.

## Considered options

1. **OIDC/OAuth2 resource server** (any compliant IdP; Keycloak, Apache-2.0, as reference and in the dev compose) + **API keys** managed by OCI.
2. Built-in username/password with sessions.
3. OAuth2 client-credentials only (no API keys).
4. Bundle Keycloak as a mandatory component.

## Decision outcome

Chosen option: **1**.

- JWT validation via issuer discovery + JWKS (Spring Security resource server); required claims: `iss`, `aud`, `exp`, `sub`; tenant from `oci_tenant` (configurable claim), roles from `oci_roles` or IdP role mapping.
- API keys: format `oci_<env>_<prefix>_<secret>` (secret ≥ 32 random bytes, base62). Stored: `prefix` and `HMAC-SHA256(pepper, secret)` (pepper from secret store). Scopes, expiry, last-used timestamp, revocation. Shown once.
- RBAC roles `VIEWER`, `ANALYST`, `RULE_AUTHOR`, `ADMIN`; API key scopes are intersected with the creator's role.
- Demo mode provides a static demo tenant and a pre-generated demo key; it is disabled unless `OCI_DEMO_MODE=true`.

### Pros

- Works with Keycloak, Authentik, Auth0, Entra ID, Okta, Google, Zitadel…; no credentials stored by OCI for humans.
- Machine access does not depend on IdP client registration.

### Cons

- Self-hosters must run or connect an IdP for multi-user setups.
- API keys are bearer secrets; leakage risk mitigated by scopes, expiry, prefix-based secret scanning (prefix registered with GitHub secret scanning later).

## Consequences

- Every authenticated request resolves a `(tenant, principal, scopes)` context propagated to repositories and to PostgreSQL RLS (`SET LOCAL app.tenant_id`).
- Authentication events and key lifecycle are written to `audit_log`.
