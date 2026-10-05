#!/usr/bin/env bash
# saiku-demo-deploy.sh - pull-based, CI-gated deploy of the public Saiku demo.
#
# Runs ON the demo VM (as root, from saiku-demo-deploy.timer). It pulls the
# `development-green` tag - which promote-green.yml only moves after the `ci`
# workflow succeeded for that commit - and, if that image differs from what is
# running:
#
#   1. starts the candidate on a loopback port the DOCKER DAEMON assigns, with a
#      THROWAWAY tmpfs saiku-home (two JVMs must never share one saiku-home), and
#      runs the smoke contract against it. The live container is untouched, so a
#      bad candidate costs no downtime.
#   2. only then stops the live container (kept, renamed, for instant rollback),
#      starts the new one on the real saiku-home, waits for /rest/saiku/info and
#      re-runs the smoke contract.
#   3. on any failure after the stop, restores the previous container, records the
#      failed image so it is not retried every tick, and exits non-zero.
#
# Usage: saiku-demo-deploy.sh [--check]   (--check: pull, report, change nothing)
# Exit codes: 0 deployed / nothing to do / another run in progress,
#             1 deploy or pull failed (previous version still serving),
#             2 deploy AND rollback failed - the demo needs a human.
#
# No CI credential lives on this host and no host credential lives on GitHub: the
# VM only ever pulls. The script never prints environment values. Config comes
# from the environment (see saiku-demo-deploy.env.example); every decision is a
# small function so tests/run.sh can drive it with a fake `docker` on PATH.

set -Eeuo pipefail

SELF="saiku-demo-deploy"
log() { printf '%s: %s\n' "$SELF" "$*"; }
err() { printf '%s: ERROR: %s\n' "$SELF" "$*" >&2; }

readonly MCP_INIT_BODY='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"saiku-demo-deploy","version":"1"}}}'
readonly NAME_RE='^[A-Za-z0-9][A-Za-z0-9_.-]*$'
readonly IMAGE_ID_RE='^sha256:[0-9a-f]{64}$'
readonly REV_RE='^[0-9a-f]{7,40}$'
readonly PORT_RE='^[0-9]{1,5}$'

# ---------------------------------------------------------------------------
# Pure decision functions (no docker, no network, no clock except as arguments)
# ---------------------------------------------------------------------------

# decide_action <candidate_id> <running_id> <is_running true|false> \
#               <deployed_cand_id> <deployed_run_id>
# Prints "noop" or "deploy". The container's reported image id and `docker image
# inspect` Id can differ on some storage drivers (containerd image store reports
# the index digest in one and the config digest in the other), so a candidate that
# we previously deployed - and whose container still reports the id we recorded
# then - also counts as running. Without this the timer would redeploy every tick.
decide_action() {
  local cand="$1" run="$2" is_running="$3" dep_cand="${4:-}" dep_run="${5:-}"
  if [[ "$is_running" != "true" || -z "$run" ]]; then
    echo deploy
    return 0
  fi
  if [[ "$cand" == "$run" ]]; then
    echo noop
    return 0
  fi
  if [[ -n "$dep_cand" && "$cand" == "$dep_cand" && "$run" == "$dep_run" ]]; then
    echo noop
    return 0
  fi
  echo deploy
}

# should_skip_failed <candidate_id> <failed_id> <failed_at_epoch> <now_epoch> <backoff_s>
# Returns 0 (skip) when this exact candidate already failed less than backoff ago.
should_skip_failed() {
  local cand="$1" failed_id="${2:-}" failed_at="${3:-0}" now="$4" backoff="$5"
  [[ -n "$failed_id" && "$cand" == "$failed_id" ]] || return 1
  [[ "$failed_at" =~ ^[0-9]+$ && "$now" =~ ^[0-9]+$ ]] || return 1
  (( now - failed_at < backoff ))
}

