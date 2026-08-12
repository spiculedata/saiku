#!/usr/bin/env bash
#
# Fuzz the Saiku UI with Bombadil (antithesishq/bombadil).
#
# Bombadil drives a headless Chrome and autonomously explores the UI, checking
# the properties in spec.ts.
#
# ── Browser: bombadil's MANAGED mode (system Chrome) ────────────────────────
# saiku#1639: this harness previously hand-launched a pinned Chrome-for-Testing
# 131 (~150 MB download into .cache-chrome/) and drove it via `test-external` +
# a remote-debugging port, on the belief that bombadil 0.6.1 couldn't drive a
# bleeding-edge system Chrome. That belief was wrong — A/B verified (154/159
# states, clean finishes) that the managed `browser test` mode drives system
# Chrome 150 fine; the `chromiumoxide WS Invalid message` flood is noisy but
# non-fatal. So: no pin, no download, no external launch. `--instrument-
# javascript inline` stays as a safety margin — it avoids intercepting the
# app's external JS bundles, which keeps hydration reliable. Note the managed
# mode has no browser-path flag (always system Chrome); if a future Chrome
# ever makes the CDP mismatch fatal, revisit pinning then.
#
# ── Auth ────────────────────────────────────────────────────────────────────
# The fuzzer must start authenticated. run.sh mints a Saiku session cookie
# (JSESSIONID) by logging in to the target (admin/admin) via mint-cookie.sh and
# injects it as a static Cookie header. Override with $FUZZ_COOKIE / $SAIKU_SESSION.
#
# Usage:
#   npm run fuzz                        # 2-min run vs http://localhost:8080/ui/
#   FUZZ_TIME=60m npm run fuzz          # fuzz for an hour
#   FUZZ_TARGET=http://localhost:8080/ui/ FUZZ_TIME=60m npm run fuzz
#
# See ./README.md for how to read the results.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UI_ROOT="$(cd "${HERE}/.." && pwd)"
TARGET="${FUZZ_TARGET:-http://localhost:8080/ui/}"
TIME_LIMIT="${FUZZ_TIME:-2m}"
SPEC="${HERE}/spec.ts"
OUT="${HERE}/out"

# ── 1. Session cookie ─────────────────────────────────────────────────────────
COOKIE_HEADER="${FUZZ_COOKIE:-}"
SESSION_FILE="${SAIKU_SESSION_FILE:-$HOME/.saiku/session}"
if [[ -z "$COOKIE_HEADER" ]]; then
  if [[ -z "${SAIKU_SESSION:-}" && -r "$SESSION_FILE" ]]; then
    SAIKU_SESSION="$(tr -d '[:space:]' <"$SESSION_FILE")"
  fi
  if [[ -n "${SAIKU_SESSION:-}" ]]; then
    COOKIE_HEADER="JSESSIONID=${SAIKU_SESSION}"
  else
    echo "▶ Minting a session cookie by logging in to ${TARGET}…" >&2
    COOKIE_HEADER="$(FUZZ_TARGET="$TARGET" bash "${HERE}/mint-cookie.sh")"
  fi
fi

# ── 2. Fuzz via the managed browser ───────────────────────────────────────────
BOMBADIL="${UI_ROOT}/node_modules/.bin/bombadil"
[[ -x "$BOMBADIL" ]] || BOMBADIL="bombadil"

echo "▶ Fuzzing ${TARGET} for ${TIME_LIMIT} via bombadil's managed browser (system Chrome; spec: spec.ts)…"
echo "  Output → ${OUT}  ·  inspect with: ${BOMBADIL} inspect ${OUT}/trace.jsonl"

exec "$BOMBADIL" browser test "$TARGET" "$SPEC" \
  --headless \
  --instrument-javascript inline \
  --time-limit="$TIME_LIMIT" \
  --header "Cookie=${COOKIE_HEADER}" \
  --output-path "$OUT" \
  --output-path-overwrite
