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
  "r.get('status')=='SUCCESS' and 'CROSSJOIN' in r['metadata']['generatedMdx'] and any('Q1' in c['caption'] for c in r['metadata']['columns'])"

check "multi-measure × dim crossjoin preserves all cells (saiku#789)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"},{"name":"Unit Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"columns":[{"dimension":"Time","hierarchy":"Time","level":"Quarter"}]}' \
  "r.get('status')=='SUCCESS' and len(r['metadata']['columns'])==8 and 'Store Sales | Q1' in [c['caption'] for c in r['metadata']['columns']] and 'Unit Sales | Q1' in [c['caption'] for c in r['metadata']['columns']] and r['data'][0]['Store Sales | Q1']['value']!=r['data'][0]['Unit Sales | Q1']['value']"

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

check "validation: MDX injection in members[] rejected" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family","members":["[Product].[Products].[Drink], Crossjoin([Time].[1997])"]}]}' \
  "r.get('status')=='VALIDATION_ERROR' and r.get('field')=='rows[0].members[0]' and 'Embedded MDX' in r.get('error','')"

check "validation: axis hierarchy reused in filter rejected (saiku#784)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Customer","hierarchy":"Customers","level":"City"}],"filters":[{"dimension":"Customer","hierarchy":"Customers","level":"State Province","op":"descendants_of","members":["[Customer].[Customers].[USA].[CA]"]}]}' \
  "r.get('status')=='VALIDATION_ERROR' and r.get('field')=='filters[0].hierarchy' and 'already on the rows/columns axis' in r.get('error','')"

check "validation: nonexistent member ref translated to 400" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family","members":["[Product].[Products].[Drink]","[Product].[Products].[Pizza]"]}]}' \
  "r.get('status')=='VALIDATION_ERROR' and r.get('field')=='members' and 'Pizza' in r.get('error','') and 'not found in cube' in r.get('error','')"

check "validation: multi-key order rejected" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"},{"name":"Unit Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"order":[{"by":"Store Sales","direction":"desc"},{"by":"Unit Sales","direction":"asc"}],"limit":2}' \
  "r.get('status')=='VALIDATION_ERROR' and r.get('field')=='order' and 'Only one sort key' in r.get('error','')"

check "validation: member at wrong level rejected (saiku#790)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family","members":["[Product].[Products].[Drink].[Beverages]"]}]}' \
  "r.get('status')=='VALIDATION_ERROR' and r.get('field')=='rows[0].members[0]' and 'Product Department' in r.get('error','') and 'Product Family' in r.get('error','')"

check "validation: order direction must be asc/desc" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"order":[{"by":"Store Sales","direction":"invalid"}],"limit":2}' \
  "r.get('status')=='VALIDATION_ERROR' and r.get('field')=='order[0].direction' and 'asc' in r.get('error','') and 'desc' in r.get('error','')"

check "validation: duplicate measures rejected (saiku#796)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"},{"name":"Store Sales"},{"name":"Unit Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}]}' \
  "r.get('status')=='VALIDATION_ERROR' and r.get('field')=='measures' and 'appears more than once' in r.get('error','')"

check "validation: duplicate rows[] axis rejected (saiku#797)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"},{"dimension":"Product","hierarchy":"Products","level":"Product Family"}]}' \
  "r.get('status')=='VALIDATION_ERROR' and r.get('field')=='rows' and 'Duplicate axis selection' in r.get('error','')"

check "validation: cross-dim member ref in filter rejected (saiku#798)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Time","hierarchy":"Time","level":"Year","members":["[Customer].[Customers].[USA]"]}]}' \
  "r.get('status')=='VALIDATION_ERROR' and r.get('field')=='filters[0].members[0]' and 'Customer' in r.get('error','') and 'Time' in r.get('error','')"

check "validation: cross-hier member ref in filter rejected (saiku#799)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Promotion","hierarchy":"Media Type","level":"Media Type","members":["[Promotion].[Promotions].[Bag Stuffers]"]}]}' \
  "r.get('status')=='VALIDATION_ERROR' and r.get('field')=='filters[0].members[0]' and 'Promotions' in r.get('error','') and 'Media Type' in r.get('error','')"

check "drillthrough on bogus queryId returns 404 (saiku#783)" GET "/rest/saiku/api/ai/query/bogus-uuid-1234/drillthrough?maxrows=5" '' \
  "http==404 and r.get('status')=='VALIDATION_ERROR' and r.get('field')=='queryId' and 'Unknown queryId' in r.get('error','')"

# Drillthrough with an unknown `returns` member should be 400, not 500
# leaking Mondrian's "unknown member ... in RETURN clause" text (saiku#795).
RET_QID=$(curl -sS -b "$COOKIES" -X POST "$URL/rest/saiku/api/ai/query" \
  -H 'Content-Type: application/json' \
  --data '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}]}' \
  | python3 -c "import json,sys;print(json.load(sys.stdin).get('queryId',''))")
