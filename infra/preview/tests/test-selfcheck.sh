#!/usr/bin/env bash
#
# Offline tests for infra/preview/selfcheck.sh.
#
# Starts fake_saiku.py on a random localhost port, runs the real selfcheck against it
# and asserts the verdicts. The point is the SILENTLY-WRONG scenarios: the shipped
# admin/admin still accepted, the documented demo accounts still loaded, FoodMart
# missing, the UI bundle absent, a wrong password. Each must show up as `inactive`, and
# `--require-all` must turn that into a failing exit code. The password must never be
# printed.
#
# Runs on macOS and Linux (bash 3.2+, python3, curl). Usage:
#   infra/preview/tests/test-selfcheck.sh
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SELFCHECK="$HERE/../selfcheck.sh"
WORK="$(mktemp -d)"
SERVER_PID=""
cleanup() {
	if [ -n "$SERVER_PID" ]; then
		kill "$SERVER_PID" 2>/dev/null
		wait "$SERVER_PID" 2>/dev/null
	fi
	rm -rf "$WORK"
}
trap cleanup EXIT

pass=0
fail=0
ok() { echo "ok   - $1"; pass=$((pass + 1)); }
bad() { echo "FAIL - $1"; fail=$((fail + 1)); }

PASSWORD="prevpw_0123456789abcdef0123456789abcdef01234567"

start_server() { # <config-json>
	if [ -n "$SERVER_PID" ]; then
		kill "$SERVER_PID" 2>/dev/null
		wait "$SERVER_PID" 2>/dev/null
	fi
	rm -f "$WORK/port"
	FAKE_SAIKU_CONFIG="$1" FAKE_SAIKU_PORT_FILE="$WORK/port" python3 "$HERE/fake_saiku.py" &
	SERVER_PID=$!
	local i=0
	while [ ! -s "$WORK/port" ] && [ "$i" -lt 50 ]; do
		sleep 0.1
		i=$((i + 1))
	done
	PORT="$(cat "$WORK/port")"
}

# run_selfcheck <args...>; output in $WORK/out, exit code in $RC. SC_ENV carries extra env.
SC_ENV=()
run_selfcheck() {
	env SAIKU_URL="http://127.0.0.1:$PORT" PREVIEW_HEALTH_TIMEOUT=2 PREVIEW_STACK=test \
		SAIKU_ADMIN_PASSWORD="$PASSWORD" ${SC_ENV[@]+"${SC_ENV[@]}"} sh "$SELFCHECK" "$@" >"$WORK/out" 2>"$WORK/err"
	RC=$?
}

row_status() { awk -v f="$1" '$1 == f { print $2 }' "$WORK/out"; }
expect_row() { # <label> <feature> <status>
	if [ "$(row_status "$2")" = "$3" ]; then ok "$1"; else bad "$1 (got '$(row_status "$2")')"; fi
}
expect_rc() { # <label> <code>
	if [ "$RC" = "$2" ]; then ok "$1"; else bad "$1 (exit $RC)"; fi
}

HEALTHY="{\"users\":{\"admin\":\"$PASSWORD\"}}"

# --- a healthy stack --------------------------------------------------------
start_server "$HEALTHY"
SC_ENV=()
run_selfcheck --require-all
for f in server ui_bundle admin_login admin_session default_admin_rejected demo_accounts_absent foodmart_cube; do
	expect_row "healthy: $f is active" "$f" active
done
expect_rc "healthy: --require-all exits 0" 0
grep -q 'PREVIEW_SELFCHECK stack=test active=7 inactive=0 skipped=0' "$WORK/out" && ok "healthy: summary line" || bad "healthy: summary line"
python3 -c "import json,sys; d=json.loads([l for l in open('$WORK/out') if l.startswith('{')][0]); assert d['active']==7 and len(d['checks'])==7" && ok "healthy: machine-readable JSON line parses" || bad "healthy: JSON line"
if grep -rq "$PASSWORD" "$WORK/out" "$WORK/err"; then bad "healthy: the password is never printed"; else ok "healthy: the password is never printed"; fi

