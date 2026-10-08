#!/usr/bin/env bash
# agent-guard.sh — PreToolUse guard for coding agents working in this repository.
#
# Mechanical enforcement of the rules this repo states in prose (AGENTS.md,
# CONTRIBUTING.md, .claude/skills/) so an agent cannot skip them by not reading
# the docs. Wired in from `.claude/settings.json`; run directly it is also usable
# from any harness:
#
#   echo '{"tool_input":{"command":"git push upstream main"}}' | scripts/agent-guard.sh bash
#   echo '{"tool_input":{"file_path":"saiku-home/data/FoodMart4.xml"}}' | scripts/agent-guard.sh edit
#
# Protocol: read the tool payload as JSON on stdin, print a reason on stderr and
# exit 2 to BLOCK the call, exit 0 to allow it. (Claude Code treats exit 2 as a
# refusal and shows stderr to the model.)
#
# Tests: scripts/agent-guard.test.sh
set -uo pipefail

mode="${1:-bash}"
payload="$(cat)"

# Read one field out of .tool_input. jq when present, python3 otherwise, and a
# crude sed fallback so the guard never silently no-ops because a tool is absent.
json_get() {
  local key="$1"
  if command -v jq >/dev/null 2>&1; then
    jq -r --arg k "$key" '(.tool_input[$k] // "")' <<<"$payload" 2>/dev/null
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c 'import json,sys
try:
    print(json.load(sys.stdin).get("tool_input", {}).get(sys.argv[1], "") or "")
except Exception:
    print("")' "$key" <<<"$payload" 2>/dev/null
  else
    sed -n "s/.*\"$key\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p" <<<"$payload"
  fi
}

block() {
  printf 'agent-guard: %s\n' "$1" >&2
  exit 2
}

# Tokenize a shell command. Approximate on purpose — it ignores quoting, so a
# refspec buried inside quotes is not seen. That errs towards allowing the call,
# which is the right way for a guard that exists to catch accidents.
tokens() {
  # shellcheck disable=SC2206 # deliberate word splitting into "$@" below
  local words=($1)
  printf '%s\n' "${words[@]}"
}

# True when the command invokes $1 as its first non-flag token.
invokes() {
  local want="$1" t
  while IFS= read -r t; do
    case "$t" in
    "$want") return 0 ;;
    -*) continue ;;
    *) return 1 ;;
    esac
  done < <(tokens "$2")
  return 1
}

# Print the arguments that follow the $1-th occurrence of the sub-command $2.
subcommand_args() {
  local want="$1" sub="$2" seen=0 t after=0
  while IFS= read -r t; do
    if [ "$after" = 1 ]; then
      printf '%s\n' "$t"
      continue
    fi
    case "$t" in
    "$sub")
      seen=$((seen + 1))
      [ "$seen" = "$want" ] && after=1
      ;;
    esac
  done < <(tokens "$3")
}

PROTECTED_BRANCHES='main master development'

case "$mode" in
bash)
  command="$(json_get command)"
  [ -z "$command" ] && exit 0

  # Rule 1 — never push straight to a protected branch. CONTRIBUTING.md: "Never
  # push directly to main or development — always PR."
  if invokes git "$command"; then
    while IFS= read -r ref; do
      local_ref="$ref"
      local_ref="${local_ref#refs/heads/}"
      local_ref="${local_ref##*/}" # upstream/development -> development
      for p in $PROTECTED_BRANCHES; do
        if [ "$local_ref" = "$p" ]; then
          block "'$ref' is a protected branch. Open a PR instead (CONTRIBUTING.md: never push directly to main or development)."
        fi
      done
    done < <(subcommand_args 1 push "$command")

    # Rule 2 — plain force pushes are never an agent's call.
    if [ "$(tokens "$command" | grep -cx -e '--force' -e '-f')" != 0 ]; then
      block "'git push --force' is blocked. Use --force-with-lease if a rewrite is genuinely required, and say why in the PR."
    fi

    # Rule 3 — DCO. Every commit carries a Signed-off-by trailer.
    if tokens "$command" | grep -qx -- commit; then
      if [ "$(tokens "$command" | grep -cx -e '--signoff' -e '-s')" = 0 ] ||
        [ "$(tokens "$command" | grep -cx -- '--no-signoff')" != 0 ]; then
        block "'git commit' without a sign-off trailer is blocked. Use 'git commit -s' (DCO requires Signed-off-by)."
      fi
    fi
  fi

  # Rule 4 — releases are CI-driven. .claude/skills/cut-release/SKILL.md step 1:
  # "Never `mvn deploy` locally."
  if invokes mvn "$command" || invokes ./mvnw "$command"; then
    while IFS= read -r goal; do
      case "$goal" in
      deploy | deploy:*)
        block "'mvn deploy' is blocked — publishing happens in CI when a v* tag is pushed (see .claude/skills/cut-release/SKILL.md)."
        ;;
      esac
    done < <(tokens "$command")
  fi

  # Rule 5 — never bypass a write gate.
  if invokes gh "$command" && [ "$(tokens "$command" | grep -cx -e '--admin')" != 0 ]; then
    block "'--admin' is blocked: it bypasses the branch-protection gate. Wait for the required checks, or ask a maintainer."
  fi
  ;;

edit | write)
  path="$(json_get file_path)"
  [ -z "$path" ] && exit 0
  norm="${path#./}"

  # Rule 6 — saiku-home/ is runtime state, seeded-if-absent and gitignored.
  # Editing it never ships and silently diverges from the tracked seed.
  case "$norm" in
  saiku-home/* | */saiku-home/*)
    block "'$norm' is under saiku-home/, which is per-machine runtime state and is never seeded again once it exists. Edit the tracked source instead (see AGENTS.md, 'Mondrian 4 virtual cubes')."
    ;;
  esac

  # Rule 7 — an agent may not widen its own permissions.
  case "$norm" in
  .claude/settings.json | .claude/settings.local.json | .claude/settings.json.*)
    block "'$norm' is this guard's own configuration. Changing it is a maintainer decision — raise it in a PR instead of editing it in-session."
    ;;
  esac
  ;;

*) exit 0 ;;
esac

exit 0
