#!/usr/bin/env bash
# Tests for saiku-demo-deploy.sh. No docker, no network: a fake `docker` and
# `curl` (tests/shims) sit first on PATH and keep their state in a temp dir.
# Plain bash on purpose - no bats dependency - but each test is a function, a
# failing assertion is reported with its name, and the exit code is non-zero.
# shellcheck disable=SC2015  # `cond && ok || fail`: ok/fail never return non-zero
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../saiku-demo-deploy.sh"
SHIMS="$HERE/shims"
PASS=0; FAIL=0; CURRENT=""
HAVE_REAL_FLOCK=0; command -v flock >/dev/null 2>&1 && HAVE_REAL_FLOCK=1

# Source once (it declares readonly constants); main() only runs when executed.
# shellcheck source=../saiku-demo-deploy.sh
source "$SCRIPT"
set +eEu +o pipefail # the script under test sets strict mode; the runner must not inherit it

fail() { FAIL=$((FAIL + 1)); printf '  FAIL [%s] %s\n' "$CURRENT" "$*"; }
ok() { PASS=$((PASS + 1)); }
assert_eq() { if [[ "$1" == "$2" ]]; then ok; else fail "${3:-assert_eq}: want '$2' got '$1'"; fi; }
assert_contains() { if grep -qF -- "$2" <<<"$1"; then ok; else fail "${3:-contains}: '$2' not found"; fi; }
assert_not_contains() { if grep -qF -- "$2" <<<"$1"; then fail "${3:-not-contains}: '$2' present"; else ok; fi; }
calls() { cat "$FAKE/calls.log" 2>/dev/null || true; }
count_calls() { calls | grep -cE -- "$1" || true; }

ID_A="sha256:$(printf 'a%.0s' {1..64})"
ID_B="sha256:$(printf 'b%.0s' {1..64})"
ID_C="sha256:$(printf 'c%.0s' {1..64})"
ID_D="sha256:$(printf 'd%.0s' {1..64})"
ID_E="sha256:$(printf 'e%.0s' {1..64})"
ID_F="sha256:$(printf 'f%.0s' {1..64})"
GREEN_KEY="ghcr.io_spiculedata_saiku_development-green"
NOW0=2000000

