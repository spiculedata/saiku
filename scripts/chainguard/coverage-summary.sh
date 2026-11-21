#!/usr/bin/env bash
# Turn a Chainguard-only resolution run into a Markdown job summary (saiku#2463).
#
#   coverage-summary.sh maven <maven-log> <maven-exit-code>
#   coverage-summary.sh npm   <npm-ci-log> <npm-ci-exit-code> <lockfile>
#
# Writes to $GITHUB_STEP_SUMMARY (or stdout when unset).
#
# Exit status:
#   0  the run completed or only reported coverage gaps (the report, not a gate - same posture as
#      quality-report.yml: a gap Chainguard has not built yet must not page anyone every Monday)
#   2  AUTH / CONFIG error: 401/403 from libraries.cgr.dev, missing credentials, or a failure we
#      cannot attribute to a missing artifact. These need a human (rotate a token, fix an entitlement).
set -uo pipefail

kind="${1:?usage: coverage-summary.sh maven|npm <log> <exit-code> [lockfile]}"
log="${2:?log file}"
rc="${3:?exit code}"
lockfile="${4:-}"
out="${GITHUB_STEP_SUMMARY:-/dev/stdout}"
monthly_quota=50000
status=0

emit() { printf '%s\n' "$@" >>"$out"; }
# shellcheck disable=SC2016  # backticks are literal Markdown
cap_list() { sort -u | head -n 200 | sed 's/^/- `/; s/$/`/'; }

is_auth_error() {
  grep -iE '(status code|status|HTTP/[0-9.]+|E)[: ]*(401|403)|unauthorized|forbidden|needs authentication' "$log" \
    | grep -iE 'cgr\.dev|chainguard|libraries' >/dev/null
}

case "$kind" in
maven)
  served=$(grep -c 'Downloaded from chainguard:' "$log" || true)
  gaps=$(grep -iE 'could not (find|transfer|resolve) artifact|could not be resolved|Non-resolvable|Failed to read artifact descriptor' "$log" \
    | grep -oE '[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+(:[A-Za-z0-9_.-]+){1,3}' \
    | grep -vE '^(org\.saikuanalytics):' | grep -vE '^(http|https|file):' | sort -u || true)
  gap_count=$(printf '%s' "$gaps" | grep -c . || true)
  model_failure=$(grep -c 'Non-resolvable \(import\|parent\) POM' "$log" || true)
  emit "## Chainguard Libraries coverage - Maven" ""
  emit "Resolved the dependency + plugin tree with \`mvn dependency:go-offline\` into an empty local repository, Maven Central mirrored to \`https://libraries.cgr.dev/java/\` and **no Central fallback in the client**. GitHub Packages repos (the Spicule forks) resolved as normal." ""
  emit "| | |" "|---|---|" "| Artifact downloads served by Chainguard | ${served} |" "| Distinct artifacts Chainguard could not serve | ${gap_count} |" "| Maven exit code | ${rc} |" ""
  if is_auth_error; then
    emit ":x: **Authentication / entitlement error** talking to libraries.cgr.dev (401/403). Check the \`CHAINGUARD_JAVA_*\` secrets (30-day TTL on Starter - rotate per docs/chainguard-libraries.md) and \`chainctl libraries entitlements list\`."
    status=2
  elif [ "$gap_count" -gt 0 ]; then
    emit "### Not served by Chainguard" ""
    printf '%s\n' "$gaps" | cap_list >>"$out"
    emit ""
    if [ "$model_failure" -gt 0 ]; then
      emit ":warning: Maven could not even build the project model (an import/parent POM is missing), so the list above stops at the **first** blocking artifact, not the full set. Typical causes: the version is inside the 7-day cooldown, Chainguard has not built it and upstream fallback is off for the JAVA entitlement (\`chainctl libraries entitlements list\`), or it comes from a repository other than Central."
    else
      emit "Each entry is either inside the 7-day cooldown, not built by Chainguard with upstream fallback off for the JAVA entitlement, or hosted outside Maven Central. A release build would fail on these. See docs/chainguard-libraries.md."
    fi
  elif [ "$rc" -ne 0 ]; then
    emit ":x: Maven failed but no missing artifact or auth error was recognised in the log - treating as a configuration error. Inspect the step log."
    status=2
  else
    emit ":white_check_mark: Every Maven Central artifact in the tree was served through Chainguard."
  fi
  emit "" "**Estimated pull usage this run:** about ${served} downloads (every POM/JAR counted as one pull, the worst case; a clean local repository fetches each file once). Weekly cadence = about $((served * 435 / 100)) a month against the ${monthly_quota}/month Starter allowance, before release builds."
  ;;
