#!/usr/bin/env bash
# Live AI Query API regression test suite.
#
# Exercises a running saiku-launcher against the Foodmart H2 cube. Asserts on
# response shape for every scenario surfaced by the ralph-loop live fuzz
# sessions (2026-05-15) plus the unit-test-mirrored happy paths.
#
# Usage:
#   ./run.sh            # in another terminal, then
#   ./test-ai-live.sh   # exits 0 on all-pass, non-zero on first FAIL
#
# Override base URL / creds via env:
#   SAIKU_URL=http://host:port SAIKU_USER=admin SAIKU_PASS=admin ./test-ai-live.sh

set -u
URL="${SAIKU_URL:-http://localhost:8080}"
USER="${SAIKU_USER:-admin}"
PASS="${SAIKU_PASS:-admin}"
CUBE="unknown_foodmart/FoodMart/FoodMart/Sales"
COOKIES=$(mktemp)
trap 'rm -f "$COOKIES"' EXIT

PASS_COUNT=0
FAIL_COUNT=0
FAILURES=()

login() {
  curl -sS -c "$COOKIES" "$URL/login" -o /dev/null
  local code
  code=$(curl -sS -b "$COOKIES" -c "$COOKIES" -X POST "$URL/login" \
    --data "username=$USER&password=$PASS" -o /dev/null -w '%{http_code}')
  if [[ "$code" != "302" ]]; then
    echo "fatal: login expected 302 got $code" >&2
    exit 1
  fi
}

# Run a query, capture the JSON response, assert with a Python predicate.
# Args: 1=label  2=method  3=path  4=body (JSON or '')  5=predicate over r
check() {
  local label="$1" method="$2" path="$3" body="$4" predicate="$5"
  local out; out=$(mktemp)
  local code
  if [[ "$method" == "GET" ]]; then
    code=$(curl -sS -b "$COOKIES" "$URL$path" -o "$out" -w '%{http_code}')
  elif [[ "$method" == "DELETE" ]]; then
    code=$(curl -sS -b "$COOKIES" -X DELETE "$URL$path" -o "$out" -w '%{http_code}')
  else
    code=$(curl -sS -b "$COOKIES" -X "$method" "$URL$path" \
      -H 'Content-Type: application/json' --data "$body" -o "$out" -w '%{http_code}')
  fi
  if ! python3 -c "
import json, sys
try:
    r = json.load(open('$out'))
except Exception as e:
    print('json-parse-fail:', e, file=sys.stderr); sys.exit(1)
http = $code
if not ($predicate):
    print('predicate-fail | http={} | response={}'.format(http, json.dumps(r)[:400]), file=sys.stderr)
    sys.exit(1)
" 2>/tmp/err.out; then
    FAIL_COUNT=$((FAIL_COUNT+1))
    FAILURES+=("$label")
    echo "FAIL  $label"
    sed 's/^/      /' /tmp/err.out
  else
    PASS_COUNT=$((PASS_COUNT+1))
    echo "pass  $label"
  fi
  rm -f "$out"
}

echo "saiku-ai-live: $URL"
login
echo ""

# ---- discovery ----
check "cubes list non-empty" GET "/rest/saiku/api/ai/cubes" '' \
  "isinstance(r, list) and len(r) >= 1 and any(c['cubeName']=='Sales' for c in r)"

check "schema Sales has Product dim" GET "/rest/saiku/api/ai/schema/$CUBE" '' \
  "'product' in r.get('dimensions',{}) and 'store sales' in r.get('measures',{})"

check "schema 3-segment cubeId → 400 cubeId" GET "/rest/saiku/api/ai/schema/foo/bar/baz" '' \
  "r.get('status')=='VALIDATION_ERROR' and r.get('field')=='cubeId'"

check "schema unknown cube → 400 cube + available" GET "/rest/saiku/api/ai/schema/unknown_foodmart/FoodMart/FoodMart/Nonsense" '' \
  "r.get('status')=='VALIDATION_ERROR' and r.get('field')=='cube' and 'Sales' in r.get('available',[])"

# ---- happy-path query shapes ----
check "simple measure × rows" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==3 and r['data'][0]['Store Sales']['value'] > 0"

check "order+limit emits TopCount" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"order":[{"by":"Store Sales","direction":"desc"}],"limit":2}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==2 and 'TopCount' in r['metadata']['generatedMdx']"

check "visualTotals=true" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Category"}],"visualTotals":true,"limit":3}' \
  "r.get('status')=='SUCCESS' and 'VISUALTOTALS' in r['metadata']['generatedMdx']"

