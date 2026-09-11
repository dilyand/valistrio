# Releasing Valistrio

Releases are deliberate: a release happens only when a human pushes an annotated
`vX.Y.Z` tag. That tag is the single source of truth for the version — there is no
version file in the repo. `sbt-dynver` derives the build version from the tag, and the
`Release` workflow (`.github/workflows/release.yml`) reacts to the tag push.

## Version scheme

- `0.1.0` — the first full release (`/validate` + `/post` both shipped).
- Pre-releases: `0.1.0-alpha.N`, tagged manually on `release/0.1.0` as each feature
  lands. These trigger the same workflow and publish a pre-release image.
- SemVer: `feat` → minor, `fix`/`chore` → patch.

## What the workflow does

Triggered on `push` of any `v*` tag:

1. Checks out full history (dynver needs the tag and its distance).
2. Builds the app image and pushes it to GHCR as
   `ghcr.io/dilyand/valistrio:<version>` **and** `ghcr.io/dilyand/valistrio:latest`.
3. Creates a GitHub Release named after the tag, with notes taken from the matching
   `## [X.Y.Z]` section of `CHANGELOG.md` (falling back to auto-generated notes if that
   section is empty).

The image coordinates are gated by `VALISTRIO_RELEASE=true`, which the workflow sets.
Local builds and the integration-test suite never set it, so they keep producing the
fixed `valistrio:it` image that `ValistrioContainer` expects.

## Cutting a release

1. On `release/0.1.0`, make sure `CHANGELOG.md` has the release's `## [X.Y.Z]` section
   and change its header from `— unreleased` to the release date
   (`## [0.1.0] — YYYY-MM-DD`). This is the changelog edit that lands in the release PR.
2. Merge the release PR to `main`.
3. Tag the merged commit and push the tag:
   ```bash
   git checkout main && git pull
   git tag -a v0.1.0 -m "v0.1.0"
   git push origin v0.1.0
   ```
   The `Release` workflow takes it from there.

## One-time GHCR setup

The first push creates the `valistrio` package under `dilyand` as **private**. Make it
public once (GitHub → Packages → valistrio → Package settings → Change visibility) so
the image can be pulled anonymously. The workflow authenticates with the built-in
`GITHUB_TOKEN`; no extra secret is needed.