# parse_published_port: stdin is `docker port` output (one or more
# "127.0.0.1:49153" / "[::]:49153" lines). Prints the first valid port.
parse_published_port() {
  local line port first=""
  # Reads ALL of stdin: returning early would SIGPIPE `docker port` and, under
  # pipefail, fail the whole pipeline.
  while IFS= read -r line; do
    port="${line##*:}"
    if [[ -z "$first" && "$port" =~ $PORT_RE ]] && (( port >= 1 && port <= 65535 )); then
      first="$port"
    fi
  done
  [[ -n "$first" ]] || return 1
  echo "$first"
}

# select_prune_ids <keep_n> <protected_id>...
# stdin: image ids, newest first (as `docker image ls` prints them). Prints the
# ids to delete: everything beyond the newest keep_n, minus protected ids and
# duplicates (an image with several tags is listed several times).
select_prune_ids() {
  local keep="$1"
  shift
  local -A protected=() seen=()
  local id n=0
  for id in "$@"; do protected["$id"]=1; done
  while IFS= read -r id; do
    [[ -n "$id" ]] || continue
    [[ -z "${seen[$id]:-}" ]] || continue
    seen["$id"]=1
    n=$((n + 1))
    (( n > keep )) || continue
    [[ -z "${protected[$id]:-}" ]] || continue
    echo "$id"
  done
}

# should_notify <prev_status> <new_status> <prev_failed_id> <new_failed_id>
# Webhook only on state change: a failure (new, or a different candidate than the
# one already reported) and a recovery after failure. Never on routine success.
should_notify() {
  local prev="${1:-ok}" new="$2" prev_id="${3:-}" new_id="${4:-}"
  case "$new" in
    failed) [[ "$prev" != "failed" || "$prev_id" != "$new_id" ]] ;;
    ok) [[ "$prev" == "failed" ]] ;;
    *) return 1 ;;
  esac
}

# ---------------------------------------------------------------------------
# Config, state
# ---------------------------------------------------------------------------

config() {
  IMAGE_REPO="${SAIKU_IMAGE_REPO:-ghcr.io/spiculedata/saiku}"
  GREEN_TAG="${SAIKU_GREEN_TAG:-development-green}"
  CONTAINER="${SAIKU_CONTAINER:-saiku-demo}"
  PREVIOUS_TAG="${SAIKU_PREVIOUS_LOCAL_TAG:-saiku-demo-previous}"
  HOME_DIR="${SAIKU_HOME_DIR:-/opt/saiku/home}"
  MCP_URL="${SAIKU_MCP_URL:-https://demo.saiku.bi/rest/saiku/api/mcp}"
  PUBLISH="${SAIKU_PUBLISH:-127.0.0.1:8080}"
  EXTRA_ENV_FILE="${SAIKU_RUNTIME_ENV_FILE:-}"
  SMOKE_USER="${SAIKU_SMOKE_USER:-admin}"
  SMOKE_PASS="${SAIKU_SMOKE_PASS:-admin}"
  CANDIDATE_MEMORY="${SAIKU_CANDIDATE_MEMORY:-1536m}"
  CANDIDATE_TMPFS_SIZE="${SAIKU_CANDIDATE_TMPFS_SIZE:-1g}"
  HEALTH_ATTEMPTS="${SAIKU_HEALTH_ATTEMPTS:-60}"
  HEALTH_INTERVAL="${SAIKU_HEALTH_INTERVAL:-5}"
  FAIL_BACKOFF="${SAIKU_FAIL_BACKOFF_SECONDS:-3600}"
  KEEP_IMAGES="${SAIKU_KEEP_IMAGES:-3}"
  STATE_DIR="${SAIKU_STATE_DIR:-/var/lib/saiku-demo-deploy}"
  BACKUP_DIR="${SAIKU_HOME_BACKUP_DIR:-}"
  BACKUP_KEEP="${SAIKU_HOME_BACKUP_KEEP:-2}"
  WEBHOOK_URL="${SAIKU_DEPLOY_WEBHOOK_URL:-}"
  PUBLIC_URL="${SAIKU_PUBLIC_URL:-}"
  NOW="${SAIKU_NOW_EPOCH:-$(date +%s)}"
  CAND_NAME="${CONTAINER}-candidate"
  OLD_NAME="${CONTAINER}-old"

  local v
  for v in "$CONTAINER" "$CAND_NAME" "$OLD_NAME" "$GREEN_TAG" "$PREVIOUS_TAG"; do
    [[ "$v" =~ $NAME_RE ]] || { err "invalid name in config: $v"; return 1; }
  done
  [[ "$IMAGE_REPO" =~ ^[a-z0-9][a-z0-9./_-]*$ ]] || { err "invalid SAIKU_IMAGE_REPO"; return 1; }
  [[ "$PUBLISH" =~ ^[0-9.]+:[0-9]+$ ]] || { err "SAIKU_PUBLISH must be <ip>:<port>"; return 1; }
  for v in "$HEALTH_ATTEMPTS" "$FAIL_BACKOFF" "$KEEP_IMAGES" "$BACKUP_KEEP" "$NOW"; do
    [[ "$v" =~ ^[0-9]+$ ]] || { err "numeric config value is not a number: $v"; return 1; }
  done
  [[ "$HEALTH_INTERVAL" =~ ^[0-9]+$ ]] || { err "SAIKU_HEALTH_INTERVAL must be an integer"; return 1; }
}

