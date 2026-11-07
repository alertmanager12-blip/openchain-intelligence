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

## Amendment 1 (2026-11-06): OFL-1.1 for font files only

- Status: accepted

### Context

The console needs self-hosted web fonts: the nginx Content-Security-Policy is `default-src 'self'`, so fonts cannot come from a CDN, and the system font stack renders differently on every analyst's machine (tabular figures in particular). Good open fonts are almost all published under the SIL Open Font License 1.1, which was outside the allowlist.

### Decision

**OFL-1.1 is allowed for font files only.** Scope and conditions:

| Condition | Rule |
|---|---|
| Scope | Font assets (`.woff2` / `.woff`) and the `@font-face` CSS that declares them. Never code: a package under OFL-1.1 that ships JavaScript is not covered. |
| Packaging | Fonts are kept as separate files in the build output (`dist/frontend/browser/media/`), never inlined into or merged with Apache-2.0 code, never renamed. Using OFL fonts in a document or a web page does not change the licence of that page (OFL-1.1, permission 4 and the FAQ). |
| Modification | Fonts are used unmodified (only the upstream Fontsource Latin subsets are shipped). A modified font must not use the Reserved Font Name. |
| Attribution | Each font is credited in `NOTICE` with its copyright line and licence. The OFL text ships inside each npm package (`LICENSE`). |
| Enforcement | The CI npm licence check allows `OFL-1.1` only for the named font packages (`@fontsource-variable/inter`, `@fontsource/jetbrains-mono`); every other package is still checked against the original allowlist. Adding another font package requires updating that list and `NOTICE`. |

Fonts adopted: Barlow (UI text), Barlow Semi Condensed (titles, numeric readouts, table headers), JetBrains Mono (addresses, hashes, YAML), via Fontsource 5.3.0, weights 400/500/600, 500/600 and 400 respectively, Latin subset.

Revision (2026-11-07): the console redesign replaces Barlow and Barlow Semi Condensed with **Inter** (`@fontsource-variable/inter` 5.3.0, OFL-1.1, Copyright 2016 The Inter Project Authors), one variable file (weight axis 100 to 900) for all UI text, titles and figures. Only the Latin weight-axis subset is declared (`frontend/src/theme/inter.css`), so a single unmodified upstream WOFF2 of about 48 KB ships. The Barlow packages are removed from the dependencies and from the CI allow-list. JetBrains Mono is unchanged.

### Consequences

- Consistent rendering and tabular figures (OpenType `tnum`, available in Inter and JetBrains Mono) across platforms.
- About 134 KB of WOFF2 for the six faces (272 KB on disk including the WOFF fallbacks); browsers download only the faces a page uses (`font-display: swap`).

