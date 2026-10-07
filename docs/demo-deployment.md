# Demo deployment (demo.saiku.bi)

The public demo is a single container, `saiku-demo`, on one VM behind Caddy. It deploys
itself from the **CI-green** image, by pulling. Operator runbook and install commands:
[deploy/demo/README.md](../deploy/demo/README.md).

## Flow

1. A push to `development` starts `ci` and, in parallel and ungated, `docker.yml`, which
   pushes `ghcr.io/spiculedata/saiku:sha-<7 hex>` (and the rolling `:development`).
2. When `ci` completes successfully for that push, `.github/workflows/promote-green.yml`
   retags the already-built `sha-<short>` image as **`:development-green`** with
   `docker buildx imagetools create` (registry-side copy by digest; no rebuild, no checkout).
3. A systemd timer on the VM runs `deploy/demo/saiku-demo-deploy.sh` every ~5 minutes. It
   pulls `:development-green`; if the digest is what is already running it stops there.
   Otherwise it smoke-tests the candidate on a throwaway tmpfs home and loopback port,
   swaps the real container, re-runs the smoke contract and rolls back on any failure.

`:development` (the unvetted tip) is deliberately **not** what the demo runs.

## Gate (`promote-green.yml`)

`gate` (read-only token) re-reads the `ci` run from the API and requires: workflow `ci`,
conclusion `success`, event `push`, branch `development`, this repository, `head_sha`
equal to the event's. It skips if `development` has already moved past that commit, and
waits for the `docker` workflow run **for the same commit** to succeed. `promote`
(the only job with `packages: write`, no checkout) then requires the image's
`org.opencontainers.image.revision` label to equal that commit before copying by digest,
and verifies `development-green` resolves to the same digest afterwards.

`workflow_dispatch` (input `sha`, dispatchable only from `development`) promotes any
commit reachable from `development`/`main` whose `sha-<short>` image still exists. This
is the rollback lever.

## Nightly reset

The demo VM used to have a nightly cron (`/etc/cron.d/saiku-reset` running
`/usr/local/bin/saiku-reset`) that removed the `saiku-demo` container, wiped `/opt/saiku/home`,
then pulled and ran `ghcr.io/spiculedata/saiku:development`. That conflicts with the green-only
gate above: it puts the demo on an image CI has not passed, recreates the container without
taking the deploy timer's lock, and the timer then swaps back to `:development-green`, a second
restart.

`deploy/demo/saiku-demo-reset.sh` replaces it. It takes the deploy script's lock (so it waits for
an in-flight deploy), stops the existing `saiku-demo` container, empties and re-owns the home,
and starts the same container again, so the demo keeps the green image and the deploy script's
exact flags. It never pulls. If there is no `saiku-demo` container, or it will not start, it
hands over to `saiku-demo-deploy.sh`, which treats nothing-running as a deploy of the current
green image. It refuses a home path that is not absolute, has `.` or `..` components, or is
shallower than two levels, because it runs `rm -rf` on it.

Installing it (swapping the cron over, keeping the old script as `saiku-reset.pre-green`) is a
manual owner step: see [deploy/demo/README.md](../deploy/demo/README.md#nightly-reset).

## Security model

| Party | Can | Cannot |
| --- | --- | --- |
| `promote-green` | move the `development-green` tag to an image docker.yml built for a CI-green `development` commit | run PR/fork code, build anything, touch the VM, be triggered by `pull_request` or `merge_group` |
| anyone who can open a PR | nothing here: `workflow_run` only fires for `ci` runs on pushes to this repo's `development` | |
| someone who can push to `development` | get their commit deployed once CI passes (same as before, plus the CI gate) | skip CI |
| someone who can dispatch workflows (write access) | promote or roll back to a previously built development/main commit | promote an arbitrary branch or PR image |
| the VM | `docker pull` a public image, run containers it names | reach GitHub with any credential (it has none) |
| GitHub / CI | write one registry tag | reach the VM (no ssh key, no tailnet node, no `scw` token) |

All event text (branch names, titles) reaches shell only through `env:` and strict regexes;
the workflow has `permissions: {}` at the top and per-job grants. Pins are full commit SHAs.

Residual risks: whoever controls the GHCR package or the `development` branch controls what
the demo runs; the timer runs the pinned script as root with the docker socket; the demo
admin password `admin` is public by design (the smoke test uses it).

## Operations

- Status: `systemctl status saiku-demo-deploy.service`, `journalctl -u saiku-demo-deploy`.
- Failure: the unit shows failed and (if `SAIKU_DEPLOY_WEBHOOK_URL` is set) one webhook
  fires; the previous version keeps serving. Retries of the same bad image back off for an hour.
- Rollback: `gh workflow run promote-green.yml --ref development -f sha=<older sha>`.
- The earlier manual runbook (`docker pull && stop && rm && run`) is still valid.
- GHCR retention (`ghcr-tag-retention.yml`, docs/ci-images.md) never collects `sha-<short>` or
  `development-green` versions: it only expires bare-hex PR SHAs and `pr-<n>` tags, and a
  version with any other tag shape is kept. Rollback targets therefore stay available.
