# CI and deployment overview

Orientation for how a change gets from a pull request to the public demo, which workflow does
what, and which GitHub-side settings the whole thing depends on. This page is a map: each
piece has its own document, linked below, and the details live there.

## The path of a change

```
PR into development
  |-- ci ............ the only REQUIRED check (build, ui, dist, scripts, flake-policy, ...)
  |-- docker ........ builds ghcr.io/spiculedata/saiku:pr-<n> and :<7-hex sha> (same-repo, path-filtered)
  |-- acceptance .... runs acceptance/<issue>/ specs (not required)
  |-- preview env ... optional throwaway container at oss-pr-<n>.preview.saiku.bi (tailnet only)
  '-- ci feedback ... sticky PR comment + ci-feedback.json when anything above is red

gh pr merge N --merge  ->  merge queue (re-runs `ci` on the exact merge result)
                       ->  development
push to development
  |-- ci ............ again, on the merged commit
  |-- docker ........ ghcr.io/spiculedata/saiku:development and :sha-<7 hex>   (parallel, ungated)
  '-- promote-green . after ci succeeds: retag sha-<short> as :development-green
VM systemd timer (~5 min) pulls :development-green, smoke-tests, swaps, rolls back on failure
```

Two things to hold on to:

- **Branch protection requires one check, `ci`.** It is an aggregator job inside `ci.yml`;
  everything that gates a merge feeds it. Other workflows (acceptance, previews, Claude review,
  docker) report on the PR but do not block it.
- **The demo runs only `:development-green`**, never the unvetted `:development` tip. See
  [demo-deployment.md](demo-deployment.md).

## The required check: `ci`

`.github/workflows/ci.yml` runs on `pull_request`, on `push` to `development` and `phase-*`,
and on `merge_group`. The final job, `ci`, `needs` every other job and fails if any of them
reports anything other than success or skipped. That is why the rule stays stable when jobs are
path-filtered or renamed.

| Job | Runs | What it checks |
| --- | --- | --- |
| `changes` | always | `dorny/paths-filter` decides `java` / `ui` / `docs`. Skipped on `merge_group` |
| `build` | java paths changed, any `push`, any `merge_group` | licence headers, `mvn verify` (Spotless, unit tests, ITs) with one retry per failing test, test-count floors (`.github/test-floors.json`), coverage floors (`.coverage-thresholds.json`) |
| `ui` | ui paths changed, any `push`, any `merge_group` | `npm run check`, vitest (failing files retried once), lint, production build, mocked-backend Playwright |
| `dist` | java paths changed, any `push`, any `merge_group` | builds the launcher fat-JAR and uploads `saiku-dist-<version>.zip` |
| `demo-deploy` | always | shellcheck + fake-docker tests for `deploy/demo/` |
| `scripts` | always | python unittests in `.github/scripts/` (GHCR retention, 40-hex `uses:` pin guard) |
| `flake-policy` | always | `node --test ".github/scripts/*.test.mjs"` and the quarantine-validity check |
| `acceptance-convention` | `pull_request` only | a PR closing issue N (at or above the cutoff) ships `acceptance/N/spec.json` or carries `acceptance-waived` |

Test and coverage floors are ratchets; see [quality.md](quality.md). AGENTS.md has the build
commands.

## Policy in a few lines

- **CI feedback** ([ci-feedback.md](ci-feedback.md)). When `acceptance`, `ci`, `design-system`,
  `docker`, `preview-infra` or `release-prep` finishes, `ci-feedback.yml` writes
  `ci-feedback.json` (the first real error per failing job) and keeps one sticky comment on the
  PR current. Read that comment before the raw log. A new PR-gating workflow must be added to its
  `workflow_run.workflows` list or a test fails.