# --- the shipped default credential still works -> inactive -----------------
start_server '{"users":{"admin":"admin"}}'
SC_ENV=(SAIKU_ADMIN_PASSWORD=admin)
run_selfcheck
expect_row "default admin/admin accepted is flagged inactive" default_admin_rejected inactive
expect_rc "report-only mode exits 0 even when something is inactive" 0
run_selfcheck --require-all
expect_rc "--require-all exits 1 when admin/admin works" 1

# --- demo accounts loaded ----------------------------------------------------
start_server "{\"users\":{\"admin\":\"$PASSWORD\",\"bob\":\"dylan\"}}"
SC_ENV=()
run_selfcheck --require-all
expect_row "a loaded demo account is flagged inactive" demo_accounts_absent inactive
expect_rc "--require-all exits 1 when demo accounts are loaded" 1

# --- FoodMart missing --------------------------------------------------------
start_server "{\"users\":{\"admin\":\"$PASSWORD\"},\"cubes\":[{\"connectionName\":\"bank\",\"catalog\":\"Bank\",\"schema\":\"Bank\",\"cubeName\":\"Accounts\"}]}"
run_selfcheck --require-all
expect_row "no FoodMart cube is flagged inactive" foodmart_cube inactive
expect_rc "--require-all exits 1 when FoodMart is missing" 1

# FoodMart present but not the Sales cube
start_server "{\"users\":{\"admin\":\"$PASSWORD\"},\"cubes\":[{\"connectionName\":\"foodmart\",\"catalog\":\"FoodMart\",\"schema\":\"FoodMart\",\"cubeName\":\"HR\"}]}"
run_selfcheck
expect_row "FoodMart without its Sales cube is flagged inactive" foodmart_cube inactive

# --- UI bundle missing -------------------------------------------------------
start_server "{\"users\":{\"admin\":\"$PASSWORD\"},\"ui\":false}"
run_selfcheck --require-all
expect_row "a missing UI bundle is flagged inactive" ui_bundle inactive
expect_rc "--require-all exits 1 when the UI is missing" 1

# --- wrong admin password ----------------------------------------------------
start_server "$HEALTHY"
SC_ENV=(SAIKU_ADMIN_PASSWORD=not-the-password)
run_selfcheck --require-all
expect_row "a rejected admin password is flagged inactive" admin_login inactive
expect_row "no session when the login failed" admin_session inactive
expect_row "no cube list when the login failed" foodmart_cube inactive
expect_rc "--require-all exits 1 on a rejected login" 1
if grep -rq "not-the-password" "$WORK/out" "$WORK/err"; then bad "a wrong password is not printed either"; else ok "a wrong password is not printed either"; fi

# --- no password in the environment ------------------------------------------
SC_ENV=(SAIKU_ADMIN_PASSWORD=)
run_selfcheck
expect_row "a missing password is reported, not a crash" admin_login inactive
grep -q 'SAIKU_ADMIN_PASSWORD is not set' "$WORK/out" && ok "the reason names the missing variable" || bad "the reason names the missing variable"

# --- the server never comes up -----------------------------------------------
start_server "{\"users\":{\"admin\":\"$PASSWORD\"},\"info\":503}"
SC_ENV=()
run_selfcheck --require-all
expect_row "a server that never answers 200 is flagged inactive" server inactive
expect_rc "--require-all exits 1 when the server is down" 1
grep -q 'answered 503' "$WORK/out" && ok "the evidence carries the last status" || bad "the evidence carries the last status"
if [ "$(wc -l <"$WORK/out" | tr -d ' ')" -le 6 ]; then ok "nothing else is probed once the server is down"; else bad "nothing else is probed once the server is down"; fi

echo
echo "passed=$pass failed=$fail"
[ "$fail" -eq 0 ]