state_get() { # key
  local f="$STATE_DIR/$1"
  if [[ -f "$f" ]]; then head -n 1 "$f"; fi
}

state_put() { # key value  (atomic)
  local tmp
  tmp="$(mktemp "$STATE_DIR/.tmp.XXXXXX")"
  printf '%s\n' "$2" >"$tmp"
  mv -f "$tmp" "$STATE_DIR/$1"
}

acquire_lock() {
  command -v flock >/dev/null 2>&1 || { err "flock (util-linux) is required"; return 1; }
  exec 9>"$STATE_DIR/lock"
  if ! flock -n 9; then
    log "another run holds the lock; exiting"
    exit 0
  fi
}

# ---------------------------------------------------------------------------
# docker helpers
# ---------------------------------------------------------------------------

image_id() { docker image inspect --format '{{.Id}}' "$1" 2>/dev/null; }

image_rev() { # short revision label for log lines; empty if absent/odd
  local rev
  rev="$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$1" 2>/dev/null || true)"
  [[ "$rev" =~ $REV_RE ]] && echo "${rev:0:7}" || echo "unknown"
}

container_exists() { docker container inspect "$1" >/dev/null 2>&1; }

container_image() { docker container inspect --format '{{.Image}}' "$1" 2>/dev/null || true; }

container_running() { # prints true/false
  docker container inspect --format '{{.State.Running}}' "$1" 2>/dev/null || echo false
}

short() { local s="${1#sha256:}"; echo "${s:0:12}"; }

# ---------------------------------------------------------------------------
# Health + smoke contract
# ---------------------------------------------------------------------------

# wait_healthy <base_url> [container_to_watch]
wait_healthy() {
  local base="$1" watch="${2:-}" i
  for ((i = 1; i <= HEALTH_ATTEMPTS; i++)); do
    if [[ -n "$watch" && "$(container_running "$watch")" != "true" ]]; then
      err "container $watch is not running (exited during startup)"
      return 1
    fi
    if curl -fsS -o /dev/null --max-time 5 --head "$base/rest/saiku/info" 2>/dev/null; then
      log "healthy after attempt $i: $base/rest/saiku/info"
      return 0
    fi
    sleep "$HEALTH_INTERVAL"
  done
  err "not healthy after $HEALTH_ATTEMPTS attempts: $base/rest/saiku/info"
  return 1
}

