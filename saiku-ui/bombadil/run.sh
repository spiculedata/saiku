#!/usr/bin/env bash
#
# Fuzz the Saiku UI with Bombadil (antithesishq/bombadil).
#
# Bombadil drives a headless Chromium and autonomously explores the UI, checking
# the properties in spec.ts.
#
# ── Why a pinned Chrome (not the system one) ────────────────────────────────
# bombadil 0.6.1 speaks an older Chrome DevTools Protocol. Driving a bleeding-
# edge system Chrome (e.g. Chrome 150) floods `chromiumoxide WS Invalid message`
# and fails to instrument the page ("no actions available"). So we drive a pinned
# **Chrome for Testing** build via bombadil's `test-external` mode: we launch it
# ourselves with a remote-debugging port + isolated profile, and point bombadil
# at it. CfT 131 is verified CDP-clean against bombadil 0.6.1.
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
#   CHROME_BIN=/path/to/chrome npm run fuzz   # use a specific Chrome-for-Testing binary
#
# See ./README.md for how to read the results.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UI_ROOT="$(cd "${HERE}/.." && pwd)"
TARGET="${FUZZ_TARGET:-http://localhost:8080/ui/}"
TIME_LIMIT="${FUZZ_TIME:-2m}"
SPEC="${HERE}/spec.ts"
OUT="${HERE}/out"
CHROME_VERSION="${CHROME_VERSION:-131.0.6778.204}"
DEBUG_PORT="${FUZZ_DEBUG_PORT:-9333}"

# ── 1. Resolve a compatible Chrome for Testing binary (download once, cached) ──
CHROME="${CHROME_BIN:-}"
if [[ -z "$CHROME" ]]; then
  CACHE="${UI_ROOT}/.cache-chrome"
  CHROME="$(find "$CACHE" -name 'Google Chrome for Testing' -type f 2>/dev/null | head -1 || true)"
  if [[ -z "$CHROME" ]]; then
    echo "▶ Downloading Chrome for Testing ${CHROME_VERSION} (one-time; bombadil needs a CDP-compatible build)…" >&2
    npx -y @puppeteer/browsers install "chrome@${CHROME_VERSION}" --path "$CACHE" >&2
    CHROME="$(find "$CACHE" -name 'Google Chrome for Testing' -type f 2>/dev/null | head -1 || true)"
  fi
fi
if [[ -z "$CHROME" || ! -x "$CHROME" ]]; then
  echo "✗ No Chrome-for-Testing binary found. Set CHROME_BIN=/path/to/'Google Chrome for Testing'." >&2
  exit 1
fi

# ── 2. Session cookie ─────────────────────────────────────────────────────────
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

# ── 3. Launch the pinned Chrome (isolated profile + debug port) ────────────────
PROFILE="$(mktemp -d)"
"$CHROME" --headless=new --remote-debugging-port="$DEBUG_PORT" --remote-allow-origins='*' \
  --user-data-dir="$PROFILE" --no-first-run --no-default-browser-check --disable-gpu \
  >/dev/null 2>&1 &
CHROME_PID=$!
cleanup() { kill "$CHROME_PID" 2>/dev/null || true; rm -rf "$PROFILE" 2>/dev/null || true; }
trap cleanup EXIT

for _ in $(seq 1 40); do
  curl -s "http://127.0.0.1:${DEBUG_PORT}/json/version" >/dev/null 2>&1 && break
  sleep 0.25
done

BOMBADIL="${UI_ROOT}/node_modules/.bin/bombadil"
[[ -x "$BOMBADIL" ]] || BOMBADIL="bombadil"

echo "▶ Fuzzing ${TARGET} for ${TIME_LIMIT} via Chrome for Testing ${CHROME_VERSION} (spec: spec.ts)…"
echo "  Output → ${OUT}  ·  inspect with: ${BOMBADIL} inspect ${OUT}/trace.jsonl"

# `--chrome-grant-permissions ''` : bombadil's default grants `local-network-access`,
#   which only exists in Chrome ~138+ and errors on CfT 131 (CDP -32602).
# `--instrument-javascript inline` : don't intercept the app's external JS bundles
#   (that interception is the flaky part); inline-only keeps the app hydrating.
exec "$BOMBADIL" browser test-external "$TARGET" "$SPEC" \
  --remote-debugger "http://127.0.0.1:${DEBUG_PORT}" \
  --create-target \
  --time-limit="$TIME_LIMIT" \
  --chrome-grant-permissions '' \
  --instrument-javascript inline \
  --header "Cookie=${COOKIE_HEADER}" \
  --output-path "$OUT" \
  --output-path-overwrite
