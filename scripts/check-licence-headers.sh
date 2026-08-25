#!/usr/bin/env bash
# Fails if any tracked Java source file is missing a licence header.
#
# Deliberately checks for the PRESENCE of a copyright/licence notice rather than
# for one exact string. The tree carries several legitimate headers that must not
# be homogenised:
#   - "Copyright <year> Spicule Ltd"            (the default for new files)
#   - "Copyright <year> OSBI Ltd"               (original Saiku copyright holder)
#   - "Copyright 2026 Paul Stoellberger / Spicule"
#   - the Pentaho Eclipse Public License header on the Mondrian override
#     (saiku-webapp/.../mondrian/rolap/SqlStatement.java - see NOTICE)
#
# This is why Spotless's <licenseHeader> is NOT used: it REPLACES whatever sits
# above the `package` line, which would strip the OSBI and Pentaho notices.
#
# Usage: ./scripts/check-licence-headers.sh [--fix]
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

HEADER='/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */'

FIX=0
[ "${1:-}" = "--fix" ] && FIX=1

missing=()
while IFS= read -r f; do
  [ -f "$f" ] || continue
  if ! head -30 "$f" | grep -qiE 'licen[cs]e|copyright|SPDX'; then
    missing+=("$f")
  fi
done < <(git ls-files '*.java')

if [ ${#missing[@]} -eq 0 ]; then
  echo "licence headers: OK ($(git ls-files '*.java' | wc -l | tr -d ' ') Java files)"
  exit 0
fi

if [ "$FIX" -eq 1 ]; then
  for f in "${missing[@]}"; do
    printf '%s\n%s\n' "$HEADER" "$(cat "$f")" > "$f.tmp" && mv "$f.tmp" "$f"
    echo "stamped $f"
  done
  echo "stamped ${#missing[@]} file(s)"
  exit 0
fi

echo "ERROR: ${#missing[@]} Java file(s) have no licence header:" >&2
printf '  %s\n' "${missing[@]}" >&2
echo >&2
echo "Run ./scripts/check-licence-headers.sh --fix to stamp them." >&2
exit 1