# Smoke contract: anonymous MCP initialize -> 401; authenticated (demo-mode
# admin) initialize -> 200 with an Mcp-Session-Id response header. Credentials go
# to curl on stdin (-K -), not argv, so they never appear in `ps`.
smoke_contract() {
  local base="$1" url="$1/rest/saiku/api/mcp" code hdrs
  local -a common=(-sS --max-time 20 -X POST
    -H 'Content-Type: application/json'
    -H 'Accept: application/json, text/event-stream'
    --data "$MCP_INIT_BODY")
  code="$(curl "${common[@]}" -o /dev/null -w '%{http_code}' "$url" 2>/dev/null || true)"
  if [[ "$code" != "401" ]]; then
    err "smoke: anonymous MCP initialize returned '$code', want 401"
    return 1
  fi
  hdrs="$(mktemp)"
  code="$(printf 'user = "%s:%s"\n' "$SMOKE_USER" "$SMOKE_PASS" |
    curl -K - "${common[@]}" -D "$hdrs" -o /dev/null -w '%{http_code}' "$url" 2>/dev/null || true)"
  if [[ "$code" != "200" ]]; then
    rm -f "$hdrs"
    err "smoke: authenticated MCP initialize returned '$code', want 200"
    return 1
  fi
  if ! grep -qi '^mcp-session-id:' "$hdrs"; then
    rm -f "$hdrs"
    err "smoke: authenticated initialize returned 200 without an Mcp-Session-Id header"
    return 1
  fi
  rm -f "$hdrs"
  log "smoke contract passed: $base"
}

# ---------------------------------------------------------------------------
# Candidate
# ---------------------------------------------------------------------------

remove_container() { docker rm -f "$1" >/dev/null 2>&1 || true; }

# Starts the candidate with a throwaway tmpfs home and checks it. The tmpfs lives
# and dies with the container, so the real saiku-home is never opened by two JVMs
# and a failed candidate leaves nothing on disk. First boot seeds that home exactly
# as it would on a fresh install (the launcher materialises it; the entrypoint only
# checks it is writable).
candidate_smoke() { # <image_id>
  local image="$1" port
  remove_container "$CAND_NAME"
  docker run -d --name "$CAND_NAME" \
    --memory "$CANDIDATE_MEMORY" \
    --tmpfs "/app/saiku-home:rw,uid=10001,gid=10001,mode=0755,size=$CANDIDATE_TMPFS_SIZE" \
    -p 127.0.0.1::8080 \
    -e SAIKU_DEMO=true \
    -e "SAIKU_MCP_URL=$MCP_URL" \
    "$image" >/dev/null || { err "could not start candidate"; return 1; }
  port="$(docker port "$CAND_NAME" 8080/tcp 2>/dev/null | parse_published_port)" || {
    err "could not determine the candidate's published port"
    return 1
  }
  log "candidate $(short "$image") listening on 127.0.0.1:$port (throwaway home)"
  wait_healthy "http://127.0.0.1:$port" "$CAND_NAME" || return 1
  smoke_contract "http://127.0.0.1:$port" || return 1
}

candidate_cleanup() { remove_container "$CAND_NAME"; }

# ---------------------------------------------------------------------------
# Real container
# ---------------------------------------------------------------------------

run_real() { # <image ref or id>
  local -a args=(-d --name "$CONTAINER" --restart unless-stopped
    -p "$PUBLISH:8080"
    -v "$HOME_DIR:/app/saiku-home"
    -e SAIKU_DEMO=true
    -e "SAIKU_MCP_URL=$MCP_URL")
  [[ -n "$EXTRA_ENV_FILE" ]] && args+=(--env-file "$EXTRA_ENV_FILE")
  docker run "${args[@]}" "$1" >/dev/null
}

real_base_url() { echo "http://${PUBLISH}"; }

