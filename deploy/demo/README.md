# Demo deploy (demo.saiku.bi): pull-based, CI-gated

Everything that runs ON the demo VM lives here. The GitHub side is
`.github/workflows/promote-green.yml`. Design, security model and rollback are
summarised in [docs/demo-deployment.md](../../docs/demo-deployment.md); this file is the
operator runbook.

```
push to development
   |-- ci ............................. build + tests (gate)
   |-- docker ......................... builds ghcr.io/spiculedata/saiku:sha-<short> (+ :development)
   '-- promote-green (after ci passes)  retags sha-<short> -> :development-green   (registry-side, no rebuild)
                                                        |
VM, every ~5 min (systemd timer)  <---- docker pull -----'
   saiku-demo-deploy.sh: new digest? -> smoke candidate on a throwaway home -> swap -> smoke -> (rollback)
```

The VM only ever pulls a public image. It holds no GitHub credential, and GitHub holds no
VM credential (no deploy key, no tailnet node, no `scw` token).

## Files

| File | Installed at |
| --- | --- |
| `saiku-demo-deploy.sh` | `/usr/local/sbin/saiku-demo-deploy.sh` (0755, root) |
| `saiku-demo-deploy.service` | `/etc/systemd/system/` |
| `saiku-demo-deploy.timer` | `/etc/systemd/system/` |
| `saiku-demo-deploy.env.example` | `/etc/saiku-demo-deploy.env` (0600, optional) |
| `saiku-demo-reset.sh` | `/usr/local/sbin/saiku-demo-reset.sh` (0755, root); replaces the old nightly `saiku-reset` (see *Nightly reset*) |
| `tests/` | not installed; run `bash deploy/demo/tests/run.sh` (CI does) |

## What the script does

1. Takes a `flock`, so two runs never overlap (a second exits 0 immediately).
2. `docker pull ghcr.io/spiculedata/saiku:development-green`. If that image is the one
   the `saiku-demo` container is running, exits 0 (the common case, a few hundred ms).
3. If this exact image failed within the last hour, exits 1 without retrying (no 5-minute
   restart loop and no repeat alerts).
4. **Pre-flight**: starts the candidate as `saiku-demo-candidate` on a loopback port the
   Docker daemon picks, with `--tmpfs /app/saiku-home` (an empty, throwaway home that the
   launcher seeds exactly like a first boot) and a memory cap. It never mounts
   `/opt/saiku/home`: two JVMs must not share one saiku-home. It waits for
   `/rest/saiku/info`, then runs the smoke contract. The live container is not touched,
   so a bad image costs no downtime.
5. **Swap**: tags the running image `saiku-demo-previous`, optionally tars the stopped
   home (`SAIKU_HOME_BACKUP_DIR`), renames the live container to `saiku-demo-old`
   (stopped, kept for rollback), and starts the new one with the runbook's flags
   (`-v /opt/saiku/home:/app/saiku-home -e SAIKU_DEMO=true -e SAIKU_MCP_URL=...`,
   `127.0.0.1:8080`, `--restart unless-stopped`).
6. Waits for `/rest/saiku/info` on 127.0.0.1:8080 and re-runs the smoke contract.
7. **Rollback** on any failure after the stop: removes the new container, renames
   `saiku-demo-old` back and starts it (recreating from `saiku-demo-previous` if it is
   gone), waits for health, records the failure, exits 1. If the rollback itself fails it
   exits 2.
8. Prunes: keeps the 3 newest images of the repository (plus running, candidate and
   `saiku-demo-previous`); never uses `-f`.

**Smoke contract** (`POST /rest/saiku/api/mcp` `initialize`): an anonymous request is refused
(403, from the CSRF filter, which runs before authentication; 401 is accepted too); a wrong
password returns 401; `admin:admin` (demo mode) returns 200 and an `Mcp-Session-Id` header. The
credentials are the public demo ones and are given to curl on stdin, not argv.

Exit codes: `0` deployed / up to date / another run in progress; `1` pull or deploy failed
(previous version still serving, `systemctl` shows the unit failed); `2` deploy and rollback
both failed, the demo may be down.

Notifications (`SAIKU_DEPLOY_WEBHOOK_URL`) fire only on a state change: a failure (or a
different failing image) and the first success after a failure. Routine deploys are silent.

## One-time install

Run from the owner's laptop. `ID` is the Scaleway server id of `cognee-server`;
`REF` is the full commit SHA on `development` that contains this directory (pin to a SHA,
not a branch: the VM then runs exactly the code you reviewed).

```bash
ID=<scaleway-server-id>
REF=<full-40-char-commit-sha>
RAW=https://raw.githubusercontent.com/spiculedata/saiku/$REF/deploy/demo
```

