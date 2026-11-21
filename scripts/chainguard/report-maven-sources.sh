#!/usr/bin/env bash
# Report which repository each cached Maven artifact came from (saiku#2463), so a release log can
# show how much was Chainguard-built vs fell back to Maven Central. Reads the `_remote.repositories`
# files Maven writes next to every artifact in the local repository; works with -ntp.
#
#   report-maven-sources.sh [local-repo]     default ~/.m2/repository
# Appends a Markdown table to $GITHUB_STEP_SUMMARY (or stdout). Never fails the build.
set -uo pipefail
repo="${1:-$HOME/.m2/repository}"
out="${GITHUB_STEP_SUMMARY:-/dev/stdout}"
[ -d "$repo" ] || { echo "no local repository at $repo" >>"$out"; exit 0; }

counts=$(find "$repo" -name _remote.repositories -not -path '*/org/saikuanalytics/*' -print0 \
  | xargs -0 cat 2>/dev/null | grep -E '\.(jar|pom)>' | sed -E 's/^.*>([^=]*)=.*$/\1/' | sort | uniq -c | sort -rn)

{
  echo "## Maven artifact sources (POM + JAR files)"
  echo
  echo "| Source repository | Files |"
  echo "|---|---|"
  printf '%s\n' "$counts" | awk 'NF {printf "| `%s` | %s |\n", $2, $1}'
  echo
  echo "\`chainguard-java\` = built by Chainguard Libraries; \`central\` = fell back to Maven Central; \`github-*\` = Spicule forks."
} >>"$out"
exit 0
