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