- **Flakes** ([ci-flakes.md](ci-flakes.md)). A failing test is retried once and recorded. Pass on
  retry is a visible flake; fail twice blocks. A quarantine entry in
  `.github/flake-quarantine.json` needs an owner and an expiry, and a quarantined test still runs.
  No test asserts on a wall clock. `flake-ledger.yml` folds recent runs into a rolling rate
  (read-only, job summary).
- **Acceptance specs** ([acceptance-specs.md](acceptance-specs.md),
  [acceptance/README.md](../acceptance/README.md)). The gate is `ci / acceptance convention`
  inside `ci.yml`. `acceptance.yml` executes the specs against `ACCEPTANCE_BASE_URL` and is not
  required: with no base URL the specs are recorded `not-run`, never `pass`.

## Images and previews

- **Per-PR images** ([ci-images.md](ci-images.md)). `docker.yml` pushes `pr-<n>` and the bare
  7-hex head SHA for same-repo PRs into `development` that touch image inputs. Fork and
  Dependabot PRs build without pushing. `ghcr-tag-retention.yml` sweeps expired PR tags weekly
  (dry run unless `GHCR_RETENTION_APPLY=true`).
- **Preview environments** ([decisions/ci-preview-environments.md](decisions/ci-preview-environments.md),
  [infra/preview/README.md](../infra/preview/README.md)). A same-repo PR authored by a listed bot
  login, labelled `preview`, or `/preview`ed by a collaborator gets a container on the shared
  preview box, reachable only from the tailnet. `preview-env.yml` (lifecycle),
  `preview-command.yml` (`/preview` comments) and `preview-reaper.yml` (hourly idle teardown) all
  skip cleanly while `PREVIEW_SSH_TARGET` is empty. `preview-infra.yml` is the offline test of
  that tooling.

## Workflow map

Every file under `.github/workflows/`.

| Workflow | Trigger | Gates or produces | Doc |
| --- | --- | --- | --- |
| `ci.yml` | PR, push to `development` / `phase-*`, `merge_group` | the required `ci` check; `saiku-dist` artifact; flake records | this page, [ci-flakes.md](ci-flakes.md) |
| `docker.yml` | push to `development` / `main`, same-repo PR (path-filtered), manual | `:development`, `:main`, `:sha-<short>`, `:pr-<n>`, SBOM + provenance | [ci-images.md](ci-images.md) |
| `promote-green.yml` | `ci` completed on `development`, manual (rollback) | retags `sha-<short>` as `:development-green` | [demo-deployment.md](demo-deployment.md) |
| `acceptance.yml` | PR opened / synchronised / reopened | executes `acceptance/<issue>/` specs; results artifact; feeds `ci-feedback.json` | [acceptance-specs.md](acceptance-specs.md) |
| `ci-feedback.yml` | `workflow_run` of the gating workflows | sticky PR comment, `ci-feedback.json` | [ci-feedback.md](ci-feedback.md) |
| `flake-ledger.yml` | weekdays 05:41 UTC, manual | rolling flake rate in the job summary and an artifact | [ci-flakes.md](ci-flakes.md) |
| `preview-env.yml` | `pull_request_target` into `development` | create / update / tear down the PR's preview | [ci-preview-environments.md](decisions/ci-preview-environments.md) |
| `preview-command.yml` | `/preview` issue comment | collaborator-triggered preview | same |
| `preview-reaper.yml` | hourly, manual | tears down idle previews (dry run by default when dispatched) | same |
| `preview-infra.yml` | PR / push touching preview tooling, manual | offline tests of the preview tooling | same |
| `ghcr-tag-retention.yml` | Mondays 03:17 UTC, manual | prunes expired `pr-<n>` / bare-SHA tags | [ci-images.md](ci-images.md) |
| `design-system.yml` | push to `development` / `main` under `saiku-ui/design-system/**`, manual | publishes `@concepttocloud/saiku-design-system` to npm if the version is new | AGENTS.md |
| `release-prep.yml` | push to `release/*`, PR into `main` | `mvn verify` before tagging | [releasing.md](releasing.md) |
| `release.yml` | tag `v*`, manual | JAR, GitHub Packages, ghcr release images, npm embed packages, docs changelog PR | [releasing.md](releasing.md) |
| `nightly-compliance.yml` | daily 02:43 UTC, manual | full java + ui + OWASP sweep of `development`; one tracking issue (`nightly-compliance`) on red | AGENTS.md |
| `quality-report.yml` | Mondays 05:17 UTC, manual | weekly quality dashboard in the job summary (a report, not a gate) | [quality.md](quality.md) |
| `pr-metrics.yml` | PR closed, Mondays 06:17 UTC, manual | acceptance-rate comment and rolling metrics | [metrics.md](metrics.md) |
| `tier-classifier.yml` | `pull_request_target` | advisory `risk/high|medium|low` label | [risk-tiers.md](risk-tiers.md) |
| `labeler.yml` | `pull_request_target` | advisory path labels (`area/*`) from `.github/labeler.yml` | none |
| `claude-code-review.yml` | PR opened / synchronised / ready / reopened | automated review comments | none |
| `claude.yml` | issue and PR comments, reviews, issues opened / assigned | Claude Code on request | none |
| `auto-review.yml` | `apply-review` label added | applies review comments as one commit on the PR branch | none |

