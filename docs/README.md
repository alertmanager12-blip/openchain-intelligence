# ChainSentinel documentation

Where to find what. Documents are in English unless marked *(FR)*. When you change behaviour, update the page that
describes it in the same pull request.

## Getting started

| Document | Purpose |
|---|---|
| [Project README](../README.md) | What ChainSentinel is, step-by-step install (demo and production), development setup |
| [Quickstart](guides/quickstart.md) | Try the demo mode in a few minutes |
| [Installation](guides/installation.md) | Production installation, every configuration variable, operations |
| [Security hardening](guides/security-hardening.md) | Checklist before exposing an instance on a network |

## Using ChainSentinel

| Document | Purpose |
|---|---|
| [Writing rules](guides/writing-rules.md) | The rule language (`openchain.io/rule/v1`), examples, dry run |
| [Rule pack](../rules/README.md) | Rules shipped with the project and how they are tested |
| [API v1 contract](api/README.md) | REST API, authentication, errors, stability guarantees |

## Architecture

| Document | Purpose |
|---|---|
| [Architecture overview](architecture/overview.md) | Modules, data flow, deployment |
| [C4 diagrams](architecture/c4/) | System context, containers, alerting components (Structurizr `workspace.dsl`) |
| [Data model](architecture/data-model.md) | PostgreSQL schema |
| [Rule engine](architecture/rule-engine.md) | How rules are evaluated, aggregated and delivered |
| [Sequence diagrams](architecture/sequences.md) | Main runtime flows |
| [Technology choices](technology-choices.md) | Why each technology was chosen |
| [Architecture decision records](adr/README.md) | Every significant decision, its context and its consequences |

## Development and contribution

| Document | Purpose |
|---|---|
| [CONTRIBUTING.md](../CONTRIBUTING.md) | Set up, branch, test, commit and open a pull request |
| [Testing strategy](testing-strategy.md) | Test levels, what to test where, Testcontainers |
| [Release process](guides/release-process.md) | Versioning, changelog, tags and GitHub releases |
| [GOVERNANCE.md](../GOVERNANCE.md) | Roles, decisions, becoming a maintainer |
| [SECURITY.md](../SECURITY.md) | Reporting vulnerabilities privately, supported versions |
| [SUPPORT.md](../SUPPORT.md) | Where to ask questions and report problems |

## Strategy and roadmap *(FR)*

| Document | Purpose |
|---|---|
| [Strategy index](strategy/README.md) | Vision, market, roadmap, target architecture, risks (2026-11-09) |
| [Feature roadmap](strategy/FEATURE_ROADMAP.md) | Phases 0 to 6 and their order |
| [Open source strategy](strategy/OPEN_SOURCE_STRATEGY.md) | Licence choice, open core boundary, community |

## Project health

| Document | Purpose |
|---|---|
| [Open source and GitHub audit](OPEN_SOURCE_GITHUB_AUDIT.md) | State of the repository's community setup and remaining actions |
| [Strategic audit (PDF, FR)](audit/ChainSentinel-audit-strategique-2026-11-06.pdf) | Audit of 2026-11-06, snapshot `f34b31d` |
