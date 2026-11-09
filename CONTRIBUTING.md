# Contributing to ChainSentinel

Thanks for your interest! ChainSentinel is an Apache-2.0 project and welcomes code, detection rules, documentation,
tests, reviews and bug reports. By participating you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

**Contents:** [Your first contribution](#your-first-contribution) · [Ground rules](#ground-rules) ·
[Set up your environment](#set-up-your-environment) · [Branches](#branches) · [Making a change](#making-a-change) ·
[Tests](#tests) · [Coding conventions](#coding-conventions) · [Commits](#commits) · [Pull requests](#pull-requests) ·
[Reviews](#reviews) · [Contributing rules](#contributing-rules) · [AI-assisted contributions](#ai-assisted-contributions) ·
[Reporting problems](#reporting-problems) · [Licence and sign-off](#licence-and-sign-off)

## Your first contribution

1. **Discover the project:** read the [README](README.md) and the [architecture overview](docs/architecture/overview.md).
2. **Install the environment** and run the tests (below). If something in the setup does not work, that is already a
   useful contribution: open a [documentation issue](https://github.com/alertmanager12-blip/openchain-intelligence/issues/new/choose).
3. **Pick a task:** issues labelled [`good first issue`](https://github.com/alertmanager12-blip/openchain-intelligence/labels/good%20first%20issue)
   or [`help wanted`](https://github.com/alertmanager12-blip/openchain-intelligence/labels/help%20wanted). Comment on
   the issue to say you are working on it, so that nobody duplicates your work.
4. **Open a branch, make the change, add tests.**
5. **Open a pull request** and answer the review.
6. A maintainer merges it; you are listed in [CONTRIBUTORS.md](CONTRIBUTORS.md) if you wish.

For anything larger than a bug fix, open an issue first and describe the design: it saves you from rewriting a change
that does not fit.

## Ground rules

These are non-negotiable, because they are what makes the project trustworthy:

1. **Explainable detection:** every signal, score and alert carries its evidence (transactions, rule version, source,
   time).
2. **No accusations:** never label an address as "criminal", "fraudster" or similar. Use neutral terms: *risk signal*,
   *anomaly*, *exposure*, *reported by <source>*.
3. **Keep evidence levels distinct:** `ANOMALY`, `RISK_SIGNAL` and `CONFIRMED_BY_SOURCE` are never merged.
4. **No user-supplied code execution:** no SpEL, scripting engines, `eval`, or reflection driven by user input, in the
   rule engine or anywhere else.
5. **Exact amounts:** `BigInteger`/`BigDecimal`, never `double`/`float` for money.
6. **Resilience:** code that calls providers or webhooks handles timeouts, rate limits and failures.
7. **Module boundaries:** `oci-blockchain`, `oci-intelligence` and `oci-alerting` depend only on `oci-core`;
   `oci-alerting` does not depend on `oci-intelligence`; `oci-core` stays framework-free.
8. **Licences:** new dependencies must be on the allowlist of [ADR-0012](docs/adr/0012-licensing-policy.md) (no
   GPL/AGPL/SSPL/BSL in shipped artefacts). CI checks this.
9. **No secrets, personal data or real customer data** in code, tests, fixtures, logs or issues. Test fixtures use
   obviously fake values.

## Set up your environment

| Tool | Version |
|---|---|
| JDK | 21 or newer (the code targets Java 21; the Docker image runs on Java 25) |
| Maven | 3.9 or newer |
| Node.js | the version in `frontend/.nvmrc` (`nvm use`); CI builds with Node 24 |
| Docker with Compose v2 | recent; needed for the PostgreSQL integration tests and the full stack |

```bash
# 1. Fork the repository on GitHub, then clone your fork
git clone https://github.com/YOUR-ACCOUNT/openchain-intelligence.git
cd openchain-intelligence
git remote add upstream https://github.com/alertmanager12-blip/openchain-intelligence.git

# 2. Backend: build and run all tests (keep Docker running, see "Tests")
mvn -B clean verify

# 3. Frontend
cd frontend && nvm use && npm ci && npm test && cd ..

# 4. Full stack in demo mode (simulated chains, no keys needed)
docker compose up -d --build
```

The [README](README.md#step-5--develop-and-test) explains how to run the backend and the console from source with live
reload.

## Branches

`main` is the stable branch: it always builds and passes CI. Work on a short-lived branch in your fork:

| Prefix | For |
|---|---|
| `feature/…` | a new capability (`feature/solana-indexer`) |
| `fix/…` | a bug fix (`fix/tron-reorg-depth`) |
| `docs/…` | documentation only |
| `refactor/…` | code changes without behaviour change |
| `security/…` | security hardening that is already public. Undisclosed vulnerabilities are fixed privately, see [SECURITY.md](SECURITY.md) |
| `rule/…` | a detection rule or rule pack |

Keep one topic per branch. Rebase on `upstream/main` (or merge it) before opening the pull request.

## Making a change

- **A feature:** open or pick an issue, agree on the approach, then implement it with tests and documentation. If it
  changes an architectural decision, add an ADR (`docs/adr/NNNN-title.md`, see [the ADR index](docs/adr/README.md)).
- **A bug fix:** first write a test that reproduces the bug and fails, then fix it. Mention the issue (`Fixes #123`).
- **A breaking change** (REST API v1, rule schema `openchain.io/rule/v1`, configuration variables, database data):
  avoid it if you can. If you cannot, say so in the pull request, explain the migration, and list it in the
  `CHANGELOG.md`.
- **Database changes** are new Flyway migrations (`oci-app/src/main/resources/db/migration/V<n>__name.sql`). Never edit
  a migration that has been merged.

## Tests

A change is not done until its tests pass. See the [testing strategy](docs/testing-strategy.md).

| What | Command |
|---|---|
| All backend tests (unit and integration) | `mvn -B clean verify` |
| One module | `mvn -B -pl oci-intelligence -am verify` |
| Frontend unit tests | `cd frontend && npm test` |
| Frontend production build | `cd frontend && npm run build` |
| End-to-end check of the Docker stack | `docker compose down && bash deploy/smoke-test.sh` |

The PostgreSQL integration tests use Testcontainers. **Without Docker they are skipped and the build still succeeds**,
so check that the Maven report shows `Skipped: 0` for `oci-app`. CI always runs them.

## Coding conventions

- **Java 21:** records for value types, immutable collections (`List.copyOf`), no `null` collections.
- Public types are documented with Javadoc stating their invariants (units, nullability, thread-safety).
- Every outbound call has a timeout; every retry has a bound and jitter.
- No secrets, full payloads or personal data in logs.
- **Frontend:** Angular standalone components, signals, strict TypeScript, no `innerHTML` with API content. Formatting
  follows `frontend/.prettierrc` and `.editorconfig`.
- Follow the style of the surrounding code; do not reformat files you do not otherwise change.

## Commits

Use [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/): `type(scope): summary`.

| Type | For |
|---|---|
| `feat` | a new capability |
| `fix` | a bug fix |
| `docs` | documentation only |
| `refactor` | no behaviour change |
| `test` | tests only |
| `build` | Maven, npm, Docker |
| `ci` | GitHub Actions |
| `security` | security hardening |
| `chore` | maintenance (dependency bumps, cleanup) |

The scope is the module or area (`alerting`, `blockchain`, `intelligence`, `frontend`, `rules`, `deploy`…). Add `!`
for a breaking change (`feat(api)!: …`) and explain it in the body. Example:

```
fix(blockchain): honour Retry-After on HTTP 429

TronGrid answers 429 with a Retry-After header; the client retried immediately and opened the circuit.
Fixes #42
```

The older history predates this convention and is not rewritten.

## Pull requests

1. Push your branch to your fork and open a pull request against `main`. The template lists what to fill in.
2. Keep it focused: one topic, no unrelated changes or formatting. Smaller pull requests are reviewed faster.
3. Add tests, update the documentation and add a line to `CHANGELOG.md` under `[Unreleased]` for user-visible changes.
4. CI must be green: backend build and tests, frontend build and tests, dependency licence check, Docker images and
   stack smoke test, CodeQL, and dependency review.
5. Mark the pull request as draft while it is not ready for review.

## Reviews

- A maintainer reviews every pull request. Changes to authentication, API keys, connectors,
  webhooks, the rule engine or the deployment get a more thorough security review.
- Answer each comment, either with a change or with an explanation. Push new commits rather than force-pushing during
  the review, so the reviewer sees what changed; the maintainer squashes or rebases on merge.
- If you disagree with a request, say why. Disagreements are settled as described in [GOVERNANCE.md](GOVERNANCE.md).
- A pull request without activity for 30 days after a review request may be closed; you can reopen it at any time.

## Contributing rules

Rules (schema `openchain.io/rule/v1`, see [writing-rules.md](docs/guides/writing-rules.md)) are reviewed like code:

1. Open a **Rule contribution** issue describing the observable behaviour, known false positives and evidence level.
2. Add the rule under `rules/<pack>/<rule-name>.yaml` with:
   - neutral `metadata.name`/`description` (behaviour, not intent);
   - `metadata.tags` and `severity` justified in the pull request;
   - a test fixture `rules/<pack>/<rule-name>.test.yaml` with input events and expected matches (positive **and**
     negative cases).
3. Provide DRY_RUN statistics on the demo dataset (and optionally on public data with public transaction hashes only).
4. Maintainers check that validation and fixtures pass in CI, that the false-positive discussion is credible, that the
   wording follows the ground rules and that there is no personal data.
5. Rules that rely on external label sources name the source and its licence.

## AI-assisted contributions

Contributions written with the help of AI tools are welcome under the same rules as any other contribution:

- **You are responsible** for what you submit: you must understand it, be able to explain it in the review, and have
  the right to contribute it under Apache-2.0.
- **Run the tests yourself** and report the real results in the pull request. Never claim a test result you did not
  observe.
- **Check new dependencies and their licences**, and check that generated code does not copy code under an
  incompatible licence.
- **No invented authors or co-authors.** Do not add `Co-Authored-By` trailers or credit tools as authors; the commit
  author is the person submitting the change.
- Every change is reviewed by a maintainer before it is merged, whatever produced it.

You do not have to declare which tools you used.

## Reporting problems

| Problem | Where |
|---|---|
| A bug | [Bug report](https://github.com/alertmanager12-blip/openchain-intelligence/issues/new?template=bug_report.yml) |
| An incompatibility (Java, Node, Docker, a provider, a chain node, an upgrade that breaks your setup) | [Bug report](https://github.com/alertmanager12-blip/openchain-intelligence/issues/new?template=bug_report.yml), with your versions and environment |
| Wrong or missing documentation | [Documentation issue](https://github.com/alertmanager12-blip/openchain-intelligence/issues/new?template=documentation_issue.yml) |
| An idea | [Feature request](https://github.com/alertmanager12-blip/openchain-intelligence/issues/new?template=feature_request.yml) |
| A security vulnerability | **Never in public:** see [SECURITY.md](SECURITY.md) |
| A question | See [SUPPORT.md](SUPPORT.md) |

## Licence and sign-off

By contributing you agree that your contributions are licensed under the [Apache License 2.0](LICENSE).

Signing off your commits with the [Developer Certificate of Origin 1.1](https://developercertificate.org/) is
**recommended, not required** (there is no CLA):

```bash
git commit -s -m "fix(blockchain): honour Retry-After on 429"
# adds: Signed-off-by: Your Name <you@example.com>
```

Signing off certifies that you wrote the patch or otherwise have the right to submit it under the Apache-2.0 licence.
