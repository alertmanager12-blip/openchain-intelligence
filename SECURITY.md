# Security Policy

## Supported versions

| Version | Supported |
|---|---|
| `main` (no release published yet; version 0.1.0-SNAPSHOT) | Yes: security fixes land on `main` |

Once releases are published, the latest minor release receives security fixes; from 1.0, the latest minor release and the
previous one do.

## Reporting a vulnerability

**Do not open public issues, discussions or pull requests for security vulnerabilities**, and do not post exploit
details anywhere public.

Report privately through **GitHub private vulnerability reporting**:
[Security tab → Report a vulnerability](https://github.com/alertmanager12-blip/openchain-intelligence/security/advisories/new)
(you need a GitHub account). Only you and the maintainers can see the report.

Please include:

- the affected version or commit, and how ChainSentinel is deployed (Docker Compose, from source, demo or production
  mode);
- the component (API, authentication, rule engine, connectors and webhooks, indexer, frontend, deployment);
- reproduction steps or a proof of concept, against your own deployment;
- the impact you expect (what an attacker can read, change or disrupt);
- any suggested fix or mitigation.

Do not include real third-party personal data, real API keys or secrets. If you are unsure whether something is a
vulnerability, report it privately anyway.

## Our commitment

| Step | Target |
|---|---|
| Acknowledgement | within 3 business days |
| Initial assessment and severity (CVSS v4) | within 10 business days |
| Fix or mitigation for High/Critical | within 30 days when possible |
| Coordinated disclosure | after a fix is available, typically ≤ 90 days from report |

**Confidentiality.** The report, the discussion and the fix are developed in a private GitHub security advisory and a
private fork. Nothing is made public before a fix or a mitigation is available, unless you and the maintainers agree
otherwise, or the vulnerability is already being exploited publicly.

**Critical vulnerabilities** (remote code execution, authentication bypass, secret disclosure, or anything exploitable
on a default production configuration) are handled first: the maintainers may publish a mitigation (configuration
change, disabled feature) before the full fix, and the fix is released as soon as it is ready.

**How fixes are published.** The fix is merged to `main` and released as a patch version (see the
[release process](docs/guides/release-process.md)); the GitHub security advisory is then published with the affected
and fixed versions, a CVE when one applies (requested through GitHub), the mitigation, and the reporter's credit. The
`CHANGELOG.md` lists it under *Security*. Operators should watch the repository's releases and security advisories.

We credit reporters in the advisory unless they prefer to remain anonymous. We will not pursue legal action against good-faith research that respects this policy: no privacy violations, no service degradation, no data destruction, testing only against your own deployment.

## Scope

In scope: this repository's code, default configurations, Docker images and documentation. Areas of particular interest:

- authentication/authorisation flaws, API key and OIDC handling;
- rule engine: any way to execute code, exhaust resources beyond documented limits, or read data a rule should not see;
- SSRF or request forgery through webhooks/connectors; HMAC signature weaknesses;
- secret leakage (connector secrets, keys) through API, logs or traces;
- injection (SQL, YAML, header), XSS in the frontend.

Out of scope: vulnerabilities in third-party chain providers or blockchains themselves, demo mode (`OCI_DEMO_MODE=true` is insecure by design), missing hardening on deployments that ignore [security-hardening.md](docs/guides/security-hardening.md), social engineering, volumetric DoS.

## Label and data disputes

If you believe an address is wrongly associated with a risk signal or label, this is not a security issue: use `POST /api/v1/labels/reports` or open an issue (without personal data). Labels from external sources must be disputed with the originating source as well.