npm)
  total=$(jq '[.packages | to_entries[] | select(.key != "" and (.value.resolved // "") != "")] | length' "$lockfile")
  built=$(jq '[.packages | to_entries[] | select((.value.resolved // "") | startswith("https://libraries.cgr.dev/"))] | length' "$lockfile")
  gaps=$(jq -r '.packages | to_entries[] | select(.key != "" and ((.value.resolved // "") | startswith("https://libraries.cgr.dev/") | not) and (.value.resolved // "") != "") | "\(.key | sub("^.*node_modules/"; ""))@\(.value.version)"' "$lockfile" | sort -u)
  gap_count=$(printf '%s' "$gaps" | grep -c . || true)
  emit "## Chainguard Libraries coverage - npm (saiku-ui)" ""
  emit "\`npm ci --ignore-scripts\` against \`https://libraries.cgr.dev/javascript/\` with an empty cache. \`chainctl libraries update-hashes\` first marks which lockfile entries Chainguard builds itself." ""
  emit "| | |" "|---|---|" "| Lockfile packages | ${total} |" "| Built by Chainguard | ${built} |" "| Not built by Chainguard (upstream fallback or 404) | ${gap_count} |" "| \`npm ci\` exit code | ${rc} |" ""
  if is_auth_error; then
    emit ":x: **Authentication / entitlement error** talking to libraries.cgr.dev (401/403). Check the \`CHAINGUARD_JS_*\` secrets (30-day TTL on Starter) and \`chainctl libraries entitlements list\`."
    status=2
  elif [ "$rc" -eq 0 ]; then
    emit ":white_check_mark: \`npm ci\` succeeded entirely through Chainguard (${gap_count} packages came from Chainguard's upstream fallback)."
  elif grep -qE 'E404|404 Not Found|POLICY_COOLDOWN|MALWARE_DETECTED' "$log"; then
    emit ":warning: \`npm ci\` stopped on a package Chainguard did not serve (npm reports only the first). Chainguard-built packages are listed above as a count; the not-built set is below. With upstream fallback off for the JAVASCRIPT entitlement a release build would fail on these." ""
    emit "First error:" '```' "$(grep -E 'E404|404 Not Found|POLICY_COOLDOWN|MALWARE_DETECTED' "$log" | head -n 3)" '```'
  else
    emit ":x: \`npm ci\` failed for a reason that is not a missing package (see step log) - treating as a configuration error."
    status=2
  fi
  if [ "$gap_count" -gt 0 ]; then
    emit "" "<details><summary>Packages not built by Chainguard (${gap_count})</summary>" ""
    printf '%s\n' "$gaps" | cap_list >>"$out"
    emit "" "</details>"
  fi
  emit "" "**Estimated pull usage this run:** about ${total} tarball requests plus roughly the same number of metadata requests, so up to about $((total * 2)) pulls (upper bound). Weekly cadence = up to about $((total * 2 * 435 / 100)) a month against the ${monthly_quota}/month Starter allowance, before release builds."
  ;;
*)
  echo "unknown kind: $kind" >&2
  exit 64
  ;;
esac

exit "$status"
