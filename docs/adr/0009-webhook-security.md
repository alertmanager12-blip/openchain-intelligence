# ADR-0009: Webhook security — HMAC signatures and SSRF protection

- Status: accepted
- Date: 2026-11-06

## Context and problem statement

Tenants configure webhook URLs that OCI calls from inside its network. Without controls this enables SSRF (cloud metadata, internal services) and receivers cannot authenticate the sender or detect replays.

## Considered options

1. **HMAC-SHA256 signature with timestamp** + strict outbound URL policy enforced at delivery time.
2. Mutual TLS only.
3. Asymmetric signatures (Ed25519) with a published JWKS.
4. Static shared token header.

## Decision outcome

Chosen option: **1** (Ed25519 may be added as an option later).

**Signature**: header `X-OCI-Signature: t=<unix>,v1=<hex HMAC-SHA256(secret, t + "." + rawBody)>`; per-connector random 32-byte secret, shown once, stored AES-256-GCM encrypted; rotation keeps two active secrets for a grace period (two `v1` values). Receivers verify in constant time and reject |now − t| > 300 s. `X-OCI-Delivery-Id` lets receivers deduplicate at-least-once deliveries.

**SSRF policy** (checked at creation and again at every delivery):

- scheme `https` only (`http` only when `OCI_DEMO_MODE=true`), default ports 443 (configurable allow-list);
- no userinfo, no IP-literal hosts unless explicitly allow-listed by an admin;
- DNS resolution by OCI; **all** resolved addresses must be public: deny loopback, `0.0.0.0/8`, RFC 1918, `100.64.0.0/10`, link-local `169.254.0.0/16` (incl. metadata `169.254.169.254`), `fc00::/7`, `fe80::/10`, IPv4-mapped IPv6 of those, multicast;
- connect to the vetted IP (prevents DNS rebinding), SNI/Host preserved;
- no redirects; connect 2 s / total 5 s timeouts; read at most 64 KiB of the response; response body never stored beyond a truncated error snippet;
- optional egress proxy mode for operators who prefer network-level enforcement.

### Pros

- Simple for receivers (snippets in Python/Node/Java in the API docs), replay-resistant, rotation-friendly.
- SSRF controls in depth (application + optional egress proxy).

### Cons

- Shared secret must be protected by the receiver; HMAC does not provide non-repudiation.

## Consequences

- Connector secrets never appear in API responses, logs or traces.
- Delivery attempts are recorded in `deliveries` with status code and truncated error.
- Security tests cover SSRF bypass vectors (decimal/octal IPs, IPv6, DNS rebinding, redirects).
