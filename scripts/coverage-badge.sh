#!/usr/bin/env bash
# Writes the repo's headline line-coverage badge (coverage.json + coverage.svg) into
# the directory given as $1. Run after `mvn verify`, which writes
# target/site/jacoco/jacoco.csv for every module with tests.
#
# The figure is the aggregate JaCoCo line coverage of the modules gated in
# .coverage-thresholds.json (the same modules scripts/check-coverage.sh enforces), so
# the badge and the gate can never disagree about which code counts. Hive reads the
# published coverage.json for the ci-maintainer card (HIVE_COVERAGE_BADGE_URL).
set -euo pipefail

cd "$(dirname "$0")/.."
OUT="${1:?usage: coverage-badge.sh <output-dir>}"
THRESHOLDS=.coverage-thresholds.json

missed=0
covered=0
while IFS= read -r module; do
  csv="${module}/target/site/jacoco/jacoco.csv"
  if [[ ! -f "$csv" ]]; then
    echo "::error::No JaCoCo report for ${module} (${csv}) - did mvn verify run its tests?"
    exit 1
  fi
  # Columns: GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,
  #          BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,...
  read -r m c < <(awk -F, 'NR > 1 { m += $8; c += $9 } END { print m + 0, c + 0 }' "$csv")
  missed=$((missed + m))
  covered=$((covered + c))
done < <(jq -r '.modules | keys[]' "$THRESHOLDS")

total=$((missed + covered))
if (( total == 0 )); then
  echo "::error::No lines found in any gated JaCoCo report"
  exit 1
fi

pct=$(awk -v c="$covered" -v t="$total" 'BEGIN { printf "%.1f", 100 * c / t }')
color=$(awk -v p="$pct" 'BEGIN { print (p >= 80 ? "#4c1" : p >= 60 ? "#dfb317" : "#fe7d37") }')
message="${pct}%"

mkdir -p "$OUT"
jq -n --arg message "$message" --arg color "${color#\#}" \
  '{schemaVersion: 1, label: "coverage", message: $message, color: $color}' > "${OUT}/coverage.json"

cat > "${OUT}/coverage.svg" <<SVG
<svg xmlns="http://www.w3.org/2000/svg" width="104" height="20" role="img" aria-label="coverage: ${message}">
  <rect width="62" height="20" fill="#555"/>
  <rect x="62" width="42" height="20" fill="${color}"/>
  <g fill="#fff" font-family="Verdana,DejaVu Sans,sans-serif" font-size="11" text-anchor="middle">
    <text x="31" y="14">coverage</text>
    <text x="83" y="14">${message}</text>
  </g>
</svg>
SVG

echo "line coverage of gated modules: ${message} (${covered}/${total} lines)"
