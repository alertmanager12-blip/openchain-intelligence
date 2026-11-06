# ADR-0012: Licensing policy — Apache-2.0, dependency allowlist, no AGPL/BSL in core

- Status: accepted
- Date: 2026-11-06

## Context and problem statement

OCI should be usable by companies (exchanges, PSPs, auditors) and by the public sector without legal friction, and accept contributions safely. Several popular infrastructure projects moved to non-OSI or strong-copyleft licences (Redis RSAL/SSPL then AGPL option, Elasticsearch SSPL/Elastic then AGPL option, Grafana AGPL, Memgraph BSL, TimescaleDB TSL).

## Considered options

1. **Apache-2.0** for the project; allowlist for linked dependencies; external services under any OSI licence allowed if run as separate processes and not redistributed in our artefacts.
2. AGPL-3.0 for the project.
3. MIT.

## Decision outcome

Chosen option: **1**.

| Category | Policy |
|---|---|
| Project code and docs | Apache-2.0 (`LICENSE`, `NOTICE`); contributions under DCO sign-off |
| **Allowed** for linked/bundled dependencies | Apache-2.0, MIT, BSD-2/3-Clause, ISC, PostgreSQL, EPL-2.0 (with secondary licence / not modified), MPL-2.0 (unmodified files), CC0, Unicode-DFS, 0BSD, Zlib, Python-2.0 (tooling only) |
| Review required | LGPL-2.1/3.0 (dynamic linking only), EPL-1.0, CDDL |
| **Forbidden** in shipped artefacts (JAR, image, SPA bundle) | GPL-2.0/3.0 (without Classpath exception), **AGPL-3.0**, **SSPL**, **BSL/BUSL**, RSAL, Elastic License, Commons Clause, TSL, proprietary |
| External services (separate process, user-operated, not bundled) | Allowed with any OSI licence, documented: e.g. Grafana (AGPL-3.0), Neo4j Community (GPLv3); non-OSI services (BSL/SSPL) are not referenced as defaults |

### Pros

- Patent grant and permissive terms favoured by enterprise legal teams.
- Clear, automatable rule set (CI licence check).

### Cons

- Excludes some convenient libraries/services from the core.

## Consequences

- CI runs a dependency licence check for Maven (e.g. `license-maven-plugin` `add-third-party` + fail on forbidden) and npm (`license-checker --onlyAllow`). A placeholder job exists in `.github/workflows/ci.yml`.
- `NOTICE` lists bundled third-party notices required by Apache-2.0 dependencies.
- Redis is not a default dependency; if a cache is needed we document **Valkey** (BSD-3-Clause).
- Docker images contain only the JRE base (GPLv2 + Classpath Exception, permitted as the runtime) and our artefact.
