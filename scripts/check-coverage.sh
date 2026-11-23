#!/usr/bin/env bash
# Coverage gate (saiku#2011). Fails if any module's JaCoCo line coverage drops
# below its floor in .coverage-thresholds.json. Run after `mvn verify`, which
# writes target/site/jacoco/jacoco.csv for every module with tests.
#
# Floors are ratchets, not targets: raise one explicitly when coverage goes up.
set -euo pipefail

cd "$(dirname "$0")/.."
THRESHOLDS=.coverage-thresholds.json
fail=0

while IFS=$'\t' read -r module floor; do
  csv="${module}/target/site/jacoco/jacoco.csv"
  if [[ ! -f "$csv" ]]; then
    echo "::error::No JaCoCo report for ${module} (${csv}) — did mvn verify run its tests?"
    fail=1
    continue
  fi
  # Columns: GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,
  #          BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,...
  pct=$(awk -F, 'NR > 1 { missed += $8; covered += $9 }
                 END { total = missed + covered; printf "%.2f", total ? 100 * covered / total : 0 }' "$csv")
  echo "  ${module}: ${pct}% line coverage (floor ${floor}%)"
  if awk -v p="$pct" -v f="$floor" 'BEGIN { exit !(p < f) }'; then
    echo "::error::${module} line coverage ${pct}% is below floor ${floor}% — add tests or justify lowering the floor"
    fail=1
  fi
done < <(jq -r '.modules | to_entries[] | "\(.key)\t\(.value)"' "$THRESHOLDS")

exit "$fail"
