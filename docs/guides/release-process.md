# Release process

How ChainSentinel versions are numbered and published. The first release is `0.1.0`, a pre-release; release notes
live in [`docs/releases/`](../releases/).

## Versioning

ChainSentinel follows [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html): `MAJOR.MINOR.PATCH`.

| Part | Increases when… | Example |
|---|---|---|
| `MAJOR` | a change breaks compatibility: REST API v1, rule schema `openchain.io/rule/v1`, configuration variables, or stored data that cannot be migrated automatically | removing an API field |
| `MINOR` | a compatible capability is added | a new detector, a new connector, a new optional variable |
| `PATCH` | a compatible fix is made, including security fixes | a detector false positive, a dependency upgrade |

While the version is `0.x`, a minor version may contain breaking changes; they are always listed in the changelog
(see the note at the top of [CHANGELOG.md](../../CHANGELOG.md)). The public API and the rule schema are stable from
`1.0.0`.

Pre-releases use a suffix: `0.2.0-rc.1`.

### What carries the version

| File | Field |
|---|---|
| `pom.xml` and every module's `pom.xml` | `<version>` (Maven `-SNAPSHOT` between releases) |
| `docker-compose.yml` | `openchain-intelligence/app` and `openchain-intelligence/frontend` image tags |
| `frontend/package.json` | `version` (currently `0.0.0`, not used at runtime) |

## Changelog

[CHANGELOG.md](../../CHANGELOG.md) follows [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/). Every pull
request with a user-visible change adds a line under `## [Unreleased]`, in the matching section: *Added*, *Changed*,
*Deprecated*, *Removed*, *Fixed*, *Security*. Write for operators: what changes for them, and what they must do.

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) (see
[CONTRIBUTING.md](../../CONTRIBUTING.md#commits)); they help write the changelog but do not replace it.

## Publishing a release

Releases are published by hand by a maintainer, with the lead maintainer's approval. Nothing is published
automatically.

1. **Check `main`:** CI is green on the commit to release, no blocking issue is open for the milestone, and the
   documentation is up to date.
2. **Prepare the release in a pull request** (`release/X.Y.Z`):
   - move the `[Unreleased]` entries to a new `## [X.Y.Z] - YYYY-MM-DD` section and update the comparison links at the
     bottom of the changelog;
   - set the version: `mvn -B versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false`, then the image tags in
     `docker-compose.yml`;
   - run `mvn -B clean verify`, the frontend tests and `bash deploy/smoke-test.sh`.
3. **Merge it, then tag the merge commit** with an annotated tag:

   ```bash
   git tag -a vX.Y.Z -m "ChainSentinel X.Y.Z"
   git push origin vX.Y.Z
   ```

4. **Create the GitHub release** from the tag, with the changelog section as release notes (`gh release create vX.Y.Z
   --title "ChainSentinel X.Y.Z" --notes-file <section>`). Mark `0.x` and release candidates as pre-releases if
   appropriate.
5. **Open the next development cycle:** set the version to the next `-SNAPSHOT` and add an empty `[Unreleased]`
   section.

Docker images are built locally by `docker compose up --build`. When a maintainer publishes a GitHub release, the
[`Release` workflow](../../.github/workflows/release.yml) checks that the tag matches the versions of `pom.xml`,
`frontend/package.json` and `CHANGELOG.md`, pushes the app and frontend images to GitHub Container Registry
(`ghcr.io/<owner>/openchain-intelligence-app:X.Y.Z`, `...-frontend:X.Y.Z`, no `latest` tag) and attaches a CycloneDX
SBOM of each image to the release. It never creates a tag or a release. New GHCR packages are private until made public
in their settings.

`deploy/smoke-test.sh` builds and starts the whole stack: run it on a machine without a deployment of ChainSentinel (CI
runs it in the "Docker images" job). Up to 0.1.0 it builds the images under the tags of `docker-compose.yml`, so on a
machine that runs ChainSentinel it would replace the images of that deployment.

## Security releases

A vulnerability fix is prepared in a private security advisory (see [SECURITY.md](../../SECURITY.md)), released as a
`PATCH` version, and the advisory is published at the same time as the release.
