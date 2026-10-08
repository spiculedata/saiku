#!/bin/sh
#
# preview-selfcheck: startup self-check for a Saiku OSS preview stack.
#
# Ported in spirit from spiculedata/saiku-cloud infra/preview/selfcheck.sh
# (spiculedata/saiku-cloud#1378, AC 5): configuration you cannot see is configuration
# you cannot trust, so every feature is reported active/inactive WITH the evidence
# behind that verdict, observed from the running server with real requests.
#
# What is checked (all observed, nothing merely declared):
#   server                  GET /rest/saiku/info answers (retried while the JVM boots)
#   ui_bundle               the SvelteKit bundle is served at /ui/
#   admin_login             POST /rest/saiku/session as the seeded admin succeeds
#   admin_session           the session it returns is an admin session
#   default_admin_rejected  admin/admin is REFUSED (the random password is in force)
#   demo_accounts_absent    the publicly documented demo user bob is REFUSED
#   foodmart_cube           the authenticated cube list contains the FoodMart Sales cube
#
# Runs inside the compose network as the `preview-selfcheck` service (curlimages/curl:
# alpine + busybox + curl). POSIX sh, no bashisms, so it also runs unchanged on macOS
# and Linux for offline testing.
#
# Usage:
#   selfcheck.sh                 # report only, exit 0
#   selfcheck.sh --require-all   # report, exit 1 if any feature is inactive
#
# Inputs: SAIKU_URL (default http://saiku:8080), PREVIEW_ADMIN_USER (default admin),
# SAIKU_ADMIN_PASSWORD. The password is handed to curl on stdin as a config file, so it
# never appears in a process list or on a command line, and it is never printed. A
# missing password does not crash the check: the login-dependent features are reported
# INACTIVE with that reason.

set -u

SAIKU_URL="${SAIKU_URL:-http://saiku:8080}"
ADMIN_USER="${PREVIEW_ADMIN_USER:-admin}"
ADMIN_PASSWORD="${SAIKU_ADMIN_PASSWORD:-}"
HEALTH_TIMEOUT="${PREVIEW_HEALTH_TIMEOUT:-180}"
STACK="${PREVIEW_STACK:-${COMPOSE_PROJECT_NAME:-preview}}"
CONNECT_TIMEOUT=5
MAX_TIME=30

REQUIRE_ALL=false
for arg in "$@"; do
	case "$arg" in
	--require-all) REQUIRE_ALL=true ;;
	esac
done

ACTIVE=0
INACTIVE=0
SKIPPED=0
ROWS=""
JSON_ROWS=""

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT INT TERM
JAR="$WORK/cookies"
BODY="$WORK/body"

# Emit the table, the summary line and the machine-readable object. The JSON is what
# a workflow parses, so it must print on every exit path.
summary() {
	echo "$ROWS"
	echo "PREVIEW_SELFCHECK stack=$STACK active=$ACTIVE inactive=$INACTIVE skipped=$SKIPPED"
	echo "{\"stack\":\"$STACK\",\"active\":$ACTIVE,\"inactive\":$INACTIVE,\"skipped\":$SKIPPED,\"checks\":[$JSON_ROWS]}"
}

# Keep evidence JSON-safe: no quotes, no backslashes, no control characters.
clean() {
	printf '%s' "$1" | tr -d '\r\n' | tr '\042\134' '__'
}

# row <feature> <active|inactive|n/a> <evidence>
row() {
	_r_evidence="$(clean "$3")"
	ROWS="${ROWS}$(printf '%-22s %-9s %s' "$1" "$2" "$_r_evidence")
"
	JSON_ROWS="${JSON_ROWS}${JSON_ROWS:+,}{\"feature\":\"$1\",\"status\":\"$2\",\"evidence\":\"${_r_evidence}\"}"
}

check() {
	if [ "$2" = "active" ]; then
		ACTIVE=$((ACTIVE + 1))
	else
		INACTIVE=$((INACTIVE + 1))
	fi
	row "$1" "$2" "$3"
}

finish() {
	summary
	if [ "$REQUIRE_ALL" = true ] && [ "$INACTIVE" -gt 0 ]; then
		echo "self-check FAILED: $INACTIVE feature(s) inactive" >&2
		exit 1
	fi
	exit 0
}

