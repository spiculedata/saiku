#!/usr/bin/env bash
# Acceptance spec for spiculedata/saiku#2549 (kind: ui / command — the contract
# is repository tooling, so there is no endpoint to call). Runs in this
# directory, with the checkout under test two levels up: it drives
# scripts/agent-guard.sh with the tool payloads the issue names, checks
# .claude/settings.json for the narrowed permission lists, and checks AGENTS.md
# matches the behaviour. Exits non-zero on the first broken clause, with the
# reason on stderr, so the runner's "exit code" detail says what broke.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
guard="$repo/scripts/agent-guard.sh"
settings="$repo/.claude/settings.json"
agents_md="$repo/AGENTS.md"

fail() {
  printf 'acceptance/2549: %s\n' "$1" >&2
  exit 1
}

guard_verdict() { # <mode> <payload value> -> the guard's exit code
  local mode="$1" value="$2" key=command
  [ "$mode" = edit ] && key=file_path
  printf '{"tool_input":{"%s":"%s"}}' "$key" "$value" | bash "$guard" "$mode" >/dev/null 2>&1
}

must_block() {
  local got
  guard_verdict "$1" "$2"
  got=$?
  [ "$got" = 2 ] || fail "the guard must DENY '$2' (mode $1) but it exited $got"
}

must_allow() {
  local got
  guard_verdict "$1" "$2"
  got=$?
  [ "$got" = 0 ] || fail "the guard must ALLOW '$2' (mode $1) but it exited $got"
}

section() { # <name> — print one permissions section of settings.json
  awk -v s="\"$1\": [" 'index($0, s) { f = 1; next } f && /]/ { f = 0 } f' "$settings"
}

in_section() { # <section> <fixed string>
  section "$1" | grep -F -q "$2"
}

[ -f "$guard" ] || fail 'scripts/agent-guard.sh is missing'
[ -f "$settings" ] || fail '.claude/settings.json is missing'
[ -f "$agents_md" ] || fail 'AGENTS.md is missing'

# C1 — every step in the issue is denied by the guard.
must_block bash 'git push origin HEAD:development'
must_block bash 'git push origin feature/x:main'
must_block bash 'git push origin +HEAD:development'
must_block bash 'git push origin :development'
must_block bash 'cd . && git push origin development'
must_block bash 'git push origin +branch'
must_block bash 'git push -fu origin branch'
must_block bash 'git push --mirror'
must_block bash 'true; git commit -m x'
must_block bash 'mvn org.apache.maven.plugins:maven-deploy-plugin:3.1.4:deploy'
must_block bash 'env mvn deploy'
must_block bash 'cd x && mvn deploy'
must_block bash 'cd . && gh pr merge 1 --admin'
must_block edit '/abs/path/to/repo/.claude/settings.json'
must_block edit '.claude/../.claude/settings.json'
must_block edit 'scripts/agent-guard.sh'

# C3 — routine read-only work still runs without the guard in the way.
must_allow bash 'git status'
must_allow bash 'mvn verify'
must_allow bash 'gh pr view'
must_allow bash 'git push upstream feature/foo'
must_allow bash 'git push --force-with-lease upstream feature/foo'
must_allow bash 'git log --grep commit'
must_allow edit 'saiku-core/saiku-service/src/main/java/org/saiku/Foo.java'

# C2 — settings no longer blanket-allow gh api or mvn; mvn exec is ask-first;
# Bash reads of the denied secret paths are denied.
if in_section allow 'Bash(gh api:*)'; then fail 'allow still contains Bash(gh api:*)'; fi
if ! in_section ask 'Bash(gh api:*)'; then fail 'ask does not contain Bash(gh api:*)'; fi
if in_section allow 'Bash(mvn:*)'; then fail 'allow still contains the blanket Bash(mvn:*)'; fi
if in_section allow 'Bash(mvn -B:*)'; then fail 'allow still contains Bash(mvn -B:*) — mvn -B exec:exec slips through it'; fi
if in_section allow 'Bash(mvn -pl:*)'; then fail 'allow still contains Bash(mvn -pl:*) — mvn -pl x exec:exec slips through it'; fi
if ! in_section ask 'Bash(mvn exec:*)'; then fail 'ask does not contain Bash(mvn exec:*)'; fi
if ! in_section deny 'Bash(cat .env:*)'; then fail 'deny has no Bash(cat .env:*) mirror of the Read deny'; fi
if ! in_section deny 'Bash(grep * .env:*)'; then fail 'deny has no Bash(grep * .env:*) mirror of the Read deny'; fi
if ! in_section deny 'Bash(cat ~/.m2/settings.xml:*)'; then fail 'deny has no Bash(cat ~/.m2/settings.xml:*) mirror'; fi

# C4 — AGENTS.md "Permissions and guard" matches the behaviour.
grep -F -q 'gh api' "$agents_md" || fail 'AGENTS.md does not document the gh api narrowing'
grep -F -q 'mvn exec' "$agents_md" || fail 'AGENTS.md does not document the mvn exec ask'
grep -F -q 'scripts/agent-guard.sh' "$agents_md" || fail 'AGENTS.md does not say the guard script itself is protected'

# The guard's own suite is green with the fix in place.
if ! bash "$repo/scripts/agent-guard.test.sh" >/dev/null 2>&1; then
  fail 'scripts/agent-guard.test.sh fails'
fi

exit 0