if [[ -n "$RET_QID" ]]; then
  check "drillthrough with unknown returns member is 400 (saiku#795)" GET "/rest/saiku/api/ai/query/$RET_QID/drillthrough?maxrows=3&returns=%5BMeasures%5D.%5BFake%5D" '' \
    "http==400 and r.get('status')=='VALIDATION_ERROR' and r.get('field')=='returns' and 'RETURN' in r.get('error','')"
fi

# Drillthrough on a 0-row query (Foodmart has no 1998 data) should be a clean
# 200 with rowCount=0 — not a 500 leaking Mondrian's "Cell coordinates fall
# outside CellSet bounds" internal message (saiku#794).
EMPTY_OUT=$(mktemp)
EMPTY_QID=$(curl -sS -b "$COOKIES" -X POST "$URL/rest/saiku/api/ai/query" \
  -H 'Content-Type: application/json' \
  --data '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Time","hierarchy":"Time","level":"Year","members":["[Time].[Time].[1998]"]}],"nonEmpty":true}' \
  | python3 -c "import json,sys;d=json.load(sys.stdin);print(d.get('queryId',''))")
if [[ -n "$EMPTY_QID" ]]; then
  check "drillthrough on 0-row query returns 200 + empty rows (saiku#794)" GET "/rest/saiku/api/ai/query/$EMPTY_QID/drillthrough?maxrows=5" '' \
    "http==200 and r.get('rowCount')==0 and r.get('rows')==[]"
fi
rm -f "$EMPTY_OUT"

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

check "validation: same-member two-form filter rejected (saiku#804)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Time","hierarchy":"Time","level":"Year","op":"in","members":["[Time].[Time].[1997]","[Time].[Time].[Year].&[1997]"]}]}' \
  "r.get('status')=='VALIDATION_ERROR' and r.get('field')=='filters[0].members[1]' and 'same member' in r.get('error','')"

check "validation: relative n<=0 rejected" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Time","hierarchy":"Time","level":"Quarter","op":"relative","value":"last_n_quarters","n":0}]}' \
  "r.get('status')=='VALIDATION_ERROR' and r.get('field')=='filters[0].n' and 'n >= 1' in r.get('error','')"

check "validation: between with heterogeneous-level endpoints rejected (saiku#802)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Time","hierarchy":"Time","level":"Quarter","op":"between","members":["[Time].[Time].[1997]","[Time].[Time].[1997].[Q3]"]}]}' \
  "r.get('status')=='VALIDATION_ERROR' and r.get('field')=='filters[0].members' and 'same level' in r.get('error','')"

check "validation: relative + members[] combo rejected" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Time","hierarchy":"Time","level":"Quarter","op":"relative","value":"ytd","members":["[Time].[Time].[1997].[Q1]"]}]}' \
  "r.get('status')=='VALIDATION_ERROR' and r.get('field')=='filters[0].members' and 'doesn' in r.get('error','')"

check "two multi-member slicer filters (saiku#801 — CROSSJOIN tuple slicer)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Time","hierarchy":"Time","level":"Quarter","op":"in","members":["[Time].[Time].[1997].[Q1]","[Time].[Time].[1997].[Q4]"]},{"dimension":"Promotion","hierarchy":"Media Type","level":"Media Type","members":["[Promotion].[Media Type].[No Media]","[Promotion].[Media Type].[Daily Paper]"]}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==3 and 'CROSSJOIN' in r['metadata']['generatedMdx']"

check "Promotion Sales (CASE-expr measure) × Quarter (saiku#805 — Calcite segment-load fallback)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Promotion Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"columns":[{"dimension":"Time","hierarchy":"Time","level":"Quarter"}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')>=3 and r['data'][0]['Promotion Sales | Q1']['value']>0"

check "Warehouse cube — Country level (saiku#781 — Calcite cardinality probe fallback)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"unknown_foodmart/FoodMart/FoodMart/Warehouse","measures":[{"name":"Warehouse Sales"}],"rows":[{"dimension":"Warehouse","hierarchy":"Warehouses","level":"Country"}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')>=1 and r['data'][0]['Warehouse Sales']['value']>0"

check "Sales 2 cube — Quarter × Sales+Customer Count" POST "/rest/saiku/api/ai/query" \
  '{"cube":"unknown_foodmart/FoodMart/FoodMart/Sales 2","measures":[{"name":"Sales Count"},{"name":"Customer Count"}],"rows":[{"dimension":"Time","hierarchy":"Time","level":"Quarter"}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==4"

