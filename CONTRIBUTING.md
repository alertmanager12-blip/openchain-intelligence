# Contributing to OpenChain Intelligence

Thanks for your interest! OCI is an Apache-2.0 project and welcomes code, rules, documentation, tests and reviews. By participating you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## Ground rules (non-negotiable)

1. **Explainable detection** — every signal, score and alert must carry its evidence (transactions, rule version, source, time).
2. **No accusations** — never label an address as "criminal", "fraudster" or similar. Use neutral terms: *risk signal*, *anomaly*, *exposure*, *reported by <source>*.
3. **Keep evidence levels distinct** — `ANOMALY` vs `RISK_SIGNAL` vs `CONFIRMED_BY_SOURCE`.
4. **No user-supplied code execution** — no SpEL, scripting engines, `eval`, or reflection driven by user input in the rule engine or anywhere else.
5. **Exact amounts** — `BigInteger`/`BigDecimal`, never `double`/`float` for money.
6. **Resilience** — code that calls providers or webhooks must handle timeouts, rate limits and failures.
7. **Module boundaries** — `oci-blockchain`, `oci-intelligence` and `oci-alerting` depend only on `oci-core`; `oci-alerting` must not depend on `oci-intelligence`; `oci-core` stays framework-free.
8. **Licences** — new dependencies must be on the allowlist of [ADR-0012](docs/adr/0012-licensing-policy.md) (no GPL/AGPL/SSPL/BSL in shipped artefacts).

## Getting started

```bash
git clone https://github.com/openchain-intelligence/openchain-intelligence.git
cd openchain-intelligence
mvn -B verify                         # JDK 21+, no Docker needed
cd frontend && npm ci && npm test
docker compose up -d --build          # full stack in demo mode
```

Read [docs/architecture/overview.md](docs/architecture/overview.md) and the [ADRs](docs/adr/) first.

## Workflow

1. Open (or pick) an issue; for significant changes discuss the design first, and add an ADR (`docs/adr/NNNN-title.md`, MADR format) when you change an architectural decision.
2. Fork, branch from `main` (`feat/…`, `fix/…`, `docs/…`, `rule/…`).
3. Write tests first or alongside; a feature is not done until tests pass. See [testing-strategy.md](docs/testing-strategy.md).
4. Use [Conventional Commits](https://www.conventionalcommits.org/) (`feat(alerting): add distinct_count aggregate`).
5. Update `CHANGELOG.md` under `Unreleased`.
6. Open a pull request using the template; CI must be green (build, tests, CodeQL, licence check).
7. At least one maintainer approval is required; two for security-sensitive areas (auth, tenancy, connectors, rule engine).

## Developer Certificate of Origin (DCO)

We use the [DCO 1.1](https://developercertificate.org/) instead of a CLA. Every commit must be signed off:

```bash
git commit -s -m "fix(blockchain): honour Retry-After on 429"
# adds: Signed-off-by: Your Name <you@example.com>
```

Signing off certifies that you wrote the patch or otherwise have the right to submit it under the Apache-2.0 licence. Fix a missing sign-off with `git commit --amend -s` or `git rebase --signoff main`.

## Coding guidelines

- Java 21: records for value types, immutable collections (`List.copyOf`), no `null` collections.
- Public types documented with Javadoc stating invariants (units, nullability, thread-safety).
- Every outbound call has a timeout; every retry has a bound and jitter.
- No secrets, full payloads or personal data in logs.
- Frontend: Angular standalone components, strict TypeScript, no `innerHTML` with API content.

## Contributing rules

Rules (schema `openchain.io/rule/v1`, see [writing-rules.md](docs/guides/writing-rules.md)) are reviewed like code:

1. Open a **Rule contribution** issue (template) describing the observable behaviour, known false positives and evidence level.
2. Add the rule under `rules/<pack>/<rule-name>.yaml` with:
   - neutral `metadata.name`/`description` (behaviour, not intent);
   - `metadata.tags` and `severity` justified in the PR;
   - a test fixture `rules/<pack>/<rule-name>.test.yaml` with input events and expected matches (positive **and** negative cases).
3. Provide DRY_RUN statistics on the demo dataset (and optionally on public data with public tx hashes only).
4. Maintainers check: validation passes, fixtures pass in CI, false-positive discussion is credible, wording follows the ground rules, no personal data.
5. Rules that rely on external label sources must name the source and its licence.

## Reporting security issues

Never in public issues — see [SECURITY.md](SECURITY.md).

## Licence

By contributing you agree that your contributions are licensed under the [Apache License 2.0](LICENSE).
