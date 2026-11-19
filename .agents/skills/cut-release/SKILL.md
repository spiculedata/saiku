---
name: cut-release
description: Cut a Saiku release via Gitflow — version bump, release branch, PR to main, tag, back-merge, and post-release chores. Use when asked to release, tag, or publish a new Saiku version.
---

# Cut a release

Publishing is entirely CI-driven. Pushing a `v*` tag runs `release.yml`, which publishes the fat-JAR and dist zip (GitHub release assets), module jars (GitHub Packages), the Docker image (`ghcr.io/spiculedata/saiku:<version>`) and the npm embed package. Never `mvn deploy` locally.

1. **Bump the version on a branch off `development`.** Every reactor pom carries the literal release version (non-SNAPSHOT).
   ```bash
   mvn versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false
   ```
   `versions:set` misses `saiku-bom/pom.xml`, so fix that by hand. Check with `git grep -n '<version>OLD' -- '*pom.xml'`.
2. **Update `CHANGELOG.md`** with a section for the release, and fold in any `docs/release-notes-*-draft.md`. Merge this to `development` via PR.
3. **Cut `release/X.Y.Z` off `development` and push it.** `release-prep.yml` runs the `mvn verify` gate.
4. **Open a PR from `release/X.Y.Z` to `main` and merge it once green.**
   > `gh pr merge` can silently no-op while a required check is pending. Before tagging, confirm `main` actually advanced: `git fetch && git log -1 origin/main` should show the release merge and the new version.
5. **Tag the merged `main` HEAD and push the tag.**
   ```bash
   git tag vX.Y.Z origin/main && git push origin vX.Y.Z
   ```
   `release.yml`'s `guard` job refuses any tag whose commit is not reachable from `main`, so tag only the merged `main` HEAD (see docs/releasing.md). If you tagged the wrong commit: `gh run cancel` the release run, `git push origin :refs/tags/vX.Y.Z`, `git tag -d vX.Y.Z`, then re-tag correctly.
6. **Replace the auto-generated release notes** (a raw commit list) with the changelog section: `gh release edit vX.Y.Z --notes-file <file>`.
7. **Back-merge `main` into `development`** via a `chore/back-merge-X.Y.Z` PR.
8. **Post-release:** bump `LATEST_VERSION` in `telemetry/wrangler.toml` (PR to `development`), and redeploy the demo box.