# http_code <curl args...>: the status code, or 000 when nothing answered. The body
# goes to $BODY. Cookies are shared through $JAR.
http_code() {
	_h_code="$(curl -sS -o "$BODY" -w '%{http_code}' --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME" "$@" 2>/dev/null)"
	printf '%s' "${_h_code:-000}"
}

# login <user> <password> <jar-flag...>: POST the REST session endpoint. The credentials
# travel on stdin as a curl config (--data-urlencode), never on argv.
login() {
	_l_user="$1"
	_l_password="$2"
	shift 2
	printf 'data-urlencode = "username=%s"\ndata-urlencode = "password=%s"\n' "$_l_user" "$_l_password" |
		http_code -K - -X POST "$@" "$SAIKU_URL/rest/saiku/session"
}

# ---- server: wait for the JVM, then record the evidence ---------------------
waited=0
code=000
while :; do
	code="$(http_code "$SAIKU_URL/rest/saiku/info")"
	[ "$code" = 200 ] && break
	[ "$waited" -ge "$HEALTH_TIMEOUT" ] && break
	sleep 3
	waited=$((waited + 3))
done
if [ "$code" != 200 ]; then
	check server inactive "GET /rest/saiku/info answered $code after ${waited}s"
	finish
fi
check server active "GET /rest/saiku/info answered 200 (waited ${waited}s)"

# ---- ui_bundle --------------------------------------------------------------
code="$(http_code "$SAIKU_URL/ui/")"
if [ "$code" = 200 ]; then
	check ui_bundle active "GET /ui/ answered 200"
else
	check ui_bundle inactive "GET /ui/ answered $code"
fi

# ---- admin_login / admin_session -------------------------------------------
if [ -z "$ADMIN_PASSWORD" ]; then
	check admin_login inactive "SAIKU_ADMIN_PASSWORD is not set in the self-check environment"
	check admin_session inactive "no login, no session"
	LOGGED_IN=false
else
	code="$(login "$ADMIN_USER" "$ADMIN_PASSWORD" -c "$JAR")"
	if [ "$code" = 200 ]; then
		check admin_login active "POST /rest/saiku/session as $ADMIN_USER answered 200"
		LOGGED_IN=true
		code="$(http_code -b "$JAR" "$SAIKU_URL/rest/saiku/session")"
		if [ "$code" = 200 ] && grep -q '"isadmin":true' "$BODY" 2>/dev/null; then
			check admin_session active "GET /rest/saiku/session answered 200 with isadmin=true"
		else
			check admin_session inactive "GET /rest/saiku/session answered $code and is not an admin session"
		fi
	else
		check admin_login inactive "POST /rest/saiku/session as $ADMIN_USER answered $code"
		check admin_session inactive "no login, no session"
		LOGGED_IN=false
	fi
fi

# ---- default_admin_rejected / demo_accounts_absent -------------------------
# Failed logins are rate limited per client, so these come AFTER the real login
# and are only two requests.
code="$(login admin admin)"
if [ "$code" = 401 ]; then
	check default_admin_rejected active "admin/admin answered 401 (the random password is in force)"
else
	check default_admin_rejected inactive "admin/admin answered $code, expected 401"
fi
code="$(login bob dylan)"
if [ "$code" = 401 ]; then
	check demo_accounts_absent active "the documented demo user bob answered 401 (demo accounts are not loaded)"
else
	check demo_accounts_absent inactive "the documented demo user bob answered $code, expected 401"
fi

# ---- foodmart_cube ----------------------------------------------------------
if [ "$LOGGED_IN" = true ]; then
	code="$(http_code -b "$JAR" "$SAIKU_URL/rest/saiku/api/ai/cubes")"
	if [ "$code" = 200 ] && grep -q '"catalog":"FoodMart"' "$BODY" && grep -q '"cubeName":"Sales"' "$BODY"; then
		n="$(grep -o '"catalog":"FoodMart"' "$BODY" | wc -l | tr -d ' ')"
		check foodmart_cube active "GET /rest/saiku/api/ai/cubes answered 200 and lists FoodMart Sales ($n FoodMart cubes)"
	else
		check foodmart_cube inactive "GET /rest/saiku/api/ai/cubes answered $code without a FoodMart Sales cube"
	fi
else
	check foodmart_cube inactive "not logged in, cannot list cubes"
fi

finish