check "Customer Count by Yearly Income desc (untested hier — iter 267)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Customer Count"}],"rows":[{"dimension":"Customer","hierarchy":"Yearly Income","level":"Yearly Income"}],"order":[{"by":"Customer Count","direction":"desc"}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==8 and r['data'][0]['Yearly Income']=='\$30K - \$50K' and r['data'][0]['Customer Count']['value']==1786.0 and 'Order(' in r['metadata']['generatedMdx']"

check "rows from two hierarchies of same dim → CROSSJOIN not Hierarchize (iter 268)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Sales Count"}],"rows":[{"dimension":"Customer","hierarchy":"Marital Status","level":"Marital Status"},{"dimension":"Customer","hierarchy":"Education Level","level":"Education Level"}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==10 and 'CROSSJOIN' in r['metadata']['generatedMdx'] and 'Hierarchize' not in r['metadata']['generatedMdx']"

check "Profit calc measure by Media Type top 5 — currency sniff EUR (iter 269)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Profit"}],"rows":[{"dimension":"Promotion","hierarchy":"Media Type","level":"Media Type"}],"order":[{"by":"Profit","direction":"desc"}],"limit":5}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==5 and r['data'][0]['Media Type']=='No Media' and r['data'][0]['Profit']['unit']=='EUR' and 'TopCount(' in r['metadata']['generatedMdx']"

check "localised measure name resolves uniqueName + % unit sniff (iter 270)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Gewinn-Wachstum"}],"rows":[{"dimension":"Time","hierarchy":"Time","level":"Quarter"}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==4 and '[Measures].[Profit Growth]' in r['metadata']['generatedMdx'] and r['data'][0]['Gewinn-Wachstum']['unit']=='%'"

check "rows-deep + columns + slicer combo: Product Subcategory × Quarter / USA (iter 271)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Subcategory"}],"columns":[{"dimension":"Time","hierarchy":"Time","level":"Quarter"}],"filters":[{"dimension":"Store","hierarchy":"Stores","level":"Store Country","op":"descendants_of","members":["[Store].[Stores].[USA]"]}],"limit":3}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==3 and len(r['metadata']['columns'])==4 and 'HEAD(' in r['metadata']['generatedMdx'] and 'WHERE ([Store].[Stores].[USA])' in r['metadata']['generatedMdx']"

check "parallel Time hierarchy: Time/Weekly/Year (iter 272)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Time","hierarchy":"Weekly","level":"Year"}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==1 and r['data'][0]['Year']=='1997' and r['data'][0]['Unit Sales']['value']==266773.0 and '[Time].[Weekly].[Year]' in r['metadata']['generatedMdx']"

# Drillthrough on a real successfully-executed query (iter 273).
DT_QID=$(curl -sS -b "$COOKIES" -X POST "$URL/rest/saiku/api/ai/query" \
  -H 'Content-Type: application/json' \
  --data '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"limit":1}' \
  | python3 -c "import json,sys;print(json.load(sys.stdin).get('queryId',''))")
if [[ -n "$DT_QID" ]]; then
  check "drillthrough on real queryId returns fact rows (iter 273)" GET "/rest/saiku/api/ai/query/$DT_QID/drillthrough?maxrows=5" '' \
    "http==200 and r.get('rowCount')==5 and len(r['rows'])==5 and 'Year' in r['rows'][0] and 'Product Family' in r['rows'][0] and 'Unit Sales' in r['rows'][0] and r['rows'][0]['Product Family']['formatted']=='Drink'"
fi

check "format=matrix uses positional keys, labels live in metadata (iter 274)" POST "/rest/saiku/api/ai/query?format=matrix" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"columns":[{"dimension":"Time","hierarchy":"Time","level":"Quarter"}]}' \
  "r.get('format')=='matrix' and len(r.get('data',[]))==0 and len(r['matrix'])==3 and list(r['matrix'][0].keys())==['0','1','2','3'] and r['matrix'][0]['0']['value']==11585.8 and r['metadata']['rows'][0]['caption']=='Drink'"

check "explicit Year members on rows + nonEmpty=false in hasAll=false hier (saiku#807 — iter 275)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Time","hierarchy":"Time","level":"Year","members":["[Time].[Time].[1997]","[Time].[Time].[1998]"]}],"nonEmpty":false}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==2 and r['data'][0]['Year']=='1997' and r['data'][0]['Store Sales']['value']==565238.13 and r['data'][1]['Year']=='1998' and r['data'][1]['Store Sales']['value'] is None"

check "explicit Quarter members in hasAll=false hier — cross-validates saiku#807 at depth>0 (iter 276)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Time","hierarchy":"Time","level":"Quarter","members":["[Time].[Time].[1997].[Q1]","[Time].[Time].[1997].[Q3]"]}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==2 and r['data'][0]['Quarter']=='Q1' and r['data'][0]['Store Sales']['value']==139628.35 and r['data'][1]['Quarter']=='Q3' and r['data'][1]['Store Sales']['value']==140271.89"

check "Customer/Gender single-member slicer — F-only (iter 277)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Sales Count"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Customer","hierarchy":"Gender","level":"Gender","op":"in","members":["[Customer].[Gender].[F]"]}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==3 and r['data'][0]['Sales Count']['value']==3953.0 and r['data'][1]['Sales Count']['value']==30848.0 and 'WHERE ([Customer].[Gender].[F])' in r['metadata']['generatedMdx']"

check "Performance Season Day dim — TopCount(5) Unit Sales (iter 278)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Performance Season Day","hierarchy":"Performance","level":"Performance Season Day"}],"order":[{"by":"Unit Sales","direction":"desc"}],"limit":5}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==5 and r['data'][0]['Performance Season Day']=='0' and r['data'][0]['Unit Sales']['value']==195448.0 and 'TopCount(' in r['metadata']['generatedMdx']"