**0. Compare the live container with what the script will create** (the script recreates
`saiku-demo`; anything it does not know about would be lost). Print config, with env var
NAMES only (no secret values):

```bash
scw instance server ssh $ID command='docker inspect saiku-demo --format "restart={{.HostConfig.RestartPolicy.Name}} mem={{.HostConfig.Memory}} net={{.HostConfig.NetworkMode}} user={{.Config.User}}{{println}}{{range .Mounts}}mount {{.Source}}:{{.Destination}}{{println}}{{end}}"; echo "env names:"; docker inspect saiku-demo --format "{{range .Config.Env}}{{println .}}{{end}}" | sed "s/=.*//"; free -m | head -2; df -h /opt/saiku | tail -1'
```

Expected env names beyond the image defaults: `SAIKU_DEMO`, `SAIKU_MCP_URL`. If there are more
(AI provider keys etc.), put them in a root-only env file and set
`SAIKU_RUNTIME_ENV_FILE` (step 3), otherwise the first deploy drops them. Need a
different restart policy / memory limit / network? Edit `run_real` in the script before
installing. The candidate needs about 1.5 GB of free RAM next to the live JVM.

**1. Install the script and units** (verify the hashes against your laptop's checkout of
`$REF` first: `git show $REF:deploy/demo/saiku-demo-deploy.sh | shasum -a 256`):

```bash
scw instance server ssh $ID command="set -e; cd /tmp; for f in saiku-demo-deploy.sh saiku-demo-deploy.service saiku-demo-deploy.timer saiku-demo-deploy.env.example; do curl -fsSL -o \$f $RAW/\$f; done; sha256sum saiku-demo-deploy.* | tee /tmp/saiku-demo-deploy.sha256"
# compare with: git show $REF:deploy/demo/<file> | shasum -a 256   (for each of the four files)
scw instance server ssh $ID command='set -e; cd /tmp; install -m 0755 -o root -g root saiku-demo-deploy.sh /usr/local/sbin/saiku-demo-deploy.sh; install -m 0644 saiku-demo-deploy.service saiku-demo-deploy.timer /etc/systemd/system/; systemctl daemon-reload'
```

**2. Dry run** (pulls and reports; changes nothing). Needs `:development-green` to exist, which
happens after the first `promote-green` run (`gh workflow run promote-green.yml --repo
spiculedata/saiku --ref development -f sha=<a recent green development sha>` makes one
immediately):

```bash
scw instance server ssh $ID command='/usr/local/sbin/saiku-demo-deploy.sh --check'
```

**3. Optional config** (`webhook`, extra runtime env, home backups):

```bash
scw instance server ssh $ID command='install -m 0600 -o root -g root /tmp/saiku-demo-deploy.env.example /etc/saiku-demo-deploy.env && mkdir -p /var/backups/saiku-demo && chmod 0700 /var/backups/saiku-demo'
# then edit /etc/saiku-demo-deploy.env: uncomment SAIKU_DEPLOY_WEBHOOK_URL / SAIKU_HOME_BACKUP_DIR / SAIKU_RUNTIME_ENV_FILE
```

**4. First real run in the foreground**, watch it, then enable the timer:

```bash
scw instance server ssh $ID command='systemctl start saiku-demo-deploy.service; journalctl -u saiku-demo-deploy.service -n 80 --no-pager; systemctl is-failed saiku-demo-deploy.service || true'
scw instance server ssh $ID command='systemctl enable --now saiku-demo-deploy.timer; systemctl list-timers saiku-demo-deploy.timer --no-pager'
```

**5. Verify from outside**:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  --data '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"check","version":"1"}}}' \
  https://demo.saiku.bi/rest/saiku/api/mcp      # expect 403 (anonymous POST: CSRF filter); 401 with a wrong password via -u admin:wrong
