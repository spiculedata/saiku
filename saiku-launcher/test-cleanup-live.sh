#!/usr/bin/env bash
# Live verification for the #942/#947 follow-ups:
#  - deleting a dashboard PURGES its sidecar comment + history files (no orphans)
#  - GET /rest/saiku/api/users returns a usernames list (for @-mention autocomplete)
# Usage: launcher on :8087 (SAIKU_DEMO=true), then ./test-cleanup-live.sh
set -u
URL="${SAIKU_URL:-http://localhost:8087}"
A=$(mktemp); trap 'rm -f "$A"' EXIT
PASS=0; FAIL=0
ok(){ echo "  PASS: $1"; PASS=$((PASS+1)); }
no(){ echo "  FAIL: $1 | code=$2 body=${3:0:180}"; FAIL=$((FAIL+1)); }
login(){ curl -s -b "$1" -c "$1" -X POST "$URL/rest/saiku/session" --data "username=$2&password=$3" -o /dev/null -w '%{http_code}'; }
req(){ local m="$1" p="$2" body="${3:-}"; local out; out=$(mktemp)
  if [ "$m" = GET ]; then RC=$(curl -s -b "$A" "$URL$p" -o "$out" -w '%{http_code}')
  elif [ "$m" = DELETE ]; then RC=$(curl -s -b "$A" -X DELETE "$URL$p" -o "$out" -w '%{http_code}')
  else RC=$(curl -s -b "$A" -X "$m" "$URL$p" -H 'Content-Type: application/json' --data "$body" -o "$out" -w '%{http_code}'); fi
  RB=$(cat "$out"); rm -f "$out"; }

DASH="dashboards/cleanup-demo.saikudash"
DGET="/rest/saiku/api/dashboards/$DASH"
C="/rest/saiku/api/dashboards/comments"
H="/rest/saiku/api/dashboards/history"
V1='{"id":"c","name":"V1","version":1,"layout":{"cols":12,"tiles":[{"id":"t1","x":0,"y":0,"w":4,"h":3,"type":"text","text":"hi"}]}}'
V2='{"id":"c","name":"V2","version":1,"layout":{"cols":12,"tiles":[{"id":"t1","x":0,"y":0,"w":4,"h":3,"type":"text","text":"hi"}]}}'

echo "== users directory (for @-mention) =="
[ "$(login "$A" admin admin)" = 200 ] && ok "admin login" || no "admin login" "?" ""
req GET "/rest/saiku/api/users"; { [ "$RC" = 200 ] && echo "$RB" | grep -q '"username":"admin"'; } && ok "GET /api/users lists usernames (incl admin)" || no "users list" "$RC" "$RB"

echo "== seed: dashboard + comment + 2 saves (history) =="
req POST "$DGET" "$V1"; echo "$RB" | grep -q '"status":"OK"' && ok "save v1" || no "save v1" "$RC" "$RB"
req POST "$DGET" "$V2"; echo "$RB" | grep -q '"status":"OK"' && ok "save v2 (archives v1)" || no "save v2" "$RC" "$RB"
req POST "$C" "{\"dashboard\":\"$DASH\",\"tile\":\"t1\",\"body\":\"a comment\"}"; echo "$RB" | grep -q 'a comment' && ok "post comment" || no "post comment" "$RC" "$RB"
req GET "$C?dashboard=$DASH&tile=t1"; echo "$RB" | grep -q 'a comment' && ok "comment present" || no "comment present" "$RC" "$RB"
req GET "$H?dashboard=$DASH"; echo "$RB" | grep -q '"version"' && ok "history present" || no "history present" "$RC" "$RB"

echo "== delete the dashboard → sidecars must be purged =="
req DELETE "$DGET"; echo "$RB" | grep -q '"status":"OK"' && ok "delete dashboard" || no "delete" "$RC" "$RB"
# Re-create at the same path; the comment + history sidecars should be EMPTY now.
req POST "$DGET" "$V1" >/dev/null
req GET "$C?dashboard=$DASH&tile=t1"; { [ "$RC" = 200 ] && [ "$RB" = "[]" ]; } && ok "comments purged on delete (empty after recreate)" || no "comments not purged" "$RC" "$RB"
req GET "$H?dashboard=$DASH"; { [ "$RC" = 200 ] && [ "$RB" = "[]" ]; } && ok "history purged on delete (empty after recreate)" || no "history not purged" "$RC" "$RB"

echo "== cleanup =="
req DELETE "$DGET" >/dev/null 2>&1
echo ""; echo "RESULT: $PASS passed, $FAIL failed"; exit $FAIL