check "order direction=asc + limit emits BottomCount (iter 279)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Store","hierarchy":"Stores","level":"Store Name"}],"order":[{"by":"Unit Sales","direction":"asc"}],"limit":3}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==3 and 'BottomCount(' in r['metadata']['generatedMdx'] and r['data'][0]['Unit Sales']['value']==2117.0 and r['data'][2]['Unit Sales']['value']==2237.0"

check "empty result set under nonEmpty=true returns 200 SUCCESS / 0 rows (iter 280)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Time","hierarchy":"Time","level":"Year","op":"in","members":["[Time].[Time].[1998]"]}],"nonEmpty":true}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==0 and r.get('data')==[] and 'WHERE ([Time].[Time].[1998])' in r['metadata']['generatedMdx']"

check "deepest level (Product Name, depth 6) — TopCount(5) Store Sales (iter 281)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Name"}],"order":[{"by":"Store Sales","direction":"desc"}],"limit":5}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==5 and r['data'][0]['Product Name']=='Hermanos Green Pepper' and r['data'][0]['Store Sales']['value']==922.54 and 'TopCount(' in r['metadata']['generatedMdx']"

check "Customer/Customers/Country geo level — single-country data (iter 282)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Customer Count"}],"rows":[{"dimension":"Customer","hierarchy":"Customers","level":"Country"}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==1 and r['data'][0]['Country']=='USA' and r['data'][0]['Customer Count']['value']==5581.0"

check "Store Size in SQFT — numeric-keyed hier with #null member (iter 283)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Store","hierarchy":"Store Size in SQFT","level":"Store Sqft"}],"order":[{"by":"Store Sales","direction":"desc"}],"limit":5}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==5 and r['data'][0]['Store Sqft']=='27694' and r['data'][1]['Store Sqft']=='#null' and r['data'][0]['Store Sales']['value']==87218.28"

check "Promotion Name × Promotion Sales (saiku#805 CASE-expr × deep promo level — iter 284)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Promotion Sales"}],"rows":[{"dimension":"Promotion","hierarchy":"Promotions","level":"Promotion Name"}],"order":[{"by":"Promotion Sales","direction":"desc"}],"limit":3}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==3 and r['data'][0]['Promotion Name']=='Cash Register Lottery' and r['data'][0]['Promotion Sales']['value']==9821.71"

check "maximally-shaped query: 2 measures × Quarter cols × Family rows + Gender slicer + TopCount (iter 285)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"},{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"columns":[{"dimension":"Time","hierarchy":"Time","level":"Quarter"}],"filters":[{"dimension":"Customer","hierarchy":"Gender","level":"Gender","op":"in","members":["[Customer].[Gender].[M]"]}],"order":[{"by":"Unit Sales","direction":"desc"}],"limit":3}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==3 and len(r['metadata']['columns'])==8 and r['data'][0]['Product Family']=='Food' and r['data'][0]['Unit Sales | Q1']['value']==23977.0 and 'TopCount(' in r['metadata']['generatedMdx'] and 'WHERE ([Customer].[Gender].[M])' in r['metadata']['generatedMdx']"

check "Customer State Province + Order(desc, no limit) emits Order BDESC (iter 286)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Customer Count"}],"rows":[{"dimension":"Customer","hierarchy":"Customers","level":"State Province"}],"order":[{"by":"Customer Count","direction":"desc"}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==3 and [d['State Province'] for d in r['data']]==['CA','WA','OR'] and r['data'][0]['Customer Count']['value']==2716.0 and sum(d['Customer Count']['value'] for d in r['data'])==5581.0 and 'Order(' in r['metadata']['generatedMdx'] and 'BDESC' in r['metadata']['generatedMdx']"

check "mixed-format multi-measure: EUR + % unit sniffers independent per cell (iter 287)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Profit"},{"name":"Gewinn-Wachstum"}],"rows":[{"dimension":"Time","hierarchy":"Time","level":"Quarter"}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==4 and r['data'][0]['Profit']['unit']=='EUR' and r['data'][0]['Gewinn-Wachstum']['unit']=='%' and r['data'][0]['Profit']['formatted']=='83,876.11 \u20ac' and r['data'][0]['Gewinn-Wachstum']['value']==0.0"

check "Time/Date Only/Date String TopCount(5) — string-keyed date hier (iter 288)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Time","hierarchy":"Date Only","level":"Date String"}],"order":[{"by":"Unit Sales","direction":"desc"}],"limit":5}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==5 and r['data'][0]['Date String']=='1997/07/27' and r['data'][0]['Unit Sales']['value']==3850.0 and '[Time].[Date Only].[Date String]' in r['metadata']['generatedMdx']"