check "explicit members on rows" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family","members":["[Product].[Products].[Drink]","[Product].[Products].[Food]"]}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==2"

check "columns axis cross-join (Quarter × measures)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"columns":[{"dimension":"Time","hierarchy":"Time","level":"Quarter"}]}' \
  "r.get('status')=='SUCCESS' and 'CROSSJOIN' in r['metadata']['generatedMdx'] and 'Q1' in [c['caption'] for c in r['metadata']['columns']]"

check "rows from same hierarchy → Hierarchize (Store State + Store Name)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Store","hierarchy":"Stores","level":"Store State"},{"dimension":"Store","hierarchy":"Stores","level":"Store Name"}],"limit":4,"nonEmpty":false}' \
  "r.get('status')=='SUCCESS' and 'Hierarchize' in r['metadata']['generatedMdx'] and 'CROSSJOIN' not in r['metadata']['generatedMdx']"

check "distinct-hierarchy rows CROSSJOIN (Time × Product)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Time","hierarchy":"Time","level":"Year"},{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"limit":6}' \
  "r.get('status')=='SUCCESS' and 'CROSSJOIN' in r['metadata']['generatedMdx']"

# ---- filter operators ----
check "filter op=in (single year)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Time","hierarchy":"Time","level":"Year","members":["[Time].[Time].[1997]"]}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==3"

check "filter op=between (Year 1997-1998 bare slicer set)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Time","hierarchy":"Time","level":"Year","op":"between","members":["[Time].[Time].[1997]","[Time].[Time].[1998]"]}],"nonEmpty":false}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==3 and r['data'][1]['Unit Sales']['value'] > 0"

check "filter op=not_in (exclude Canada)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Store","hierarchy":"Stores","level":"Store Country","op":"not_in","members":["[Store].[Stores].[Canada]"]}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==3"

check "filter op=descendants_of (USA)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Store","hierarchy":"Stores","level":"Store Country","op":"descendants_of","members":["[Store].[Stores].[USA]"]}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==3"

check "filter op=relative last_n_quarters n=2 (executes; empty under NON EMPTY is data-specific)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Time","hierarchy":"Time","level":"Quarter","op":"relative","value":"last_n_quarters","n":2}]}' \
  "r.get('status')=='SUCCESS' and 'Tail' in r['metadata']['generatedMdx']"

# ---- validation paths ----
check "validation: unknown measure" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Made Up Measure"}],"rows":[]}' \
  "r.get('status')=='VALIDATION_ERROR' and r.get('field')=='measures[].name' and 'Store Sales' in r.get('available',[])"

check "validation: two filters on one hierarchy" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Time","hierarchy":"Time","level":"Year","members":["[Time].[Time].[1997]"]},{"dimension":"Time","hierarchy":"Time","level":"Quarter","members":["[Time].[Time].[1997].[Q1]"]}]}' \
  "r.get('status')=='VALIDATION_ERROR' and r.get('field').endswith('.hierarchy') and 'Multiple filters' in r.get('error','')"

# ---- members search ----
check "members search case-insensitive (q=Excellent)" GET "/rest/saiku/api/ai/members/search?cubeId=$CUBE&dimension=Product&hierarchy=Products&level=Brand%20Name&q=Excellent&limit=5" '' \
  "isinstance(r, list) and len(r) >= 1 and any(m['caption']=='Excellent' for m in r)"

# ---- preview ----
check "preview emits MDX without execute" POST "/rest/saiku/api/ai/query/preview" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}]}' \
  "r.get('status')=='PREVIEW' and 'SELECT' in r.get('generatedMdx','')"

# ---- async pipeline ----
ASYNC_OUT=$(mktemp)
curl -sS -b "$COOKIES" -X POST "$URL/rest/saiku/api/ai/query/execute-async" \
  -H 'Content-Type: application/json' \
  --data '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}]}' \
  -o "$ASYNC_OUT"
ASYNC_QID=$(python3 -c "import json; print(json.load(open('$ASYNC_OUT')).get('queryId',''))")
rm -f "$ASYNC_OUT"
if [[ -n "$ASYNC_QID" ]]; then
  check "async result is SUCCESS with rows" GET "/rest/saiku/api/ai/query/result/$ASYNC_QID" '' \
    "r.get('status')=='SUCCESS' and r.get('totalRows')==3"
else
  FAIL_COUNT=$((FAIL_COUNT+1)); FAILURES+=("async submit returned no queryId"); echo "FAIL  async submit returned no queryId"
fi

