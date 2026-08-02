#!/usr/bin/env bash
#
# Print the Cookie header that authenticates the Bombadil fuzzer against a local
# Saiku launcher. Saiku uses Spring Security session auth: POST username/password
# to /rest/saiku/session sets an httpOnly JSESSIONID (+ an XSRF-TOKEN). We echo
# both as a single `Cookie` header value for run.sh to pass to Bombadil verbatim.
#
# Env (all optional):
#   FUZZ_TARGET   base URL of the running Saiku (default http://localhost:8080)
#   SAIKU_USER    login user     (default admin)
#   SAIKU_PASS    login password (default admin)
#
# Usage:
#   FUZZ_COOKIE="$(bash bombadil/mint-cookie.sh)" npm run fuzz
# (run.sh calls this automatically when no cookie is supplied.)
set -euo pipefail

# Strip a trailing /ui or /ui/ so we hit the REST origin, not the SPA base path.
BASE="${FUZZ_TARGET:-http://localhost:8080}"
BASE="${BASE%/}"
BASE="${BASE%/ui}"
USER="${SAIKU_USER:-admin}"
PASS="${SAIKU_PASS:-admin}"

JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT

code="$(curl -s -o /dev/null -w '%{http_code}' -c "$JAR" \
  -X POST "${BASE}/rest/saiku/session" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode "username=${USER}" \
  --data-urlencode "password=${PASS}")"

if [[ "$code" != "200" ]]; then
  echo "✗ login to ${BASE}/rest/saiku/session failed (HTTP ${code}). Is the launcher running, and are ${USER}/${PASS} valid?" >&2
  exit 1
fi

# Emit "NAME=value; NAME=value" for JSESSIONID (+ XSRF-TOKEN if present). Netscape
# cookie-jar columns: 6 = name, 7 = value.
awk 'NF>=7 && ($6=="JSESSIONID" || $6=="XSRF-TOKEN") { printf "%s%s=%s", sep, $6, $7; sep="; " } END { print "" }' "$JAR"