check "HR cube: Pay Type × Org Salary + Number of Employees — GBP unit sniff (iter 289)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"unknown_foodmart/FoodMart/FoodMart/HR","measures":[{"name":"Org Salary"},{"name":"Number of Employees"}],"rows":[{"dimension":"Employee","hierarchy":"Pay Type","level":"Pay Type"}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==2 and r['data'][0]['Pay Type']=='Hourly' and r['data'][0]['Org Salary']['unit']=='GBP' and r['data'][0]['Number of Employees']['value']==283.0 and r['data'][1]['Number of Employees']['value']==333.0"

check "HR Management Role + Avg Salary — org-pyramid shape (iter 290)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"unknown_foodmart/FoodMart/FoodMart/HR","measures":[{"name":"Number of Employees"},{"name":"Avg Salary"}],"rows":[{"dimension":"Employee","hierarchy":"Position","level":"Management Role"}],"order":[{"by":"Number of Employees","direction":"desc"}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==5 and r['data'][0]['Management Role']=='Store Full Time Staff' and r['data'][0]['Number of Employees']['value']==405.0 and r['data'][4]['Management Role']=='Senior Management' and r['data'][4]['Number of Employees']['value']==8.0 and r['data'][4]['Avg Salary']['unit']=='GBP'"

check "HR cube + unwirable Store dim returns structured 400 not opaque 500 (saiku#808 — iter 291)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"unknown_foodmart/FoodMart/FoodMart/HR","measures":[{"name":"Org Salary"}],"rows":[{"dimension":"Store","hierarchy":"Stores","level":"Store Country"}]}' \
  "http==400 and r.get('status')=='VALIDATION_ERROR' and r.get('field')=='rows' and 'PhysPath' in r.get('error','') and 'wired' in r.get('error','')"

check "3-dim rows crossjoin: Family × Quarter × Gender + HEAD (iter 292)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"},{"dimension":"Time","hierarchy":"Time","level":"Quarter"},{"dimension":"Customer","hierarchy":"Gender","level":"Gender"}],"limit":6}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==6 and r['data'][0]['Product Family']=='Drink' and r['data'][0]['Quarter']=='Q1' and r['data'][0]['Gender']=='F' and r['data'][0]['Unit Sales']['value']==2934.0 and r['metadata']['generatedMdx'].count('CROSSJOIN')==2"

check "columns 2-dim crossjoin: Quarter × Gender — pipe-separated captions (iter 293)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Sales Count"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"columns":[{"dimension":"Time","hierarchy":"Time","level":"Quarter"},{"dimension":"Customer","hierarchy":"Gender","level":"Gender"}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==3 and len(r['metadata']['columns'])==8 and 'Sales Count | Q1 | F' in [c['caption'] for c in r['metadata']['columns']] and r['data'][0]['Sales Count | Q1 | F']['value']==953.0 and r['metadata']['generatedMdx'].count('CROSSJOIN')==2"

check "HR Education Level × Avg Salary — education-salary correlation (iter 294)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"unknown_foodmart/FoodMart/FoodMart/HR","measures":[{"name":"Avg Salary"},{"name":"Number of Employees"}],"rows":[{"dimension":"Employee","hierarchy":"Education Level","level":"Education Level"}],"order":[{"by":"Avg Salary","direction":"desc"}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==5 and r['data'][0]['Education Level']=='Graduate Degree' and r['data'][4]['Education Level']=='Partial High School' and sum(d['Number of Employees']['value'] for d in r['data'])==616.0"

check "Sales 2 cube: Profit is USD not EUR (per-cube format isolation, iter 295)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"unknown_foodmart/FoodMart/FoodMart/Sales 2","measures":[{"name":"Sales Count"},{"name":"Profit"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"columns":[{"dimension":"Time","hierarchy":"Time","level":"Quarter"}],"filters":[{"dimension":"Gender","hierarchy":"Gender","level":"Gender","op":"in","members":["[Gender].[Gender].[F]"]}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==3 and len(r['metadata']['columns'])==8 and r['data'][0]['Sales Count | Q1']['value']==953.0 and r['data'][0]['Profit | Q1']['unit']=='USD' and 'WHERE ([Gender].[Gender].[F])' in r['metadata']['generatedMdx']"

check "Customer descendants_of slicer compacts to ancestor in WHERE (iter 296)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Customer Count"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Customer","hierarchy":"Customers","level":"State Province","op":"descendants_of","members":["[Customer].[Customers].[USA].[CA]"]}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==3 and r['data'][0]['Customer Count']['value']==1434.0 and r['data'][1]['Customer Count']['value']==2676.0 and 'WHERE ([Customer].[Customers].[USA].[CA])' in r['metadata']['generatedMdx']"

check "HR Employee/Gender — 4th path to 616 aggregate (iter 297)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"unknown_foodmart/FoodMart/FoodMart/HR","measures":[{"name":"Number of Employees"},{"name":"Avg Salary"}],"rows":[{"dimension":"Employee","hierarchy":"Gender","level":"Gender"}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==2 and r['data'][0]['Gender']=='F' and r['data'][0]['Number of Employees']['value']==330.0 and r['data'][1]['Number of Employees']['value']==286.0 and sum(d['Number of Employees']['value'] for d in r['data'])==616.0"

check "members search with no-match q returns empty list 200 (iter 298)" GET "/rest/saiku/api/ai/members/search?cubeId=$CUBE&dimension=Product&hierarchy=Products&level=Brand%20Name&q=ZZZZNothingMatches&limit=10" '' \
  "http==200 and isinstance(r, list) and r==[]"

check "members search unknown dim → 400 + field + available list (iter 299)" GET "/rest/saiku/api/ai/members/search?cubeId=$CUBE&dimension=NotARealDim&hierarchy=Products&level=Product%20Family&q=&limit=10" '' \
  "http==400 and r.get('status')=='VALIDATION_ERROR' and r.get('field')=='dimension' and 'Unknown dimension' in r.get('error','') and 'Product' in r.get('available',[]) and 'Customer' in r.get('available',[])"

check "preview emits the same MDX /query would execute, status=PREVIEW (iter 300)" POST "/rest/saiku/api/ai/query/preview" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"},{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"columns":[{"dimension":"Time","hierarchy":"Time","level":"Quarter"}],"filters":[{"dimension":"Customer","hierarchy":"Gender","level":"Gender","op":"in","members":["[Customer].[Gender].[M]"]}],"order":[{"by":"Unit Sales","direction":"desc"}],"limit":3}' \
  "r.get('status')=='PREVIEW' and r.get('queryId') and 'TopCount' in r.get('generatedMdx','') and 'WHERE ([Customer].[Gender].[M])' in r.get('generatedMdx','') and 'CROSSJOIN' in r.get('generatedMdx','')"

check "preview with bogus measure uses same validation envelope as /query (iter 301)" POST "/rest/saiku/api/ai/query/preview" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Made-Up Measure"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}]}' \
  "http==400 and r.get('status')=='VALIDATION_ERROR' and r.get('field')=='measures[].name' and 'Unknown measure' in r.get('error','') and 'Store Sales' in r.get('available',[]) and 'Profit' in r.get('available',[])"

check "2-axis nonEmpty=false preserves empty cells across both axes (iter 302)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Time","hierarchy":"Time","level":"Year","members":["[Time].[Time].[1997]","[Time].[Time].[1998]"]}],"columns":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"nonEmpty":false}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==2 and len(r['metadata']['columns'])==3 and r['data'][0]['Unit Sales | Drink']['value']==24597.0 and r['data'][1]['Unit Sales | Drink']['value'] is None and r['data'][1]['Unit Sales | Drink']['formatted']=='' and 'NON EMPTY' not in r['metadata']['generatedMdx']"

