# "Good first issue" proposals

Drafts to create on GitHub by the maintainer (none is created yet). First written on 2026-11-12 against `main` at
`5f581fe` (file:line references are on `main`), rewritten on 2026-11-13 after the scratchpad copy was lost. Labels:
`good first issue`, the roadmap phase (`docs/strategy/FEATURE_ROADMAP.md`: `phase-1` reliable intelligence core,
`phase-2` graph intelligence) and an area label.

Status on 2026-11-13: issues 1 and 5 were replaced (the lag metric and the watchlist audit were fixed on the working
branch `wip/adr-0024-step-3a`); part (a) of issue 7 is fixed there too (commit `444e423`), part (b) is open. File:line
references of issues 1 and 5 are on the working branch. Check again before creating them.

---

## 1. Honour a provider's Retry-After beyond 5 seconds

Labels: `good first issue`, `phase-1`, `area:providers`

When a provider answers HTTP 429 with `Retry-After`, `RetryPolicy` waits `max(retryAfter, backoff)` but then caps the
wait at `maxBackoff` (`oci-blockchain/.../transport/RetryPolicy.java:42-45`), which is 5 s by default
(`ResilienceConfig.java:27`). A provider asking for 30 s is called again after 5 s, which prolongs its rate limiting
(observed with TronGrid on 2026-11-12).

Files: `RetryPolicy.java`, `ResilienceConfig.java`, `oci-blockchain/src/test/.../transport/ResiliencePrimitivesTest.java`.

Acceptance: a server `Retry-After` is honoured up to a separate, configurable bound (for example 60 s), and a longer one
fails the attempt as retryable instead of blocking the indexer; the computed backoff keeps its 5 s cap; tests cover a
`Retry-After` below, equal to and above both bounds with the recording sleeper.

## 2. Count blocks skipped when the resume gap is exceeded

Labels: `good first issue`, `phase-1`, `area:observability`

After a long downtime, `Bootstrap.guardResumeGap` (`oci-app/.../config/Bootstrap.java:365-378`) restarts near the head
and only logs "blocks X..Y are NOT indexed". Nothing else records the gap.

Files: `Bootstrap.java`, `MetricsConfiguration.java`, a test.

Acceptance: a counter `oci_indexer_skipped_blocks_total{network}` increases by `start - last - 1` when the gap is applied;
the log line is unchanged; a test checks the counter with a checkpoint older than `max-resume-gap`.

## 3. Intelligence journal: retry a failed batch before dropping it

Labels: `good first issue`, `phase-1`, `area:persistence`

`JdbcIntelligenceJournal` drops a whole batch on the first `RuntimeException`
(`oci-app/.../journal/JdbcIntelligenceJournal.java:86-91`): a transient database error loses up to 1,000 records.

Files: `JdbcIntelligenceJournal.java`, `oci-app/src/test/.../persistence/JdbcRepositoriesPersistenceTest.java`.

Acceptance: a failed batch is retried a bounded number of times (for example 3, with a short back-off) before being counted
in `oci_journal_dropped_total`; the order of inserts and deletes is preserved; a test with a failing first attempt shows
no record lost.

## 4. Intelligence journal: purge in bounded chunks

Labels: `good first issue`, `phase-1`, `area:persistence`

`purge` runs one unbounded `DELETE … WHERE ts < :before` per table (`JdbcIntelligenceJournal.java:158-162`). At chain-wide
volume (about 4.8 million flows per day) a single statement holds locks and produces a large WAL burst.

Files: `JdbcIntelligenceJournal.java`, its persistence test.

Acceptance: deletion in chunks (for example 10,000 rows per statement, looping until none is left); same total returned;
the test purges more rows than one chunk and checks the count.

## 5. Fund flow graph: say that dates are UTC

Labels: `good first issue`, `phase-2`, `area:frontend`

The date filter of the fund flow graph turns the chosen days into `T00:00:00Z` and `T23:59:59Z`
(`frontend/src/app/features/investigation/investigation.ts:200-202`): the range is a UTC day, while the date picker
shows local dates without saying so. An analyst east or west of UTC gets a range shifted by their offset.

Files: `investigation.ts`, its template, a spec.

Acceptance: the filter states "UTC" next to the dates (and in the tooltip of an edge's time), or offers a local/UTC
choice that is applied consistently; a spec checks the generated `from`/`to` for one choice.

## 6. Unit tests for OIDC authority mapping

Labels: `good first issue`, `phase-1`, `area:security`

`SecurityConfiguration.jwtAuthorities` (`oci-app/.../security/SecurityConfiguration.java:143`) maps `scope`/`scp` claims
and a configurable roles claim to permissions. It has no direct test.

Files: new test next to `SecurityApiTest`.

Acceptance: tests for `scope` (space-separated) and `scp` (list), unknown scopes ignored, role mapping through
`Scopes.forRole`, missing claims giving no authority.

## 7. Fund flow graph: stale elements and date filter

Labels: `good first issue`, `phase-2`, `area:frontend`

Two defects of the graph page. (a) On the same root, a narrower token, date range or depth only added elements, so stale
transfers stayed on screen (`graph-canvas.ts`, fixed on the working branch). (b) Edges are aggregated per (source,
target, token) and the client-side filter `keyed()`
(`frontend/src/app/features/investigation/investigation.ts:35-39`) compares the range with `edge.timestamp`, which is the
**last** transfer only: an edge whose earlier transfers fall in the range can disappear.

Files: `investigation.ts`, `graph-canvas.ts`, specs.

Acceptance: the drawn graph always matches the current data; the client does not drop server-filtered edges (the server
already filters by `from`/`to`), or filters on the first and last instants; specs cover both cases.

## 8. Fund flow graph: path that follows the transfer direction

Labels: `good first issue`, `phase-2`, `area:frontend`

The "path to the root" uses `aStar({ … directed: false })` (`frontend/src/app/features/investigation/graph-canvas.ts:174`)
and says "direction ignored". It can link two wallets that no value could have moved between.

Files: `graph-canvas.ts`, `graph-inspector.ts`, specs.

Acceptance: a toggle "follow transfer direction" (default on) uses `directed: true`; the message says which mode was used
and "no directed path in the loaded graph" when none exists; specs cover both modes.

## 9. CI: fail when integration tests are skipped

Labels: `good first issue`, `phase-1`, `area:ci`

The PostgreSQL integration tests are skipped when Docker is missing (`CONTRIBUTING.md:113`). In CI they must run: the
phase 1 exit criterion is a restart test green in CI with zero skipped tests.

Files: `.github/workflows/ci.yml` (and possibly `oci-app/src/test/.../persistence/PostgresSupport.java`).

Acceptance: in CI (`CI=true`), a missing Docker daemon fails the build instead of skipping; or a CI step fails when the
surefire reports contain `Skipped` greater than 0. Local builds without Docker keep skipping.

## 10. Document `max-resume-gap` in the installation guide

Labels: `good first issue`, `phase-1`, `area:docs`

The resume-gap behaviour (restart near the head when the checkpoint is more than 7,200 blocks behind, range logged as not
indexed) is described only in `docs/adr/0024-persist-chain-derived-state.md:29`, not in `docs/guides/installation.md`.

Files: `docs/guides/installation.md`.

Acceptance: the guide documents `oci.chains.<network>.max-resume-gap` (environment variable form, default 7,200, `0` =
always resume), what happens to the skipped range, and how to find it in the logs.
