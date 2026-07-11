# Saiku AI Query API — Usage Guide

A typed REST surface at `/saiku/api/ai/*` for agents and LLMs to query
Saiku Semantic Layer cubes **without ever seeing MDX**. The agent fetches a typed
schema, fills in a JSON request against it, the server validates names
against the live cube, builds MDX internally, and returns formatted
results.

Companion to the spec at
`saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/AiQueryPlan.md`.

> **Using this from Claude Desktop / Cursor / Cline?** The MCP wrapper
> in [`saiku-mcp/`](../saiku-mcp/) exposes this API as 6 typed tools.
> Build with `mvn -pl saiku-mcp -am -DskipTests package` and follow
> [`saiku-mcp/README.md`](../saiku-mcp/README.md) for the Claude Desktop
> config snippet. The MCP layer is a thin pass-through — every contract
> below applies to the MCP tools too.

---

## Quick orientation

Three endpoints cover ~90% of agent use:

| Endpoint | What it returns |
| --- | --- |
| `GET /saiku/api/ai/cubes` | List of available cubes |
| `GET /saiku/api/ai/schema/{cubeId}` | **Self-describing** typed schema for one cube (with sample members + ready-made example requests + JSON Schema of the request body) |
| `POST /saiku/api/ai/query` | Execute a typed request, return **records** (default) or matrix + metadata |

Plus the long-tail:

| Endpoint | Purpose |
| --- | --- |
| `POST /saiku/api/ai/query/preview` | Validate + compile to MDX **without executing**. Returns `{queryId, status:PREVIEW, generatedMdx}`. Useful for audit logs / cost estimation. |
| `GET /saiku/api/ai/members/search` | Substring member search: `?cubeId=...&dimension=...&hierarchy=...&level=...&q=...&limit=20` |
| `POST /saiku/api/ai/query/execute-async` | Submit, return a queryId immediately |
| `GET /saiku/api/ai/query/status/{queryId}` | Poll for `PENDING` / `RUNNING` / `DONE` / `FAILED` / `CANCELLED` |
| `GET /saiku/api/ai/query/result/{queryId}` | Fetch the materialised result |
| `DELETE /saiku/api/ai/query/{queryId}` | Cancel an in-flight query |
| `GET /saiku/api/ai/query/{queryId}/drillthrough?maxrows=N` | Get the raw fact rows behind a result. Add `?firstRowset=N` for warehouse-side short-circuit; add `?returns=col1,col2` to project a subset. |
| `GET /saiku/api/ai/query/{queryId}/drillthrough/columns` | List the drillthrough columns available for `returns=` (saiku#774) |
| `GET /saiku/api/ai/query/{queryId}/drillthrough/export/csv` | Same params as the JSON drillthrough (`position`, `returns`, `maxrows`, `firstRowset`); streams `text/csv` with `Content-Disposition: attachment` for direct download (saiku#1051). |
| `POST /saiku/api/ai/anomaly` | Run a query, then flag anomalous points along a time axis. Returns the typed records response with an `anomaly:{score,expected,direction}` block on each flagged cell, plus an `anomaly` summary (`method`, `threshold`, `anomalyCount`) (saiku#907). |
| `POST /saiku/api/ai/forecast` | Run a time-series query, then project `horizon` future points with prediction intervals. Returns the typed records response (observed data untouched) plus a `forecast` block keyed by measure (saiku#908). |

All routes require an authenticated session (form login at `POST /login`
on the launcher; same auth as the regular UI).

---

## Step 1 — list the cubes

```http
GET /rest/saiku/api/ai/cubes
```

```json
[
  {
    "connectionName": "unknown_foodmart",
    "catalog": "FoodMart",
    "schema": "FoodMart",
    "cubeName": "Sales",
    "cubeCaption": "Sales",
    "defaultMeasure": "Unit Sales",
    "measureCount": 8
  },
  {
    "connectionName": "unknown_foodmart",
    "catalog": "FoodMart",
    "schema": "FoodMart",
    "cubeName": "HR",
    "cubeCaption": "HR",
    "defaultMeasure": "Org Salary",
    "measureCount": 5
  }
  // …Sales 2, Store, Warehouse, Warehouse and Sales…
]
```

The `connectionName`/`catalog`/`schema`/`cubeName` quadruplet is the cube
identifier the agent uses everywhere else.

---

## Step 2 — fetch the typed schema for a cube

```http
GET /rest/saiku/api/ai/schema/unknown_foodmart/FoodMart/FoodMart/Sales
```

The path segment after `/schema/` is just `connection/catalog/schema/cubeName`
joined with `/`. Don't URL-encode the slashes — JAX-RS accepts the
multi-segment form via a `{cubeId:.+}` template.

The response is dense — this is what makes the API self-describing:

```jsonc
{
  "cubeId": "unknown_foodmart/FoodMart/FoodMart/Sales",
  "cubeName": "Sales",
  "cubeUniqueName": "[unknown_foodmart].[FoodMart].[FoodMart].[Sales]",

  "measures": {
    "store sales": {
      "name": "Store Sales",
      "uniqueName": "[Measures].[Store Sales]",
      "displayName": null,                                   // Phase-3 alias if enrichment overlays one
      "description": "Net retail revenue in USD across all transactions.",
      "synonyms": ["revenue", "turnover", "top-line", "sales"], // saiku#818 — accepted as `name` on input
      "unit": "USD",                                          // saiku#818 — free text: USD, hours, count, percent
      "currency": "USD",                                      // saiku#818 — ISO 4217 when monetary
      "aggregationKind": "sum",                               // saiku#818 — sum | count | distinct-count | non-additive
      "visible": true
    },
    "unit sales": { "name": "Unit Sales", "uniqueName": "[Measures].[Unit Sales]" },
    "profit":     { "name": "Profit",     "uniqueName": "[Measures].[Profit]" }
    // …8 measures total…
  },

  "measureAliases": {                                         // saiku#818 — every synonym → canonical key
    "revenue":   "store sales",
    "turnover":  "store sales",
    "top-line":  "store sales",
    "sales":     "store sales",
    "cogs":      "store cost",
    "unique customers": "customer count"
    // …also picks up any Phase-3 displayName aliases…
  },

  "dimensionAliases": {                                       // saiku#818 follow-up
    "shopper":  "customer",
    "buyer":    "customer",
    "date":     "time"
    // single canonical-key value per synonym — dimensions don't collide in a cube
  },

  "levelAliases": {                                           // saiku#818 follow-up
    "quarterly": [{ "dimension": "time", "hierarchy": "time", "level": "quarter" }],
    "qtr":       [{ "dimension": "time", "hierarchy": "time", "level": "quarter" }],
    "nation":    [{ "dimension": "customer", "hierarchy": "customers", "level": "country" }]
    // list-valued because the same level name can live in multiple hierarchies
    // (e.g. Quarter in both Time/Time and Time/Fiscal). Each target carries
    // canonical (dimension, hierarchy, level) keys so the agent can drill
    // straight back into the schema maps without re-walking.
  },

  "dimensions": {
    "time": {
      "name": "Time",
      "uniqueName": "[Time]",
      "hierarchies": {
        "time by": {
          "name": "Time By",
          "uniqueName": "[Time].[Time By]",
          "levels": {
            "year": {
              "name": "Year", "uniqueName": "[Time].[Time By].[Year]",
              "sampleMembers": [
                { "caption": "1997", "uniqueName": "[Time].[Time By].[Year].&[1997]" },
                { "caption": "1998", "uniqueName": "[Time].[Time By].[Year].&[1998]" }
              ]
            },
            "quarter": {
              "name": "Quarter", "uniqueName": "[Time].[Time By].[Quarter]",
              "description": "Calendar quarter; aggregates 3 months.", // saiku#818
              "synonyms": ["quarterly", "qtr", "q"],                   // saiku#818 — accepted as `level` on input
              "cardinality": "low",                                    // saiku#818 — low | medium | high
              "grain": "quarter",                                      // saiku#818 — year | quarter | month | week | day | hour | minute
              "requiredFilters": [],                                   // saiku#818 — see "required_filters" below
              "sampleMembers": [
                { "caption": "Q1", "uniqueName": "[Time].[Time By].[Quarter].&[Q1]" },
                { "caption": "Q2", "uniqueName": "[Time].[Time By].[Quarter].&[Q2]" },
                { "caption": "Q3", "uniqueName": "[Time].[Time By].[Quarter].&[Q3]" },
                { "caption": "Q4", "uniqueName": "[Time].[Time By].[Quarter].&[Q4]" }
              ]
            },
            "month": {
              "name": "Month", "uniqueName": "[Time].[Time By].[Month]",
              "sampleMembers": [
                { "caption": "1", "uniqueName": "[Time].[Time By].[Month].&[1]" },
                { "caption": "2", "uniqueName": "[Time].[Time By].[Month].&[2]" }
                // …deduped, so Q1 doesn't repeat across years
              ]
            }
          }
        }
      }
    },
    "product": {
      "name": "Product",
      "hierarchies": {
        "products": {
          "name": "Products",
          "levels": {
            "product family": {
              "name": "Product Family",
              "sampleMembers": [
                { "caption": "Drink",          "uniqueName": "[Product].[Products].[Product Family].&[Drink]" },
                { "caption": "Food",           "uniqueName": "[Product].[Products].[Product Family].&[Food]" },
                { "caption": "Non-Consumable", "uniqueName": "[Product].[Products].[Product Family].&[Non-Consumable]" }
              ]
            },
            "product department": {
              "name": "Product Department",
              "sampleMembers": [
                { "caption": "Alcoholic Beverages", "uniqueName": "[Product].[Products].[Product Department].&[Alcoholic Beverages]" },
                { "caption": "Beverages",           "uniqueName": "[Product].[Products].[Product Department].&[Beverages]" },
                { "caption": "Dairy",               "uniqueName": "[Product].[Products].[Product Department].&[Dairy]" }
              ]
            }
            // …Brand Name, Product Name, etc.…
          }
        }
      }
    }
    // …Customer, Promotion, Store, Performance Season Day…
  },

  "suggestions": [],                       // Phase-3 LLM suggestions from the sidecar (if any)

  "examples": [                            // 2-3 ready-made AiQueryRequest bodies for this cube
    { /* breakdown */ },
    { /* top-10 */ },
    { /* visualTotals */ }
  ],

  "requestSchema": {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "title": "AiQueryRequest",
    "required": ["cube", "measures"],
    "properties": {
      "cube": { /* … */ },
      "measures": { /* … */ },
      "rows": { /* … */ },
      "columns": { /* … */ },
      "filters": { /* … */ },
      "limit": { "type": "integer", "default": 0, "description": "…" },
      "visualTotals": { "type": "boolean", "default": false },
      "nonEmpty": { "type": "boolean", "default": true }
    }
  }
}
```

**What an LLM gets from this single response:**

1. **Every valid name** — measures, dimensions, hierarchies, levels.
2. **Real sample values** per level (`["1997","1998"]`, `["Drink","Food","Non-Consumable"]`).
   No more hallucinated members.
3. **Descriptions** from the cube author / LLM enrichment overlay.
4. **Three working example request bodies** the LLM can copy and adapt.
5. **The full JSON Schema** of the request contract — the LLM can self-validate.
6. **Semantic annotations (saiku#818)** — every annotated measure carries `unit` /
   `currency` / `aggregationKind` so the agent knows whether `Store Sales` is in
   dollars or units, and whether `Customer Count` can be aggregated further.
   Every annotated level carries `cardinality` and (for time) `grain` so the
   agent maps "quarterly" / "by month" straight to the right level instead of
   guessing.
7. **Input synonyms** — `measures[].name`, `rows[].dimension|level` /
   `columns[].dimension|level` and `filters[].dimension|level` all accept any
   entry from `measureAliases` / `dimensionAliases` / per-hierarchy
   `levelAliases`. The agent can post `{"measures": [{"name": "revenue"}]}`
   and the server resolves to `[Measures].[Store Sales]` with no /schema
   round-trip. See "Display names + semantic annotations" below for how XML
   annotations and the Phase-3 overlay contribute aliases.
8. **Flat alias overview** — top-level `measureAliases`,
   `dimensionAliases`, and `levelAliases` give an agent the whole synonym
   set in one read, the same way the schema body gives it the whole name
   set in one read. Resolution still happens against the per-hierarchy
   `Hierarchy.levelAliases` map (the converter knows which hierarchy the
   request named, so per-hierarchy is correct), but the top-level
   overview is what an agent inspects when constructing the query.

---

## Step 3 — execute a query

**Question:** "Show me Store Sales and Unit Sales by Product Family, top 3 by Store Sales."

```http
POST /rest/saiku/api/ai/query
Content-Type: application/json
```

```json
{
  "cube": "unknown_foodmart/FoodMart/FoodMart/Sales",
  "measures": [
    { "name": "Store Sales" },
    { "name": "Unit Sales" }
  ],
  "rows": [
    { "dimension": "Product", "hierarchy": "Products", "level": "Product Family" }
  ],
  "order": [{ "by": "Store Sales", "direction": "desc" }],
  "limit": 3
}
```

`cube` accepts either the 4-segment object form or this compact
`"connection/catalog/schema/cube"` string — same value as the `cubeId`
path segment in `/ai/schema`.

**Response (200):**

```json
{
  "queryId": "49127ee9-0ee2-4337-8560-41df11c3d458",
  "status": "SUCCESS",
  "format": "records",
  "metadata": {
    "rows": [
      { "name": "Food",           "caption": "Food" },
      { "name": "Non-Consumable", "caption": "Non-Consumable" },
      { "name": "Drink",          "caption": "Drink" }
    ],
    "columns": [
      { "name": "Store Sales", "caption": "Store Sales" },
      { "name": "Unit Sales", "caption": "Unit Sales" }
    ],
    "measures": ["Store Sales", "Unit Sales"],
    "generatedMdx": "SELECT NON EMPTY {[Measures].[Store Sales], [Measures].[Unit Sales]} ON COLUMNS,\nNON EMPTY TopCount([Product].[Products].[Product Family].Members, 3, [Measures].[Store Sales]) ON ROWS\nFROM [Sales]",
    "freshness": {
      "computedAt":       "2026-05-15T10:23:00Z",
      "computedAtMillis": 1715798421042,
      "cached": false
    }
  },
  "data": [
    {
      "Product Family": "Food",
      "Store Sales":    { "value": 409035.59, "formatted": "409,035.59", "unit": null },
      "Unit Sales":     { "value": 191940.0,  "formatted": "191,940",    "unit": null }
    },
    {
      "Product Family": "Non-Consumable",
      "Store Sales":    { "value": 107366.33, "formatted": "107,366.33", "unit": null },
      "Unit Sales":     { "value": 50236.0,   "formatted": "50,236",     "unit": null }
    },
    {
      "Product Family": "Drink",
      "Store Sales":    { "value": 48836.21,  "formatted": "48,836.21",  "unit": null },
      "Unit Sales":     { "value": 24597.0,   "formatted": "24,597",     "unit": null }
    }
  ],
  "totalRows": 3,
  "runtimeMs": 421
}
```

**Why records?** Each row is a self-describing object keyed by the human
column captions — no separate header lookup, no positional rendering, no
locale-dependent string parsing. Each numeric cell is a typed envelope:

- `value` — parsed `Double` (use for math / sorting / charting)
- `formatted` — Mondrian's pre-formatted display string (use for UI)
- `unit` — sniffed from the formatted string when present (`USD`, `GBP`,
  `EUR`, `JPY`, or `%`); `null` otherwise

The above table at a glance:

| Product Family | Store Sales | Unit Sales |
| --- | --- | --- |
| Food | 409,035.59 | 191,940 |
| Non-Consumable | 107,366.33 | 50,236 |
| Drink | 48,836.21 | 24,597 |

### Matrix format (back-compat)

Position-indexed clients can opt out of records with `?format=matrix`:

```http
POST /rest/saiku/api/ai/query?format=matrix
```

The same query then returns `matrix` instead of `data`, with each row
keyed by the column index as a string — but cells are still the typed
`{value, formatted, unit}` envelope, not bare strings:

```json
{
  "format": "matrix",
  "matrix": [
    { "0": { "value": 409035.59, "formatted": "409,035.59", "unit": null },
      "1": { "value": 191940.0,  "formatted": "191,940",    "unit": null } },
    { "0": { "value": 107366.33, "formatted": "107,366.33", "unit": null },
      "1": { "value": 50236.0,   "formatted": "50,236",     "unit": null } },
    { "0": { "value": 48836.21,  "formatted": "48,836.21",  "unit": null },
      "1": { "value": 24597.0,   "formatted": "24,597",     "unit": null } }
  ]
}
```

`generatedMdx` is echoed for human/debugging consumption. Agents
typically ignore it. `freshness.computedAtMillis` is when the engine
finished the query; `cached` indicates a cache-hit response.

### Privacy: k-anonymity small-cell suppression (saiku#905)

When `ai.kAnonymity` is set (default `5`; `0` disables), the server masks
small-cell measure values before the result crosses the AI boundary. Any row —
in **either** `records` **or** `matrix` format — whose in-result count measure (a
column whose caption names a count on a word boundary, e.g. Mondrian's `Fact
Count`, so `Discount` / `Account` are *not* mistaken for it) falls below `k` has
every measure cell in that row masked. A masked cell carries `suppressed: true`,
a nulled `value`, and a `formatted` of `—` (or the configured
`ai.kAnonymity.maskValue`). This is the standard statistical small-cell control:
a "SUM of salary by department" stops disclosing a one-person department's
salary. Both egress shapes run the same suppression core, so they cannot drift
apart (the `format=matrix` bypass was closed in saiku#1324).

**v1 limit (the shadow-count follow-up is tracked as saiku#905-B):**

- **Only in-result count measures are covered.** A cube whose result carries no
  count measure — and the `/ai/anomaly` / `/ai/forecast` surfaces — are not yet
  suppressed.

---

## Step 4 — validation: how the API teaches the agent

When the agent supplies a name that doesn't resolve, the server returns
**400** with a structured body the agent can self-correct from:

```http
POST /rest/saiku/api/ai/query
```

```json
{
  "cube": { /* …Sales… */ },
  "measures": [{ "name": "Made Up Measure" }],
  "rows": [{ "dimension": "Product", "hierarchy": "Products", "level": "Product Family" }]
}
```

**Response (400):**

```json
{
  "status": "VALIDATION_ERROR",
  "error": "Unknown measure 'Made Up Measure'",
  "field": "measures[].name",
  "available": [
    "Unit Sales", "Store Cost", "Store Sales", "Sales Count",
    "Customer Count", "Promotion Sales", "Profit", "Gewinn-Wachstum"
  ]
}
```

The agent sees exactly what went wrong (`field`), what the legal values
are (`available`), and can immediately retry with a corrected name. No
prompt engineering required.

**Two layers of validation.** A request is checked twice and either layer
can return a `VALIDATION_ERROR`:

1. **Shape validator (JSON Schema, runs first).** Catches structural
   problems before the request reaches the cube — missing required
   fields, wrong types, values outside an enum. Field paths preserve
   array indices so the agent knows exactly which entry is wrong
   (`filters[0].op`, `order[0].direction`, `rows[2].dimension`). For
   enum violations (`op`, `direction`, relative-preset `value`),
   `available[]` is populated with the legal values directly from the
   schema.

   ```json
   { "status": "VALIDATION_ERROR",
     "error": "$.filters[0].op: does not have a value in the enumeration [\"in\", \"not_in\", \"between\", \"descendants_of\", \"relative\"]",
     "field": "filters[0].op",
     "available": ["in", "not_in", "between", "descendants_of", "relative"] }
   ```

2. **Semantic validator (cube-resolution, runs after).** Catches names
   that are shape-valid but don't exist in the cube — unknown measure
   names, unresolvable dimensions, members from the wrong hierarchy.
   Field paths use `[]` for generic name-resolution errors that aren't
   tied to a specific array element (`measures[].name`), and indexed
   paths for per-element issues (`filters[0]` for an offending filter).

The contract for the agent is the same either way: read `field`, read
`available[]`, fix and retry.

**Self-correcting error messages.** Semantic-validator errors carry the
literal fix in the `error` string when there is one. For example,
putting the same hierarchy on both an axis AND a filter — Mondrian
rejects that — produces:

```json
{
  "status": "VALIDATION_ERROR",
  "error":  "Hierarchy 'Time' is already on the rows/columns axis. Mondrian rejects the same hierarchy on two independent axes. Either move the filter members onto the axis selection's `members[]`, or filter on a different hierarchy/dimension.",
  "field":  "filters[0].hierarchy",
  "available": []
}
```

The message names the conflict, explains *why*, and tells the agent the
two ways out. `available[]` is empty here because the fix isn't a
choice from a list — it's a structural change to the request shape.

**Missing required filter (saiku#818 — opt-in per level).** When a level
in the schema declares `requiredFilters`, the converter rejects any query
that touches the level without satisfying every entry. Empty `members[]`
on the satisfying filter does **not** count — the agent must actually
pick a member.

```json
{
  "status": "VALIDATION_ERROR",
  "error": "Level [Time].[Time].[Quarter] requires a filter on Time By/Year with non-empty members.",
  "field": "filters",
  "available": ["Time By/Year", "Customer/Country"]
}
```

The `available[]` lists every required filter declared anywhere on the
cube — the agent can construct a complete query in one retry without
fetching the schema again. Cubes without `requiredFilters` annotations
are unaffected (zero impact on existing deployments).

**Error taxonomy.** Statuses use a strict enum:

- `VALIDATION_ERROR` — bad name, bad shape, bad operator
- `EXECUTION_ERROR` — generic Mondrian/server-side failure
- `WAREHOUSE_ERROR` — underlying SQL warehouse refused the query
- `PERMISSION_DENIED` — auth/ACL failure
- `RATE_LIMITED` — too many requests
- `TIMEOUT` — server-side hard cap exceeded
- `CUBE_NOT_FOUND` — cube reference resolved to nothing

Validation runs on:

- `cube` — must resolve to a real cube
- `measures[].name` — must exist on the cube (canonical name or Phase-3
  display name)
- `rows[].dimension`, `rows[].hierarchy`, `rows[].level` — same
- `columns[].*` — same
- `filters[].dimension`, `.hierarchy`, `.level` — same
- `filters[].op` — one of `in` (default), `not_in`, `between`,
  `descendants_of`, `relative`
- `filters[].members` — must satisfy the op's arity:
  - `in` / `not_in` — ≥ 1
  - `between` — exactly 2 (start, end)
  - `descendants_of` — exactly 1
  - `relative` — `members` is not used; supply `value` (and `n` for
    `last_n_*`) instead. `value` must be one of the relative-preset enum
    (see "Relative-time filters"); `n` must be ≥ 1 when `value` starts
    with `last_n_`.
- `order[].by` — must be a measure on the cube

---

## Step 5 — drill through

Given a `queryId` from any prior `POST /query` (sync or async), grab the
underlying fact rows:

```http
GET /rest/saiku/api/ai/query/{queryId}/drillthrough?maxrows=5
```

```json
{
  "queryId": "49127ee9-0ee2-4337-8560-41df11c3d458",
  "rowCount": 5,
  "rows": [
    {
      "Year":                { "value": 1997.0, "formatted": "1997", "unit": null },
      "Quarter":             { "value": null,   "formatted": "Q4",   "unit": null },
      "Month":               { "value": 12.0,   "formatted": "12",   "unit": null },
      "Product Family":      { "value": null,   "formatted": "Drink",        "unit": null },
      "Product Department":  { "value": null,   "formatted": "Beverages",    "unit": null },
      "Product Category":    { "value": null,   "formatted": "Drinks",       "unit": null },
      "Product Subcategory": { "value": null,   "formatted": "Flavored Drinks", "unit": null },
      "Brand Name":          { "value": null,   "formatted": "Excellent",    "unit": null },
      "Product Name":        { "value": 322.0,  "formatted": "322",          "unit": null },
      "Store Sales":         { "value": 104.3,  "formatted": "104.3000",     "unit": null }
    }
    // …
  ]
}
```

Each row cell is the same `{value, formatted, unit}` envelope as the query
response — numeric warehouse columns get a typed `value`; string columns
populate `formatted` only with `value: null`. The column set is determined
by the cube's fact table; use `?returns=col1,col2,col3` to project a subset,
or `?maxrows=N` to bound the payload.

**Discover the drillthrough column list** before passing `?returns=`:

```http
GET /rest/saiku/api/ai/query/{queryId}/drillthrough/columns
```

```json
{
  "queryId": "49127ee9-0ee2-4337-8560-41df11c3d458",
  "columns": [
    { "name": "[Time].[Time].[Year]",                  "type": "VARCHAR" },
    { "name": "[Time].[Time].[Quarter]",               "type": "VARCHAR" },
    { "name": "[Product].[Products].[Product Family]", "type": "VARCHAR" },
    { "name": "[Measures].[Store Sales]",              "type": "DECIMAL" }
  ]
}
```

The `name` values are the MDX-qualified labels the downstream `?returns=`
parameter expects. Use this endpoint to populate a column picker (UI) or
to know which columns are valid before issuing a constrained drillthrough
(agents).

**Two row-bounding options**, with different semantics:

- `?maxrows=N` — emits `DRILLTHROUGH MAXROWS N`. Mondrian materialises
  the full result internally then trims. Cheaper for small N against
  cubes where the cellset is already small.
- `?firstRowset=N` — emits `DRILLTHROUGH FIRST_ROWSET N`. The warehouse
  short-circuits and only streams the first N rows. Cheaper for small
  N against multi-million-row fact tables (Snowflake, BigQuery,
  Postgres with appropriate planner hints).

If both are supplied, `firstRowset` wins.

**Per-cell drillthrough** — by default the endpoint drills the result as a
whole. To drill the fact rows behind a *single* cell (what a dashboard
cell-click does, saiku#930), pass the cellset coordinate as
`?position=col:row` — the **column-axis** position index first, then the
**row-axis** position index, zero-based:

```http
GET /rest/saiku/api/ai/query/{queryId}/drillthrough?position=2:1&maxrows=20
```

Here `2:1` means "the cell at column-axis position 2, row-axis position 1".
The indices are positions on the cellset axes, not member ordinals — they
mirror the indices the workspace (`Query2Resource`) uses, so the same cell
yields the same rows in either surface. A malformed `position` returns a
`400` with a descriptive message rather than drilling the whole result.
The CSV export endpoint
(`/query/{queryId}/drillthrough/export/csv`) accepts the same `position`.

---

## Step 6 — async path (long-running queries)

For queries that take seconds to minutes, the async path:

```http
POST /rest/saiku/api/ai/query/execute-async
```

returns `202 Accepted` with the queryId, validation having already happened
synchronously:

```json
{ "queryId": "7f8b94b5-03aa-4fd9-aead-11ed3bdadfcb", "status": "SUCCESS" }
```

Poll for status:

```http
GET /rest/saiku/api/ai/query/status/7f8b94b5-03aa-4fd9-aead-11ed3bdadfcb
```

```json
{ "queryId": "7f8b94b5-…", "status": "DONE" }
```

States: `PENDING` → `RUNNING` → `DONE` / `FAILED` / `CANCELLED`.

When `DONE`, fetch the materialised result — same shape as the
synchronous `POST /query` response:

```http
GET /rest/saiku/api/ai/query/result/7f8b94b5-03aa-4fd9-aead-11ed3bdadfcb
```

Cancel an in-flight query:

```http
DELETE /rest/saiku/api/ai/query/7f8b94b5-03aa-4fd9-aead-11ed3bdadfcb
```

Cancellation is best-effort but real — it calls `OlapStatement.cancel()`
on the live Mondrian statement, not just a soft flag.

---

## Step 7 — analytics: anomaly detection + forecast

Two server-side analytics endpoints run a query through the **same path**
`POST /query` uses, then layer a statistical pass on top of the result.
Both are **Tier-3, in-JVM** — no LLM, no external model, no network call —
and both reuse the self-correcting `400` envelope (`field` + `available`)
for bad parameters.

### Anomaly detection — `POST /ai/anomaly` (saiku#907)

Flags anomalous points along a time axis. Body wraps an ordinary
`AiQueryRequest` plus the time axis to scan:

```jsonc
{
  "query": { /* a normal /query request body — cube, measures, rows, … */ },
  "timeAxis": "[Time].[Time].[Month]",  // required: unique name of the time axis
  "method": "zscore",                    // optional: "zscore" (default) | "mad"
  "threshold": 3.0                       // optional: positive; default per method
}
```

- `method` — `zscore` (rolling mean ± σ, default threshold **3.0**) or
  `mad` (median absolute deviation, more outlier-robust, default
  threshold **3.5**). `stl` is registered but returns a `400` until the
  impl lands. Unknown methods return the candidate list in `available`.
- `threshold` — must be a positive number; larger = stricter (fewer
  points flagged).

The response is the standard **records** response, with an `anomaly`
block added to each flagged cell, plus a sibling summary so "no
anomalies" is an explicit `0` — never a missing field:

```jsonc
{
  "response": {
    "format": "records",
    "data": [
      /* … normal rows; the measure cell is the {value,formatted,unit} envelope … */
      {
        "Month": "December",
        "Unit Sales": {
          "value": 9281.0, "formatted": "9,281", "unit": null,
          "anomaly": { "score": 4.12, "expected": 5230.0,
                       "direction": "high", "anomaly": true }
        }
      }
    ]
  },
  "anomaly": { "method": "zscore", "threshold": 3.0,
               "timeAxis": "[Time].[Time].[Month]", "anomalyCount": 1 }
}
```

The `anomaly` object hangs off the flagged **measure cell** inside the
`data` row (non-anomalous cells are left untouched, so the payload stays
lean). `direction` is `high` / `low` relative to `expected`; `score` is
the detector's distance metric (σ for `zscore`, scaled MAD for `mad`).
The dashboard chart tile reads these to drop marker points on the series.

### Forecast — `POST /ai/forecast` (saiku#908)

Projects future points for each measure in a time-series query, with
prediction intervals:

```jsonc
{
  "query": { /* a normal /query request body */ },
  "timeAxis": "[Time].[Time].[Month]",  // required
  "method": "ets",                       // optional: "ets" (default)
  "horizon": 6,                          // optional: future points, 1–365 (default 6)
  "confidence": 0.95                     // optional: interval level, 0–1 exclusive (default 0.95)
}
```

- `method` — `ets` (exponential smoothing, Holt's linear trend) is the
  only live forecaster; `arima` and `prophet` are registered stubs that
  `400` until implemented.
- `horizon` — number of future periods, **1–365**.
- `confidence` — interval level, strictly between 0 and 1.

The response echoes the typed records response (observed data
**untouched**) plus a `forecast` block keyed by measure caption. Each
projected point carries the point estimate and the interval bounds:

```jsonc
{
  "response": { /* the observed series, unchanged */ },
  "forecast": {
    "method": "ets", "horizon": 6, "confidence": 0.95,
    "timeAxis": "[Time].[Time].[Month]",
    "series": {
      "Unit Sales": [
        { "value": 5310.4, "lower": 4980.1, "upper": 5640.7, "forecast": true }
      ]
    }
  }
}
```

`forecast: true` marks projected points (vs observed); `lower`/`upper`
are the interval bounds at the requested `confidence`. The chart tile
appends these as a dashed continuation with a shaded confidence band.

---

## Request body — every option

```jsonc
{
  "cube": {                                        // Required.
    "connectionName": "unknown_foodmart",
    "catalog": "FoodMart",
    "schema": "FoodMart",
    "cubeName": "Sales"
  },
  "measures": [                                    // Required. Goes on COLUMNS.
    { "name": "Store Sales" }                      // Canonical or display name.
  ],
  "rows": [                                        // Optional. CROSSJOIN-ed when >1 entry.
    {
      "dimension": "Time",
      "hierarchy": "Time",                         // Optional if dim has only one hierarchy.
      "level": "Year",
      "members": []                                // Optional — restrict to specific members.
    }
  ],
  "columns": [                                     // Optional. Cross-joined with measures.
    /* same shape as rows */
  ],
  "filters": [                                     // Optional. Lands in WHERE.
    {
      "dimension": "Store",
      "hierarchy": "Stores",
      "level": "Store Country",
      "op": "in",                                  // Optional. in | not_in | between | descendants_of | relative. Default in.
      "members": [                                 // Unique names. Required for in/not_in/between/descendants_of.
        "[Store].[Stores].[Store Country].&[USA]"
      ],
      "value": null,                               // Only for op=relative. See "Relative-time filters" below.
      "n": 1                                       // Only for op=relative, last_n_* presets. Default 1.
    }
  ],
  "order": [                                       // Optional. Sort + top-N.
    {
      "by": "Store Sales",                         // Measure name. With limit > 0: emits TopCount/BottomCount.
      "direction": "desc"                          // asc | desc. Default desc.
    }
  ],
  "limit": 0,                                      // Optional. With order > 0 → TopCount/BottomCount; without order → HEAD(rows, N).
  "visualTotals": false,                           // Optional. Wraps rows in VISUALTOTALS().
  "nonEmpty": true                                 // Optional. Default true.
}
```

### Filter operator reference

| `op` | Emitted MDX | `members` arity |
| --- | --- | --- |
| `in` (default) | `{m1, m2, …}` (or just `m1` for a single member) | ≥1 |
| `not_in` | `Except(level.Members, {m1, m2, …})` | ≥1 |
| `between` | `m1 : m2` (range) | exactly 2 (start, end) |
| `descendants_of` | `Descendants(m1)` | exactly 1 |
| `relative` | see "Relative-time filters" below | n/a (uses `value` + `n`) |

### Member-name format

Members in the `members` array are **MDX unique names**, not bare captions.
The schema's `sampleMembers` ships them ready-made:

```json
"sampleMembers": [
  { "caption": "USA", "uniqueName": "[Store].[Stores].[Store Country].&[USA]" }
]
```

Copy the `uniqueName` directly into `members`. For dimensions where sample
coverage is insufficient (large dimensions, fuzzy lookup), fetch more via
`GET /ai/members/search?cubeId=…&level=…&q=USA` — each hit's `uniqueName`
field is the value to drop into `members`.

If you ever need to assemble a unique name by hand, the pattern is
`level.uniqueName + ".&[" + caption + "]"`:

```
level.uniqueName            "[Store].[Stores].[Store Country]"
caption                     "USA"
unique-name to send         "[Store].[Stores].[Store Country].&[USA]"
```

Submitting a bare caption to `members` produces an MDX-parse error at
execution time.

For `between` over a time dimension, both ends must be unique names at the
same level:

```json
{ "op": "between",
  "members": ["[Time].[Time By].[Year].&[2020]",
              "[Time].[Time By].[Year].&[2025]"] }
```

### Relative-time filters

When the agent thinks in terms of "last quarter" or "year to date" rather than
explicit member names, use `op: "relative"`. No round-trip through
`/ai/members/search` required; the engine resolves the set against the
selected `level`.

| `value` | Emitted MDX | Notes |
| --- | --- | --- |
| `last_n_days` / `_months` / `_quarters` / `_years` | `Tail(level.Members, n)` | Pick the level that matches the period; `n` defaults to 1. |
| `ytd` / `mtd` / `qtd` | `Ytd()` / `Mtd()` / `Qtd()` | Depends on the cube's time-default member. |
| `previous_period` | `Tail(level.Members, 2).Item(0)` | Member preceding the **latest member that has data in the cube** — not "yesterday" relative to wall-clock time. If the warehouse last loaded on Tuesday, this returns Monday on Friday too. |

**Year-over-year comparison is not yet supported** as a relative preset. At
Year level it would collapse to `previous_period`; at Month/Quarter level
the year-aware MDX needs a hierarchy-aware `ParallelPeriod` the converter
doesn't yet introspect. For now, pass two explicit year unique-names via
`op: "in"` instead.

Example: "last 30 days of sales by product family":

```json
{
  "cube": "unknown_foodmart/FoodMart/FoodMart/Sales",
  "measures": [{ "name": "Store Sales" }],
  "rows": [{ "dimension": "Product", "hierarchy": "Products", "level": "Product Family" }],
  "filters": [{
    "dimension": "Time",
    "hierarchy": "Time By",
    "level": "Day",
    "op": "relative",
    "value": "last_n_days",
    "n": 30
  }]
}
```

---

## Response body — every field

```jsonc
{
  "queryId": "uuid",                               // Use for drillthrough or async polling.
  "status": "SUCCESS",                             // See "Error taxonomy" above for full list.
  "format": "records",                             // "records" (default) or "matrix".
  "metadata": {
    "rows":    [{ "name": "…", "caption": "…" }],  // Row captions in row order.
    "columns": [{ "name": "…", "caption": "…" }],  // Column captions in column order.
    "measures": ["…"],                             // Measure captions (same as columns for measure-only axes).
    "generatedMdx": "SELECT …",                    // Audit trail — agent can ignore.
    "freshness": {                                 // When + whether cached.
      "computedAt":       "2026-05-15T10:23:00Z",  // ISO 8601 in UTC — for "as of X minutes ago" UX.
      "computedAtMillis": 1715798421042,           // Unix epoch in millis — same instant, code-friendly.
      "cached": false
    }
  },
  "data": [                                        // records format. Populated when format=records.
    {
      "<row-header caption>": "<member caption>",  // String key per row-axis level.
      "<column caption>": {                        // Typed cell per measure/column.
        "value": 123.45,                           // Parsed number (Double) or null.
        "formatted": "123.45",                     // Mondrian's display string.
        "unit": "USD",                             // Sniffed currency/% or null.
        "properties": {                            // Raw Mondrian cell properties — client can re-format
          "formatString":  "#,###.00",             //   locale-aware rather than relying on `formatted`.
          "datatype":      "Numeric",              //   "Numeric" | "String" | "Boolean" | "DateTime" …
          "actionType":    "256",                  //   Bitmap of MDSCHEMA action types (drillthrough etc.).
          "fontFlags":     "0",                    //   Cell-formatting font hints (bold/italic bits).
          "solveOrder":    "0"                     //   Calc-member solve order; 0 for plain measures.
        }
      }
    }
  ],
  "matrix": [                                      // matrix format. Populated when format=matrix.
    { "0": { "value": 123.45, "formatted": "123.45", "unit": null, "properties": { /* … */ } } }
  ],
  "totalRows": 3,
  "runtimeMs": 421,

  // Populated only on errors:
  "error": "Unknown measure 'X'",
  "field": "measures[].name",                      // Field path the agent should fix.
  "available": ["…", "…"]                          // Candidate values for that field.
}
```

Only one of `data` / `matrix` is populated per response; the other is
the empty list.

---

## Display names + semantic annotations

Saiku schemas have two complementary ways to enrich the canonical
measures and levels for an LLM:

1. **XML annotations** (`saiku.semantic.*`) — permanent metadata coupled to
   the cube. Lives on `<Measure>` and `<Level>` elements in the Mondrian
   schema XML. Authoring reference: [`docs/schema-annotations.md`](schema-annotations.md).
2. **Phase-3 `.generated.json` overlay** — runtime curation by operators
   or the schema-gen tooling, applied on top of the XML. **Overlay wins
   on conflict.**

Both routes feed the same typed fields on `AiSchema.Measure` /
`AiSchema.Level` and the same alias maps, so the API surface is identical
regardless of where the metadata came from.

### XML annotation example

```xml
<Measure name='Store Sales' column='store_sales' aggregator='sum' formatString='#,###.00'>
    <Annotations>
        <Annotation name='saiku.semantic.description'>Net retail revenue in USD across all transactions.</Annotation>
        <Annotation name='saiku.semantic.synonyms'>revenue, turnover, top-line, sales</Annotation>
        <Annotation name='saiku.semantic.unit'>USD</Annotation>
        <Annotation name='saiku.semantic.currency'>USD</Annotation>
        <Annotation name='saiku.semantic.aggregation_kind'>sum</Annotation>
    </Annotations>
</Measure>
```

After this, `/ai/schema` surfaces the typed fields (see Step 2) and
`measureAliases` carries every synonym → canonical mapping. The agent
posts `{"measures": [{"name": "revenue"}]}` and the converter resolves
to `[Measures].[Store Sales]` automatically.

### Phase-3 overlay (`<datasource>.generated.json`)

If your deployment has a schema-generator sidecar in the saiku
repository, `/ai/schema/{cubeId}` overlays its renames, suggestions, and
**annotations block** (saiku#818) onto the canonical schema:

```jsonc
{
  "measures": {
    "store sales": {
      "name": "Store Sales",
      "uniqueName": "[Measures].[Store Sales]",
      "displayName": "Revenue",                    // ← rename from the LLM-curated draft
      "description": "Total store revenue"
    }
  },
  "suggestions": [
    {
      "op": "rename",
      "targetPath": "cubes/sales_fact/measures/store_sales",
      "confidence": 0.92,
      "rationale": "matches common analyst vocabulary",
      "suggestedValue": "Revenue"
    }
  ],
  "annotations": {                                       // saiku#818 — overlay > XML on conflict
    "measures.Store Sales": {
      "saiku.semantic.synonyms": "revenue, turnover, top-line"
    },
    "dimensions.Time.hierarchies.Time By.levels.Quarter": {
      "saiku.semantic.cardinality": "low",
      "saiku.semantic.grain": "quarter"
    }
  }
}
```

**The contract:** display names AND `saiku.semantic.synonyms` entries
are both **first-class query identifiers**. The agent can use the
canonical name, the display name, or any synonym in any name field of
`AiQueryRequest`:

```json
{ "measures": [{ "name": "Store Sales" }] }   // canonical — always works
{ "measures": [{ "name": "Revenue" }] }       // Phase-3 display name — works after enrichment
{ "measures": [{ "name": "revenue" }] }       // saiku#818 synonym — works after XML annotation OR overlay
```

The generated MDX always emits the canonical `uniqueName`, so the engine
sees the same query either way. Validation error candidate lists include
canonical names so the agent always sees a stable retry target — synonyms
are advisory and live in `measureAliases` / `levelAliases` on the schema
response for the agent to inspect directly.

---

## A typical agent loop

```
1. GET /ai/cubes                                     → discover available cubes
2. GET /ai/schema/{cubeId}                           → typed schema + sample members
                                                       (with unique names) + examples + JSON Schema
3. Construct an AiQueryRequest using names from `measures`/`dimensions`
   (canonical), or any entry from `measureAliases`/`levelAliases`
   (display names + saiku#818 synonyms) — all three resolve identically.
4. POST /ai/query                                    → results
   ↳ 400 VALIDATION_ERROR? Read `field` + `available`, fix, retry.
     Missing `filters` (saiku#818 required_filters)? `available[]` lists
     the exact `Hier/Level` pairs the cube needs.
   ↳ 200 SUCCESS? Render `data` (records — default), or `matrix` when format=matrix.
                  metadata.rows/columns name the row/column captions either way.
5. (Optional) GET /ai/query/{id}/drillthrough        → raw fact rows (typed cells) for any cell of interest
```

A correctly-grounded agent never sees MDX, never invents names, and gets
self-correcting validation feedback when it misses.

---

## Natural-language ask layer — `POST /ai/ask`

The endpoints above assume the caller speaks the **typed** AiQueryRequest
shape — agents do, humans don't. The optional ask layer adds an LLM bridge
on the server: it takes a plain-English question, asks the configured LLM
provider to fill in an `AiQueryRequest` against the live cube schema, runs
that request through the same `/ai/query` converter, and returns the
executed result + the model's MDX + the structured request the model
emitted.

This powers the workspace AI Query drawer (#1093). Off by default —
without provider config the endpoint returns 503 with a clear
"not configured" body, so existing deployments are unaffected until
someone opts in.

### Activation

Two properties + one env var per provider. Both can be set on the JVM
(`-Dsaiku.ai.ask.provider=anthropic`) or via the deployment's properties
file consumed by Spring.

```properties
# anthropic
saiku.ai.ask.provider = anthropic
# env ANTHROPIC_API_KEY = sk-ant-...

# openai (or any OpenAI-compatible host — vLLM, Ollama, Together, LiteLLM)
saiku.ai.ask.provider = openai
# env OPENAI_API_KEY    = sk-...

# azure-openai (saiku#1431) — Azure OpenAI Service. Requires an endpoint.
saiku.ai.ask.provider = azure-openai
saiku.ai.ask.endpoint = https://<resource>.openai.azure.com/openai/deployments/<deployment>/chat/completions?api-version=2024-02-15-preview
saiku.ai.ask.model    = <deployment>          # the deployment name in the URL; sent as `model` in the body too
# env AZURE_OPENAI_API_KEY = <azure-api-key>

# optional, all providers
saiku.ai.ask.model    = claude-sonnet-4-7 | gpt-4o-mini | <azure-deployment> | ...
saiku.ai.ask.endpoint = https://my.openai-compatible.host/v1/chat/completions
saiku.ai.ask.apiKey   = sk-...   # explicit override of the env var
```

Provider defaults: `anthropic` → `claude-sonnet-4-6`,
`openai` → `gpt-4o-mini` against `https://api.openai.com/v1/chat/completions`,
`azure-openai` → no default endpoint (must be configured explicitly;
provider refuses to construct otherwise so the key can't accidentally
leak to the wrong host).

### Bring-your-own LLM (saiku#1431)

Enterprise deployments often can't send prompts to Anthropic or OpenAI
directly — the LLM has to run inside the customer's VPC or behind their
existing procurement. The ask layer covers the three most common
BYOLLM shapes:

| Shape                    | Provider          | Endpoint                                                                              | Auth header               |
|--------------------------|-------------------|---------------------------------------------------------------------------------------|---------------------------|
| Azure OpenAI Service     | `azure-openai`    | `https://<resource>.openai.azure.com/openai/deployments/<deployment>/chat/completions?api-version=<v>` | `api-key: <key>`         |
| Self-hosted / OpenAI-compat proxy (vLLM, Ollama, LiteLLM, Together) | `openai`          | any URL that speaks OpenAI's Chat Completions API                                     | `Authorization: Bearer …` |
| AWS Bedrock              | `openai` via [LiteLLM](https://docs.litellm.ai/) proxy | LiteLLM in front of Bedrock (`https://litellm.internal/v1/chat/completions`) | `Authorization: Bearer …` (LiteLLM handles SigV4 upstream) |

The native `azure-openai` adapter takes care of the two Azure-specific
things (deployment-name-in-URL and `api-key` header) so operators don't
have to run a translation proxy for the most common case.

For Bedrock, the recommended pattern is LiteLLM as an OpenAI-compatible
front — it handles SigV4 upstream and Saiku hits it as a plain
`openai` provider. This avoids baking the AWS SDK into Saiku itself
(30+ MB of jars for a rarely-changing surface). A native Bedrock
provider can land as a follow-up if the LiteLLM path proves painful.

**API keys are never logged.** A single INFO line at boot records the
selected provider + model:

```
INFO  o.s.s.o.a.a.NlAskProviderFactory - AI ask provider: anthropic (model=claude-sonnet-4-6)
```

When the provider is set but the key isn't, the factory falls back to
the noop provider with a WARN explaining what's missing, rather than
failing to boot.

Docker example:

```bash
docker run -d --name saiku \
  -e SAIKU_DEMO=true -e SAIKU_HOME=/app/saiku-home \
  -e ANTHROPIC_API_KEY=sk-ant-... \
  -e JAVA_OPTS='-Dsaiku.ai.ask.provider=anthropic' \
  ghcr.io/spiculedata/saiku:latest
```

### Request

```http
POST /saiku/api/ai/ask
Content-Type: application/json

{
  "question": "show sales by country last quarter",
  "cube": {
    "connectionName": "foodmart",
    "catalog": "FoodMart",
    "schema": "FoodMart",
    "cubeName": "Sales"
  },
  "history": [
    { "role": "user",      "content": "earlier question" },
    { "role": "assistant", "content": "earlier summary"  }
  ]
}
```

`history` is optional. When supplied, prior `(user, assistant)` turns
are sent back to the model so follow-ups like *"now break it down by
region"* resolve against the earlier question. System prompts are
controlled by the provider — callers cannot inject them.

### Response

```json
{
  "degraded": false,
  "model": "claude-sonnet-4-6",
  "request": { /* the structured AiQueryRequest the model emitted */ },
  "response": { /* the full AiQueryResponse — same shape as /ai/query */ },
  "generatedMdx": "SELECT NON EMPTY ... FROM [Sales]"
}
```

- `request` is the AiQueryRequest the model produced. Hand it to
  `POST /ai/query` verbatim to re-execute, or expose it to the user as
  "the typed query behind your question."
- `response` is the executed result. If the model emitted a request the
  schema rejects, `response.status` is `VALIDATION_ERROR` and the body
  carries `field` + `available` candidates — same self-correction
  envelope `/ai/query` uses. The translation succeeded; execution
  layered the error.
- `generatedMdx` is a convenience mirror of
  `response.metadata.generatedMdx`.

### Status codes

| HTTP | When |
| --- | --- |
| 200 | Translation succeeded; `response` carries the executed result (or VALIDATION_ERROR for the user to self-correct). |
| 200 + `degraded:true` | Translation failed at the provider layer (transport error, model refused, parse error). `reason` carries the explanation. |
| 400 | Missing `question` or `cube` in the body. |
| 503 + `degraded:true` | Provider is `noop` (not configured) — body's `reason` explains how to enable. |

### Examples

```bash
# Off by default — 503 with a usable "how to enable" reason.
curl -sS -X POST -H 'Content-Type: application/json' \
  -u admin:admin \
  http://localhost:8080/saiku/api/ai/ask \
  -d '{"question":"show sales by country","cube":{"connectionName":"foodmart","catalog":"FoodMart","schema":"FoodMart","cubeName":"Sales"}}'
# {"degraded":true,"reason":"AI ask is not configured. Set saiku.ai.ask.provider..."}

# With anthropic enabled: full round-trip.
curl -sS -X POST -H 'Content-Type: application/json' \
  -u admin:admin \
  http://localhost:8080/saiku/api/ai/ask \
  -d '{"question":"show sales by country","cube":{"connectionName":"foodmart","catalog":"FoodMart","schema":"FoodMart","cubeName":"Sales"}}' \
  | jq '{model, generatedMdx, rows: .response.totalRows}'
# {
#   "model": "claude-sonnet-4-6",
#   "generatedMdx": "SELECT NON EMPTY {[Measures].[Store Sales]} ON COLUMNS, NON EMPTY {[Customers].[Country].Members} ON ROWS FROM [Sales]",
#   "rows": 3
# }
```

### Streaming variant — `POST /ai/ask/stream` (saiku#1433)

Same request body, same auth, same rate/size guards, same envelope
shape — but the response is a Server-Sent Events stream so embedded
chat surfaces can render progress as it arrives.

```http
POST /rest/saiku/api/ai/ask/stream
Content-Type: application/json
Accept:       text/event-stream
```

Response is a sequence of SSE events per [WHATWG](https://html.spec.whatwg.org/multipage/server-sent-events.html):

```
event: model
data: {"model":"claude-sonnet-4-6"}

event: intent
data: {"kind":"INSIGHT"}

event: chunk
data: {"delta":"Store "}

event: chunk
data: {"delta":"Sales trended up 12% week-on-week."}

event: final
data: {"degraded":false,"model":"claude-sonnet-4-6","insight":{"markdown":"Store Sales trended up 12% week-on-week."}}
```

Event names:

| Event    | When                                                    | Payload                                                                         |
|----------|---------------------------------------------------------|---------------------------------------------------------------------------------|
| `model`  | Always fires first when the provider returned a model id | `{"model": "<model-id>"}`                                                        |
| `intent` | After tool routing, before payload                       | `{"kind": "QUERY" \| "INSIGHT" \| "VIEW_CHANGE"}`                                |
| `chunk`  | For prose-carrying intents (INSIGHT + VIEW_CHANGE `reason`), zero or more times | `{"delta": "<word or whitespace run>"}` — concatenating all deltas recovers the source |
| `final`  | Always fires last on success                             | the complete `AskResponse` envelope — same shape as sync `/ai/ask` returns       |
| `error`  | On degraded (provider transport / parse / auth failure)  | `{"reason": "<explanation>"}` — followed by a `final` event with `degraded:true` |

**Streaming semantics (v1).** The underlying provider call is still
synchronous — the LLM's tool-use response arrives whole. The endpoint
then splits any prose fields (insight markdown, view-change reason)
into word-sized deltas so the client renders progressively. True
per-token streaming from the LLM provider is a follow-up; the wire
shape above is stable so a future PR that plugs in real LLM streaming
won't require any client changes.

**Client-side accumulation:**

```js
const es = new EventSource('/rest/saiku/api/ai/ask/stream', { withCredentials: true });
let markdown = "";
es.addEventListener('chunk', (e) => {
  markdown += JSON.parse(e.data).delta;
  render(markdown);
});
es.addEventListener('final', (e) => {
  const full = JSON.parse(e.data);
  // full === same shape as POST /ai/ask returns
  es.close();
});
es.addEventListener('error', (e) => {
  const err = JSON.parse(e.data);
  showError(err.reason);
});
```

Or with `fetch` for POST bodies (EventSource is GET-only):

```js
const res = await fetch('/rest/saiku/api/ai/ask/stream', {
  method: 'POST',
  headers: { 'content-type': 'application/json', accept: 'text/event-stream' },
  body: JSON.stringify({ question, cube }),
});
const reader = res.body.getReader();
const decoder = new TextDecoder();
let buffer = '';
while (true) {
  const { done, value } = await reader.read();
  if (done) break;
  buffer += decoder.decode(value, { stream: true });
  // Split on double-newlines (event boundaries) and dispatch each event.
  const events = buffer.split('\n\n');
  buffer = events.pop();
  for (const raw of events) dispatchSseEvent(raw);
}
```

### What the model sees vs doesn't

The ask layer sends the model **only** the cube's AiSchema (serialised
JSON — measures, dimensions, hierarchies, levels, sample members) plus
the AiQueryRequest JSON Schema as a tool's `input_schema`. The model is
forced via `tool_choice` to emit a tool call; raw prose is rejected as
degraded.

The model never sees:
- Other cubes the user can access — only the one named in the request.
- The underlying SQL or warehouse credentials.
- The conversation history of other users.
- The HTTP request, session cookies, or any header the caller sent.

This is the same isolation model the existing `/ai/query` agent surface
uses, applied one layer earlier.

## Skills — admin-authored workflows for `/ai/ask` (saiku#1426)

Skills are markdown files with YAML frontmatter that land under
`saiku-home/skills/`. The launcher scans them lazily (mtime-based
signature check — no watcher thread) and injects the catalogue into the
LLM system prompt every ask. Full file-format reference is in
[docs/SKILLS-SPEC.md](./SKILLS-SPEC.md).

Two invocation paths, both routing through the same `POST /ai/ask`
endpoint documented above:

- **Slash-command:** `POST /ai/ask` with a question that starts
  `/<skill-name>`. If the skill exists, the ask service expands the
  skill's body verbatim as the question, appended with the user's
  refinement. The LLM sees the workflow AND the follow-up together, so
  `/weekly-rollup for Q4 instead of this week` works exactly as an
  operator would hope.
- **Natural language:** the skill catalogue lands in the LLM system
  prompt as a bulleted list of `/<name>: <description>` — the model
  picks a matching skill on its own when the user's ask lines up with a
  description. No explicit slash needed.

### File format

```markdown
---
name: weekly-foodmart-rollup
description: |
  Weekly revenue rollup for the FoodMart Sales cube: total Store Sales
  by Product Family for the last 7 days, compared to the prior 7 days.
cube: unknown_foodmart/FoodMart/FoodMart/Sales
---

## Steps

1. Query total `[Measures].[Store Sales]` by `[Product].[Product Family]`
   for the last 7 days.
2. Query the same for the prior 7 days.
3. Flag any family whose delta swings by more than 20%.
```

- `name` — kebab-case, `[a-z][a-z0-9-]{0,63}`. Used as the slash slug.
- `description` — required. Fed to the LLM as the routing hint.
- `cube` — optional `connection/catalog/schema/cubeName` ref. Scopes the
  skill.

Unknown top-level frontmatter keys are **rejected** so a typo
(`descripton`) surfaces as a structured error rather than a silent
nameless skill.

### REST surface

- `GET /rest/saiku/api/ai/skills` — catalogue of `{name, description,
  cube}` summaries.
- `GET /rest/saiku/api/ai/skills?errors=true` — same, plus the list of
  files that failed to parse this scan, each with a stable machine
  code (`EMPTY_SKILL`, `MISSING_FRONTMATTER`, `MALFORMED_YAML`,
  `INVALID_NAME`, `DUPLICATE_NAME`, `UNKNOWN_FIELD`, …). Operators fix
  frontmatter from this endpoint without reading server logs.
- `GET /rest/saiku/api/ai/skills/{name}` — full body of one skill.
  Handy for a UI slash-menu preview.
- `POST /rest/saiku/api/ai/skills/refresh` — force a rescan (bypasses
  the mtime signature check).

### Bundled example

Fresh installs stage `weekly-foodmart-rollup.md` into
`saiku-home/skills/` on first boot — see
`saiku-launcher/src/main/resources/seed/skills/`. The DimSum widget's
`/ai/skills` catalogue has something to return immediately without an
operator editing a file.

### Error surfacing

Broken skills don't take down the catalogue: a `ParseException` on one
file discards that one entry and leaves the rest intact. Every failure
carries a stable code — see the full table in
[docs/SKILLS-SPEC.md](./SKILLS-SPEC.md#errors).

## Agent Spaces — persona layer over `/ai/ask` (saiku#1440)

Where skills codify individual workflows, **spaces** codify a persona:
a named viewpoint that scopes an ask surface. Each space bundles a
system prompt, a cube allowlist, a skill allowlist, and a suggested-
prompts list. Persisted as JSON under `saiku-home/agent-spaces/`; the
launcher scans lazily with the same mtime-signature model as skills.
Full reference: [docs/AGENT-SPACES-SPEC.md](./AGENT-SPACES-SPEC.md).

Two things make a space genuinely enforce scope:

- **Cube allowlist.** `POST /ai/spaces/{id}/ask` refuses any cube ref
  outside the allowlist with a 403 `FORBIDDEN`. When the body omits
  `cube`, the space's first allowlisted ref is used as the default.
- **System prompt injection.** The space's `systemPrompt` is prepended
  to the built-in `SYSTEM_PROMPT` on the provider side. Users can't
  override it through `history` or `question`.

The skill catalogue seen by the LLM is filtered to the space's
`skillAllowlist` too — a slash-command for a skill outside the space
falls through as a raw ask.

### On-disk shape

```json title="saiku-home/agent-spaces/foodmart-sales-analyst.json"
{
  "id": "foodmart-sales-analyst",
  "name": "FoodMart Sales Analyst",
  "description": "Weekly and monthly sales rollups over FoodMart.",
  "systemPrompt": "You are the FoodMart Sales Analyst. Prefer weekly grain...",
  "cubeAllowlist": [
    {"connectionName": "unknown_foodmart", "catalog": "FoodMart", "schema": "FoodMart", "cubeName": "Sales"}
  ],
  "skillAllowlist": ["weekly-foodmart-rollup"],
  "suggestedPrompts": [
    "How did Store Sales track last week vs the prior week?",
    "/weekly-foodmart-rollup"
  ]
}
```

### REST surface

- `GET /rest/saiku/api/ai/spaces` — catalogue (compact summaries —
  `id`, `name`, `description`, `suggestedPrompts`). The `systemPrompt`
  and `cubeAllowlist` are omitted so an unauthenticated embed can't
  scrape the routing.
- `GET /rest/saiku/api/ai/spaces?errors=true` — same, plus parse
  errors.
- `GET /rest/saiku/api/ai/spaces/{id}` — full record (for the admin
  UI when editing a persona).
- `POST /rest/saiku/api/ai/spaces/{id}/ask` — space-scoped ask. Body
  shape mirrors `/ai/ask` but `cube` is optional.
- `POST /rest/saiku/api/ai/spaces/{id}/ask/stream` — SSE streaming
  variant. Same event schema as [`/ai/ask/stream`](#streaming-variant--post-aiaskstream-saiku1433)
  (`model` → `intent` → `chunk` → `final`), but the persona scoping
  from the space applies — the client sees identical wire events
  whether they hit `/ai/ask/stream` or the space-scoped mirror.
- `POST /rest/saiku/api/ai/spaces/refresh` — force a rescan.

### Bundled examples

Fresh launcher installs stage two personas:

- **FoodMart Sales Analyst** — analytical, brief, numbers-first;
  `weekly-foodmart-rollup` in its skill allowlist.
- **FoodMart Finance Ops** — cautious, precise, margin-focused;
  empty skill allowlist = all skills allowed.

See `saiku-launcher/src/main/resources/seed/agent-spaces/`. A fresh
demo has personas ready to click without any operator authoring.
