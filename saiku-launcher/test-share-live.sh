#!/usr/bin/env bash
# Live verification for #941 — shareable read-only dashboard links.
#
# Proves the security guarantees against a running launcher:
#   - an owner (canGrant) can mint a link; a guest with the token can VIEW the
#     pinned dashboard + run its tiles, but the token grants NOTHING else
#     (no /ai/query, no drillthrough, no other dashboard, no mint/list);
#   - revocation + malformed/traversal tokens fail closed (401 SHARE_INVALID).
#
# Usage: start the launcher (port 8087, SAIKU_DEMO=true), then ./test-share-live.sh
set -u
URL="${SAIKU_URL:-http://localhost:8087}"
A=$(mktemp); trap 'rm -f "$A"' EXIT          # admin cookie jar
PASS=0; FAIL=0
ok(){ echo "  PASS: $1"; PASS=$((PASS+1)); }
no(){ echo "  FAIL: $1 | code=$2 body=${3:0:200}"; FAIL=$((FAIL+1)); }

login(){ curl -s -b "$1" -c "$1" -X POST "$URL/rest/saiku/session" --data "username=$2&password=$3" -o /dev/null -w '%{http_code}'; }
# admin request (cookie). sets RC/RB
areq(){ local m="$1" p="$2" body="${3:-}"; local out; out=$(mktemp)
  if [ "$m" = GET ]; then RC=$(curl -s -b "$A" "$URL$p" -o "$out" -w '%{http_code}')
  elif [ "$m" = DELETE ]; then RC=$(curl -s -b "$A" -X DELETE "$URL$p" -o "$out" -w '%{http_code}')
  else RC=$(curl -s -b "$A" -X "$m" "$URL$p" -H 'Content-Type: application/json' --data "$body" -o "$out" -w '%{http_code}'); fi
  RB=$(cat "$out"); rm -f "$out"; }
# guest request (NO cookie; share token header). sets RC/RB
greq(){ local m="$1" p="$2" tok="$3"; local out; out=$(mktemp)
  if [ "$m" = GET ]; then RC=$(curl -s "$URL$p" -H "X-Saiku-Share-Token: $tok" -o "$out" -w '%{http_code}')
  else RC=$(curl -s -X "$m" "$URL$p" -H "X-Saiku-Share-Token: $tok" -H 'Content-Type: application/json' --data '{}' -o "$out" -w '%{http_code}'); fi
  RB=$(cat "$out"); rm -f "$out"; }
denied(){ case "$1" in 401|403|302) return 0;; *) return 1;; esac; }

CUBE="unknown_foodmart/FoodMart/FoodMart/Sales"
DASH="dashboards/sharetest941.saikudash"
DASHBODY='{"id":"s941","name":"Share Test 941","version":1,"layout":{"cols":12,"tiles":[{"id":"t1","x":0,"y":0,"w":6,"h":4,"type":"chart","chartType":"bar","query":{"kind":"inline","body":{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}]}}}]}}'

echo "== setup: admin mints a share link =="
[ "$(login "$A" admin admin)" = 200 ] && ok "admin login" || no "admin login" "?" ""
areq POST "/rest/saiku/api/dashboards/$DASH" "$DASHBODY"; echo "$RB" | grep -q '"status":"OK"' && ok "admin saved dashboard" || no "admin save" "$RC" "$RB"
areq POST "/rest/saiku/share/mint" "{\"dashboardPath\":\"$DASH\",\"ttlHours\":24,\"label\":\"board\"}"
TOKEN=$(echo "$RB" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
{ [ -n "$TOKEN" ] && echo "$RB" | grep -q '"status":"OK"'; } && ok "mint -> token issued" || no "mint" "$RC" "$RB"

echo "== guest VIEW (token in header) works =="
greq GET "/rest/saiku/share/view/dashboard" "$TOKEN"; echo "$RB" | grep -q 'Share Test 941' && ok "guest GET dashboard" || no "guest dashboard" "$RC" "$RB"
greq POST "/rest/saiku/share/view/tile/t1/query" "$TOKEN"; { [ "$RC" = 200 ] && echo "$RB" | grep -q '"status":"SUCCESS"'; } && ok "guest runs tile query (owner scope)" || no "guest tile query" "$RC" "$RB"

echo "== escalation BLOCKED: the token grants nothing outside /share/view =="
greq GET  "/rest/saiku/api/ai/cubes" "$TOKEN";        denied "$RC" && ok "guest /ai/cubes denied ($RC)"       || no "ai/cubes not denied" "$RC" "$RB"
greq POST "/rest/saiku/api/ai/query" "$TOKEN";        denied "$RC" && ok "guest /ai/query denied ($RC)"       || no "ai/query not denied" "$RC" "$RB"
greq GET  "/rest/saiku/api/ai/query/x/drillthrough" "$TOKEN"; denied "$RC" && ok "guest drillthrough denied ($RC)" || no "drillthrough not denied" "$RC" "$RB"
greq GET  "/rest/saiku/api/dashboards/$DASH" "$TOKEN"; denied "$RC" && ok "guest dashboards-API denied ($RC)"  || no "dashboards API not denied" "$RC" "$RB"
greq GET  "/rest/saiku/share/tokens" "$TOKEN";        denied "$RC" && ok "guest token list denied ($RC)"      || no "token list not denied" "$RC" "$RB"
greq POST "/rest/saiku/share/mint" "$TOKEN";          denied "$RC" && ok "guest mint denied ($RC)"            || no "mint not denied" "$RC" "$RB"

echo "== malformed / traversal token fails closed =="
greq GET "/rest/saiku/share/view/dashboard" "../../etc/passwd"; { [ "$RC" = 401 ] || echo "$RB" | grep -q 'SHARE_INVALID'; } && ok "traversal token rejected ($RC)" || no "traversal not rejected" "$RC" "$RB"
greq GET "/rest/saiku/share/view/dashboard" "nonexistenttoken0001"; { [ "$RC" = 401 ] || echo "$RB" | grep -q 'SHARE_INVALID'; } && ok "unknown token rejected ($RC)" || no "unknown not rejected" "$RC" "$RB"

echo "== revocation bites immediately =="
areq DELETE "/rest/saiku/share/tokens/$TOKEN"; echo "$RB" | grep -q '"status":"OK"' && ok "admin revokes token" || no "revoke" "$RC" "$RB"
greq GET "/rest/saiku/share/view/dashboard" "$TOKEN"; { [ "$RC" = 401 ] || echo "$RB" | grep -q 'SHARE_INVALID'; } && ok "revoked token denied ($RC)" || no "revoked not denied" "$RC" "$RB"

echo "== cleanup =="
areq DELETE "/rest/saiku/api/dashboards/$DASH" >/dev/null 2>&1

echo ""; echo "RESULT: $PASS passed, $FAIL failed"; exit $FAIL
