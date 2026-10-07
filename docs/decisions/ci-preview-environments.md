# Per-PR preview environments

> Status: Proposed. Scope: the preview **lifecycle** and its infrastructure. The
> end-to-end tests that run against a preview, and the validation report, are a
> later phase and are not part of this change.

## Problem

CI for this repository is hermetic: unit tests, ITs against an embedded Jetty and a
docker image build. Nothing boots the **published image** as a user would, so a PR
that breaks first boot, the admin-password policy, the sample data or the SPA bundle
is green until somebody pulls the image by hand. Hive workers produce patches
continuously, and a human cannot start every one of them. A per-PR environment
gives a validator (a person or an agent) a real URL and a real credential for the PR
under review, and gives CI one more mechanical question to ask: does this commit boot,
log in and serve the sample cube?

This is a port of the system that already runs for `spiculedata/saiku-cloud` (see
`saiku-cloud/docs/decisions/ci-preview-validation.md`), adapted to this repository's
single-container product.

## Decision

One container per PR on the **shared preview box** `saiku-preview-1` (the box that
already hosts the saiku-cloud previews, reachable on the tailnet only), driven by
trusted base-branch workflows over ssh.

| | saiku-cloud | this repository |
|---|---|---|
| Unit of a preview | a compose stack (control plane, engine, gateway, dashboard, databases) | **one container**: `ghcr.io/spiculedata/saiku:<7-hex head sha>` |
| Compose project | `saiku-pr-<n>[-strict\|-minimal\|-base]` | `saiku-oss-pr-<n>` (nothing else) |
| Host state | `/var/lib/saiku-preview/{src,env}`, `registry.json`, `lock` | `/var/lib/saiku-preview-oss/{src,env}`, its own `registry.json` and `lock` |
| Hostname | `pr-<n>[-api\|-engine].preview.saiku.bi` | `oss-pr-<n>.preview.saiku.bi` (one label under the base, so the existing wildcard certificate covers it) |
| Concurrency | 5 (design), 4 on the shared box | **3** (`PREVIEW_MAX_ENVS`); the box has 16 GB and also runs up to 4 cloud previews |
| Idle teardown | 24 h | 24 h (`PREVIEW_IDLE_HOURS`) |
| Images | per component, `develop` fallback for unchanged ones | one image, **no fallback**: a PR without its image is not previewed (docs-only PRs get a quiet "NO PREVIEW", a PR whose build is late gets a bounded wait) |
| Seeding | database migrations + tenants + key hashes | none: the container seeds itself; a random admin password is rendered per environment |
| Eligibility | Hive bot logins, `preview` label, `/preview` | the same, with `spicule-hive[bot]` as the default Hive login (`PREVIEW_AUTHORS`) |

Everything else (lock, registry, queue, disk guards, hourly reaper, collaborator
`/preview`, review-activity polling, sticky comment, credential hand-off) is the cloud
implementation, kept structurally identical so a fix there can be ported mechanically.

### How a preview works

1. A same-repo PR into `development` is opened or pushed to (`preview-env.yml`), or a
   collaborator comments `/preview` (`preview-command.yml`).
2. The workflow, running from the **base branch**, joins the tailnet, takes the host
   lock and syncs three trusted files to the box: `docker-compose.preview.yml`,
   `render-env.sh`, `selfcheck.sh`.
