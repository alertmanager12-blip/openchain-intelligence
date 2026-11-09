# Governance

This document describes how ChainSentinel is run today and how that is meant to evolve as the community grows. It is
deliberately light: the project currently has one maintainer.

## Roles

| Role | Who | Responsibilities |
|---|---|---|
| **Users** | Anyone running ChainSentinel | Report bugs, suggest features, share feedback |
| **Contributors** | Anyone whose issue, review, documentation, rule or code was accepted | Listed in [CONTRIBUTORS.md](CONTRIBUTORS.md) if they wish |
| **Maintainers** | Listed below | Review and merge pull requests, triage issues, decide on the roadmap, publish releases, handle security reports, enforce the [Code of Conduct](CODE_OF_CONDUCT.md) |
| **Lead maintainer** | Listed below | Everything a maintainer does, plus the final say when maintainers disagree, repository settings and access, and changes to this document and to the licence |

### Current maintainers

| GitHub account | Role |
|---|---|
| [@alertmanager12-blip](https://github.com/alertmanager12-blip) | Lead maintainer |

Until other maintainers are appointed as described below, the lead maintainer holds every responsibility in this
document, including the validation of every change merged to `main`.

## How decisions are made

- **Day-to-day changes** (bug fixes, documentation, small features, dependency updates) are decided in the pull
  request: a maintainer approves and merges once CI is green and review comments are resolved.
- **Significant changes** (new modules, new chains, public API or rule schema changes, new runtime dependencies,
  changes to detection or scoring semantics) are discussed in an issue first. When they change an architectural
  decision, they are recorded as an [ADR](docs/adr/README.md), which states the context, the options considered and
  the consequences.
- **Lazy consensus:** a proposal that has been open for discussion for a reasonable time (at least a week for
  significant changes) without a substantiated objection from a maintainer can proceed.
- **Disagreements** are first argued on the merits in the issue or pull request, with evidence (tests, measurements,
  references). If maintainers still disagree, the lead maintainer decides and records the reasoning.
- Decisions follow the project's [ground rules](CONTRIBUTING.md#ground-rules): explainable detection, neutral wording,
  no user code execution, licence policy. A change that breaks them is not accepted, whoever proposes it.

## Pull requests

A pull request is merged when:

1. CI passes;
2. at least one maintainer who is not its author has approved it. While the project has a single maintainer, the
   maintainer may merge their own changes after CI passes;
3. review comments are resolved, and documentation and `CHANGELOG.md` are updated where needed.

Changes to authentication, API keys, connectors and webhooks, the rule engine, the CI workflows and the deployment
files get a security-focused review.

## Releases

Releases follow [Semantic Versioning](https://semver.org/) and the [release process](docs/guides/release-process.md).
A maintainer prepares the release; the lead maintainer approves the publication of each release.

## Security incidents

Vulnerability reports are handled privately by the maintainers as described in [SECURITY.md](SECURITY.md). If a
secret of the project is exposed (for example a token in the history), the maintainers revoke it first, then assess
the impact and publish an advisory when users are affected.

## Roadmap

The [roadmap](docs/strategy/FEATURE_ROADMAP.md) is maintained by the maintainers and changes through pull requests to
that document, informed by issues, feature requests and contributions. Anyone may propose a change to it.

## Becoming a maintainer

A contributor can be invited as a maintainer by the lead maintainer when they have, over several months, made
substantial and sustained contributions (code, reviews, triage, documentation), shown good judgement in reviews, and
followed the ground rules and the Code of Conduct. Nobody becomes a maintainer without accepting the role. New
maintainers are added to the table above through a pull request.

Maintainers who have been inactive for a long period, or who ask to step down, move to an emeritus status and keep
their credit in [CONTRIBUTORS.md](CONTRIBUTORS.md).

## Changing this document

This governance is expected to evolve, for example towards several maintainers deciding by consensus. Changes are made
through a pull request approved by the lead maintainer.
