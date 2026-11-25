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

# --- refspec destinations and compound lines (issue #2549) -------------------
expect bash block "push HEAD:development" "$(cmd 'git push origin HEAD:development')"
expect bash block "push feature/x:main" "$(cmd 'git push origin feature/x:main')"
expect bash block "push +HEAD:development" "$(cmd 'git push origin +HEAD:development')"
expect bash block "delete development" "$(cmd 'git push origin :development')"
expect bash block "push development after cd" "$(cmd 'cd . && git push origin development')"
expect bash allow "push HEAD onto a feature branch" "$(cmd 'git push origin HEAD:feature/x')"

# --- force in disguise -------------------------------------------------------
expect bash block "push a +refspec" "$(cmd 'git push origin +branch')"
expect bash block "force inside combined short flags" "$(cmd 'git push -fu origin branch')"
expect bash block "push --mirror" "$(cmd 'git push --mirror')"
expect bash block "env mvn exec" "$(cmd 'env mvn exec:exec -Dexec.executable=/bin/sh')"

# --- compound lines are scanned whole ----------------------------------------
expect bash block "commit without -s after true" "$(cmd 'true; git commit -m x')"
expect bash block "gh pr merge --admin after cd" "$(cmd 'cd . && gh pr merge 1 --admin')"
expect bash allow "an unrelated --grep does not trip the DCO rule" "$(cmd 'git log --grep commit')"

# --- operators without spaces, quoting and indirection (review item 1) -------
expect bash block "no space before &&" "$(cmd 'cd .&&git push origin development')"
expect bash block "no space after &&" "$(cmd 'cd . &&git push origin development')"
expect bash block "semicolon before echo" "$(cmd 'git push origin HEAD:development; echo ok')"
expect bash block "parenthesised subshell" "$(cmd '(git push origin development)')"
expect bash block "command substitution" "$(cmd 'echo \$(git push origin development)')"
expect bash block "backtick substitution" "$(cmd 'echo `git push origin development`')"
expect bash block "quotes hide a compound" "$(cmd 'bash -c "git push origin HEAD:main"')"
expect bash block "absolute path to git" "$(cmd '/usr/bin/git push origin development')"
expect bash block "mvn deploy without space" "$(cmd 'cd x;mvn deploy')"
expect bash block "trailing semicolon after deploy" "$(cmd 'mvn clean deploy;')"
expect bash block "gh --admin before semicolon" "$(cmd 'gh pr merge 1 --admin;')"
expect bash block "gh --admin in a subshell" "$(cmd '(gh pr merge 1 --admin)')"
expect bash block "gh --admin=true (pflag bool)" "$(cmd 'gh pr merge 1 --admin=true')"
expect bash block "quoted +refspec" "$(cmd "git push origin '+feat'")"

# --- mvn exec is never agent-safe, however it is spelled (review item 2) -----
expect bash block "mvn exec after a safe goal" "$(cmd 'mvn verify exec:exec -Dexec.executable=/bin/sh')"
expect bash block "fully-qualified exec plugin goal" "$(cmd 'mvn org.apache.maven.plugins:maven-exec-maven-plugin:3.0.0:exec')"
expect bash block "mvn help:evaluate exec spelling" "$(cmd 'mvn help:evaluate verify:exec')"
expect bash allow "mvn verify stays allowed" "$(cmd 'mvn -B -ntp verify')"
expect bash allow "mvn spotless:apply stays allowed" "$(cmd 'mvn spotless:apply')"

# --- credential files are out of bounds in any spelling (review item 4) ------
expect bash block "cat -n .env" "$(cmd 'cat -n .env')"
expect bash block "cat -- .env" "$(cmd 'cat -- .env')"
expect bash block "cat HOME/.ssh/id_rsa" "$(cmd 'cat \$HOME/.ssh/id_rsa')"
expect bash block "cat an absolute aws credentials path" "$(cmd 'cat /Users/me/.aws/credentials')"
expect bash block "grep an .npmrc" "$(cmd 'grep proxy ~/.npmrc')"
expect bash allow "cat an ordinary file" "$(cmd 'cat README.md')"
expect bash allow "git commands unaffected by the credential rule" "$(cmd 'git log --oneline -5')"

# --- case-variant paths cannot edit the guard or its settings (review item 3)-
expect edit block "case-variant settings path" "$(file '.Claude/Settings.json')"
expect edit block "upper-case settings filename" "$(file '.claude/SETTINGS.JSON')"
expect edit block "case-variant guard path" "$(file 'scripts/Agent-Guard.sh')"

# --- fully-qualified deploy goals --------------------------------------------
expect bash block "fully-qualified deploy plugin goal" "$(cmd 'mvn org.apache.maven.plugins:maven-deploy-plugin:3.1.4:deploy')"
expect bash block "env mvn deploy" "$(cmd 'env mvn deploy')"
expect bash block "mvn deploy after cd" "$(cmd 'cd x && mvn deploy')"

# --- write gates -------------------------------------------------------------
expect bash block "gh pr merge --admin" "$(cmd 'gh pr merge 42 --admin --squash')"
expect bash allow "gh pr create" "$(cmd 'gh pr create --title x --body y')"

# --- runtime home is never the source of truth -------------------------------
expect edit block "write under saiku-home" "$(file 'saiku-home/data/FoodMart4.xml')"
expect edit block "write under a nested saiku-home" "$(file '/tmp/x/saiku-home/repository/foo.saikuapp')"
expect edit allow "write the tracked seed schema" "$(file 'saiku-launcher/src/main/resources/seed/FoodMart4.xml')"
expect edit allow "write a Java source file" "$(file 'saiku-core/saiku-service/src/main/java/org/saiku/Foo.java')"

# --- edit paths are canonicalised (issue #2549) -------------------------------
expect edit block "absolute settings path" "$(file '/abs/path/to/repo/.claude/settings.json')"
expect edit block "settings via .." "$(file '.claude/../.claude/settings.json')"
expect edit allow "absolute path to an ordinary file" "$(file '/abs/path/to/repo/saiku-core/saiku-service/src/main/java/org/saiku/Foo.java')"

# --- the guard cannot widen itself -------------------------------------------
expect edit block "rewrite its own settings" "$(file '.claude/settings.json')"
expect edit block "write local settings" "$(file '.claude/settings.local.json')"
expect edit block "rewrite the guard script itself" "$(file 'scripts/agent-guard.sh')"
expect edit block "rewrite the guard script by absolute path" "$(file '/abs/path/to/repo/scripts/agent-guard.sh')"
expect edit allow "an ordinary script beside the guard" "$(file 'scripts/other-tool.sh')"

# --- malformed input must not crash or block ---------------------------------
expect bash allow "empty command" '{"tool_input":{}}'
expect bash allow "unparseable payload" 'not json at all'

printf '\n%d passed, %d failed\n' "$pass" "$fail"
[ "$fail" = 0 ]
