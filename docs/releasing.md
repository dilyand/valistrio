# Releasing Valistrio

Releases are deliberate: a release happens only when a human pushes an annotated
`X.Y.Z` tag. That tag is the single source of truth for the version — there is no
version file in the repo. `sbt-dynver` derives the build version from the tag, and the
`Release` workflow (`.github/workflows/release.yml`) reacts to the tag push.

## Version scheme

Tags are plain SemVer (`X.Y.Z`), no `v` prefix. A pre-release adds a suffix
(`X.Y.Z-alpha.N`): it builds and pushes a version-stamped image but is **not** turned
into a GitHub Release.

## What the workflow does

Triggered on `push` of a SemVer tag:

1. Checks out full history (dynver needs the tag and its distance).
2. Builds the app image and pushes it to GHCR as
   `ghcr.io/dilyand/valistrio:<version>` **and** `ghcr.io/dilyand/valistrio:latest`.
3. For a prod-ready version (no `-suffix`), creates a GitHub Release named after the tag,
   with notes taken from the matching `## [X.Y.Z]` section of `CHANGELOG.md`. The section
   must exist and be non-empty — the workflow fails the release if it is missing.

The image coordinates are gated by `VALISTRIO_RELEASE=true`, which the workflow sets.
Local builds and the integration-test suite never set it, so they keep producing the
fixed `valistrio:it` image that `ValistrioContainer` expects.

Deploy the immutable `:<version>` tag, not `:latest`, so a running deploy is always
traceable to an exact release and rollback is deterministic. `:latest` also moves on
every release and is unsuitable as a deploy target.

> Images publish to GHCR; the package must be public for anonymous pulls.

## Cutting a release

1. In the release PR, make sure `CHANGELOG.md` has the release's `## [X.Y.Z]` section and
   change its header from `— unreleased` to the release date (`## [0.1.0] — YYYY-MM-DD`).
2. Merge the PR into `main` locally, but do not push `main` yet.
3. Tag the merged commit and push **only the tag**:
   ```bash
   git tag -a 0.1.0 -m "0.1.0"
   git push origin 0.1.0
   ```
   The `Release` workflow builds and pushes the image and creates the GitHub Release.
4. Once the release asset has published, push `main`:
   ```bash
   git push origin main
   ```
   Pushing the tag first means a failed release never advances `main`.