check "filter via parallel Time/Weekly hier — slicer agrees with Time/Time totals (iter 303)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Time","hierarchy":"Weekly","level":"Year","op":"in","members":["[Time].[Weekly].[1997]"]}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==3 and r['data'][0]['Unit Sales']['value']==24597.0 and r['data'][1]['Unit Sales']['value']==191940.0 and r['data'][2]['Unit Sales']['value']==50236.0 and 'WHERE ([Time].[Weekly].[1997])' in r['metadata']['generatedMdx']"

check "HR Marital Status — 5th path to 616 aggregate (iter 304)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"unknown_foodmart/FoodMart/FoodMart/HR","measures":[{"name":"Number of Employees"},{"name":"Avg Salary"}],"rows":[{"dimension":"Employee","hierarchy":"Marital Status","level":"Marital Status"}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==2 and r['data'][0]['Marital Status']=='M' and r['data'][0]['Number of Employees']['value']==311.0 and r['data'][1]['Number of Employees']['value']==305.0 and sum(d['Number of Employees']['value'] for d in r['data'])==616.0"

check "order.by targets named measure, not first measure (iter 305)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"},{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"order":[{"by":"Store Sales","direction":"asc"}],"limit":2}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==2 and r['data'][0]['Product Family']=='Drink' and r['data'][0]['Store Sales']['value']==48836.21 and 'BottomCount(' in r['metadata']['generatedMdx'] and '[Measures].[Store Sales])' in r['metadata']['generatedMdx']"

check "members search unknown level → 400 + 7-level available (iter 306)" GET "/rest/saiku/api/ai/members/search?cubeId=$CUBE&dimension=Product&hierarchy=Products&level=NotALevel&q=&limit=5" '' \
  "http==400 and r.get('status')=='VALIDATION_ERROR' and r.get('field')=='level' and 'Unknown level' in r.get('error','') and 'Product Family' in r.get('available',[]) and 'Product Name' in r.get('available',[])"

check "members search unknown hier → 400 + 5-hier available — completes the leg trifecta (iter 307)" GET "/rest/saiku/api/ai/members/search?cubeId=$CUBE&dimension=Customer&hierarchy=NotAHier&level=Country&q=&limit=5" '' \
  "http==400 and r.get('status')=='VALIDATION_ERROR' and r.get('field')=='hierarchy' and 'Unknown hierarchy' in r.get('error','') and 'Customers' in r.get('available',[]) and 'Yearly Income' in r.get('available',[])"