setup() { # fresh fake world; sets FAKE, STATE, and PATH
  CURRENT="$1"
  FAKE="$(mktemp -d)"; STATE="$FAKE/state"; export FAKE
  mkdir -p "$FAKE/registry" "$FAKE/tags" "$FAKE/containers" "$FAKE/labels" "$STATE"
  : >"$FAKE/imagelist"
  BIN="$FAKE/bin"; mkdir -p "$BIN"
  cp "$SHIMS/docker" "$SHIMS/curl" "$BIN/"
  [[ "$HAVE_REAL_FLOCK" -eq 1 ]] || cp "$SHIMS/flock" "$BIN/"
  chmod +x "$BIN"/*
  # shellcheck disable=SC2123  # deliberate: the shims must win
  PATH="$BIN:$PATH"
}

set_green() { echo "$1" >"$FAKE/registry/$GREEN_KEY"; }
make_running() { # <name> <image id> [mode]
  local d="$FAKE/containers/$1"; mkdir -p "$d"
  echo "$2" >"$d/image"; echo true >"$d/running"; echo "${3:-ok}" >"$d/mode"; echo 8080 >"$d/port"
}
img_list() { printf '%s\n' "$@" >"$FAKE/imagelist"; }
bad_cand() { echo "$1=$2" >>"$FAKE/bad_cand"; }
bad_real() { echo "$1=$2" >>"$FAKE/bad_real"; }

run_deploy() { # extra env as KEY=VAL args; sets OUT and RC
  OUT="$(env SAIKU_STATE_DIR="$STATE" SAIKU_HEALTH_ATTEMPTS=2 SAIKU_HEALTH_INTERVAL=0 \
    SAIKU_NOW_EPOCH="$NOW0" SAIKU_DEPLOY_WEBHOOK_URL=https://hooks.example/T0KEN-SECRET \
    SAIKU_SMOKE_PASS=hunter2-secret "$@" bash "$SCRIPT" 2>&1)"
  RC=$?
}

webhooks() { [[ -f "$FAKE/webhook.log" ]] && wc -l <"$FAKE/webhook.log" | tr -d ' ' || echo 0; }

# ---------------------------------------------------------------- unit tests

test_decide_action() {
  setup decide_action
    assert_eq "$(decide_action "$ID_A" "$ID_A" true "" "")" noop "same id, running"
  assert_eq "$(decide_action "$ID_B" "$ID_A" true "" "")" deploy "different id"
  assert_eq "$(decide_action "$ID_A" "$ID_A" false "" "")" deploy "same id but stopped"
  assert_eq "$(decide_action "$ID_A" "" false "" "")" deploy "no container"
  assert_eq "$(decide_action "$ID_B" "$ID_C" true "$ID_B" "$ID_C")" noop "recorded alias of the same deploy"
  assert_eq "$(decide_action "$ID_B" "$ID_D" true "$ID_B" "$ID_C")" deploy "recorded candidate but container changed since"
}

test_should_skip_failed() {
  setup skip_failed
    should_skip_failed "$ID_A" "$ID_A" 1000 1500 3600 && ok || fail "inside backoff must skip"
  should_skip_failed "$ID_A" "$ID_A" 1000 4600 3600 && fail "at backoff boundary must retry" || ok
  should_skip_failed "$ID_B" "$ID_A" 1000 1500 3600 && fail "a different candidate must not skip" || ok
  should_skip_failed "$ID_A" "" 0 1500 3600 && fail "no failure recorded" || ok
  should_skip_failed "$ID_A" "$ID_A" garbage 1500 3600 && fail "corrupt state must not skip" || ok
}

test_parse_published_port() {
  setup parse_port
    assert_eq "$(printf '127.0.0.1:49153\n[::]:49153\n' | parse_published_port)" 49153 "ipv4 line first"
  assert_eq "$(printf '[::]:32768\n' | parse_published_port)" 32768 "ipv6 only"
  printf 'garbage\n' | parse_published_port >/dev/null && fail "garbage accepted" || ok
  printf '127.0.0.1:99999\n' | parse_published_port >/dev/null && fail "out of range accepted" || ok
  printf '' | parse_published_port >/dev/null && fail "empty accepted" || ok
}

test_select_prune_ids() {
  setup select_prune
    assert_eq "$(printf '%s\n' i1 i2 i3 i4 i5 | select_prune_ids 3 | tr '\n' ' ')" "i4 i5 " "keeps newest three"
  assert_eq "$(printf '%s\n' i1 i2 i3 i4 i5 | select_prune_ids 3 i4 | tr '\n' ' ')" "i5 " "protected id survives"
  assert_eq "$(printf '%s\n' i1 i1 i2 i3 i4 | select_prune_ids 3 | tr '\n' ' ')" "i4 " "duplicates (multi-tag) counted once"
  assert_eq "$(printf '%s\n' i1 i2 | select_prune_ids 3 | tr '\n' ' ')" "" "fewer than keep"
}

test_should_notify() {
  setup should_notify
    should_notify ok failed "" X && ok || fail "first failure must notify"
  should_notify failed failed X X && fail "same failure must not renotify" || ok
  should_notify failed failed X Y && ok || fail "a different failing candidate must notify"
  should_notify failed ok X "" && ok || fail "recovery must notify"
  should_notify ok ok "" "" && fail "routine success must stay silent" || ok
}

# ------------------------------------------------------------ scenario tests

test_noop_when_up_to_date() {
  setup noop
  set_green "$ID_A"; make_running saiku-demo "$ID_A"
  run_deploy
  assert_eq "$RC" 0 "exit"
  assert_eq "$(count_calls '^docker run')" 0 "no container started"
  assert_eq "$(count_calls '^docker (stop|rename)')" 0 "nothing touched"
  assert_eq "$(webhooks)" 0 "no webhook"
}

test_check_mode_changes_nothing() {
  setup check_mode
  set_green "$ID_B"; make_running saiku-demo "$ID_A"
  OUT="$(env SAIKU_STATE_DIR="$STATE" bash "$SCRIPT" --check 2>&1)"; RC=$?
  assert_eq "$RC" 0 "exit"
  assert_contains "$OUT" "check only: would deploy" "reports"
  assert_eq "$(count_calls '^docker (run|stop|rename|tag)')" 0 "nothing changed"
  OUT="$(env SAIKU_STATE_DIR="$STATE" bash "$SCRIPT" --bogus 2>&1)"; RC=$?
  assert_eq "$RC" 64 "usage error"
}

test_successful_deploy() {
  setup deploy_ok
  set_green "$ID_B"; make_running saiku-demo "$ID_A"
  echo "0123456789abcdef0123456789abcdef01234567" >"$FAKE/labels/$(echo "$ID_B" | tr '/:@' '___')"
  img_list "$ID_B" "$ID_A" "$ID_C" "$ID_D" "$ID_E" "$ID_F"
  run_deploy
  assert_eq "$RC" 0 "exit: $OUT"
  assert_contains "$OUT" "deployed" "logged"
  assert_contains "$OUT" "rev 0123456" "revision shown"
  # candidate: throwaway tmpfs home, loopback ephemeral port, never the real home
  local cargs; cargs="$(calls | grep 'docker run' | grep -- '--name saiku-demo-candidate')"
  assert_contains "$cargs" "--tmpfs /app/saiku-home:" "candidate uses tmpfs home"
  assert_contains "$cargs" "-p 127.0.0.1::8080" "candidate port assigned by docker on loopback"
  assert_not_contains "$cargs" "/opt/saiku/home" "candidate must not mount the real home"
  # real container: same mounts/env as the runbook
  local rargs; rargs="$(calls | grep 'docker run' | grep -- '--name saiku-demo ')"
  assert_contains "$rargs" "-v /opt/saiku/home:/app/saiku-home" "real home mounted"
  assert_contains "$rargs" "-p 127.0.0.1:8080:8080" "bound to loopback 8080"
  assert_contains "$rargs" "SAIKU_DEMO=true" "demo mode"
  assert_contains "$rargs" "SAIKU_MCP_URL=https://demo.saiku.bi/rest/saiku/api/mcp" "mcp url"
  assert_eq "$(cat "$FAKE/containers/saiku-demo/image")" "$ID_B" "new image running"
  [[ -d "$FAKE/containers/saiku-demo-candidate" ]] && fail "candidate left behind" || ok
  [[ -d "$FAKE/containers/saiku-demo-old" ]] && fail "old container left behind" || ok
  assert_eq "$(cat "$FAKE/tags/saiku-demo-previous" 2>/dev/null)" "$ID_A" "previous kept as local tag"
  # order: candidate before the live container is stopped
  local first_cand first_stop
  first_cand="$(calls | grep -n 'name saiku-demo-candidate' | head -1 | cut -d: -f1)"
  first_stop="$(calls | grep -n '^docker stop' | head -1 | cut -d: -f1)"
  (( first_cand < first_stop )) && ok || fail "candidate must be smoked before the live container stops"
  # prune: newest 3 of the list kept (+ protected), the rest removed
  assert_eq "$(tr '\n' ' ' <"$FAKE/imagelist")" "$ID_B $ID_A $ID_C " "pruned beyond keep=3"
  # second run is a no-op
  : >"$FAKE/calls.log"
  run_deploy
  assert_eq "$RC" 0 "second exit"
  assert_eq "$(count_calls '^docker run')" 0 "idempotent: no second deploy"
}

test_candidate_health_failure_leaves_live_untouched() {
  setup cand_health
  set_green "$ID_B"; make_running saiku-demo "$ID_A"; bad_cand "$ID_B" down
  run_deploy
  assert_eq "$RC" 1 "exit"
  assert_eq "$(count_calls '^docker (stop|rename)')" 0 "live container never stopped"
  assert_eq "$(cat "$FAKE/containers/saiku-demo/image")" "$ID_A" "still serving A"
  assert_eq "$(cat "$FAKE/containers/saiku-demo/running")" true "still running"
  [[ -d "$FAKE/containers/saiku-demo-candidate" ]] && fail "candidate left behind" || ok
  assert_eq "$(webhooks)" 1 "one failure webhook"
  # retried within the backoff: skipped quickly, no new candidate, no second webhook
  : >"$FAKE/calls.log"
  run_deploy SAIKU_NOW_EPOCH=$((NOW0 + 60))
  assert_eq "$RC" 1 "backoff run still reports failure"
  assert_eq "$(count_calls '^docker run')" 0 "no retry inside backoff"
  assert_eq "$(webhooks)" 1 "no repeat webhook"
  # after the backoff the same candidate is retried (and fails again silently)
  : >"$FAKE/calls.log"
  run_deploy SAIKU_NOW_EPOCH=$((NOW0 + 3600))
  assert_eq "$(count_calls '^docker run')" 1 "retried after backoff"
  assert_eq "$(webhooks)" 1 "same failing candidate does not renotify"
}

test_candidate_smoke_contract_violations() {
  local mode
  for mode in anon200 nosession auth401; do
    setup "cand_$mode"
    set_green "$ID_B"; make_running saiku-demo "$ID_A"; bad_cand "$ID_B" "$mode"
    run_deploy
    assert_eq "$RC" 1 "$mode exit"
    assert_eq "$(count_calls '^docker stop')" 0 "$mode: live untouched"
    assert_contains "$OUT" "smoke:" "$mode: smoke error logged"
  done
}

test_real_swap_failure_rolls_back() {
  setup rollback
  set_green "$ID_B"; make_running saiku-demo "$ID_A"; bad_real "$ID_B" down
  run_deploy
  assert_eq "$RC" 1 "exit"
  assert_eq "$(cat "$FAKE/containers/saiku-demo/image")" "$ID_A" "A restored under the live name"
  assert_eq "$(cat "$FAKE/containers/saiku-demo/running")" true "A running again"
  [[ -d "$FAKE/containers/saiku-demo-old" ]] && fail "old name left behind" || ok
  assert_eq "$(cat "$STATE/failed_id")" "$ID_B" "failed candidate recorded"
  assert_eq "$(cat "$STATE/last_status")" failed "status failed"
  assert_eq "$(webhooks)" 1 "failure webhook"
  # the green tag still points at the bad image: no redeploy loop inside the backoff
  : >"$FAKE/calls.log"
  run_deploy SAIKU_NOW_EPOCH=$((NOW0 + 300))
  assert_eq "$RC" 1 "still failing"
  assert_eq "$(count_calls '^docker (run|stop)')" 0 "not retried"
}

test_post_swap_smoke_failure_rolls_back() {
  setup rollback_smoke
  set_green "$ID_B"; make_running saiku-demo "$ID_A"; bad_real "$ID_B" nosession
  run_deploy
  assert_eq "$RC" 1 "exit"
  assert_eq "$(cat "$FAKE/containers/saiku-demo/image")" "$ID_A" "rolled back"
}

test_rollback_failure_exits_2() {
  setup rollback_fail
  set_green "$ID_B"; make_running saiku-demo "$ID_A" down; bad_real "$ID_B" down
  run_deploy
  assert_eq "$RC" 2 "exit 2 when the rollback also fails"
  assert_contains "$OUT" "ROLLBACK FAILED" "loud"
}

test_first_deploy_nothing_running() {
  setup first_deploy
  set_green "$ID_B"
  run_deploy
  assert_eq "$RC" 0 "exit: $OUT"
  assert_eq "$(cat "$FAKE/containers/saiku-demo/image")" "$ID_B" "started"
  setup first_deploy_bad
  set_green "$ID_B"; bad_real "$ID_B" down
  run_deploy
  assert_eq "$RC" 1 "failure with nothing to roll back is exit 1"
  [[ -d "$FAKE/containers/saiku-demo" ]] && fail "failed container left running" || ok
}

test_recovery_notification() {
  setup recovery
  set_green "$ID_B"; make_running saiku-demo "$ID_A"; bad_cand "$ID_B" down
  run_deploy
  assert_eq "$(webhooks)" 1 "failure webhook"
  set_green "$ID_C"
  run_deploy SAIKU_NOW_EPOCH=$((NOW0 + 10))
  assert_eq "$RC" 0 "good candidate deploys: $OUT"
  assert_eq "$(webhooks)" 2 "recovery webhook"
  assert_contains "$(tail -n 1 "$FAKE/webhook.log")" "recovered" "message"
  set_green "$ID_D"
  run_deploy SAIKU_NOW_EPOCH=$((NOW0 + 20))
  assert_eq "$(webhooks)" 2 "routine success stays silent"
}

test_noop_after_failure_counts_as_recovery() {
  setup noop_recovery
  set_green "$ID_B"; make_running saiku-demo "$ID_A"; bad_cand "$ID_B" down
  run_deploy
  set_green "$ID_A" # operator rolled the green tag back to what is running
  run_deploy SAIKU_NOW_EPOCH=$((NOW0 + 10))
  assert_eq "$RC" 0 "noop"
  assert_eq "$(webhooks)" 2 "recovery webhook"
  assert_eq "$(cat "$STATE/last_status")" ok "status ok"
}

test_pull_failure() {
  setup pull_fail
  set_green "$ID_B"; make_running saiku-demo "$ID_A"; touch "$FAKE/pull_fail"
  run_deploy
  assert_eq "$RC" 1 "exit"
  assert_eq "$(count_calls '^docker (run|stop|rename)')" 0 "nothing touched"
}

test_no_secret_leakage() {
  setup secrets
  set_green "$ID_B"; make_running saiku-demo "$ID_A"; bad_cand "$ID_B" down
  run_deploy
  assert_not_contains "$OUT" "T0KEN-SECRET" "webhook url in output"
  assert_not_contains "$OUT" "hunter2-secret" "smoke password in output"
  assert_not_contains "$(calls)" "T0KEN-SECRET" "webhook url in argv"
  assert_not_contains "$(calls)" "hunter2-secret" "smoke password in argv"
  assert_contains "$(cat "$FAKE/webhook.log")" "T0KEN-SECRET" "webhook did receive the url on stdin"
}

test_webhook_failure_is_not_fatal() {
  setup webhook_fail
  set_green "$ID_B"; make_running saiku-demo "$ID_A"; touch "$FAKE/webhook_fail"
  run_deploy
  assert_eq "$RC" 0 "deploy still succeeds"
  setup webhook_fail2
  set_green "$ID_B"; make_running saiku-demo "$ID_A"; bad_cand "$ID_B" down; touch "$FAKE/webhook_fail"
  run_deploy
  assert_eq "$RC" 1 "exit stays 1 (not changed by webhook failure)"
}

test_home_backup_retention() {
  setup backup
  local home="$FAKE/home" bk="$FAKE/bk"
  mkdir -p "$home" "$bk"; echo data >"$home/f"
  touch -t 202001010101 "$bk/saiku-home-20200101T010101Z.tar.gz" "$bk/saiku-home-20200102T010101Z.tar.gz"
  set_green "$ID_B"; make_running saiku-demo "$ID_A"
  run_deploy SAIKU_HOME_DIR="$home" SAIKU_HOME_BACKUP_DIR="$bk" SAIKU_HOME_BACKUP_KEEP=2
  assert_eq "$RC" 0 "exit: $OUT"
  assert_eq "$(find "$bk" -name 'saiku-home-*.tar.gz' | wc -l | tr -d ' ')" 2 "retention keeps 2"
  tar -tzf "$(find "$bk" -name 'saiku-home-*.tar.gz' -newer "$home/f" | head -1)" | grep -q 'f$' && ok || fail "backup holds the home contents"
}

test_config_validation() {
  setup config
  set_green "$ID_B"
  run_deploy SAIKU_CONTAINER='bad name; rm -rf /'
  assert_eq "$RC" 1 "rejects unsafe container name"
  assert_eq "$(count_calls '^docker')" 0 "no docker call"
}

test_overlap_lock() {
  setup lock
  if [[ "$HAVE_REAL_FLOCK" -ne 1 ]]; then echo "  skip: no flock on this host"; return; fi
  set_green "$ID_B"; make_running saiku-demo "$ID_A"
  ( exec 8>"$STATE/lock"; flock 8; sleep 3 ) &
  local holder=$!
  sleep 1
  run_deploy
  wait "$holder" 2>/dev/null
  assert_eq "$RC" 0 "overlap exits 0"
  assert_contains "$OUT" "another run holds the lock" "message"
  assert_eq "$(calls | wc -l | tr -d ' ')" 0 "no docker activity while locked"
}

for t in $(declare -F | awk '{print $3}' | grep '^test_'); do
  printf 'RUN  %s\n' "$t"
  "$t"
done
printf '\n%d assertions passed, %d failed\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]
