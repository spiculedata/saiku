#!/usr/bin/env bash
# Saiku launcher wrapper (macOS / Linux).
#
# Locates the saiku-*.jar shipped alongside this script and forwards every CLI
# argument straight to Saiku's Picocli entry point. The first invocation seeds
# saiku-home/ next to this script and runs the H2 FoodMart bootstrap (~30 s,
# ~200 MB written under saiku-home/data/). Subsequent runs reuse it.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=$(ls "$SCRIPT_DIR"/saiku-*.jar 2>/dev/null | head -n1)

if [[ -z "$JAR" ]]; then
  echo "error: no saiku-*.jar found in $SCRIPT_DIR" >&2
  exit 1
fi

# Default to a saiku-home that lives next to the JAR (not the user's CWD).
exec java -jar "$JAR" serve --home "$SCRIPT_DIR/saiku-home" "$@"
