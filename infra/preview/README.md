# Per-PR preview environments (OSS)

An ephemeral environment for one PR: **one container** of the published image
`ghcr.io/spiculedata/saiku:<7-hex head sha>`, on the shared preview box, at
`https://oss-pr-<n>.preview.saiku.bi` (tailnet only). Design, security model and the
provenance of every file: [`docs/decisions/ci-preview-environments.md`](../../docs/decisions/ci-preview-environments.md).

> **Status.** The lifecycle, compose file, env renderer and self-check are verified
> **offline** (see [Testing](#testing)). Nothing here has been run against the real
> preview box yet; see [What is not covered](#what-is-not-covered).

## Files

| File | What |
|---|---|
| `docker-compose.preview.yml` | The stack: the `saiku` service (no published ports, Traefik labels, 1.5 GB cap, project-scoped network and volume) and the profile-gated `preview-selfcheck` one-shot |
| `render-env.sh` | Renders the per-PR env file: project `saiku-oss-pr-<n>`, image, hostname `oss-pr-<n>.<base>`, random admin password (stable across re-renders of the same file) |
| `selfcheck.sh` | Logs in through `POST /rest/saiku/session`, lists the cubes, reports each feature `active`/`inactive` with evidence, never prints the password |
| `tests/` | Offline tests (no daemon, no images) for all of the above |

The lifecycle that drives these (`preview-ctl.mjs`, the guard, the host layer) is in
`.github/scripts/`; the workflows are `preview-env.yml`, `preview-command.yml` and
`preview-reaper.yml`.

## Who gets a preview

A **same-repo** PR into `development` (never a fork), when any of these holds:

* it is authored by a login in the repository variable `PREVIEW_AUTHORS` (default
  `spicule-hive[bot]`, the Hive bot);
* it carries the `preview` label;
* a collaborator with write access or above comments `/preview` (or `/preview restart` to
  rebuild at the same commit).

A preview is rebuilt on every push, torn down when the PR closes or merges, or after
24 h (`PREVIEW_IDLE_HOURS`) without a push, a collaborator `/preview` or a collaborator
review. At most 3 run at once (`PREVIEW_MAX_ENVS`); further PRs are queued with a visible
position and promoted hourly. The sticky PR comment says which state a PR is in.

A PR that changes nothing `docker.yml` builds (docs, CI, scripts only) has no image and gets a
quiet **NO PREVIEW** comment instead of a wait; it is created automatically on a push that touches a built path.

If the image for the head commit is not in GHCR yet the run waits up to 20 minutes for it
(the sticky comment says **BUILDING IMAGE** meanwhile and is updated in place), then fails
with *"image not ready, comment /preview after the docker build for this commit has
finished"*.

**A PR with no image at all is built for you.** Per-PR images are only built on a push to the
PR, so a PR last pushed before that existed (or whose build was cancelled) has none. A
`/preview` comment (not the automatic flows) then dispatches `docker.yml` with `pr=<n>`: the
workflow's `target` job re-checks from the API that the PR is open, from this repository,
targeting `development` and not Dependabot's, builds exactly the PR **head** commit, and
publishes only `pr-<n>` and the 7-hex SHA (never `:development`, `:main` or `sha-<n>`). If a
build for that commit or PR is already queued or running, none is started. This is why only
`preview-command.yml` holds `actions: write`.

## Bring one up by hand (on a preview host)

`preview-ctl` does all of this over ssh; by hand, on a box that has the marker file:

```bash
cd <synced tree>                    # the project dir: the self-check mounts ./infra/preview
ENV_FILE=$(infra/preview/render-env.sh 1234 --sha "$(git rev-parse HEAD)")
docker compose -p saiku-oss-pr-1234 --project-directory . --env-file "$ENV_FILE" \
  -f infra/preview/docker-compose.preview.yml up -d --wait
docker compose -p saiku-oss-pr-1234 --project-directory . --env-file "$ENV_FILE" \
  -f infra/preview/docker-compose.preview.yml --profile selfcheck run --rm -T preview-selfcheck --require-all
```

On a developer machine the compose file also works with any 7-hex tag you have pulled
(`render-env.sh 99 --image-tag abcdef0 --base-domain preview.example.test`); the Traefik labels are
inert without the shared edge, so reach the container with `docker compose exec` or a published port
in a local override.

## What is different from a normal install

| | Preview | Why |
|---|---|---|
| Admin password | random per environment, `SAIKU_ADMIN_PASSWORD` | the launcher refuses to boot on `admin/admin` otherwise (saiku#1153) |
| Demo mode | **off** (`SAIKU_DEMO` unset) | it loads `bob/dylan`, `krishna`, `smith` with published passwords |
| Sample data | on, via `SAIKU_SEED=true` | FoodMart is what the self-check and a validator query |
| Telemetry | off (`SAIKU_TELEMETRY=off`) | a throwaway must not count as an install |
| Host ports | none | the shared Traefik routes by docker label |
| Memory | `mem_limit: 1536m` | the JVM sizes its heap from the container limit (`MaxRAMPercentage=75` in the image) |
| Hardening | `no-new-privileges`, `cap_drop: [ALL]` | the container is already non-root (uid 10001) |

## The self-check

```
server                 active    GET /rest/saiku/info answered 200 (waited 0s)
ui_bundle              active    GET /ui/ answered 200
admin_login            active    POST /rest/saiku/session as admin answered 200
admin_session          active    GET /rest/saiku/session answered 200 with isadmin=true
default_admin_rejected active    admin/admin answered 401 (the random password is in force)
demo_accounts_absent   active    the documented demo user bob answered 401 (demo accounts are not loaded)
foodmart_cube          active    GET /rest/saiku/api/ai/cubes answered 200 and lists FoodMart Sales (6 FoodMart cubes)

PREVIEW_SELFCHECK stack=saiku-oss-pr-1234 active=7 inactive=0 skipped=0
{"stack":"saiku-oss-pr-1234","active":7,...}
```

Every row is **observed** from the running server, none is merely declared. With
`--require-all` (the default in the rendered env file) any `inactive` row exits 1 and the
lifecycle removes the stack and says why. The two negative probes catch the failures that
look like success: a launcher that still accepts `admin/admin`, or one that loaded the
demo accounts.

## Credentials for validators

A validation job is a trusted base-branch workflow with tailnet and ssh access. It fetches
the PR's credentials with

```bash
node .github/scripts/preview-ctl.mjs creds --pr 1234 --host ssh --out "$RUNNER_TEMP/preview.env"
# and/or --github-output, to expose them as step outputs of the same job
```

* only `ORIGIN`, `PREVIEW_ADMIN_USER` and `SAIKU_ADMIN_PASSWORD` are read off the host
  (a fixed `grep`), each value is validated against a strict character set, and the
  password is masked with `::add-mask::` **before** anything is written;
* the result is `PREVIEW_BASE_URL`, `PREVIEW_ADMIN_USER`, `PREVIEW_ADMIN_PASSWORD` in a
  0600 file and/or step outputs. Nothing is printed, put in a summary or posted in a
  comment, and the PR comment only links here;
* use them from a **later step of the same job**: GitHub drops job outputs that contain a
  registered secret, and artifacts are readable by anyone with repo read access.

The password is stable while the environment lives (a refresh of the same PR re-renders
over the same env file and keeps it) and changes on the next bring-up after a teardown.

## Host layout

State is separate from the saiku-cloud previews on the same box:

| Path | Owner | What |
|---|---|---|
| `/etc/saiku-preview-host` | root | marker file; every mutating command is gated on it |
| `/var/lib/saiku-preview-oss/` | `saiku` | state dir. The lifecycle lock is `mkdir lock` **inside** it, so the directory itself must be writable by the ssh user |
| `/var/lib/saiku-preview-oss/src` | `saiku` | the three synced trusted files (replaced on every run) |
| `/var/lib/saiku-preview-oss/env` | `saiku` | per-PR env files, 0600 |
| `/var/lib/saiku-preview-oss/registry.json` | `saiku` | environments and queue |

They are created by the `preview_host` Ansible role in `spiculedata/saiku-cloud`
(`chore/preview-host-oss-state-dir`). The shared Traefik and the wildcard certificate are
also provisioned there.

## Repository configuration (owner)

| Kind | Name | Value |
|---|---|---|
| secret | `PREVIEW_SSH_PRIVATE_KEY` | a **new** key dedicated to OSS previews, authorised for `saiku@saiku-preview-1` |
| secret | `TAILSCALE_OAUTH_CLIENT_ID`, `TAILSCALE_OAUTH_SECRET` | an OAuth client that owns `tag:ci` (the ACL already lets `tag:ci` reach the box) |
| variable | `PREVIEW_SSH_TARGET` | `saiku@100.78.167.101` (while empty, every preview workflow skips cleanly) |
| variable | `PREVIEW_SSH_KNOWN_HOSTS` | the pinned host key line(s): `ssh-keyscan -t ed25519 100.78.167.101` run from a trusted tailnet machine |
| variable | `PREVIEW_BASE_DOMAIN` | `preview.saiku.bi` (the default) |
| variable, optional | `PREVIEW_AUTHORS` | comma-separated Hive logins (default `spicule-hive[bot]`) |
| variable, optional | `PREVIEW_MAX_ENVS`, `PREVIEW_IDLE_HOURS` | defaults 3 and 24 |
| label | `preview` | opts any same-repo PR in |

## Testing

```bash
node --test .github/scripts/preview-*.test.mjs   # guard, lifecycle, host, creds, workflows
infra/preview/tests/test-render-env.sh           # the env renderer
infra/preview/tests/test-compose.sh              # renders the compose file, asserts, then breaks it on purpose
infra/preview/tests/test-selfcheck.sh            # against a fake Saiku: every silently-wrong case is `inactive`
```

CI runs the same in `.github/workflows/preview-infra.yml`. The compose and self-check
tests carry **negative controls**: they break a copy of the file (publish a port, turn
demo mode on, route a cloud-style hostname, ...) and require the assertions to fail, so a
green run proves the checks can fire.

## What is not covered

* No run against the real preview box: the ssh layer is tested against a fake runner, and
  the lifecycle against an in-memory host.
* That the shared Traefik actually routes the container (label correctness is asserted, the
  routing is not exercised), and that the wildcard certificate covers `oss-pr-<n>`.
* The e2e-against-preview job and the validation report (a later phase).