3. It waits up to 20 minutes for `ghcr.io/spiculedata/saiku:<7-hex head sha>` to exist
   (`docker manifest inspect` with the box's own GHCR login). If it never appears the
   run fails with *"image not ready, comment /preview after the docker build for this
   commit has finished"*; no other image is ever substituted. When the request came from
   a `/preview` comment (`--ensure-build github`) and no `docker` build for the commit or
   the PR is queued or running, the run first dispatches `docker.yml` with `pr=<n>`
   (see the `target` job: it re-validates the PR from the API and publishes only
   `pr-<n>` + the head SHA), and the PR comment says **BUILDING IMAGE** while it waits.
   One exception, to avoid
   holding the host lock for 20 minutes for nothing: `docker.yml` only builds a PR image
   when the PR touches its `pull_request.paths` ("docs-only PRs do not build one"), so when
   the image is missing the lifecycle asks GitHub for the PR's changed files and, if none
   is a built path (`IMAGE_BUILD_PATHS`, pinned to `docker.yml` by a test), posts
   **NO PREVIEW** (not a failure) and does not wait.
4. `render-env.sh` writes a 0600 env file (project, image, hostname, random admin
   password), `docker compose up -d --wait` starts the container and waits for its
   healthcheck (`/rest/saiku/info`).
5. `selfcheck.sh` runs as a one-shot container on the stack's network: it logs in
   through `POST /rest/saiku/session`, lists the cubes and reports one
   `active`/`inactive` line per feature **with evidence**. A failure removes the stack.
6. The shared Traefik (host-networked, already on the box) discovers the container by
   its docker labels and routes `https://oss-pr-<n>.preview.saiku.bi` to port 8080 on
   the `websecure` entrypoint with the wildcard certificate. Nothing is published on a
   host port.
7. The hourly reaper tears down environments whose PR closed or that were idle for 24 h,
   removes orphan `saiku-oss-pr-*` projects and volumes, prunes images built from this
   repository, and promotes queued PRs into free slots.

### What the self-check proves

`server`, `ui_bundle`, `admin_login`, `admin_session`, `foodmart_cube`, plus two
negative probes that catch a silently-wrong configuration: `default_admin_rejected`
(`admin/admin` must be 401, so the random password is in force) and
`demo_accounts_absent` (the publicly documented `bob/dylan` must be 401).

Sample data is switched on with `SAIKU_SEED=true`, **not** `SAIKU_DEMO=true`: demo mode
loads three accounts with published passwords. `SAIKU_SEED` is read by launchers that
gate fixtures on it (saiku#1953); older launchers always stage the fixtures, so the
FoodMart row is the real check either way.

### Credentials for validators

`preview-ctl.mjs creds --pr N --host ssh (--out FILE | --github-output)` reads exactly
three keys off the host (`ORIGIN`, `PREVIEW_ADMIN_USER`, `SAIKU_ADMIN_PASSWORD`),
validates each value, emits `::add-mask::` for the password **before** writing, and
writes `PREVIEW_BASE_URL`, `PREVIEW_ADMIN_USER`, `PREVIEW_ADMIN_PASSWORD` to a 0600 file
and/or step outputs. Nothing is printed, summarised or commented. The consumer must be a
later step in the **same job** (job outputs drop secrets).

## Security model

The repository is **public**, which is why the rules below are enforced by tests
(`.github/scripts/preview-workflow.test.mjs`) and not by convention.

* `pull_request_target` and `issue_comment` run the workflow from the base branch. The
  checkout is the base branch only: no PR-authored file is executed, sourced or copied to
  the box. The PR contributes one thing, its head sha, and that is validated as 40 hex
  before it becomes a 7-hex image tag.
* PR text (title, branch, body, comment body beyond the exact `/preview` match) is never
  interpolated into a script and never enters a decision, a command or a comment.
* Fork PRs never get an environment: the job `if`, `eligibility()` and the `/preview`
  gate each refuse them, and an unknown origin fails closed.
* `/preview` needs write access or above (collaborator-permission API, fail closed) on a
  same-repo, open PR.
* Review-based idle refresh is **polled by the reaper** (default branch). A
  `pull_request_review` workflow would run from the PR's merge ref and could leak the
  secrets.
* Every host command is built by `preview-guard.mjs` as a validated argv array, quoted
  per word, behind the `/etc/saiku-preview-host` marker. The guard accepts the project
  name `saiku-oss-pr-<n>` and nothing else, so the cloud's `saiku-pr-<n>*` stacks on the
  same box are refused by construction (and listed as "ignored, never touched" by the
  reaper). Volumes are removable only with their project prefix. Image pruning is the one
  non-project-scoped command; it is restricted by the OCI source label to images built
  from this repository, so it can never delete an image a cloud preview holds.
* The ssh key is dedicated to previews (never a deploy key); host keys are pinned
  (`StrictHostKeyChecking=yes`); the runner reaches the box only over the tailnet as
  `tag:ci`.
* Secrets: the admin password is random per environment and stable across refreshes of
  the same env file (a validator keeps working); it is masked (`::add-mask::`) and only
  ever written to 0600 files. The previews run with `SAIKU_TELEMETRY=off`, `no-new-privileges`
  and all capabilities dropped.
* Actions are pinned to full 40-hex commits with a version comment (a test pins it).

## Provenance

Files copied or adapted from `spiculedata/saiku-cloud` (`develop` at
`b4ef213dd3dddd47f1c3ebe0772bd772741c6a28`). The last commit that touched each source file is
recorded so a later cloud fix can be diffed and ported.

| This repository | Source in saiku-cloud | Last source commit | Change |
|---|---|---|---|
| `.github/scripts/preview-guard.mjs` | same path | `8700834` | project grammar `saiku-oss-pr-<n>`, own state dir, label-scoped image prune, base-domain validation; variants and seed removed |
| `.github/scripts/preview-host.mjs` | same path | `8700834` | one image, no seed step, `up` raises a typed step error |
| `.github/scripts/preview-lifecycle.mjs` | same path | `12ff565` | defaults (3 envs, 20 min image wait), single image in the registry, new comment |
| `.github/scripts/preview-ctl.mjs` | same path | `12ff565` | `waitForImage` replaces `resolveImages` (changed files only decide "no image will ever exist"); no seed |
| `.github/scripts/preview-images.mjs` | same path | `a376975` | reduced to one image and the 7-hex grammar; `IMAGE_BUILD_PATHS` replaces the per-component path rules |
| `.github/scripts/preview-command.mjs` | same path | `152046d` | header only |
| `.github/scripts/preview-activity.mjs` | same path | `152046d` | header only |
| `.github/scripts/preview-creds.mjs` | same path | `8700834` | three credential keys |
| `.github/scripts/preview-workflow.test.mjs` | same path | `039ec80` | extended (public-repo checks, pins, label gate) |
| `.github/actions/preview-host-access/action.yml` | same path | `2060e31` | SHA-pinned; `jq -er` |
| `.github/workflows/preview-env.yml` | same path | `2060e31` | `development` only, label gate, SHA pins |
| `.github/workflows/preview-reaper.yml` | same path | `152046d` | SHA pins, empty-array-safe flags |
| `.github/workflows/preview-command.yml` | same path | `152046d` | SHA pins |
| `.github/workflows/preview-infra.yml` | `preview-compose.yml` | `12ff565` | offline checks for this stack |
| `infra/preview/docker-compose.preview.yml` | same path | `da72b23` | standalone, one service |
| `infra/preview/render-env.sh` | same path | `8700834` | project/host/image/password only |
| `infra/preview/selfcheck.sh` | same path | `12ff565` | REST session login, FoodMart cube, negative probes |
| `infra/preview/tests/*` | same paths | `da72b23`, `8700834`, `178c976` | rewritten for the above |

The shared edge (Traefik) and the host provisioning are **not** in this repository: they
live in `saiku-cloud` (`infra/preview/traefik`, `ansible/roles/preview_host`). The one host
change this system needs is the OSS state directory, `/var/lib/saiku-preview-oss/{src,env}`,
added to that role in a companion PR.

## Not covered

* Nothing here has run against the real box. The lifecycle runs offline against an
  in-memory host, the compose file is rendered (not started), and the self-check was run
  against a real 4.8.0 launcher locally and against a fake Saiku in tests.
* The e2e-against-preview job and the validation report are a later phase.
* The image contract is `docker.yml` (saiku#2170): same-repo PRs push `pr-<n>` and the 7-hex
  PR **head** sha, and a test pins that length and the head-sha source. Per `docs/ci-images.md`
  the bare-hex tag is collected after 30 days, which is far beyond a preview's life.

## Consequences

* An extra tenant on a shared box: three OSS previews at a 1.5 GB cap each can oversubscribe a
  16 GB box that also runs four cloud previews at 3 GB. The caps are limits, not
  reservations, and the disk and capacity guards queue rather than fail.
* One ssh user (`saiku`) serves both lifecycles; their separation is enforced by the
  guards on the CI side, not by the box. See the residual risks in the PR.