`ci-feedback.yml` also lists `model-diff` and `security-scan`; neither workflow exists on
`development` yet (see [ci-feedback.md](ci-feedback.md)).

## Merge queue

`development` has a native GitHub merge queue. The queue builds the exact merge result (the PR
on top of every entry ahead of it) and merges only when `ci` passes on that result, so a PR
cannot land on a stale base.

Configuration (repository ruleset `development merge queue`, target `refs/heads/development`,
the only rule is `merge_queue`):

| Setting | Value |
| --- | --- |
| Merge method | `MERGE` |
| Grouping | `ALLGREEN` |
| Max entries to build / to merge | 5 / 5 |
| Min entries to merge | 1 (waits 1 minute for more) |
| Check response timeout | 60 minutes |
| Bypass | the repository admin role, always |

The required status check, `ci`, comes from classic branch protection on `development`: no
required reviews, not strict (the queue provides the up-to-date guarantee), admins not enforced.

What this means in `ci.yml`:

- `merge_group: types: [checks_requested]` is a trigger. Without it the queue would wait for a
  `ci` that never starts.
- On `merge_group` the path filter is skipped and `build`, `ui` and `dist` always run, so every
  queued merge exercises everything. `acceptance-convention` is `pull_request`-only and reports
  as skipped.
- Only the `ci` workflow runs on the `gh-readonly-queue/...` ref. `docker.yml`, `acceptance.yml`
  and the preview workflows do not, so nothing in the queue builds an image.

Using it:

```bash
gh pr merge <N> --merge
# prints: The merge strategy for development is set by the merge queue
# the PR is enqueued, not merged; it lands when ci passes on the queued merge result
```

Check where a PR is:

```bash
gh api graphql -f query='
  query($n: Int!) { repository(owner: "spiculedata", name: "saiku") {
    pullRequest(number: $n) { state isInMergeQueue mergeQueueEntry { position state } }
  } }' -F n=<N>
```

`isInMergeQueue: false` with `state: OPEN` means the PR was never enqueued (or was removed because
the queued `ci` failed); `state: MERGED` means it landed. After it lands, the push to
`development` starts the post-merge `ci` / `docker` / `promote-green` chain above. If the queue
is stuck, an admin can bypass it.

## GitHub configuration inventory

Everything the workflows read from repository settings, derived from the `secrets.*` and
`vars.*` references in `.github/workflows/` and the `preview-host-access` composite action.
Names only; never put a value in the repo or in a PR.

**Secrets** (Settings, Secrets and variables, Actions):