# Submit a fresh query for the cancel test
ASYNC_OUT=$(mktemp)
curl -sS -b "$COOKIES" -X POST "$URL/rest/saiku/api/ai/query/execute-async" \
  -H 'Content-Type: application/json' \
  --data '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Name"}],"limit":1000}' \
  -o "$ASYNC_OUT"
ASYNC_QID=$(python3 -c "import json; print(json.load(open('$ASYNC_OUT')).get('queryId',''))")
rm -f "$ASYNC_OUT"
if [[ -n "$ASYNC_QID" ]]; then
  check "async cancel returns CANCELLED" DELETE "/rest/saiku/api/ai/query/$ASYNC_QID" '' \
    "r.get('status')=='CANCELLED' and r.get('queryId')=='$ASYNC_QID'"
fi

# ---- drillthrough ----
DRILL_OUT=$(mktemp)
curl -sS -b "$COOKIES" -X POST "$URL/rest/saiku/api/ai/query" \
  -H 'Content-Type: application/json' \
  --data '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}]}' \
  -o "$DRILL_OUT"
DRILL_QID=$(python3 -c "import json; print(json.load(open('$DRILL_OUT')).get('queryId',''))")
rm -f "$DRILL_OUT"
if [[ -n "$DRILL_QID" ]]; then
  check "drillthrough returns typed cell envelopes" GET "/rest/saiku/api/ai/query/$DRILL_QID/drillthrough?maxrows=3" '' \
    "r.get('rowCount')==3 and 'Year' in r['rows'][0] and r['rows'][0]['Year'].get('value')==1997.0"
fi

# ---- other cubes ----
check "HR cube (avoiding Department) — Time × Org Salary works" POST "/rest/saiku/api/ai/query" \
  '{"cube":"unknown_foodmart/FoodMart/FoodMart/HR","measures":[{"name":"Org Salary"}],"rows":[{"dimension":"Time","hierarchy":"Time","level":"Year"}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==2 and r['data'][0]['Org Salary'].get('unit')=='GBP'"

check "Store cube — Store Type level" POST "/rest/saiku/api/ai/query" \
  '{"cube":"unknown_foodmart/FoodMart/FoodMart/Store","measures":[{"name":"Store Sqft"}],"rows":[{"dimension":"Store Type","level":"Store Type"}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==5"

check "Sales 2 cube — Quarter × Sales+Customer Count" POST "/rest/saiku/api/ai/query" \
  '{"cube":"unknown_foodmart/FoodMart/FoodMart/Sales 2","measures":[{"name":"Sales Count"},{"name":"Customer Count"}],"rows":[{"dimension":"Time","hierarchy":"Time","level":"Quarter"}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==4"

# ---- legacy /query/execute coverage ----
check "legacy /query/execute raw MDX" POST "/rest/saiku/api/query/execute" \
  '{"name":"live-mdx","cube":{"connection":"unknown_foodmart","catalog":"FoodMart","schema":"FoodMart","name":"Sales","uniqueName":"[Sales]","caption":"Sales"},"type":"MDX","mdx":"SELECT NON EMPTY {[Measures].[Store Sales]} ON COLUMNS, NON EMPTY [Product].[Products].[Product Family].Members ON ROWS FROM [Sales]"}' \
  "r.get('error') is None and r.get('height')==4 and r.get('width')==2"

check "legacy /query/execute TopCount(Order(...)) MDX" POST "/rest/saiku/api/query/execute" \
  '{"name":"live-top5","cube":{"connection":"unknown_foodmart","catalog":"FoodMart","schema":"FoodMart","name":"Sales","uniqueName":"[Sales]","caption":"Sales"},"type":"MDX","mdx":"SELECT NON EMPTY {[Measures].[Store Sales]} ON COLUMNS, NON EMPTY TopCount(Order([Product].[Products].[Product Category].Members, [Measures].[Store Sales], BDESC), 5, [Measures].[Store Sales]) ON ROWS FROM [Sales]"}' \
  "r.get('error') is None and r.get('height')==6"

# ---- info / version ----
check "info endpoint 200" GET "/rest/saiku/info" '' "isinstance(r, list)"
check "mondrian server version" GET "/rest/saiku/statistics/mondrian/server/version" '' \
  "r.get('majorVersion')==4 and r.get('productName')=='mondrian'"

# ---- summary ----
echo ""
echo "---"
echo "PASS: $PASS_COUNT"
echo "FAIL: $FAIL_COUNT"
if (( FAIL_COUNT > 0 )); then
  echo "failed:"
  for f in "${FAILURES[@]}"; do echo "  - $f"; done
  exit 1
fi
echo "all green."
