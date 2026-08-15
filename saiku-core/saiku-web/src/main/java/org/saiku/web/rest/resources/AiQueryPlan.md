# AI-Friendly Query API — Original Phase-1 Spec (historical)

> **This document is a frozen Phase-1 design snapshot.** The shipped contract
> has diverged from it across the v2 (records + typed cells + ops + sort)
> and v3 (relative-time filters, typed sample members) reshapes. **Always
> treat `docs/AI-QUERY-API.md` as the authoritative current contract.**
>
> ### Major drifts from this plan
>
> | Area | Plan said | Actually shipped |
> | --- | --- | --- |
> | Schema container | `dimensions[]` / `levels[]` arrays | Maps keyed by lower-cased name |
> | Measure `aggregators` | Listed on measures + advertised in request | Dropped from v1 contract entirely |
> | Sample members | (not in plan) | `sampleMembers: [{caption, uniqueName}]`, deduped |
> | Request — `columns[].unique` | Field present | Field never shipped; columns use same axis shape as rows |
> | Request — `order[]` | (not in plan) | Sort + TopCount/BottomCount; supersedes limit→HEAD when present |
> | Filter `op` | (not in plan; implicit "in") | Enum: in / not_in / between / descendants_of / relative |
> | Relative-time filters | (not in plan) | `op:"relative"` with last_n_* / ytd / mtd / qtd / previous_period |
> | Response `status` | `"success"` (lowercase string) | Enum: SUCCESS / VALIDATION_ERROR / EXECUTION_ERROR / PERMISSION_DENIED / RATE_LIMITED / TIMEOUT / WAREHOUSE_ERROR / CUBE_NOT_FOUND |
> | Response payload | `matrix` only, with formatted strings | `data` (records, default) or `matrix`, both with `{value, formatted, unit}` typed cells |
> | `format` query param | (not in plan) | `?format=records\|matrix` on POST /ai/query |
> | Freshness metadata | (not in plan) | `freshness: {computedAt, computedAtMillis, cached}` |
> | Preview endpoint | (not in plan) | `POST /ai/query/preview` — validate + emit MDX without executing |
> | Member-search endpoint | (not in plan) | `GET /ai/members/search` |
> | Drillthrough payload | "raw row detail" (unspecified) | Rows of `{column → AiCell{value, formatted, unit}}` |
> | Error envelope | Bespoke `{ error: "validation", field, message }` | Same `AiQueryResponse` shape with `status:VALIDATION_ERROR`, `field`, `available` |
> | Dropped measures | (not in plan; silently omitted) | A requested measure with no join path to a filtered dimension is surfaced explicitly as a typed cell `{value:null, formatted:null, unavailable:"no join path to filtered dimension(s): …"}`, not dropped (saiku#1780) |
> | File locations | `saiku-web/.../rest/objects/ai/` | `saiku-service/.../service/olap/ai/` (request types live with the converter, not the resource) |
> | Phase-3 enrichment | Separate `enrichedDimensions` field | Overlay merged in-place onto each level/dimension; `displayName` alongside `name` |
>
> The rest of this file is preserved as-is for design-history purposes only.
> Don't write new client code against it.

---

# AI-Friendly Query API — Spec (original Phase-1 design)

## Problem

- Agents/AI are terrible at writing MDX — they hallucinate dimension names, pick wrong members,
  get hierarchy levels confused
- Humans building MDX are guided by the UI; AI needs a typed structure with a finite name set

## Solution

A REST API where the agent receives a JSON schema, fills in a typed `AiQueryRequest`, and the
server converts it to MDX + executes it. The agent **never sees MDX**.

## API Surface (new base path: `/saiku/api/ai/query`)

### 1. `GET /saiku/api/ai/cubes` — list available cubes

```json
// Response
[
  {
    "connectionName": "foodmart",
    "catalog": "FoodMart 2009",
    "schema": "FoodMart",
    "cubeName": "Sales",
    "cubeCaption": "Sales",
    "defaultMeasure": "Store Sales",
    "measureCount": 14
  }
]
```

### 2. `GET /saiku/api/ai/schema/{cubeId}` — get the queryable schema (cubeId = connection/catalog/schema/cube)

Returns the **complete, valid name set** an agent may use:

```json
{
  "cubeId": "foodmart/FoodMart 2009/FoodMart/Sales",
  "cubeName": "Sales",
  "measures": [
    {
      "name": "Store Sales",
      "caption": "Store Sales",
      "uniqueName": "[Measures].[Store Sales]",
      "aggregators": ["SUM", "COUNT", "AVG"]
    },
    {
      "name": "Store Cost",
      "caption": "Store Cost",
      "uniqueName": "[Measures].[Store Cost]",
      "aggregators": ["SUM"]
    },
    {
      "name": "Unit Sales",
      "caption": "Unit Sales",
      "uniqueName": "[Measures].[Unit Sales]",
      "aggregators": ["SUM", "COUNT", "AVG", "MIN", "MAX"]
    }
  ],
  "dimensions": [
    {
      "name": "Time",
      "caption": "Time",
      "uniqueName": "[Time]",
      "hierarchies": [
        {
          "name": "Time By",
          "caption": "Time By",
          "uniqueName": "[Time].[Time By]",
          "levels": [
            {
              "name": "All_TIME",
              "caption": "All Time",
              "uniqueName": "[Time].[Time By].[All TIME]"
            },
            {
              "name": "Year",
              "caption": "Year",
              "uniqueName": "[Time].[Time By].[Year]"
            },
            {
              "name": "Quarter",
              "caption": "Quarter",
              "uniqueName": "[Time].[Time By].[Quarter]"
            },
            {
              "name": "Month",
              "caption": "Month",
              "uniqueName": "[Time].[Time By].[Month]"
            }
          ]
        }
      ]
    },
    {
      "name": "Product",
      "caption": "Product",
      "uniqueName": "[Product]",
      "hierarchies": [
        {
          "name": "Product",
          "uniqueName": "[Product].[Product]",
          "levels": [
            {
              "name": "All Products",
              "caption": "All Products",
              "uniqueName": "[Product].[Product].[All Products]"
            },
            {
              "name": "Department",
              "caption": "Department",
              "uniqueName": "[Product].[Product].[Department]"
            },
            {
              "name": "ProductName",
              "caption": "Product Name",
              "uniqueName": "[Product].[Product].[ProductName]"
            }
          ]
        }
      ]
    },
    {
      "name": "Customers",
      "caption": "Customers",
      "uniqueName": "[Customers]",
      "hierarchies": [
        {
          "name": "Customers",
          "uniqueName": "[Customers].[Customers]",
          "levels": [
            {
              "name": "All Customers",
              "caption": "All Customers",
              "uniqueName": "[Customers].[Customers].[All Customers]"
            },
            {
              "name": "Name",
              "caption": "Customer Name",
              "uniqueName": "[Customers].[Customers].[Name]"
            }
          ]
        }
      ]
    }
  ]
}
```

If schema generation (LLM enrichment) is active, the response includes an `enrichedDimensions` field with:

- Renamed dimensions/levels (better, human-readable names)
- Suggested aggregators per level
- Degenerate dimensions promoted
- `suggestions` array with `op`/`targetPath`/`confidence`/`rationale` fields

### 3. `POST /saiku/api/ai/query` — execute an AI-structured query

**Request (`AiQueryRequest`):**

```json
{
  "cube": {
    "connectionName": "foodmart",
    "catalog": "FoodMart 2009",
    "schema": "FoodMart",
    "cubeName": "Sales"
  },
  "measures": [{ "name": "Store Sales", "aggregators": ["SUM"] }],
  "rows": [
    {
      "dimension": "Time",
      "hierarchy": "Time By",
      "level": "Year"
    },
    {
      "dimension": "Product",
      "hierarchy": "Product",
      "level": "Department"
    }
  ],
  "columns": [
    {
      "dimension": "Quarter",
      "unique": false
    }
  ],
  "filters": [
    {
      "dimension": "Time",
      "hierarchy": "Time By",
      "level": "Year",
      "members": [
        "[Time].[Time By].[Year].&[2001]",
        "[Time].[Time By].[Year].&[2009]"
      ]
    }
  ],
  "limit": 100
}
```

**Response (`AiQueryResponse`):**

```json
{
  "queryId": "abc-123",
  "status": "success",
  "metadata": {
    "rows": [
      { "name": "2001", "caption": "2001" },
      { "name": "Apparel", "caption": "Apparel" }
    ],
    "columns": [
      { "name": "Q1-01", "caption": "Q1 2001" },
      { "name": "Q1-09", "caption": "Q1 2009" }
    ],
    "columnsCaption": ["Q1 2001", "Q1 2009"],
    "measures": ["Store Sales"]
  },
  "matrix": [{ "0": "1,000,000" }, { "0": "2,500,000" }],
  "totalRows": 123,
  "runtimeMs": 450
}
```

### 4. `POST /saiku/api/ai/query/execute-async` — async variant

Returns 202: `{ "queryId": "abc-123", "status": "PENDING" }`

Then poll:

- `GET /saiku/api/ai/query/status/{queryId}` → `{ "queryId": "abc-123", "status": "DONE" }`
- `GET /saiku/api/ai/query/result/{queryId}` → same JSON as synchronous

### 5. `GET /saiku/api/ai/query/{queryId}/drillthrough` — drill into results

Returns raw row detail for a specific cell position.

## Implementation Plan

### Phase 1: Core API (Week 1)

1. **Create `AiQueryRequest.java`** — typed JSON structure:
   - `AiCubeRef` (connection, catalog, schema, cubeName)
   - `AiMeasureSelection` (name, optional aggregators, optional unique flag)
   - `AiAxisSelection` (dimension, optional hierarchy, optional level, optional unique, optional members[] for filter)
   - `AiFilterSelection` (dimension, hierarchy?, level?, members[])
   - Top-level: cube, measures[], rows[], columns[], filters[], limit

2. **Create `AiQueryResponse.java`** — standard result shape:
   - queryId, status, metadata, matrix, totalRows, runtimeMs

3. **Create `AiQueryResource.java`** — REST resource at `/saiku/api/ai/query`:
   - `POST /query` — main execution endpoint
   - Support sync & async (reuse existing `AsyncQueryService`)
   - Reuse `ThinQueryService.execute()` by converting AiQueryRequest → ThinQuery
   - Support Arrow and JSON output (content negotiation like existing API)

4. **Create `AiSchemaConverter.java`** — converts AiQueryRequest → ThinQuery:
   - Maps dimension/hierarchy/level names to unique names
   - Converts measures to ThinMeasure objects
   - Builds axis maps for COLUMNS, ROWS, FILTER, PAGES
   - Handles aggregators
   - Applies limit via TopCount

### Phase 2: Schema Discovery (Week 2)

5. **Create `AiCubeMetadataService.java`** — pre-fetches and caches cube metadata:
   - Uses existing `OlapDiscoverService`
   - Flattens dimension → hierarchy → level → member into a flat lookup table
   - Returns the structured schema for `/ai/schema/{cubeId}`

6. **Add endpoints to `AiQueryResource`**:
   - `GET /ai/cubes` — list available cubes
   - `GET /ai/schema/{cubeId}` — get schema for an AI agent

### Phase 3: Schema-Generated Schema Integration (Week 3)

7. **Integrate with existing schema-generator**:
   - If a generated schema exists for the cube, enrich the schema response with:
     - Renamed dimensions/levels from LLM suggestions
     - Suggested aggregators
     - Applied operations
   - The `/ai/schema` endpoint checks for a draft/generated schema and merges it in

8. **Validation layer**:
   - Validate all dimension/hierarchy/level/member names against the schema
   - Return 400 with clear error: `"Unknown member '[Product].[Product].[FakeProduct]' — valid members: [Product].[Product].&[1], ..."`

### Phase 4: Polish & Async (Week 4)

9. **Async support**:
   - `POST /ai/query/execute-async` — submit query, get queryId
   - `GET /ai/query/status/{id}` — poll status
   - `GET /ai/query/result/{id}` — get result (content-negotiates Arrow/JSON)

10. **Drill-through**:
    - `GET /ai/query/{id}/drillthrough?row=0&col=1` — drill into a specific cell

## Key Validation Rules

The server **always** validates against the live OLAP schema:

- `cube` ref must resolve to an existing cube
- Each `dimension` in rows/columns/filters must exist on the cube
- Each `hierarchy` must exist on that dimension
- Each `level` must exist on that hierarchy
- Each `member` in filters must exist on that level
- Each `measure` must exist in the cube's measures

Error responses:

```json
{
  "error": "validation",
  "field": "filters[0].level",
  "message": "Unknown level 'FakeLevel'. Available levels: All Products, Department, ProductName"
}
```

## File Locations (new files)

| File                                                        | Purpose                    |
| ----------------------------------------------------------- | -------------------------- |
| `saiku-web/src/.../rest/resources/AiQueryResource.java`     | Endpoint definitions       |
| `saiku-web/src/.../rest/objects/ai/AiQueryRequest.java`     | Request model (typed JSON) |
| `saiku-web/src/.../rest/objects/ai/AiQueryResponse.java`    | Unified response           |
| `saiku-web/src/.../rest/objects/ai/AiCubeRef.java`          | Cube reference             |
| `saiku-web/src/.../rest/objects/ai/AiMeasureSelection.java` | Measure selection          |
| `saiku-web/src/.../rest/objects/ai/AiAxisSelection.java`    | Row/column filter axis     |
| `saiku-web/src/.../rest/objects/ai/AiFilterSelection.java`  | Filter selection           |
| `saiku-web/src/.../rest/objects/ai/AiQueryMetadata.java`    | Response metadata          |
| `saiku-service/src/.../olap/query2/AiSchemaConverter.java`  | AiQueryRequest → ThinQuery |
| `saiku-service/src/.../olap/OiCubeMetadataService.java`     | Schema discovery service   |

## Example: Agent Flow

1. Agent calls `GET /saiku/api/ai/cubes` → gets list of cubes
2. Agent calls `GET /saiku/api/ai/schema/Sales` → gets full schema with ALL available dimensions, hierarchies, levels, measures, and optional enrichments
3. Agent fills in a typed JSON:

```json
{
  "cube": { "cubeName": "Sales" },
  "measures": [{ "name": "Store Sales", "aggregators": ["SUM"] }],
  "rows": [
    { "dimension": "Product", "hierarchy": "Product", "level": "Department" }
  ],
  "columns": [{ "dimension": "Time", "hierarchy": "Time By", "level": "Year" }],
  "filters": [
    {
      "dimension": "Store",
      "hierarchy": "Store",
      "level": "Store Country",
      "members": ["Store Country"]
    }
  ],
  "limit": 50
}
```

4. Server validates names against schema → executes → returns result

## Implementation Notes

1. **Reuse existing infrastructure**: ThinQuery, ThinQueryService, OlapDiscoverService, ArrowCellsetWriter
2. **No MDX exposure**: MDX is built internally; agent never sees it
3. **Schema caching**: Cube metadata can be cached (changes rarely)
4. **Async parity**: Same async pattern as the existing `/query/execute-async`
5. **Content negotiation**: Same Arrow/JSON negotiation as existing API
6. **No session required**: Unlike UI queries, AI queries can be stateless — or use queryId for async
7. **Schema-generator integration**: The `/schema` endpoint merges LLM enrichment data if available