backup_home() {
  [[ -n "$BACKUP_DIR" ]] || return 0
  local stamp file
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  file="$BACKUP_DIR/saiku-home-$stamp.tar.gz"
  mkdir -p "$BACKUP_DIR"
  # The old container is stopped, so this is a consistent copy.
  tar -C "$HOME_DIR" -czf "$file" . || { err "home backup failed (continuing)"; rm -f "$file"; return 0; }
  log "saiku-home backed up to $file"
  # shellcheck disable=SC2012  # names are generated by us above
  ls -1t "$BACKUP_DIR"/saiku-home-*.tar.gz 2>/dev/null | tail -n +"$((BACKUP_KEEP + 1))" | while IFS= read -r old; do
    rm -f -- "$old"
  done
}

# Restores the previous container. Returns 0 only if it is serving again.
rollback() { # <previous_image_id>
  local prev="$1"
  log "rolling back"
  remove_container "$CONTAINER"
  if container_exists "$OLD_NAME"; then
    docker rename "$OLD_NAME" "$CONTAINER" && docker start "$CONTAINER" >/dev/null || return 1
  elif [[ -n "$prev" ]]; then
    log "old container is gone; recreating from the previous image"
    run_real "$prev" || return 1
  else
    err "nothing to roll back to"
    return 1
  fi
  wait_healthy "$(real_base_url)" "$CONTAINER"
}

prune_images() {
  local -a keep_ids=()
  local id
  for id in "$@"; do
    if [[ -n "$id" ]]; then keep_ids+=("$id"); fi
  done
  # Scoped to this repository by reference; never force, so anything still in use
  # (or sharing a layer-set with a container) is skipped instead of destroyed.
  while IFS= read -r id; do
    [[ -n "$id" ]] || continue
    if docker rmi "$id" >/dev/null 2>&1; then
      log "pruned old image $(short "$id")"
    else
      log "kept image $(short "$id") (in use or multi-tagged)"
    fi
  done < <(docker image ls --no-trunc --filter "reference=$IMAGE_REPO" --format '{{.ID}}' |
    select_prune_ids "$KEEP_IMAGES" "${keep_ids[@]}")
}

# ---------------------------------------------------------------------------
# Notification (best effort; never changes the exit status)
# ---------------------------------------------------------------------------

