#!/usr/bin/env bash
# Live verification for #947 — dashboard versioning / history.
# Proves: each save archives the replaced version; list/preview/restore work;
# restore is reversible (pre-restore state archived); access gated on dashboard
# canRead (non-reader + anonymous denied).
# Usage: launcher on :8087 (SAIKU_DEMO=true), then ./test-history-live.sh
set -u
URL="${SAIKU_URL:-http://localhost:8087}"
A=$(mktemp); B=$(mktemp); trap 'rm -f "$A" "$B"' EXIT
PASS=0; FAIL=0
ok(){ echo "  PASS: $1"; PASS=$((PASS+1)); }
no(){ echo "  FAIL: $1 | code=$2 body=${3:0:180}"; FAIL=$((FAIL+1)); }
login(){ curl -s -b "$1" -c "$1" -X POST "$URL/rest/saiku/session" --data "username=$2&password=$3" -o /dev/null -w '%{http_code}'; }
req(){ local jar="$1" m="$2" p="$3" body="${4:-}"; local out cj; out=$(mktemp)
  if [ "$jar" = "-" ]; then cj=""; else cj="-b $jar"; fi
  if [ "$m" = GET ]; then RC=$(curl -s $cj "$URL$p" -o "$out" -w '%{http_code}')
  else RC=$(curl -s $cj -X "$m" "$URL$p" -H 'Content-Type: application/json' --data "$body" -o "$out" -w '%{http_code}'); fi
  RB=$(cat "$out"); rm -f "$out"; }
denied(){ case "$1" in 401|403) return 0;; *) return 1;; esac; }

H="/rest/saiku/api/dashboards/history"
DASH="dashboards/histtest947.saikudash"
DGET="/rest/saiku/api/dashboards/$DASH"
V1='{"id":"h1","name":"V1","version":1,"layout":{"cols":12,"tiles":[]}}'
V2='{"id":"h1","name":"V2","version":1,"layout":{"cols":12,"tiles":[]}}'

echo "== setup: save v1 then v2 (v2 save archives v1) =="
[ "$(login "$A" admin admin)" = 200 ] && ok "admin login" || no "admin login" "?" ""
[ "$(login "$B" bob dylan)" = 200 ] && ok "bob login" || no "bob login" "?" ""
req "$A" POST "$DGET" "$V1"; echo "$RB" | grep -q '"status":"OK"' && ok "save v1" || no "save v1" "$RC" "$RB"
req "$A" POST "$DGET" "$V2"; echo "$RB" | grep -q '"status":"OK"' && ok "save v2" || no "save v2" "$RC" "$RB"

echo "== history lists the archived v1 =="
req "$A" GET "$H?dashboard=$DASH"
VID=$(echo "$RB" | sed -n 's/.*"version":"\([^"]*\)".*/\1/p' | head -1)
{ [ "$RC" = 200 ] && [ -n "$VID" ] && echo "$RB" | grep -q '"author":"admin"'; } && ok "history shows 1 archived version (v1, author admin)" || no "history list" "$RC" "$RB"
# Preview returns the stored dashboard JSON (pretty-printed), so match name tolerantly.
req "$A" GET "$H/version?dashboard=$DASH&version=$VID"; echo "$RB" | grep -Eq '"name" *: *"V1"' && ok "preview returns the v1 snapshot" || no "preview" "$RC" "$RB"

echo "== restore v1 (reversible) =="
req "$A" POST "$H/restore?dashboard=$DASH&version=$VID"; echo "$RB" | grep -q '"status":"OK"' && ok "restore v1" || no "restore" "$RC" "$RB"
req "$A" GET "$DGET"; echo "$RB" | grep -q '"name":"V1"' && ok "live dashboard is now V1" || no "restored content" "$RC" "$RB"
req "$A" GET "$H?dashboard=$DASH"
# >= 2 (v1 + the pre-restore V2). >= rather than == so the script is re-runnable:
# the saiku-history-*.jsonl persists across runs (not removed on dashboard delete).
CNT=$(echo "$RB" | grep -o '"version":"' | wc -l | tr -d ' ')
[ "$CNT" -ge 2 ] && ok "history grew to $CNT (v1 + pre-restore V2 archived → restore is reversible)" || no "history count after restore ($CNT)" "$RC" "$RB"

echo "== access gating =="
req "$B" GET "$H?dashboard=$DASH"; denied "$RC" && ok "bob (non-reader) history denied ($RC)" || no "bob history not denied" "$RC" "$RB"
req "$B" POST "$H/restore?dashboard=$DASH&version=$VID"; denied "$RC" && ok "bob restore denied ($RC)" || no "bob restore not denied" "$RC" "$RB"
req "-" GET "$H?dashboard=$DASH"; denied "$RC" && ok "anonymous denied ($RC)" || no "anon not denied" "$RC" "$RB"

echo "== cleanup =="
req "$A" DELETE "$DGET" >/dev/null 2>&1
echo ""; echo "RESULT: $PASS passed, $FAIL failed"; exit $FAIL
