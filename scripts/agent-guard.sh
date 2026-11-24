#!/usr/bin/env bash
# agent-guard.sh — PreToolUse guard for coding agents working in this repository.
#
# Mechanical enforcement of the rules this repo states in prose (AGENTS.md,
# CONTRIBUTING.md, .agents/skills/) so an agent cannot skip them by not reading
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

# True when the command invokes $1 anywhere in it — not only as its first
# token — so a compound line like `cd x && git push …` is still scanned.
invokes() {
  tokens "$2" | grep -qx -- "$1"
}

# Print every token that follows an occurrence of the sub-command $1 (all
# occurrences, so `git push a && git push b` yields a, &&, git, push, b).
subcommand_args() {
  local sub="$1" after=0 t
  while IFS= read -r t; do
    if [ "$after" = 1 ]; then
      printf '%s\n' "$t"
      continue
    fi
    case "$t" in
    "$sub") after=1 ;;
    esac
  done < <(tokens "$2")
}

# True when token $2 immediately follows token $1 on the command line.
adjacent() {
  local prev="" t
  while IFS= read -r t; do
    if [ "$prev" = "$1" ] && [ "$t" = "$2" ]; then
      return 0
    fi
    prev="$t"
  done < <(tokens "$3")
  return 1
}

# Print the destination ref of every push refspec on the command line. A
# refspec is [+]<src>[:<dst>]; the destination is what moves, so that is what
# the protected-branch rule must check — `HEAD:development`, `:development`
# (a deletion) and `feature/x:main` all name the destination, not the text
# after the last `/` of the source.
push_destinations() {
  local a src dst
  while IFS= read -r a; do
    a="${a#+}" # the leading + of a forced refspec is not part of the ref
    case "$a" in
    --*) continue ;; # an option, possibly --force-with-lease=<ref>:<value>
    *:*)
      src="${a%%:*}"
      dst="${a#*:}"
      [ -n "$dst" ] || dst="$src" # "<src>:" pushes to the same name
      if [ -n "$dst" ]; then
        printf '%s\n' "$dst"
      fi
      ;;
    *)
      if [ -n "$a" ]; then
        printf '%s\n' "$a"
      fi
      ;;
    esac
  done < <(subcommand_args push "$1")
}

# Canonicalise a tool path the way an editor resolves it: expand ~, drop "."
# segments and collapse "a/../" — purely lexical, because the target may not
# exist yet. Relative and absolute paths both come back with a leading "/", so
# the suffix matches below cover both spellings.
canon_path() {
  local p="$1" out="" seg
  case "$p" in
  # SC2088: these are case patterns matched literally, not expansions.
  # shellcheck disable=SC2088
  "~") p="$HOME" ;;
  # shellcheck disable=SC2088
  "~/"*) p="$HOME/${p#~/}" ;;
  esac
  while IFS= read -r seg; do
    case "$seg" in
    "" | ".") continue ;;
    "..") out="${out%/*}" ;;
    *) out="$out/$seg" ;;
    esac
  done < <(printf '%s\n' "$p" | tr '/' '\n')
  printf '%s' "$out"
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
    done < <(push_destinations "$command")

    # Rule 2 — plain force pushes are never an agent's call. -f counts inside
    # combined short flags (-fu), a leading + on a refspec forces, and
    # --mirror rewrites every ref. --force-with-lease stays allowed.
    push_args="$(subcommand_args push "$command")"
    if printf '%s\n' "$push_args" | grep -qx -e '--force' -e '--mirror' -e '-[[:alpha:]]*f[[:alpha:]]*' ||
      printf '%s\n' "$push_args" | grep -q '^+'; then
      block "'git push --force' is blocked. Use --force-with-lease if a rewrite is genuinely required, and say why in the PR."
    fi

    # Rule 3 — DCO. Every commit carries a Signed-off-by trailer. "commit" is
    # matched in sub-command position (right after git) so an unrelated
    # --grep=commit argument does not trip the rule.
    if adjacent git commit "$command"; then
      if [ "$(tokens "$command" | grep -cx -e '--signoff' -e '-s')" = 0 ] ||
        [ "$(tokens "$command" | grep -cx -- '--no-signoff')" != 0 ]; then
        block "'git commit' without a sign-off trailer is blocked. Use 'git commit -s' (DCO requires Signed-off-by)."
      fi
    fi
  fi

  # Rule 4 — releases are CI-driven. .agents/skills/cut-release/SKILL.md step 1:
  # "Never `mvn deploy` locally." Fully-qualified plugin goals count too
  # (org.apache.maven.plugins:maven-deploy-plugin:3.1.4:deploy).
  if invokes mvn "$command" || invokes ./mvnw "$command"; then
    while IFS= read -r goal; do
      case "$goal" in
      deploy | deploy:* | *:deploy)
        block "'mvn deploy' is blocked — publishing happens in CI when a v* tag is pushed (see .agents/skills/cut-release/SKILL.md)."
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
  norm="$(canon_path "$path")"

  # Rule 6 — saiku-home/ is runtime state, seeded-if-absent and gitignored.
  # Editing it never ships and silently diverges from the tracked seed.
  case "$norm" in
  *saiku-home/* | */saiku-home)
    block "'$norm' is under saiku-home/, which is per-machine runtime state and is never seeded again once it exists. Edit the tracked source instead (see AGENTS.md, 'Mondrian 4 virtual cubes')."
    ;;
  esac

  # Rule 7 — an agent may not widen its own permissions. The patterns match a
  # suffix of the canonicalised path, so absolute and ../ spellings are caught
  # too (editing some other checkout's settings is denied as well — this
  # errors towards blocking, which is the safe direction for this rule).
  case "$norm" in
  *"/.claude/settings.json" | *"/.claude/settings.local.json" | *"/.claude/settings.json."*)
    block "'$norm' is this guard's own configuration. Changing it is a maintainer decision — raise it in a PR instead of editing it in-session."
    ;;
  esac

  # Rule 8 — the guard cannot weaken itself: the hook reads this script on
  # every call, so editing it in-session widens permissions exactly as
  # editing its settings would.
  case "$norm" in
  *"scripts/agent-guard.sh")
    block "'$norm' is this guard. Changing it is a maintainer decision — raise it in a PR instead of editing it in-session."
    ;;
  esac
  ;;

*) exit 0 ;;
esac

exit 0
