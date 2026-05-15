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
| `POST /saiku/api/ai/query` | Execute a typed request, return matrix + metadata |

Plus four for the long-tail:

| Endpoint | Purpose |
| --- | --- |
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
        "time": {
          "name": "Time",
          "uniqueName": "[Time].[Time]",
          "levels": {
            "year":    { "name": "Year",    "uniqueName": "[Time].[Time].[Year]",
                         "sampleMembers": ["1997", "1998"] },
            "quarter": { "name": "Quarter", "uniqueName": "[Time].[Time].[Quarter]",
                         "sampleMembers": ["Q1", "Q2", "Q3", "Q4", "Q1"] },
            "month":   { "name": "Month",   "uniqueName": "[Time].[Time].[Month]",
                         "sampleMembers": ["1", "2", "3", "4", "5"] }
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
              "sampleMembers": ["Drink", "Food", "Non-Consumable"]
            },
            "product department": {
              "name": "Product Department",
              "sampleMembers": ["Alcoholic Beverages", "Beverages", "Dairy"]
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

**Question:** "Show me Store Sales and Unit Sales by Product Family, top 3."

```http
POST /rest/saiku/api/ai/query
Content-Type: application/json
```

```json
{
  "cube": {
    "connectionName": "unknown_foodmart",
    "catalog": "FoodMart",
    "schema": "FoodMart",
    "cubeName": "Sales"
  },
  "measures": [
    { "name": "Store Sales" },
    { "name": "Unit Sales" }
  ],
  "rows": [
    { "dimension": "Product", "hierarchy": "Products", "level": "Product Family" }
  ],
  "limit": 3
}
```

**Response (200):**

```json
{
  "queryId": "49127ee9-0ee2-4337-8560-41df11c3d458",
  "status": "SUCCESS",
  "metadata": {
    "rows": [
      { "name": "Drink", "caption": "Drink" },
      { "name": "Food", "caption": "Food" },
      { "name": "Non-Consumable", "caption": "Non-Consumable" }
    ],
    "columns": [
      { "name": "Store Sales", "caption": "Store Sales" },
      { "name": "Unit Sales", "caption": "Unit Sales" }
    ],
    "measures": ["Store Sales", "Unit Sales"],
    "generatedMdx": "SELECT NON EMPTY {[Measures].[Store Sales], [Measures].[Unit Sales]} ON COLUMNS,\nNON EMPTY HEAD([Product].[Products].[Product Family].Members, 3) ON ROWS\nFROM [Sales]"
  },
  "matrix": [
    { "0": "48,836.21",   "1": "24,597"  },
    { "0": "409,035.59",  "1": "191,940" },
    { "0": "107,366.33",  "1": "50,236"  }
  ],
  "totalRows": 3,
  "runtimeMs": 421
}
```

**How to read the matrix:** row-major. Row index `i` maps to
`metadata.rows[i]`. Cell key `"0"` → `metadata.columns[0]` (Store Sales),
`"1"` → `metadata.columns[1]` (Unit Sales). So:

| Product Family | Store Sales | Unit Sales |
| --- | --- | --- |
| Drink | 48,836.21 | 24,597 |
| Food | 409,035.59 | 191,940 |
| Non-Consumable | 107,366.33 | 50,236 |

`generatedMdx` is echoed for human/debugging consumption. Agents
typically ignore it.

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

Validation runs on:

- `cube` — must resolve to a real cube
- `measures[].name` — must exist on the cube (canonical name or Phase-3
  display name)
- `rows[].dimension`, `rows[].hierarchy`, `rows[].level` — same
- `columns[].*` — same
- `filters[].dimension`, `.hierarchy`, `.level` — same
- `filters[].members` — must be non-empty

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
      "Year": "1997",
      "Quarter": "Q4",
      "Month": "12",
      "Product Family": "Drink",
      "Product Department": "Beverages",
      "Product Category": "Drinks",
      "Product Subcategory": "Flavored Drinks",
      "Brand Name": "Excellent",
      "Product Name": "322",
      "Store Sales": "104.3000"
    },
    {
      "Year": "1997", "Quarter": "Q2", "Month": "4",
      "Product Family": "Drink", "Product Department": "Beverages",
      "Product Category": "Hot Beverages", "Product Subcategory": "Coffee",
      "Brand Name": "Plato", "Product Name": "1234", "Store Sales": "5.6000"
    }
    // …
  ]
}
```

The column set is determined by the cube's fact table — every drillable
column comes back per row. Use `?returns=col1,col2,col3` to project a
subset, or `?maxrows=N` to bound the payload.

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
    {
      "name": "Store Sales",                       // Canonical or display name.
      "aggregators": []                            // Optional override hints (Phase 3).
    }
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
      "members": [                                 // Required and non-empty.
        "[Store].[Stores].[Store Country].&[USA]"
      ]
    }
  ],
  "limit": 0,                                      // Optional. 0 = no cap; >0 → HEAD(rows, N).
  "visualTotals": false,                           // Optional. Wraps rows in VISUALTOTALS().
  "nonEmpty": true                                 // Optional. Default true.
}
```

---

## Response body — every field

```jsonc
{
  "queryId": "uuid",                               // Use for drillthrough or async polling.
  "status": "SUCCESS",                             // SUCCESS | VALIDATION_ERROR | EXECUTION_ERROR.
  "metadata": {
    "rows": [{ "name": "…", "caption": "…" }],     // Row captions in matrix order.
    "columns": [{ "name": "…", "caption": "…" }],  // Column captions in matrix order.
    "measures": ["…"],                             // Measure captions (same as columns for measure-only axes).
    "generatedMdx": "SELECT …"                     // Audit trail — agent can ignore.
  },
  "matrix": [                                      // Row-major. Empty when status != SUCCESS.
    { "0": "value", "1": "value" }                 // Key = column index as string.
  ],
  "totalRows": 3,
  "runtimeMs": 421,

  // Populated only on errors:
  "error": "Unknown measure 'X'",
  "field": "measures[].name",                      // Field path the agent should fix.
  "available": ["…", "…"]                          // Candidate values for that field.
}
```

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
2. GET /ai/schema/{cubeId}                           → typed schema + sample members + examples + JSON Schema
3. Construct an AiQueryRequest using only the names in the schema response
4. POST /ai/query                                    → results
   ↳ 400 VALIDATION_ERROR? Read `field` + `available`, fix, retry
   ↳ 200 SUCCESS? Render the matrix using metadata.rows/columns
5. (Optional) GET /ai/query/{id}/drillthrough        → raw fact rows for any cell of interest
```

A correctly-grounded agent never sees MDX, never invents names, and gets
self-correcting validation feedback when it misses.