```

## Nightly reset

demo.saiku.bi wipes its home every night (00:00 America/New_York, `/etc/cron.d/saiku-reset`). The
original `/usr/local/bin/saiku-reset` did that by pulling and running `:development` itself, which
bypasses the green-only gate and recreates the container behind this timer's back. Replace it
with `saiku-demo-reset.sh`: it takes the same lock as the deploy script, stops the existing
`saiku-demo` container, empties and re-owns `/opt/saiku/home` (uid/gid 10001), and starts the
**same container** again, so the demo keeps running the green image with the deploy script's
exact flags. It never pulls. If there is no `saiku-demo` container, or it will not start, it
hands over to `saiku-demo-deploy.sh`, which deploys the current green image.

Install after step 1 (the cron entry itself stays as it is; only the script it calls changes).
Keep the old script until the new one has run once:

```bash
scw instance server ssh $ID command='set -e; cd /tmp; curl -fsSL -o saiku-demo-reset.sh '"$RAW"'/saiku-demo-reset.sh; sha256sum saiku-demo-reset.sh'
# compare with: git show $REF:deploy/demo/saiku-demo-reset.sh | shasum -a 256
scw instance server ssh $ID command='set -e; install -m 0755 -o root -g root /tmp/saiku-demo-reset.sh /usr/local/sbin/saiku-demo-reset.sh; cp -a /usr/local/bin/saiku-reset /usr/local/bin/saiku-reset.pre-green; sed -i "s#/usr/local/bin/saiku-reset #/usr/local/sbin/saiku-demo-reset.sh #" /etc/cron.d/saiku-reset; cat /etc/cron.d/saiku-reset'
# run it once by hand and check the demo came back on an empty home:
scw instance server ssh $ID command='/usr/local/sbin/saiku-demo-reset.sh; docker ps --filter name=saiku-demo --format "{{.Names}} {{.Status}}"'
```

The old script also passed `-e SAIKU_SECURITY_ACKNOWLEDGED=true`. Nothing in the launcher reads
that name (only `-Dsaiku.security.acknowledged` suppresses the startup banner), so the
deploy script does not set it; the only effect is the banner in the container log.

## Day to day

```bash
# status / history
scw instance server ssh $ID command='systemctl status saiku-demo-deploy.service saiku-demo-deploy.timer --no-pager; journalctl -u saiku-demo-deploy.service --since "1 day ago" --no-pager | tail -100'
# what is running vs what is green
scw instance server ssh $ID command='docker inspect saiku-demo --format "{{.Image}}"; docker image inspect ghcr.io/spiculedata/saiku:development-green --format "{{.Id}} {{index .Config.Labels \"org.opencontainers.image.revision\"}}"'
# pause auto-deploys (e.g. during an incident or a demo)
scw instance server ssh $ID command='systemctl stop saiku-demo-deploy.timer'      # resume: systemctl start saiku-demo-deploy.timer
# deploy now
scw instance server ssh $ID command='systemctl start saiku-demo-deploy.service'
```

The unit shows **failed** while a rejected image is still `development-green`, until a fixed
or older image is promoted. That is intentional.

Updating the script itself is a manual re-run of install step 1 with a new `REF`; the VM never
fetches or runs code on its own.

## Rollback

The usual way, nothing on the VM: dispatch `promote-green` with an older, previously built
commit. The timer then deploys it within ~5 minutes (or `systemctl start` it).

```bash
gh workflow run promote-green.yml --repo spiculedata/saiku --ref development -f sha=<older sha, 7-40 hex>
gh run watch --repo spiculedata/saiku
```

The sha must be on `development`/`main` and its `sha-<short>` image must still exist in GHCR.
A manual promote does not re-check that commit's CI.

If GitHub Actions is unavailable, pin a tag on the VM (stop the timer first or it will move back):

```bash
scw instance server ssh $ID command='systemctl stop saiku-demo-deploy.timer; set -a; [ -f /etc/saiku-demo-deploy.env ] && . /etc/saiku-demo-deploy.env; set +a; SAIKU_GREEN_TAG=sha-<short> /usr/local/sbin/saiku-demo-deploy.sh'
# when done: systemctl start saiku-demo-deploy.timer
```

(After an automatic rollback the old image was the one that failed smoke, not the home; if a
candidate got far enough to migrate saiku-home before failing, restore the newest tarball in
`SAIKU_HOME_BACKUP_DIR` after stopping the container.)

The existing manual runbook (`docker pull && stop && rm && run`) is still valid for a one-off.
The script recreates the container with the same flags, so manual and automated deploys can be
mixed; if you start a container by hand, make sure it is named `saiku-demo`.

## Uninstall

```bash
scw instance server ssh $ID command='systemctl disable --now saiku-demo-deploy.timer; rm -f /etc/systemd/system/saiku-demo-deploy.{service,timer} /usr/local/sbin/saiku-demo-deploy.sh; systemctl daemon-reload'
```

## Tests

`bash deploy/demo/tests/run.sh` runs the whole flow against a fake `docker` and `curl`
(`tests/shims/`): idempotence, candidate isolation, rollback, backoff, notification state,
secret hygiene, locking. No docker, network or root needed. CI runs it with shellcheck.
What it cannot cover: real Docker semantics (tmpfs ownership, port publishing, restart
policy), real GHCR, systemd. See the PR description for what was and was not exercised.
