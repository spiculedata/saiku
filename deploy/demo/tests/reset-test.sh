#!/usr/bin/env bash
# Tests for saiku-demo-reset.sh. A fake `docker` on PATH records every call; the deploy
# script is a stub that records that it ran. No docker, network or root needed.
#
# SAFETY: the script under test runs `rm -rf` on a path it is given. Every test therefore
# puts a fake `rm` first on PATH that refuses (exit 99, and logs it) to remove anything
# outside this test's own temp dir, so a regression in the path guard can never delete real
# files. Do not remove that shim, and never feed the script the real default home.
# shellcheck disable=SC2015  # `cond && ok || fail`: ok/fail never return non-zero
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../saiku-demo-reset.sh"
SHIMS="$HERE/shims"
PASS=0; FAIL=0; CURRENT=""
HAVE_REAL_FLOCK=0; command -v flock >/dev/null 2>&1 && HAVE_REAL_FLOCK=1
# Resolve the real rm ONCE, before any test puts its fake rm on PATH (resolving it per test
# would find the previous test's fake and make the fakes exec themselves forever).
REAL_RM="$(command -v rm)"; export REAL_RM

fail() { FAIL=$((FAIL + 1)); printf '  FAIL [%s] %s\n' "$CURRENT" "$*"; }
ok() { PASS=$((PASS + 1)); }
assert_eq() { if [[ "$1" == "$2" ]]; then ok; else fail "${3:-assert_eq}: want '$2' got '$1'"; fi; }
assert_contains() { if grep -qF -- "$2" <<<"$1"; then ok; else fail "${3:-contains}: '$2' not found"; fi; }
calls() { cat "$FAKE/calls.log" 2>/dev/null || true; }
count_calls() { calls | grep -cE -- "$1" || true; }

