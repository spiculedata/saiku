#!/usr/bin/env bash
#
# Fuzz the Saiku UI with Bombadil (antithesishq/bombadil).
#
# Bombadil drives a headless Chromium and autonomously explores the UI, checking
# the properties in spec.ts. It must START authenticated (headless can't fill the
# login form and keep a session), so this harness injects a Saiku session cookie
# as a static `Cookie` request header.
#
# Cookie resolution, in order:
#   1. $FUZZ_COOKIE            — a complete Cookie header value, used verbatim.
#   2. $SAIKU_SESSION          — a JSESSIONID value (or a file at
#      / $SAIKU_SESSION_FILE     $SAIKU_SESSION_FILE, default ~/.saiku/session).
#   3. auto-mint               — log in to $FUZZ_TARGET with $SAIKU_USER/$SAIKU_PASS
#                                (default admin/admin) via mint-cookie.sh. This is
#                                what makes `npm run fuzz` work out-of-the-box
#                                against a local launcher.
#
# Usage:
#   npm run fuzz                        # 2-min run vs http://localhost:8080/ui/, auto-login admin/admin
#   FUZZ_TIME=60m npm run fuzz          # fuzz for an hour
#   FUZZ_TARGET=http://localhost:8080/ui/ FUZZ_TIME=60m npm run fuzz
#
# See ./README.md for how to read the results.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="${FUZZ_TARGET:-http://localhost:8080/ui/}"
TIME_LIMIT="${FUZZ_TIME:-2m}"
SPEC="${HERE}/spec.ts"
OUT="${HERE}/out"

COOKIE_HEADER="${FUZZ_COOKIE:-}"
SESSION_FILE="${SAIKU_SESSION_FILE:-$HOME/.saiku/session}"
if [[ -z "$COOKIE_HEADER" ]]; then
  if [[ -z "${SAIKU_SESSION:-}" && -r "$SESSION_FILE" ]]; then
    SAIKU_SESSION="$(tr -d '[:space:]' <"$SESSION_FILE")"
  fi
  if [[ -n "${SAIKU_SESSION:-}" ]]; then
    COOKIE_HEADER="JSESSIONID=${SAIKU_SESSION}"
  else
    echo "▶ No cookie supplied — logging in to ${TARGET} to mint one…" >&2
    COOKIE_HEADER="$(FUZZ_TARGET="$TARGET" bash "${HERE}/mint-cookie.sh")"
  fi
fi

if [[ -z "$COOKIE_HEADER" ]]; then
  echo "✗ Could not obtain a session cookie. Start the launcher (java -jar saiku-<v>.jar serve) or set FUZZ_COOKIE / SAIKU_SESSION." >&2
  exit 1
fi

# Resolve the bombadil binary from the local install.
BOMBADIL="${HERE}/../node_modules/.bin/bombadil"
[[ -x "$BOMBADIL" ]] || BOMBADIL="bombadil"

echo "▶ Fuzzing ${TARGET} for ${TIME_LIMIT} (spec: spec.ts)…"
echo "  Output → ${OUT}  ·  inspect with: ${BOMBADIL} inspect ${OUT}/trace.jsonl"

# bombadil v0.6.x's `browser test` has no --cookie flag, so we inject the session
# as a static Cookie request header (sent with every browser request).
exec "$BOMBADIL" browser test "$TARGET" "$SPEC" \
  --headless \
  --time-limit="$TIME_LIMIT" \
  --header "Cookie=${COOKIE_HEADER}" \
  --output-path "$OUT" \
  --output-path-overwrite
