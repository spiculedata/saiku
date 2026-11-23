#!/usr/bin/env bash
# Nightly reset of the demo (demo.saiku.bi): wipe the home, keep the image.
#
# Replaces the hand-written /usr/local/bin/saiku-reset, which pulled and ran
# `ghcr.io/spiculedata/saiku:development` itself. That put the demo on an image CI
# had not passed (the whole point of promote-green) and recreated the container behind
# the deploy timer's back. This script never pulls and never picks an image: it stops
# the container saiku-demo-deploy.sh created, empties and re-owns the home, and starts
# the same container again, so it keeps running the green image and its exact flags.
# New images still arrive only through saiku-demo-deploy.sh.
#
# It takes the deploy script's lock, so it cannot interleave with a deploy swap. If
# there is no saiku-demo container (or it will not start) it hands over to the deploy
# script, which treats "nothing running" as a deploy of the current green image.
#
# Usage: saiku-demo-reset.sh        (cron: 0 0 * * * root, CRON_TZ=America/New_York)
set -euo pipefail

CONTAINER="${SAIKU_CONTAINER:-saiku-demo}"
HOME_DIR="${SAIKU_HOME_DIR:-/opt/saiku/home}"
STATE_DIR="${SAIKU_STATE_DIR:-/var/lib/saiku-demo-deploy}"
# The image runs as uid/gid 10001 (saiku#1989) and fails closed on an unwritable home.
HOME_OWNER="${SAIKU_HOME_OWNER:-10001:10001}"
DEPLOY_SCRIPT="${SAIKU_DEPLOY_SCRIPT:-/usr/local/sbin/saiku-demo-deploy.sh}"
SELF="saiku-demo-reset"

log() { printf '%s %s: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$SELF" "$*"; }
err() { log "ERROR: $*" >&2; }

# Prints the normalised path (duplicate and trailing slashes collapsed) when it is absolute,
# has no . or .. component, and is at least two levels deep (so never /, // or /opt).
# The result is the only value that may reach rm -rf.
safe_home_dir() {
  local p="$1"
  [[ "$p" == /* ]] || return 1
  [[ "$p" != *"/../"* && "$p" != */.. && "$p" != *"/./"* && "$p" != */. ]] || return 1
  p="$(printf '%s' "$p" | tr -s '/')"
  p="${p%/}"
  [[ "$p" =~ ^/[^/]+/[^/]+ ]] || return 1
  printf '%s\n' "$p"
}

# The deploy script takes this lock non-blocking on its own file description, so it must
# be released before handing over to it.
hand_over() {
  log "$1; handing over to the deploy script"
  exec 9>&-
  exec "$DEPLOY_SCRIPT"
}

main() {
  local safe_home
  if ! safe_home="$(safe_home_dir "$HOME_DIR")"; then
    err "refusing to reset unsafe home dir '$HOME_DIR'"
    exit 1
  fi
  HOME_DIR="$safe_home"
  if [[ ! "$CONTAINER" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]]; then
    err "unsafe container name '$CONTAINER'"
    exit 1
  fi

  mkdir -p "$STATE_DIR"
  exec 9>"$STATE_DIR/lock"
  flock 9 # wait for an in-flight deploy rather than skipping: a reset must happen

  if ! docker container inspect "$CONTAINER" >/dev/null 2>&1; then
    hand_over "no $CONTAINER container"
  fi

  docker stop -t 30 "$CONTAINER" >/dev/null
  rm -rf -- "$HOME_DIR"
  mkdir -p -- "$HOME_DIR"
  chown "$HOME_OWNER" "$HOME_DIR"

  if ! docker start "$CONTAINER" >/dev/null; then
    hand_over "$CONTAINER would not start"
  fi
  log "reset: $CONTAINER restarted on an empty home"
}

main "$@"