setup() { # fresh fake world; sets FAKE, HOMEDIR, STATE, BIN and PATH
  CURRENT="$1"
  FAKE="$(mktemp -d)"; export FAKE
  HOMEDIR="$FAKE/home"; STATE="$FAKE/state"; BIN="$FAKE/bin"
  mkdir -p "$HOMEDIR" "$STATE" "$BIN"
  echo "demo data" >"$HOMEDIR/saiku.db"
  : >"$FAKE/calls.log"
  cat >"$BIN/rm" <<'RM'
#!/usr/bin/env bash
for a in "$@"; do
  [[ "$a" == -* ]] && continue
  case "$a" in
    "$FAKE"/*) ;;
    *) echo "rm-outside-sandbox $a" >>"$FAKE/calls.log"; exit 99 ;;
  esac
done
exec "$REAL_RM" "$@"
RM
  cat >"$BIN/docker" <<'DOCKER'
#!/usr/bin/env bash
# Fake docker: log the call; `start` also records how many files the home held then.
echo "docker $*" >>"$FAKE/calls.log"
case "$1 $2" in
  "container inspect") [[ -e "$FAKE/has_container" ]] ;;
  "stop "*) exit 0 ;;
  "start "*)
    echo "home_entries_at_start=$(find "$FAKE/home" -mindepth 1 | wc -l | tr -d ' ')" >>"$FAKE/calls.log"
    [[ ! -e "$FAKE/start_fails" ]]
    ;;
  *) exit 0 ;;
esac
DOCKER
  cat >"$FAKE/deploy-stub.sh" <<'STUB'
#!/usr/bin/env bash
echo "deploy-script ran" >>"$FAKE/calls.log"
STUB
  [[ "$HAVE_REAL_FLOCK" -eq 1 ]] || cp "$SHIMS/flock" "$BIN/flock"
  chmod +x "$BIN"/* "$FAKE/deploy-stub.sh"
  # shellcheck disable=SC2123  # deliberate: the fakes must win
  PATH="$BIN:$PATH"
}

run_reset() { # extra env as KEY=VAL args; sets OUT and RC
  OUT="$(env SAIKU_HOME_DIR="$HOMEDIR" SAIKU_STATE_DIR="$STATE" SAIKU_HOME_OWNER="$(id -u):$(id -g)" \
    SAIKU_DEPLOY_SCRIPT="$FAKE/deploy-stub.sh" "$@" bash "$SCRIPT" 2>&1)"
  RC=$?
}

test_restarts_same_container_on_an_empty_home() {
  setup restart; touch "$FAKE/has_container"
  run_reset
  assert_eq "$RC" 0 "exit code"
  assert_eq "$(find "$HOMEDIR" -mindepth 1 | wc -l | tr -d ' ')" 0 "home emptied"
  [[ -d "$HOMEDIR" ]] && ok || fail "home dir recreated"
  assert_contains "$(calls)" "docker stop -t 30 saiku-demo" "stopped first"
  assert_contains "$(calls)" "home_entries_at_start=0" "home already empty when the container starts"
  assert_eq "$(count_calls 'docker (pull|run|rm|rmi|image)')" 0 "never pulls, runs or removes anything"
  assert_eq "$(count_calls 'deploy-script')" 0 "deploy script not involved"
}

test_stops_before_wiping() {
  setup order; touch "$FAKE/has_container"
  run_reset
  local stop_line start_line
  stop_line="$(calls | grep -n 'docker stop' | head -1 | cut -d: -f1)"
  start_line="$(calls | grep -n 'docker start' | head -1 | cut -d: -f1)"
  [[ -n "$stop_line" && -n "$start_line" && "$stop_line" -lt "$start_line" ]] && ok || fail "stop must precede start"
}

test_no_container_hands_over_to_deploy_and_keeps_home() {
  setup nocontainer
  run_reset
  assert_eq "$RC" 0 "exit code"
  assert_contains "$(calls)" "deploy-script ran" "deploy script ran"
  assert_eq "$(count_calls 'docker stop')" 0 "nothing stopped"
  [[ -f "$HOMEDIR/saiku.db" ]] && ok || fail "home untouched when there is no container to reset"
}

test_start_failure_hands_over_to_deploy() {
  setup startfail; touch "$FAKE/has_container" "$FAKE/start_fails"
  run_reset
  assert_contains "$(calls)" "deploy-script ran" "deploy script recovers a container that will not start"
  assert_contains "$OUT" "would not start" "says why"
}

test_refuses_unsafe_home_dir() {
  # An empty SAIKU_HOME_DIR falls back to the default, so it is not an "unsafe" input and
  # must never be exercised here: the default is the real /opt/saiku/home.
  local bad
  for bad in "/" "//" "///" "/opt" "/opt/" "relative/path" "/opt/saiku/../.." "/opt/./x/.."; do
    setup "unsafe-$bad"; touch "$FAKE/has_container"
    run_reset SAIKU_HOME_DIR="$bad"
    assert_eq "$RC" 1 "rejects '$bad'"
    assert_eq "$(count_calls '^docker')" 0 "no docker call for '$bad'"
    assert_eq "$(count_calls 'rm-outside-sandbox')" 0 "nothing outside the sandbox touched for '$bad'"
  done
}

test_accepts_a_normal_home_and_collapses_slashes() {
  setup normalise; touch "$FAKE/has_container"
  run_reset SAIKU_HOME_DIR="$HOMEDIR//"
  assert_eq "$RC" 0 "trailing double slash is normalised, not rejected"
  assert_eq "$(find "$HOMEDIR" -mindepth 1 | wc -l | tr -d ' ')" 0 "home emptied"
}

test_refuses_unsafe_container_name() {
  setup badname; touch "$FAKE/has_container"
  run_reset SAIKU_CONTAINER='bad name; rm -rf /'
  assert_eq "$RC" 1 "rejects unsafe container name"
  assert_eq "$(count_calls '^docker')" 0 "no docker call"
}

test_waits_for_an_inflight_deploy() {
  setup lock
  if [[ "$HAVE_REAL_FLOCK" -ne 1 ]]; then echo "  skip: no flock on this host"; return; fi
  touch "$FAKE/has_container"
  ( exec 8>"$STATE/lock"; flock 8; sleep 2 ) &
  local holder=$!
  sleep 0.5
  run_reset
  wait "$holder" 2>/dev/null
  assert_eq "$RC" 0 "reset still happens after the deploy releases the lock"
  assert_contains "$(calls)" "docker stop" "ran after waiting"
}

for t in $(declare -F | awk '{print $3}' | grep '^test_'); do
  printf 'RUN  %s\n' "$t"
  "$t"
done
printf '\n%d assertions passed, %d failed\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]
