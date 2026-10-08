# CI images: per-PR tags and retention

`.github/workflows/docker.yml` publishes `ghcr.io/spiculedata/saiku`. Besides
the rolling `development`/`main` images and the release images (see
[releasing.md](releasing.md)), it publishes a per-PR image so a reviewer can
pull exactly what a PR built.

## Tag grammar

| Tag | Pushed by | Mutable | Retention |
|---|---|---|---|
| `development`, `main` | push to that branch | yes (rolling tip) | never deleted |
| `sha-<7 hex>` | push to `development` / `main` | no | never deleted |
| `<semver>`, `latest` | `release.yml` on a `v*` tag | no / yes | never deleted |
| `pr-<n>` | same-repo PR targeting `development` | yes (moves with each PR push) | 14 days |
| `<7 hex>` (bare) | same-repo PR targeting `development` | no | 30 days |

The bare `<7 hex>` tag is the **PR head** commit, not the synthetic merge
commit GitHub builds on `pull_request` events, so it maps back to a commit on
the PR branch. Note the prefix difference: rolling pushes use `sha-<short>`,
PRs use the bare hex. Only the bare form is ever eligible for deletion.

## Who gets an image

| Event | Builds | Pushes |
|---|---|---|
| push to `development` / `main` | yes | yes (unchanged) |
| `workflow_dispatch` | yes | yes (branch tag) |
| `workflow_dispatch` with `pr=<n>` (sent by `/preview`) | yes (that PR's head commit) | yes (`pr-<n>` + head SHA **only**; same-repo, open, targets `development`, not Dependabot) |
| `pull_request` from a branch in this repo | yes | yes (`pr-<n>` + head SHA) |
| `pull_request` from a fork | yes (best effort) | **no** |
| `pull_request` opened by Dependabot | yes (best effort) | **no** |

PR runs are path-filtered to what can change the image (`pom.xml`,
`**/pom.xml`, `saiku-*/**`, `lib/**`, `Dockerfile`, `docker/**`, the workflow
itself). Docs-only PRs build no image. A newer push to the same PR cancels the
older run.

### Why fork and Dependabot PRs cannot push

A fork's `GITHUB_TOKEN` has no `packages:write`, so the push would fail, and
publishing an outside contributor's build into our registry is not something
the workflow should do even if it could. The `Resolve publish eligibility` step
compares `github.event.pull_request.head.repo.full_name` with
`github.repository` and refuses `dependabot[bot]`; every login, push, SBOM,
provenance and attestation step keys off that one output. Fork and Dependabot
runs also do not receive `GH_PACKAGES_TOKEN`, so the Maven build usually cannot
resolve the GitHub Packages dependencies; that is the same limitation `ci.yml`
has for them.

The build job keeps `contents: read` only; the push job (which holds
`packages: write`) never runs Maven or npm. It only restores the built JAR.
A same-repo PR author already has write access, so the PR-built JAR reaching
GHCR under `pr-<n>` does not widen who can publish.

## Retention

`.github/workflows/ghcr-tag-retention.yml` runs weekly (Monday 03:17 UTC) and on
demand, using `.github/scripts/ghcr_tag_retention.py`:

- Only `pr-<n>` tags older than 14 days and bare hex-SHA tags older than 30
  days are eligible.
- GHCR deletes by version, and a version can carry several tags. A version is
  deleted only if **every** tag on it is eligible and expired. Any other tag
  (`development`, `main`, `latest`, a release version, `sha-<short>`, a
  `sha256-...` attestation tag, anything unrecognised) vetoes deletion.
- The 10 most recently updated versions are always kept (`--min-keep`).
- Untagged versions are left alone.
- **Dry run by default.** Deletion happens only when a manual run sets the
  `apply` input, or a scheduled run finds the repository variable
  `GHCR_RETENTION_APPLY=true`. Read a few dry-run logs before setting it.

Run it locally (dry run, needs `gh` logged in with `read:packages`):

```bash
python3 .github/scripts/ghcr_tag_retention.py fetch > versions.json
python3 .github/scripts/ghcr_tag_retention.py plan --versions versions.json
```

Deleting a multi-arch image version does not remove separately stored
attestation artifacts; those keep their `sha256-...` tags and are never
collected by this script.

## Tests

`python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v` runs in the
`scripts` job of `ci.yml` (part of the `ci` rollup). It covers the retention
policy and a guard that fails if any workflow `uses:` pin that looks like a hex
SHA is not exactly 40 characters, or if one SHA is pinned for two different
actions.
