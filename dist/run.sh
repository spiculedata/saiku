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

# Optional OpenTelemetry: drop opentelemetry-javaagent.jar next to this
# script and set OTEL_EXPORTER_OTLP_ENDPOINT to enable. See
# docs/observability.md for the download command and config reference.
JAVA_OPTS=()
OTEL_AGENT_JAR="$SCRIPT_DIR/opentelemetry-javaagent.jar"
if [[ -n "${OTEL_EXPORTER_OTLP_ENDPOINT:-}" && -f "$OTEL_AGENT_JAR" ]]; then
  JAVA_OPTS+=("-javaagent:$OTEL_AGENT_JAR")
  export OTEL_SERVICE_NAME="${OTEL_SERVICE_NAME:-saiku}"
fi

# Default to a saiku-home that lives next to the JAR (not the user's CWD).
#
# JVM safety defaults (saiku#1914, CWE-400): match the image's
# JAVA_TOOL_OPTIONS in the Dockerfile so a bare-metal / dist run gets the same
# OOM behaviour the container does.
#   -XX:+ExitOnOutOfMemoryError  fail fast on OOM instead of limping on a corrupt heap
#   -XX:MaxRAMPercentage=75      size the heap from the machine's RAM
# Both are overridable by prepending your own flags to JAVA_OPTS.
exec java -XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=75 "${JAVA_OPTS[@]}" \
  -jar "$JAR" serve --home "$SCRIPT_DIR/saiku-home" "$@"
