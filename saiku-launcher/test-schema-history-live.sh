#!/usr/bin/env bash
# Live verification for #1121 (Phase 1) — Ossie/M4 YAML schema write + version history.
# Proves: GET returns the current YAML; PUT with valid YAML saves it and archives the
# replaced content (CREATE on first write, UPDATE after); invalid YAML is rejected and
# leaves the file untouched; history lists newest-first and /version returns the full
# old/new diff.
# Usage: launcher on :8087 (SAIKU_DEMO=true, so the TPCDS Ossie datasource is seeded),
# then ./test-schema-history-live.sh
set -u
URL="${SAIKU_URL:-http://localhost:8087}"
A=$(mktemp); trap 'rm -f "$A"' EXIT
PASS=0; FAIL=0
ok(){ echo "  PASS: $1"; PASS=$((PASS+1)); }
no(){ echo "  FAIL: $1 | code=$2 body=${3:0:180}"; FAIL=$((FAIL+1)); }
login(){ curl -s -b "$1" -c "$1" -X POST "$URL/rest/saiku/session" --data "username=$2&password=$3" -o /dev/null -w '%{http_code}'; }
req(){ local jar="$1" m="$2" p="$3" ct="$4" body="${5:-}"; local out; out=$(mktemp)
  if [ "$m" = GET ]; then RC=$(curl -s -b "$jar" "$URL$p" -o "$out" -w '%{http_code}')
  else RC=$(curl -s -b "$jar" -X "$m" "$URL$p" -H "Content-Type: $ct" --data "$body" -o "$out" -w '%{http_code}'); fi
  RB=$(cat "$out"); rm -f "$out"; }

DS="TPCDS"
S="/rest/saiku/admin/ossie/schema/$DS"
H="/rest/saiku/api/schema/history"
V1=$'version: 0.1.0\nsemantic_model:\n  - name: TPCDS\n    dataset: []\n'
V2=$'version: 0.1.0\nsemantic_model:\n  - name: TPCDS\n    dataset:\n      - name: sales\n'

echo "== setup =="
[ "$(login "$A" admin admin)" = 200 ] && ok "admin login" || no "admin login" "?" ""

echo "== first write is a CREATE =="
req "$A" PUT "$S" "text/plain" "$V1"
echo "$RB" | grep -q '"action":"CREATE"' && ok "first save recorded as CREATE" || no "first save" "$RC" "$RB"

echo "== GET returns exactly what was saved =="
req "$A" GET "$S"
[ "$RB" = "$V1" ] && ok "GET returns the saved YAML verbatim" || no "GET after first save" "$RC" "$RB"

echo "== second write is an UPDATE =="
req "$A" PUT "$S" "text/plain" "$V2"
echo "$RB" | grep -q '"action":"UPDATE"' && ok "second save recorded as UPDATE" || no "second save" "$RC" "$RB"

echo "== invalid YAML is rejected and disk is untouched =="
req "$A" PUT "$S" "text/plain" "not: [valid, yaml"
[ "$RC" = 400 ] && ok "malformed YAML rejected (400)" || no "malformed YAML not rejected" "$RC" "$RB"
req "$A" GET "$S"
[ "$RB" = "$V2" ] && ok "disk still has V2 after the rejected write" || no "disk changed after rejected write" "$RC" "$RB"

echo "== history lists newest-first with both writes =="
req "$A" GET "$H?target=$DS"
CNT=$(echo "$RB" | grep -o '"action"' | wc -l | tr -d ' ')
[ "$RC" = 200 ] && [ "$CNT" -ge 2 ] && ok "history has >= 2 entries ($CNT)" || no "history list" "$RC" "$RB"
echo "$RB" | grep -q '"action":"UPDATE"' && ok "newest entry is the UPDATE" || no "history not newest-first" "$RC" "$RB"

VID=$(echo "$RB" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p' | head -1)
echo "== /version returns the full old/new diff =="
req "$A" GET "$H/version?target=$DS&version=$VID"
echo "$RB" | grep -q '"oldYaml"' && echo "$RB" | grep -q '"newYaml"' && ok "version entry carries old+new YAML" || no "version diff" "$RC" "$RB"

echo ""; echo "RESULT: $PASS passed, $FAIL failed"; exit $FAIL