notify() { # <new_status> <failed_id or ""> <message>
  local new="$1" new_id="$2" msg="$3" prev prev_id
  prev="$(state_get last_status)"
  prev_id="$(state_get failed_id)"
  if [[ -n "$WEBHOOK_URL" ]] && should_notify "${prev:-ok}" "$new" "$prev_id" "$new_id"; then
    # URL via stdin config: the webhook URL is a bearer secret and must not hit argv.
    printf 'url = "%s"\n' "$WEBHOOK_URL" |
      curl -sS -K - --max-time 10 -X POST -H 'Content-Type: application/json' \
        --data "{\"text\":\"${msg}\"}" -o /dev/null 2>/dev/null ||
      log "webhook delivery failed (ignored)"
  fi
  state_put last_status "$new"
  if [[ "$new" == "failed" ]]; then
    state_put failed_id "$new_id"
    state_put failed_at "$NOW"
  else
    state_put failed_id ""
    state_put failed_at "0"
  fi
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

deploy() { # <cand_id> <run_id>
  local cand="$1" run="$2" had_old=false rev prev_rev
  rev="$(image_rev "$cand")"
  prev_rev="unknown"
  [[ -n "$run" ]] && prev_rev="$(image_rev "$run")"

  if ! candidate_smoke "$cand"; then
    docker logs --tail 40 "$CAND_NAME" 2>&1 | sed 's/^/candidate| /' || true
    candidate_cleanup
    err "candidate $(short "$cand") failed pre-flight; live container untouched"
    notify failed "$cand" "saiku demo deploy FAILED pre-flight: candidate ${rev} rejected, still serving ${prev_rev}"
    return 1
  fi
  candidate_cleanup

  if [[ -n "$run" ]]; then
    docker tag "$run" "$PREVIOUS_TAG" || log "could not tag previous image (continuing)"
  fi
  remove_container "$OLD_NAME"
  if container_exists "$CONTAINER"; then
    had_old=true
    docker stop -t 30 "$CONTAINER" >/dev/null || true
    docker rename "$CONTAINER" "$OLD_NAME"
  fi
  backup_home

  if run_real "$cand" && wait_healthy "$(real_base_url)" "$CONTAINER" && smoke_contract "$(real_base_url)"; then
    # State is recorded before the old container goes: both ids, see decide_action.
    state_put deployed_cand_id "$cand"
    state_put deployed_run_id "$(container_image "$CONTAINER")"
    remove_container "$OLD_NAME"
    if [[ -n "$PUBLIC_URL" ]]; then
      curl -fsS -o /dev/null --max-time 15 --head "$PUBLIC_URL/rest/saiku/info" 2>/dev/null ||
        log "warning: public URL check failed (proxy?): $PUBLIC_URL"
    fi
    log "deployed $(short "$cand") (rev $rev)"
    notify ok "" "saiku demo recovered: now serving ${rev}"
    prune_images "$cand" "$run" "$(image_id "$PREVIOUS_TAG" || true)"
    return 0
  fi

  err "new container failed post-swap checks"
  if [[ "$had_old" != "true" && -z "$run" ]]; then
    remove_container "$CONTAINER"
    err "nothing was running before, so there is nothing to roll back to"
    notify failed "$cand" "saiku demo deploy FAILED: candidate ${rev} did not come up and nothing was running before"
    return 1
  fi
  if rollback "$run"; then
    err "rolled back to ${prev_rev}"
    notify failed "$cand" "saiku demo deploy FAILED: candidate ${rev} rolled back to ${prev_rev}"
    return 1
  fi
  err "ROLLBACK FAILED; the demo may be down"
  notify failed "$cand" "saiku demo deploy FAILED and ROLLBACK FAILED for ${rev}: manual action needed"
  return 2
}

main() {
  local check_only=false
  case "${1:-}" in
    "") ;;
    --check) check_only=true ;;
    *) err "usage: $SELF [--check]   (--check pulls and reports what it would do, then exits)"; exit 64 ;;
  esac
  config
  mkdir -p "$STATE_DIR"
  acquire_lock
  trap candidate_cleanup EXIT

  docker pull --quiet "$IMAGE_REPO:$GREEN_TAG" >/dev/null || { err "pull of $IMAGE_REPO:$GREEN_TAG failed"; exit 1; }
  local cand run is_running action
  cand="$(image_id "$IMAGE_REPO:$GREEN_TAG")"
  [[ "$cand" =~ $IMAGE_ID_RE ]] || { err "unexpected image id for $GREEN_TAG: '$cand'"; exit 1; }

  run=""
  is_running=false
  if container_exists "$CONTAINER"; then
    run="$(container_image "$CONTAINER")"
    is_running="$(container_running "$CONTAINER")"
  fi
  action="$(decide_action "$cand" "$run" "$is_running" "$(state_get deployed_cand_id)" "$(state_get deployed_run_id)")"

  if [[ "$action" == "noop" ]]; then
    log "up to date: $(short "$cand") (rev $(image_rev "$cand"))"
    if [[ "$(state_get last_status)" == "failed" ]]; then
      notify ok "" "saiku demo recovered: serving $(image_rev "$cand")"
    fi
    exit 0
  fi

  if [[ "$check_only" == "true" ]]; then
    log "check only: would deploy $(short "$cand") (rev $(image_rev "$cand")) over ${run:+$(short "$run")}${run:-nothing}"
    exit 0
  fi

  if should_skip_failed "$cand" "$(state_get failed_id)" "$(state_get failed_at)" "$NOW" "$FAIL_BACKOFF"; then
    err "candidate $(short "$cand") failed earlier; not retrying for ${FAIL_BACKOFF}s (promote a fixed or older sha)"
    exit 1
  fi

  local rc=0
  deploy "$cand" "$run" || rc=$?
  exit "$rc"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