| Name | Used by | Purpose | Set by | When unset |
| --- | --- | --- | --- | --- |
| `GITHUB_TOKEN` | most workflows | automatic per-run token | GitHub | n/a |
| `GH_PACKAGES_TOKEN` | `ci`, `docker`, `release`, `release-prep`, `nightly-compliance`, `quality-report` | classic PAT with `read:packages`; reads the Spicule artifacts on GitHub Packages (the auto token cannot read cross-org) | owner | Maven cannot resolve dependencies; build jobs fail. Required. See AGENTS.md |
| `CLAUDE_CODE_OAUTH_TOKEN` | `claude`, `claude-code-review`, `auto-review` | authenticates Claude Code | owner | those three workflows cannot run; nothing gating depends on them |
| `NPM_TOKEN` | `release` (npm embed packages), `design-system` | npm Automation token for the `@concepttocloud` scope | owner | `npm publish` cannot authenticate; the design-system publish step and the release npm jobs fail |
| `DOCS_SITE_PAT` | `release` (`docs-changelog`) | PAT with `repo` on `spiculedata/saiku-cloud` to open the changelog PR | owner | the job logs a warning and skips the docs PR; the release does not fail |
| `PREVIEW_SSH_PRIVATE_KEY` | `preview-env`, `preview-command`, `preview-reaper` | SSH key authorised on the preview box only | owner | with `PREVIEW_SSH_TARGET` set, the host-access step fails; with it empty the workflows skip first |
| `TAILSCALE_OAUTH_CLIENT_ID`, `TAILSCALE_OAUTH_SECRET` | same three | OAuth client owning `tag:ci`, to put the runner on the tailnet | owner | as above |
| `SAIKU_ACCEPTANCE_USER`, `SAIKU_ACCEPTANCE_PASSWORD` | `acceptance` | override of the acceptance login | owner | optional. They default to the public demo login (`admin` / `admin`), so login-dependent specs run without them, fork PRs included. Set them only to aim acceptance at a private instance: as secrets (they override the default and are withheld from forks), never as variables (unmasked) |
| `NVD_API_KEY` | `nightly-compliance` (OWASP profile, same as `mvn -P security verify`) | NVD feed API key | owner | optional; the job warns and the NVD download may be rate-limited or time out |

**Variables**:

| Name | Used by | Purpose | When unset |
| --- | --- | --- | --- |
| `ACCEPTANCE_ENFORCE_FROM_ISSUE` | `ci` (`acceptance-convention`) | cutoff issue number: only issues at or above it owe `acceptance/N/spec.json`. Set to `2181` | nothing is enforced; closing PRs are reported `grandfathered` |
| `ACCEPTANCE_BASE_URL` | `acceptance` | where specs run. Set to `https://demo.saiku.bi` | specs recorded `not-run`; run is green |
| `GHCR_RETENTION_APPLY` | `ghcr-tag-retention` | `true` makes the scheduled sweep delete; manual dispatch uses its `apply` input | scheduled runs are dry runs |
| `PREVIEW_SSH_TARGET` | `preview-env`, `preview-command`, `preview-reaper` | `user@host` of the preview box | all three preview workflows skip cleanly |
| `PREVIEW_SSH_KNOWN_HOSTS` | same | pinned host key line(s) | the host-access step has no pinned key and fails when a target is set |
| `PREVIEW_BASE_DOMAIN` | same | base domain for `oss-pr-<n>.<domain>` | defaults to `preview.saiku.bi` in the lifecycle and env renderer |
| `PREVIEW_AUTHORS` | same | comma-separated bot logins that get a preview automatically | defaults to `spicule-hive[bot]` |
| `PREVIEW_MAX_ENVS`, `PREVIEW_IDLE_HOURS` | same | concurrent preview cap, idle teardown | defaults 3 and 24 |

Why the acceptance values are what they are: the cutoff makes the gate apply only to issues
numbered 2181 and up, so older work is not retroactively blocked. The base URL is the public demo
because GitHub-hosted runners are not on the tailnet, so preview environments are unreachable
from `acceptance.yml`, and the public demo is the only reachable instance.

