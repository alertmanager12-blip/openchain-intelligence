# Security Policy

## Supported versions

| Version | Supported |
|---|---|
| 0.1.x (unreleased / main) | Yes — security fixes on `main` |
| < 0.1 | No |

Once 1.0 is released, the latest minor release and the previous one receive security fixes.

## Reporting a vulnerability

**Please do not open public issues, discussions or pull requests for security vulnerabilities.**

Report privately through one of:

1. **GitHub private vulnerability reporting**: *Security* tab → *Report a vulnerability* (preferred).
2. Email **security@openchain-intelligence.org** (encrypt with the PGP key published in the repository's security advisories page, if available).

Please include: affected version/commit, component (API, rule engine, connectors, indexer, frontend, deployment), reproduction steps or proof of concept, impact, and any suggested fix. Do not include real third-party personal data.

## Our commitment

| Step | Target |
|---|---|
| Acknowledgement | within 3 business days |
| Initial assessment and severity (CVSS v4) | within 10 business days |
| Fix or mitigation for High/Critical | within 30 days when possible |
| Coordinated disclosure | after a fix is available, typically ≤ 90 days from report |

We credit reporters in the advisory unless they prefer to remain anonymous. We will not pursue legal action against good-faith research that respects this policy: no privacy violations, no service degradation, no data destruction, testing only against your own deployment.

## Scope

In scope: this repository's code, default configurations, Docker images and documentation. Areas of particular interest:

- tenant isolation bypass, authentication/authorisation flaws, API key handling;
- rule engine: any way to execute code, exhaust resources beyond documented limits, or read other tenants' data through rules;
- SSRF or request forgery through webhooks/connectors; HMAC signature weaknesses;
- secret leakage (connector secrets, keys) through API, logs or traces;
- injection (SQL, YAML, header), XSS in the frontend.

Out of scope: vulnerabilities in third-party chain providers or blockchains themselves, demo mode (`OCI_DEMO_MODE=true` is insecure by design), missing hardening on deployments that ignore [security-hardening.md](docs/guides/security-hardening.md), social engineering, volumetric DoS.

## Label and data disputes

If you believe an address is wrongly associated with a risk signal or label, this is not a security issue: use `POST /api/v1/labels/reports` or open an issue (without personal data). Labels from external sources must be disputed with the originating source as well.
