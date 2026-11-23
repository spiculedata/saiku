#!/usr/bin/env bash
# Quality report — renders the repository's quality signals as a Markdown
# fragment for the job summary produced by .github/workflows/quality-report.yml
# (saiku#2098).
#
# This is a REPORT, not a gate. The hard gates already exist and run on every
# PR:
#   - test-count floors  → .github/test-floors.json, enforced by ci.yml
#   - coverage floors    → .coverage-thresholds.json, enforced by scripts/check-coverage.sh
#   - formatting         → spotless:check, bound to the `verify` phase
# The point here is that those gates tell you a signal BROKE, not where it
# stands and by how much. This script renders them as a table so a human can
# audit the trend from a single page (Actions → the run's summary).
#
# Reads only build output that `mvn verify` already wrote:
#   <module>/target/surefire-reports/TEST-*.xml   test/failure/error/skip counts
#   <module>/target/site/jacoco/jacoco.csv        line coverage
# Missing reports are reported as "not measured" rather than treated as zero —
# a module whose tests didn't run is a different thing from a module with no
# tests, and conflating them makes the trend lie.
#
# Usage: ./scripts/quality-report.sh [--strict] [--title "Java"]
#   --strict  exit 1 if any declared signal is below its floor (for local
#              use and ad-hoc verification; the workflow does not use it —
#              ci.yml is the gate)
# Writes the fragment to stdout.
set -euo pipefail

cd "$(git rev-parse --show-toplevel 2>/dev/null || echo "$(dirname "$0")/..")"

TEST_FLOORS=.github/test-floors.json
COV_FLOORS=.coverage-thresholds.json
STRICT=0
TITLE="Java"

while [ $# -gt 0 ]; do
  case "$1" in
    --strict) STRICT=1 ;;
    --title) TITLE="${2:?--title needs a value}"; shift ;;
    -h | --help)
      sed -n '2,25p' "$0"
      exit 0
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 2
      ;;
  esac
  shift
done

command -v jq >/dev/null || {
  echo "quality-report: jq is required (present on GitHub runners)" >&2
  exit 2
}

# Sum surefire's per-class attributes across every TEST-*.xml in a module.
# $1 = module path → echoes "tests failures errors skipped", or nothing when the
# module produced no reports at all.
surefire_counts() {
  local module="$1" reports="$1/target/surefire-reports"
  [ -d "$reports" ] || return 0
  set -- "$reports"/TEST-*.xml
  [ -e "$1" ] || return 0
  local attr
  for attr in tests failures errors skipped; do
    grep -hoE "${attr}=\"[0-9]+\"" "$@" \
      | grep -oE '[0-9]+' | awk -v a="$attr" '{s+=$1} END {printf "%s ", s+0}'
  done
}

# Line coverage from jacoco.csv, printed with 2 decimals. Columns:
# GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,
# BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED
line_coverage() {
  local csv="$1/target/site/jacoco/jacoco.csv"
  [ -f "$csv" ] || return 0
  awk -F, 'NR > 1 { missed += $8; covered += $9 }
           END { total = missed + covered; printf "%.2f", total ? 100 * covered / total : 0 }' "$csv"
}

regressions=0
unmeasured=0
notes=()

printf '### %s\n\n' "$TITLE"
printf '| Module | Tests | Floor | Headroom | Fail | Err | Skip | Line cov. | Cov. floor | Status |\n'
printf '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | :---: |\n'

# Union of the two floor files, so a module missing from one of them still
# shows up (as "—" for that column) instead of vanishing from the report.
modules=$(jq -r '(.modules // {}) | keys[]' "$TEST_FLOORS" "$COV_FLOORS" 2>/dev/null | sort -u)

while IFS= read -r module; do
  [ -n "$module" ] || continue
  test_floor=$(jq -r --arg m "$module" '.modules[$m] // empty' "$TEST_FLOORS")
  cov_floor=$(jq -r --arg m "$module" '.modules[$m] // empty' "$COV_FLOORS")

  counts=$(surefire_counts "$module")
  total=$(printf '%s' "$counts" | cut -d' ' -f1)
  failed=$(printf '%s' "$counts" | cut -d' ' -f2)
  errored=$(printf '%s' "$counts" | cut -d' ' -f3)
  skipped=$(printf '%s' "$counts" | cut -d' ' -f4)
  cov=$(line_coverage "$module")

  row_status="ok"
  row_note=""
  if [ -z "$total" ]; then
    row_status="not measured"
    row_note="no surefire report — did the module's tests run?"
    unmeasured=$((unmeasured + 1))
    notes+=("\`$module\` — $row_note")
  else
    if [ -n "$test_floor" ]; then
      headroom=$((total - test_floor))
      if [ "$headroom" -lt 0 ]; then
        row_status="below test floor"
        regressions=$((regressions + 1))
      fi
    fi
    if [ -n "$cov_floor" ] && [ -n "$cov" ]; then
      if awk -v c="$cov" -v f="$cov_floor" 'BEGIN { exit !(c + 0 < f + 0) }'; then
        row_status="below coverage floor"
        regressions=$((regressions + 1))
      fi
    fi
  fi

  icon="✅"
  case "$row_status" in
    ok) ;;
    "not measured") icon="⚪" ;;
    *) icon="⚠️" ;;
  esac

  printf '| `%s` | %s | %s | %s | %s | %s | %s | %s | %s | %s |\n' \
    "$module" \
    "${total:-—}" \
    "${test_floor:-—}" \
    "$(if [ -n "$total" ] && [ -n "$test_floor" ]; then printf '%+d' "$((total - test_floor))"; else printf '—'; fi)" \
    "${failed:-—}" "${errored:-—}" "${skipped:-—}" \
    "$(if [ -n "$cov" ]; then printf '%s%%' "$cov"; else printf '—'; fi)" \
    "$(if [ -n "$cov_floor" ]; then printf '%s%%' "$cov_floor"; else printf '—'; fi)" \
    "$icon"
done <<<"$modules"

printf '\n'
if [ ${#notes[@]} -gt 0 ]; then
  printf 'Notes:\n'
  for note in "${notes[@]}"; do
    printf -- '- %s\n' "$note"
  done
  printf '\n'
fi
if [ "$regressions" -gt 0 ]; then
  printf '**Verdict: %s signal(s) below floor.** A PR that caused this would be blocked by `ci.yml`; this report shows the drift, `ci.yml` decides.\n' "$regressions"
elif [ "$unmeasured" -gt 0 ]; then
  printf '**Verdict: no floor is breached, but %s module(s) were not measured** — the numbers above are incomplete, not good news.\n' "$unmeasured"
else
  printf '**Verdict: every measured signal is at or above its floor.**\n'
fi

if [ "$STRICT" -eq 1 ] && [ "$regressions" -gt 0 ]; then
  exit 1
fi