**Labels** the workflows read or write:

| Label | Effect |
| --- | --- |
| `acceptance-waived` | human-applied; waives the acceptance spec on a docs-or-tests-only PR |
| `preview` | opts any same-repo PR into a preview environment |
| `apply-review` | maintainer-applied; `auto-review.yml` applies review comments, then removes the label |
| `risk/high`, `risk/medium`, `risk/low` | advisory, set by `tier-classifier.yml` |
| `area/*` | advisory, set by `labeler.yml` |
| `nightly-compliance` | on the single tracking issue `nightly-compliance.yml` opens on a red night |
| `dependencies` plus `maven`, `npm`, `github-actions`, `docker` | set by Dependabot (`.github/dependabot.yml`) |

**Rules**: `development` has classic branch protection requiring the `ci` check and the
`development merge queue` ruleset (see above). Both live in repository settings, not in the repo.

## Dependabot

`.github/dependabot.yml` covers Maven, GitHub Actions, the Dockerfile base image and
`saiku-ui` npm, weekly. npm updates are grouped (`build-toolchain`, `storybook`, `dev-tooling`,
`production-minor-patch`), each bounded to minor and patch, so a major arrives as its own PR.
Dependabot PRs build the image but never push it ([ci-images.md](ci-images.md)).

Known gotchas:

- **An npm override on a direct dependency must be written `"$name"`.** `saiku-ui/package.json`
  has `"overrides": { "dompurify": "$dompurify", ... }`. A hard-coded range there pins the
  package, and when Dependabot bumps the direct dependency to a version outside that range
  `npm install` fails with `EOVERRIDE`. `"$dompurify"` makes the override track whatever
  version the direct dependency declares (#2178). Use the same form for any future override of a
  package that is also a direct dependency.
- **Dependabot runs on its weekly schedule only.** There is no workflow or CLI trigger. The one
  manual lever is the **Check for updates** button on the repository's Dependabot page (Insights,
  Dependency graph, Dependabot, then the `package.json` entry).
- A red Dependabot PR is read like any other: start from the `ci feedback` comment.

## When something is red

| Symptom | Look at |
| --- | --- |
| `ci` red on a PR | the sticky `ci feedback` comment, then the failing job's log. `ci / flake-policy tests` is the CI tooling's own tests; `ci / acceptance convention` is the spec gate |
| Test failed once then passed | not red: a flake, recorded in the run summary and the `flake-records-*` artifact ([ci-flakes.md](ci-flakes.md)) |
| `build` fails on test count or coverage | floors: `.github/test-floors.json`, `.coverage-thresholds.json` ([quality.md](quality.md)); never delete tests to get green |
| `ci / acceptance convention` red | the PR body says "closes #N" at or above the cutoff with no `acceptance/N/spec.json`; ship it or, on a docs-or-tests-only change, add `acceptance-waived` ([acceptance-specs.md](acceptance-specs.md)) |
| PR sits in the merge queue | the `ci` run on the `gh-readonly-queue/...` ref (Actions, workflow `ci`); a failure there removes the PR from the queue |
| Demo not updating | `promote-green` run for the commit (did `ci` or `docker` fail?), then the VM timer ([demo-deployment.md](demo-deployment.md)); manual dispatch of `promote-green` is also the rollback |
| No preview appeared | `PREVIEW_SSH_TARGET` empty (skipped), PR not eligible, or at `PREVIEW_MAX_ENVS` and queued ([ci-preview-environments.md](decisions/ci-preview-environments.md)) |
| Acceptance specs show `not-run` | `ACCEPTANCE_BASE_URL` or the acceptance credentials are unset; this is green by design |
| Nightly red | the `nightly-compliance` tracking issue; the OWASP lane without `NVD_API_KEY` can fail on rate limits |
| Release job red | `release-prep` on the release branch first; see [releasing.md](releasing.md) |
