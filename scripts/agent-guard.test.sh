#!/usr/bin/env bash
# Test suite for scripts/agent-guard.sh. Each case pipes a tool payload on stdin
# and asserts the guard's verdict: exit 2 = blocked, exit 0 = allowed.
#
#   scripts/agent-guard.test.sh
set -uo pipefail

GUARD="$(dirname "$0")/agent-guard.sh"
pass=0
fail=0

# expect <mode> <expected: allow|block> <description> <tool_input json>
expect() {
  local mode="$1" want="$2" desc="$3" payload="$4" got
  printf '%s' "$payload" | bash "$GUARD" "$mode" >/dev/null 2>&1
  # NB: `got` must be read before any other command — `local got` resets $?.
  got=$?
  if { [ "$want" = block ] && [ "$got" = 2 ]; } || { [ "$want" = allow ] && [ "$got" = 0 ]; }; then
    pass=$((pass + 1))
    printf 'ok   %s\n' "$desc"
  else
    fail=$((fail + 1))
    printf 'FAIL %s (want %s, exit %s)\n' "$desc" "$want" "$got"
  fi
}

# Render a raw string as a JSON string value (escape backslash, then quote).
json_str() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  printf '"%s"' "$s"
}
cmd() { printf '{"tool_input":{"command":%s}}' "$(json_str "$1")"; }
file() { printf '{"tool_input":{"file_path":%s}}' "$(json_str "$1")"; }

# --- protected branches ------------------------------------------------------
expect bash block "push to development" "$(cmd 'git push upstream development')"
expect bash block "push to main" "$(cmd 'git push origin main')"
expect bash block "push to master" "$(cmd 'git push origin master')"
expect bash block "push a full refspec to main" "$(cmd 'git push upstream refs/heads/main')"
expect bash allow "push a feature branch" "$(cmd 'git push upstream feature/foo')"
expect bash allow "push a tag" "$(cmd 'git push upstream v4.6.1')"

# --- force pushes ------------------------------------------------------------
expect bash block "plain force push" "$(cmd 'git push --force upstream feature/foo')"
expect bash allow "force-with-lease" "$(cmd 'git push --force-with-lease upstream feature/foo')"

# --- sign-off (DCO) ----------------------------------------------------------
expect bash allow "commit -s" "$(cmd 'git commit -s -m "fix: thing"')"
expect bash allow "commit --signoff" "$(cmd 'git commit --signoff -m "fix: thing"')"
expect bash block "commit without -s" "$(cmd 'git commit -m "fix: thing"')"
expect bash block "commit -am without -s" "$(cmd 'git commit -am "fix: thing"')"
expect bash block "commit -s --no-signoff" "$(cmd 'git commit -s --no-signoff -m "x"')"

# --- releases are CI-driven --------------------------------------------------
expect bash block "mvn deploy" "$(cmd 'mvn deploy')"
expect bash block "mvn deploy with flags before it" "$(cmd 'mvn -B -ntp deploy -DskipTests')"
expect bash allow "mvn verify" "$(cmd 'mvn -B -ntp -DskipITs=false verify')"
expect bash allow "mvn spotless:apply" "$(cmd 'mvn spotless:apply')"

# --- write gates -------------------------------------------------------------
expect bash block "gh pr merge --admin" "$(cmd 'gh pr merge 42 --admin --squash')"
expect bash allow "gh pr create" "$(cmd 'gh pr create --title x --body y')"

# --- runtime home is never the source of truth -------------------------------
expect edit block "write under saiku-home" "$(file 'saiku-home/data/FoodMart4.xml')"
expect edit block "write under a nested saiku-home" "$(file '/tmp/x/saiku-home/repository/foo.saikuapp')"
expect edit allow "write the tracked seed schema" "$(file 'saiku-launcher/src/main/resources/seed/FoodMart4.xml')"
expect edit allow "write a Java source file" "$(file 'saiku-core/saiku-service/src/main/java/org/saiku/Foo.java')"

# --- the guard cannot widen itself -------------------------------------------
expect edit block "rewrite its own settings" "$(file '.claude/settings.json')"
expect edit block "write local settings" "$(file '.claude/settings.local.json')"

# --- malformed input must not crash or block ---------------------------------
expect bash allow "empty command" '{"tool_input":{}}'
expect bash allow "unparseable payload" 'not json at all'

printf '\n%d passed, %d failed\n' "$pass" "$fail"
[ "$fail" = 0 ]