check "legacy /query/execute with bad MDX → 200 + error field (legacy envelope) (iter 308)" POST "/rest/saiku/api/query/execute" \
  '{"name":"bad-mdx","cube":{"connection":"unknown_foodmart","catalog":"FoodMart","schema":"FoodMart","name":"Sales","uniqueName":"[Sales]","caption":"Sales"},"type":"MDX","mdx":"SELECT [NotAMeasure] ON COLUMNS FROM [Sales]"}' \
  "http==200 and r.get('error') is not None and 'not found in cube' in r.get('error','') and r.get('cellset') is None and r.get('height') is None"

# Drillthrough with returns=[Measures].[Unit Sales] (iter 309) — chained from a fresh queryId.
DT2_QID=$(curl -sS -b "$COOKIES" -X POST "$URL/rest/saiku/api/ai/query" \
  -H 'Content-Type: application/json' \
  --data '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"limit":1}' \
  | python3 -c "import json,sys;print(json.load(sys.stdin).get('queryId',''))")
if [[ -n "$DT2_QID" ]]; then
  check "drillthrough with custom returns yields aggregate, not fact rows (iter 309)" GET "/rest/saiku/api/ai/query/$DT2_QID/drillthrough?maxrows=3&returns=%5BMeasures%5D.%5BUnit%20Sales%5D" '' \
    "http==200 and r.get('rowCount')==1 and len(r['rows'])==1 and list(r['rows'][0].keys())==['Unit Sales'] and r['rows'][0]['Unit Sales']['value']==24597.0"
fi

check "filter op=relative last_n_months n=3 emits Tail (iter 310)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Time","hierarchy":"Time","level":"Month","op":"relative","value":"last_n_months","n":3}],"nonEmpty":false}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==3 and 'Tail([Time].[Time].[Month].Members, 3)' in r['metadata']['generatedMdx']"

check "filter op=relative ytd emits Ytd() — matches full-year totals (iter 311)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Time","hierarchy":"Time","level":"Quarter","op":"relative","value":"ytd"}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==3 and r['data'][0]['Unit Sales']['value']==24597.0 and r['data'][1]['Unit Sales']['value']==191940.0 and 'WHERE (Ytd())' in r['metadata']['generatedMdx']"

check "filter op=relative qtd emits Qtd() (iter 312)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Time","hierarchy":"Time","level":"Month","op":"relative","value":"qtd"}]}' \
  "r.get('status')=='SUCCESS' and 'WHERE (Qtd())' in r['metadata']['generatedMdx']"

check "filter op=relative mtd emits Mtd() (iter 313)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Time","hierarchy":"Time","level":"Month","op":"relative","value":"mtd"}]}' \
  "r.get('status')=='SUCCESS' and 'WHERE (Mtd())' in r['metadata']['generatedMdx']"

check "filter op=relative previous_period emits Tail(...,2).Item(0) (iter 314)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Time","hierarchy":"Time","level":"Quarter","op":"relative","value":"previous_period","n":1}]}' \
  "r.get('status')=='SUCCESS' and 'Tail([Time].[Time].[Quarter].Members, 2).Item(0)' in r['metadata']['generatedMdx']"

check "unknown relative preset → 400 + field + 8 presets in available (iter 314b)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Time","hierarchy":"Time","level":"Quarter","op":"relative","value":"parallel_period","n":1}]}' \
  "http==400 and r.get('field')=='filters[0].value' and 'Unknown relative preset' in r.get('error','') and 'ytd' in r.get('available',[]) and 'previous_period' in r.get('available',[]) and len(r.get('available',[]))==8"

check "filter op=relative last_n_years n=2 emits Tail at Year level (iter 315)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Time","hierarchy":"Time","level":"Year","op":"relative","value":"last_n_years","n":2}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==3 and r['data'][0]['Unit Sales']['value']==24597.0 and 'Tail([Time].[Time].[Year].Members, 2)' in r['metadata']['generatedMdx']"

check "filter op=relative last_n_days at Time/Weekly/Day — 8/8 relative presets pinned (iter 316)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Time","hierarchy":"Weekly","level":"Day","op":"relative","value":"last_n_days","n":5}]}' \
  "r.get('status')=='SUCCESS' and 'Tail([Time].[Weekly].[Day].Members, 5)' in r['metadata']['generatedMdx']"

check "HR Employee/Store Type — 6th path to 616, HQ-salary anomaly (iter 317)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"unknown_foodmart/FoodMart/FoodMart/HR","measures":[{"name":"Number of Employees"},{"name":"Avg Salary"}],"rows":[{"dimension":"Employee","hierarchy":"Store Type","level":"Store Type"}],"order":[{"by":"Number of Employees","direction":"desc"}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==6 and r['data'][0]['Store Type']=='Supermarket' and r['data'][0]['Number of Employees']['value']==372.0 and sum(d['Number of Employees']['value'] for d in r['data'])==616.0 and any(d['Store Type']=='HeadQuarters' and d['Avg Salary']['value']>200 for d in r['data'])"

