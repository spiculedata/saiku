#!/usr/bin/env bash
#
# Fuzz the Saiku UI with Bombadil (antithesishq/bombadil).
#
# Bombadil drives a headless browser and autonomously explores the UI, checking
# the properties in spec.ts.
#
# ── Chrome ──────────────────────────────────────────────────────────────────
# Uses bombadil's managed `browser test` mode, which drives the system Chrome.
# bombadil 0.6.1 emits noisy `chromiumoxide WS Invalid message` warnings against
# a newer Chrome, but they're NON-FATAL — exploration works fine (verified on
# Chrome 150). `--instrument-javascript inline` keeps the app hydrating: it
# avoids intercepting the app's external JS bundles (the flaky part). If a future
# Chrome update ever makes the CDP mismatch fatal, see issue #1639 for the
# pinned Chrome-for-Testing / `test-external` fallback.
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

# ── Session cookie ────────────────────────────────────────────────────────────
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

BOMBADIL="${UI_ROOT}/node_modules/.bin/bombadil"
[[ -x "$BOMBADIL" ]] || BOMBADIL="bombadil"

echo "▶ Fuzzing ${TARGET} for ${TIME_LIMIT} (spec: spec.ts)…"
echo "  Output → ${OUT}  ·  inspect with: ${BOMBADIL} inspect ${OUT}/trace.jsonl"

exec "$BOMBADIL" browser test "$TARGET" "$SPEC" \
  --headless \
  --time-limit="$TIME_LIMIT" \
  --instrument-javascript inline \
  --header "Cookie=${COOKIE_HEADER}" \
  --output-path "$OUT" \
  --output-path-overwrite
