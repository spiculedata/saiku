# Saiku AI Query API — Usage Guide

A typed REST surface at `/saiku/api/ai/*` for agents and LLMs to query
Saiku OLAP cubes **without ever seeing MDX**. The agent fetches a typed
schema, fills in a JSON request against it, the server validates names
against the live cube, builds MDX internally, and returns formatted
results.

Companion to the spec at
`saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/AiQueryPlan.md`.

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
| `GET /saiku/api/ai/query/{queryId}/drillthrough?maxrows=N` | Get the raw fact rows behind a result |

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
      "description": "Total store revenue"                    // from olap4j Member.getDescription()
    },
    "unit sales": { "name": "Unit Sales", "uniqueName": "[Measures].[Unit Sales]" },
    "profit":     { "name": "Profit",     "uniqueName": "[Measures].[Profit]" }
    // …8 measures total…
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
        "unit": "USD"                              // Sniffed currency/% or null.
      }
    }
  ],
  "matrix": [                                      // matrix format. Populated when format=matrix.
    { "0": { "value": 123.45, "formatted": "123.45", "unit": null } }
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

## Display names + LLM enrichment (Phase 3)

If your deployment has a schema-generator sidecar (`<datasource>.generated.json`
in the saiku repository), `/ai/schema/{cubeId}` overlays its renames and
suggestions onto the canonical schema:

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
  ]
}
```

**The contract:** display names are **first-class query identifiers**. The
agent can use either the canonical name or the display name in any field
of `AiQueryRequest`:

```json
{ "measures": [{ "name": "Store Sales" }] }   // canonical — always works
{ "measures": [{ "name": "Revenue" }] }       // display name — also works after enrichment
```

The generated MDX always emits the canonical `uniqueName`, so the engine
sees the same query either way. Validation error candidate lists include
both canonical and display names so the agent sees every legal string.

---

## A typical agent loop

```
1. GET /ai/cubes                                     → discover available cubes
2. GET /ai/schema/{cubeId}                           → typed schema + sample members
                                                       (with unique names) + examples + JSON Schema
3. Construct an AiQueryRequest using only the names in the schema response
4. POST /ai/query                                    → results
   ↳ 400 VALIDATION_ERROR? Read `field` + `available`, fix, retry
   ↳ 200 SUCCESS? Render `data` (records — default), or `matrix` when format=matrix.
                  metadata.rows/columns name the row/column captions either way.
5. (Optional) GET /ai/query/{id}/drillthrough        → raw fact rows (typed cells) for any cell of interest
```

A correctly-grounded agent never sees MDX, never invents names, and gets
self-correcting validation feedback when it misses.