# Pins saiku#809: TopCount on HR/Employee/Salary (numeric-keyed level)
# returns the right *members* but in natural ordinal order, not
# descending order of the sort criterion. Top member (Salary=20.0
# with 283 employees) is correct; the rest are the right 4 but
# shuffled. Asserting top + membership only, not the in-order
# ranking.
check "TopCount on numeric-keyed Salary level — top-by-membership not by ordering (saiku#809 — iter 318)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"unknown_foodmart/FoodMart/FoodMart/HR","measures":[{"name":"Number of Employees"}],"rows":[{"dimension":"Employee","hierarchy":"Salary","level":"Salary"}],"order":[{"by":"Number of Employees","direction":"desc"}],"limit":5}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==5 and r['data'][0]['Salary']=='20.0' and r['data'][0]['Number of Employees']['value']==283.0 and set(d['Salary'] for d in r['data'])=={'20.0','7000.0','8200.0','4400.0','6700.0'}"

check "Customer City TopCount(5) — string-keyed level sorts strictly desc (control for saiku#809 — iter 319)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Customer Count"}],"rows":[{"dimension":"Customer","hierarchy":"Customers","level":"City"}],"order":[{"by":"Customer Count","direction":"desc"}],"limit":5}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==5 and r['data'][0]['City']=='Lebanon' and r['data'][0]['Customer Count']['value']==108.0 and [d['Customer Count']['value'] for d in r['data']]==sorted([d['Customer Count']['value'] for d in r['data']], reverse=True)"

check "mixed-op multi-filter: descendants_of + in → tuple slicer (iter 320)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Sales Count"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Store","hierarchy":"Stores","level":"Store Country","op":"descendants_of","members":["[Store].[Stores].[USA]"]},{"dimension":"Customer","hierarchy":"Gender","level":"Gender","op":"in","members":["[Customer].[Gender].[F]"]}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==3 and r['data'][0]['Sales Count']['value']==3953.0 and r['data'][1]['Sales Count']['value']==30848.0 and 'WHERE ([Store].[Stores].[USA], [Customer].[Gender].[F])' in r['metadata']['generatedMdx']"

check "filter op=in 2-member set → set-literal slicer (iter 321)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Time","hierarchy":"Time","level":"Quarter","op":"in","members":["[Time].[Time].[1997].[Q1]","[Time].[Time].[1997].[Q3]"]}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==3 and r['data'][0]['Unit Sales']['value']==12041.0 and r['data'][1]['Unit Sales']['value']==95249.0 and 'WHERE ({[Time].[Time].[1997].[Q1], [Time].[Time].[1997].[Q3]})' in r['metadata']['generatedMdx']"

check "key-form member ref ([Year].&[1997]) executes against Mondrian (iter 322)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Unit Sales"}],"rows":[{"dimension":"Time","hierarchy":"Time","level":"Year","members":["[Time].[Time].[Year].&[1997]"]}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==1 and r['data'][0]['Year']=='1997' and r['data'][0]['Unit Sales']['value']==266773.0 and '[Time].[Time].[Year].&[1997]' in r['metadata']['generatedMdx']"

check "filter op=not_in with 2 members → Except set slicer (iter 323)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"'"$CUBE"'","measures":[{"name":"Store Sales"}],"rows":[{"dimension":"Product","hierarchy":"Products","level":"Product Family"}],"filters":[{"dimension":"Store","hierarchy":"Stores","level":"Store Country","op":"not_in","members":["[Store].[Stores].[Canada]","[Store].[Stores].[Mexico]"]}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==3 and r['data'][0]['Store Sales']['value']==48836.21 and 'WHERE (Except([Store].[Stores].[Store Country].Members, {[Store].[Stores].[Canada], [Store].[Stores].[Mexico]}))' in r['metadata']['generatedMdx']"

check "/cubes shape: 6 cubes, each has the 7-field summary (iter 324)" GET "/rest/saiku/api/ai/cubes" '' \
  "isinstance(r, list) and len(r)==6 and set(r[0].keys())=={'connectionName','catalog','schema','cubeName','cubeCaption','defaultMeasure','measureCount'} and set(c['cubeName'] for c in r)=={'HR','Sales','Sales 2','Store','Warehouse','Warehouse and Sales'} and any(c['cubeName']=='HR' and c['defaultMeasure']=='Org Salary' and c['measureCount']==5 for c in r)"

check "Warehouse and Sales virtual cube — Store2 aliased dim resolves (iter 325)" POST "/rest/saiku/api/ai/query" \
  '{"cube":"unknown_foodmart/FoodMart/FoodMart/Warehouse and Sales","measures":[{"name":"Sales Count"}],"rows":[{"dimension":"Store2","hierarchy":"Store Type","level":"Store Type"}]}' \
  "r.get('status')=='SUCCESS' and r.get('totalRows')==5 and any(d['Store Type']=='Supermarket' and d['Sales Count']['value']==47795.0 for d in r['data']) and sum(d['Sales Count']['value'] for d in r['data'])==86837.0"

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
