#!/usr/bin/env bash
# Live verification for #942 — per-tile dashboard comments.
# Proves: author CRUD; @-mention captured; access gated on dashboard canRead
# (a non-reader and an anonymous client are denied); delete is author/admin-only.
# Usage: launcher on :8087 (SAIKU_DEMO=true), then ./test-comments-live.sh
set -u
URL="${SAIKU_URL:-http://localhost:8087}"
A=$(mktemp); B=$(mktemp); trap 'rm -f "$A" "$B"' EXIT   # admin, bob cookie jars
PASS=0; FAIL=0
ok(){ echo "  PASS: $1"; PASS=$((PASS+1)); }
no(){ echo "  FAIL: $1 | code=$2 body=${3:0:180}"; FAIL=$((FAIL+1)); }
login(){ curl -s -b "$1" -c "$1" -X POST "$URL/rest/saiku/session" --data "username=$2&password=$3" -o /dev/null -w '%{http_code}'; }
# req: jar method path [body]  (jar="-" => no cookie / anonymous). sets RC/RB
req(){ local jar="$1" m="$2" p="$3" body="${4:-}"; local out cj; out=$(mktemp)
  if [ "$jar" = "-" ]; then cj=""; else cj="-b $jar"; fi
  if [ "$m" = GET ]; then RC=$(curl -s $cj "$URL$p" -o "$out" -w '%{http_code}')
  elif [ "$m" = DELETE ]; then RC=$(curl -s $cj -X DELETE "$URL$p" -o "$out" -w '%{http_code}')
  else RC=$(curl -s $cj -X "$m" "$URL$p" -H 'Content-Type: application/json' --data "$body" -o "$out" -w '%{http_code}'); fi
  RB=$(cat "$out"); rm -f "$out"; }
denied(){ case "$1" in 401|403) return 0;; *) return 1;; esac; }

C="/rest/saiku/api/dashboards/comments"
DASH="dashboards/commenttest942.saikudash"
DBODY='{"id":"c942","name":"Comment Test","version":1,"layout":{"cols":12,"tiles":[{"id":"t1","x":0,"y":0,"w":6,"h":4,"type":"text","text":"hi"}]}}'

echo "== setup =="
[ "$(login "$A" admin admin)" = 200 ] && ok "admin login" || no "admin login" "?" ""
[ "$(login "$B" bob dylan)" = 200 ] && ok "bob login" || no "bob login" "?" ""
req "$A" POST "/rest/saiku/api/dashboards/$DASH" "$DBODY"; echo "$RB" | grep -q '"status":"OK"' && ok "admin saved dashboard (SECURED /dashboards)" || no "save" "$RC" "$RB"

echo "== author CRUD + @-mention =="
req "$A" POST "$C" "{\"dashboard\":\"$DASH\",\"tile\":\"t1\",\"body\":\"hello @bob please review\"}"
CID=$(echo "$RB" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
{ [ "$RC" = 200 ] && echo "$RB" | grep -q '"bob"'; } && ok "admin posts comment + @bob mention captured" || no "post" "$RC" "$RB"
req "$A" GET "$C?dashboard=$DASH&tile=t1"; { [ "$RC" = 200 ] && echo "$RB" | grep -q 'hello @bob'; } && ok "admin lists the comment" || no "list" "$RC" "$RB"
req "$A" GET "$C?dashboard=$DASH&tile=t2"; { [ "$RC" = 200 ] && [ "$RB" = "[]" ]; } && ok "other tile is empty (per-tile scope)" || no "tile scope" "$RC" "$RB"

echo "== access gating: non-reader + anonymous denied =="
req "$B" GET "$C?dashboard=$DASH&tile=t1"; denied "$RC" && ok "bob (can't read dashboard) list denied ($RC)" || no "bob list not denied" "$RC" "$RB"
req "$B" POST "$C" "{\"dashboard\":\"$DASH\",\"tile\":\"t1\",\"body\":\"sneak\"}"; denied "$RC" && ok "bob post denied ($RC)" || no "bob post not denied" "$RC" "$RB"
req "-" GET "$C?dashboard=$DASH&tile=t1"; denied "$RC" && ok "anonymous denied ($RC)" || no "anon not denied" "$RC" "$RB"

echo "== delete is author/admin only =="
# Open the dashboard to everyone so bob CAN read but still can't delete admin's comment.
req "$A" POST "/rest/saiku/api/repository/resource/acl" "" >/dev/null 2>&1
curl -s -b "$A" -X POST "$URL/rest/saiku/api/repository/resource/acl" --data "file=$DASH" --data-urlencode 'acl={"owner":"admin","type":"PUBLIC","roles":{},"users":{}}' -o /dev/null
req "$B" GET "$C?dashboard=$DASH&tile=t1"; [ "$RC" = 200 ] && ok "bob can read after dashboard set PUBLIC" || no "bob read after public" "$RC" "$RB"
req "$B" DELETE "$C/$CID?dashboard=$DASH"; denied "$RC" && ok "bob cannot delete admin's comment ($RC)" || no "bob delete not denied" "$RC" "$RB"
req "$A" DELETE "$C/$CID?dashboard=$DASH"; echo "$RB" | grep -q '"status":"OK"' && ok "admin (author) deletes own comment" || no "admin delete" "$RC" "$RB"
req "$A" GET "$C?dashboard=$DASH&tile=t1"; { [ "$RC" = 200 ] && [ "$RB" = "[]" ]; } && ok "soft-deleted comment hidden from list" || no "soft-delete hidden" "$RC" "$RB"

echo "== cleanup =="
req "$A" DELETE "/rest/saiku/api/dashboards/$DASH" >/dev/null 2>&1
echo ""; echo "RESULT: $PASS passed, $FAIL failed"; exit $FAIL
