# Saiku AI Query API — Ossie models

Typed REST + MCP surface at `/saiku/api/ai/ossie/*` for agents and LLMs
to query Ossie semantic models **without ever writing SQL**. Companion
to [`AI-QUERY-API.md`](AI-QUERY-API.md) (MDX cubes) — same discipline,
different query domain.

Ships in issue #1394 (R1–R5). Every endpoint validates names against
the live semantic model and returns a typed `VALIDATION_ERROR` with
`field` + `available` candidate lists on any mismatch, so agents
self-correct on the next round rather than pre-flighting each name.

---

## Quick orientation

Three endpoints cover ~90% of agent use:

| Endpoint | What it returns |
| --- | --- |
| `GET /saiku/api/ai/ossie/models` | List of available Ossie models |
| `GET /saiku/api/ai/ossie/schema/{connection}/{model}` | **Self-describing** typed schema for one model (with sample values + cardinality + ready-made example requests + JSON Schema of the request body) |
| `POST /saiku/api/ai/ossie/query` | Execute a typed request, return **records** (default) or matrix |

Plus the long-tail:

| Endpoint | Purpose |
| --- | --- |
| `POST /saiku/api/ai/ossie/query/preview` | Validate + compile to SQL **without executing**. Returns `{queryId, status:"PREVIEW", generatedSql}`. |
| `GET /saiku/api/ai/ossie/values/search` | Substring value search on a field: `?connection=…&dataset=…&field=…&q=…&limit=20` |
| `POST /saiku/api/ai/ossie/query/execute-async` | Submit, return a queryId immediately |
| `GET /saiku/api/ai/ossie/query/status/{queryId}` | Poll for `PENDING`/`RUNNING`/`DONE`/`FAILED`/`CANCELLED` |
| `GET /saiku/api/ai/ossie/query/result/{queryId}` | Fetch the materialised result (records or `?format=matrix`) |
| `DELETE /saiku/api/ai/ossie/query/{queryId}` | Cancel an in-flight query |
| `POST /saiku/api/ai/ossie/row-detail` | Ossie's answer to MDX drillthrough — re-runs the shelf state with `values=[]` for raw rows |
| `POST /saiku/api/ai/ossie/anomaly` | Run the query then flag anomalous points along a time axis. Detectors: `zscore`, `mad`, `stl` |
| `POST /saiku/api/ai/ossie/forecast` | Run a time-series query then project `horizon` future points. Forecasters: `ets`, `arima`, `prophet` |
| `POST /saiku/api/ai/ossie/ask` | Natural-language layer — the LLM composes the query from a prose question (needs an API key) |
| `GET /saiku/api/ai/ossie/ask/health` | Whether the ask layer is configured on this server |

All routes require an authenticated session (form login at
`POST /login` on the launcher; same auth as the regular UI).
POST endpoints require the CSRF cookie/header pair.

---

## Step 1 — list the models

```http
GET /rest/saiku/api/ai/ossie/models
```

```json
[
  {
    "connectionName": "unknown_Pharma",
    "modelName": "Pharma",
    "description": "Pharma Rx sales — small synthetic warehouse.",
    "factDataset": "fact_pharma",
    "datasetCount": 4,
    "metricCount": 3
  }
]
```

The `connectionName` + `modelName` pair is the model identifier the
agent uses everywhere else.

---

## Step 2 — fetch the typed schema

```http
GET /rest/saiku/api/ai/ossie/schema/unknown_Pharma/Pharma
```

Add `?refresh=true` to bypass the sample-value cache (5-minute TTL
by default; override via `SAIKU_AI_OSSIE_SAMPLES_TTL_MINUTES`).

Response — dense, self-describing:

```jsonc
{
  "modelId": "unknown_Pharma/Pharma",
  "connectionName": "unknown_Pharma",
  "modelName": "Pharma",
  "description": "Pharma Rx sales — small synthetic warehouse.",
  "factDataset": "fact_pharma",

  "datasets": {
    "fact_pharma": {
      "name": "fact_pharma",
      "source": "FACT_PHARMA",
      "primaryKey": ["RXKEY"],
      "fields": {
        "netrevenue": {
          "name": "NETREVENUE",
          "type": "DECIMAL",
          "cardinality": "medium",
          "estimatedDistinct": 12,      // #1405 — HyperLogLog / COUNT DISTINCT estimate
          "sampleValues": ["60.25", "85.00", "95.50", "120.50", "155.75"]
        },
        "rxcount": {
          "name": "RXCOUNT",
          "type": "INTEGER",
          "cardinality": "medium",
          "sampleValues": ["8", "9", "11", "12"]
        }
      }
    },
    "geography": {
      "name": "geography", "source": "DIM_GEOGRAPHY",
      "primaryKey": ["GEOKEY"],
      "fields": {
        "region": {
          "name": "REGION",
          "type": "VARCHAR",
          "cardinality": "low",
          "sampleValues": ["Northeast", "Midwest", "South", "West"]
        }
      }
    }
  },

  "metrics": {
    "net_revenue": {
      "name": "net_revenue",
      "expression": "SUM(\"fact_pharma\".\"NETREVENUE\")",
      "aggregationKind": "sum",
      "supportedOverrides": ["SUM", "AVG", "MIN", "MAX", "COUNT"]
    },
    "line_count": {
      "name": "line_count",
      "expression": "COUNT(*)",
      "aggregationKind": "count",
      "supportedOverrides": ["COUNT"]   // COUNT(*) can only override to COUNT
    }
  },

  "relationships": [
    {
      "name": "fact_to_geography",
      "from": "fact_pharma",
      "to": "geography",
      "fromColumns": ["GEOKEY"],
      "toColumns": ["GEOKEY"]
    }
  ],

  "requestSchema": { /* JSON Schema of the POST /query body */ },

  "examples": {
    "simpleGroupBy": {
      "description": "Total net_revenue grouped by geography.region",
      "body": {
        "model": "Pharma",
        "rows": [{"dataset": "geography", "field": "region"}],
        "values": [{"metric": "net_revenue"}]
      }
    },
    "crosstab": { /* ... */ },
    "topN": { /* ... */ }
  }
}
```

**Key affordances:**

- `sampleValues` gives the agent real values to use in filters.
- `cardinality` (`low` / `medium` / `medium-high` / `high`) hints at
  what filters make sense (`low` → dropdown-picker; `high` → prompt
  the user).
- `estimatedDistinct` (#1405) is the raw distinct-count estimate for
  agents that want the number rather than the bucket.
- `supportedOverrides` lists the aggregations `swapAggregation` will
  actually rewrite. `COUNT(*)` metrics only accept COUNT — the fuzz
  suite (#1388) surfaced the `SUM(*)` gap; this list closes it at the
  agent layer.
- `examples` gives the agent copy-paste request bodies for the common
  shapes.

---

## Step 3 — execute a query

```http
POST /rest/saiku/api/ai/ossie/query
Content-Type: application/json
```

Request:

```json
{
  "connection": "unknown_Pharma",
  "model": "Pharma",
  "rows": [{"dataset": "geography", "field": "region"}],
  "columns": [{"dataset": "payer", "field": "channel"}],
  "values": [
    {"metric": "net_revenue", "aggregation": "AVG"}
  ],
  "filters": [
    {"dataset": "geography", "field": "state", "op": "IN", "values": ["NY", "TX"]}
  ],
  "sorts": [{"metric": "net_revenue", "direction": "DESC"}],
  "limit": 25
}
```

Response — **records format** (default):

```jsonc
{
  "queryId": "ossie-ai-a3f81",
  "runtime": 42,
  "columns": [
    {"key": "geography.region",  "label": "Region",   "type": "dimension"},
    {"key": "payer.channel",     "label": "Channel",  "type": "dimension"},
    {"key": "net_revenue",       "label": "Net Revenue", "type": "metric",
     "aggregationKind": "avg"}
  ],
  "records": [
    {
      "geography.region": "Northeast",
      "payer.channel": "Commercial",
      "net_revenue": {"value": 250.00, "formatted": "$250.00"}
    }
  ],
  "meta": {
    "rowCount": 1,
    "truncated": false
  }
}
```

Add `?format=matrix` for the position-indexed shape:

```jsonc
{
  "queryId": "…",
  "runtime": 40,
  "format": "matrix",
  "cellSetHeaders": [[{"value":"geography.region","type":"COLUMN_HEADER"}, ...]],
  "cellSetBody": [[{"value":"Northeast","type":"ROW_HEADER"}, {"value":"675.75","type":"DATA_CELL","raw":675.75}]]
}
```

### Filter operators

Same set as the shelf translator: `EQ`, `NEQ`, `LT`, `LTE`, `GT`,
`GTE`, `IN`, `BETWEEN`, `IS_NULL`, `IS_NOT_NULL`. Single-value ops
use `value`; `IN`/`BETWEEN` use `values`. Empty `IN` synthesises the
trivially-false predicate (`1 = 0`) — returns zero rows rather than
a parse error.

### Aggregation override on the fly

`values[i].aggregation` swaps the metric's declared outer aggregation
(SUM → AVG etc.) via the translator's `swapAggregation`. The server
rejects overrides that aren't in the metric's `supportedOverrides`:

```json
// Request
{"metric": "line_count", "aggregation": "SUM"}

// Response — 400
{
  "error": "VALIDATION_ERROR",
  "field": "values[0].aggregation",
  "message": "aggregation 'SUM' not supported for metric 'line_count'",
  "available": ["COUNT"]
}
```

### Privacy: k-anonymity suppression

Configure at the launcher via env `SAIKU_AI_KANONYMITY_K` (default 5)
and `SAIKU_AI_KANONYMITY_MASK` (default `null`).

When the threshold is set and a query includes a count-shaped metric
(`aggregationKind: "count"`), the server checks each row's count
against `k`. Rows whose count falls below `k` have every metric cell
masked and the top-level `meta.suppressed` block records the count:

```jsonc
{
  "records": [
    {
      "geography.region": "Midwest",
      "line_count": {"formatted": "null"}   // masked because backing count < k
    }
  ],
  "meta": {
    "rowCount": 4,
    "suppressed": {"count": 4, "reason": "k-anonymity threshold k=5"}
  }
}
```

Suppression applies to both records **and** matrix output (#1402).

### PII redaction

Fields marked `pii: true` in the Ossie YAML are stripped entirely
from the schema view. Callers can't reference them; a query that does
returns `VALIDATION_ERROR` naming the field as "unknown".

---

## Step 4 — validation: how the API teaches the agent

Every wrong name comes back with a candidate list. The agent's next
attempt uses one of the alternatives:

```jsonc
// Request with a typo
{"rows": [{"dataset": "geographi", "field": "region"}], "values": [{"metric": "net_revenue"}]}

// 400 Response
{
  "error": "VALIDATION_ERROR",
  "field": "rows[0].dataset",
  "message": "unknown dataset 'geographi'",
  "available": ["fact_pharma", "geography", "payer", "product"]
}
```

The same shape covers:

- unknown datasets / fields / metrics
- unsupported filter ops
- BETWEEN with fewer than two values
- sort refs that name both `metric` and `field`
- limit ≤ 0
- empty rows/columns/values
- (#1399) `timeAxis` on `/anomaly` + `/forecast` that isn't a real
  column key AND doesn't appear in `rows[]` or `columns[]`

---

## Step 5 — preview

```http
POST /rest/saiku/api/ai/ossie/query/preview
```

Same body as `/query`. Response:

```json
{
  "queryId": "ossie-ai-preview-9fe75acf",
  "status": "PREVIEW",
  "generatedSql": "SELECT \"geography\".\"region\" AS \"geography.region\", SUM(\"fact_pharma\".\"NETREVENUE\") AS \"net_revenue\" FROM \"fact_pharma\", \"geography\" GROUP BY \"geography\".\"region\""
}
```

Uses the same translator the executor runs — what you see here is 1:1
what `/query` would dispatch.

---

## Step 6 — value search

```http
GET /rest/saiku/api/ai/ossie/values/search?connection=unknown_Pharma&dataset=payer&field=CHANNEL&q=medi&limit=20
```

```json
{
  "connection": "unknown_Pharma",
  "model": "Pharma",
  "dataset": "payer",
  "field": "CHANNEL",
  "q": "medi",
  "limit": 20,
  "matches": ["Medicaid", "Medicare"]
}
```

Runs `SELECT DISTINCT <col> FROM <dataset> WHERE UPPER(CAST(<col> AS VARCHAR)) LIKE '%…%' LIMIT n`.
Omit `q` for the first N distinct values.

---

## Step 7 — row detail (drillthrough)

```http
POST /rest/saiku/api/ai/ossie/row-detail?maxrows=5
```

Body: the same shelf state you'd send to `/query`. The server re-runs
it with `values=[]` so the executor emits raw rows instead of an
aggregate:

```json
{
  "connection": "unknown_Pharma",
  "model": "Pharma",
  "rows": [{"dataset": "geography", "field": "region"}],
  "filters": [{"dataset": "geography", "field": "region", "op": "EQ", "value": "West"}]
}
```

Response: records format, `meta.truncated: true` when the row cap is
hit (default 100, max 10 000).

---

## Step 8 — async

For queries you expect to take more than a few seconds:

```http
POST /rest/saiku/api/ai/ossie/query/execute-async
```

Same body as `/query`. Response 202:

```json
{"queryId": "ossie-ai-async-b940ff73", "status": "PENDING"}
```

Poll:

```http
GET /rest/saiku/api/ai/ossie/query/status/{queryId}
```

Status transitions: `PENDING` → `RUNNING` → `DONE` | `FAILED` | `CANCELLED`.

Fetch:

```http
GET /rest/saiku/api/ai/ossie/query/result/{queryId}
```

Returns 202 with `{queryId, status}` while running, 200 with the full
records response (or `?format=matrix`) on DONE.

Cancel:

```http
DELETE /rest/saiku/api/ai/ossie/query/{queryId}
```

**Ownership** (#1403): every handle is scoped to the caller's Spring
Security principal. A foreign session polling another user's queryId
gets 404 — the same shape as an unknown id, so status codes can't be
used as an existence oracle. Admins can poll any handle.

---

## Step 9 — analytics

### Anomaly detection

```http
POST /rest/saiku/api/ai/ossie/anomaly
```

```json
{
  "query": {
    "connection": "unknown_Pharma", "model": "Pharma",
    "rows": [{"dataset": "geography", "field": "region"}],
    "values": [{"metric": "net_revenue"}]
  },
  "timeAxis": "geography.region",
  "method": "zscore",
  "threshold": 1.5
}
```

Detectors (`AnomalyDetectors.methods()`):

- `zscore` — classic z-score; threshold = sigmas from the mean
- `mad` — median absolute deviation; robust to outliers
- `stl` — seasonal-trend decomposition (stubbed; falls back to zscore
  on non-seasonal data)

Response: the same records shape as `/query` with an anomaly-annotated
metric cell where the detector flagged a point:

```jsonc
{
  "records": [
    {
      "geography.region": "Midwest",
      "net_revenue": {
        "value": 675.75,
        "formatted": "675.75",
        "anomaly": {
          "score": 1.7,
          "expected": 575.81,
          "direction": "high"
        }
      }
    }
  ],
  "anomaly": {
    "method": "zscore",
    "threshold": 1.5,
    "anomalyCount": 1,
    "timeAxis": "geography.region"
  }
}
```

### Forecast

```http
POST /rest/saiku/api/ai/ossie/forecast
```

```json
{
  "query": {
    "connection": "unknown_Pharma", "model": "Pharma",
    "rows": [{"dataset": "geography", "field": "region"}],
    "values": [{"metric": "net_revenue"}]
  },
  "timeAxis": "geography.region",
  "method": "ets",
  "horizon": 3,
  "interval": 0.95
}
```

Forecasters (`Forecasters.methods()`): `ets`, `arima`, `prophet`.

Response — historical records are untouched; projections land under a
top-level `forecast` block keyed by metric:

```jsonc
{
  "records": [ /* the historical rows, in time order */ ],
  "forecast": {
    "net_revenue": {
      "method": "ets",
      "horizon": 3,
      "confidence": 0.95,
      "points": [
        {"index": 4, "value": 573.41, "lower": 417.73, "upper": 729.08},
        {"index": 5, "value": 598.97, "lower": 378.81, "upper": 819.14},
        {"index": 6, "value": 624.53, "lower": 340.24, "upper": 908.82}
      ]
    }
  }
}
```

**timeAxis validation** (#1399): must be `<dataset>.<field>` where the
ref exists on the model AND appears in the query's `rows[]` or
`columns[]`. Anything else returns a `VALIDATION_ERROR` with the query's
axis candidates.

---

## Step 10 — natural-language ask

```http
GET /rest/saiku/api/ai/ossie/ask/health
```

```json
{"configured": true, "provider": "anthropic (claude-sonnet-4-6)"}
```

Off by default. To enable, set on the launcher:

- `saiku.ai.ask.provider` = `anthropic` | `openai`
- env `ANTHROPIC_API_KEY` (Anthropic) or `OPENAI_API_KEY` (OpenAI)
- optional `saiku.ai.ask.model` = model id override
- optional `saiku.ai.ask.endpoint` = custom base URL (OpenAI-compatible
  proxies like vLLM, Ollama, Together)

Then:

```http
POST /rest/saiku/api/ai/ossie/ask
```

```json
{
  "connection": "unknown_Pharma",
  "model": "Pharma",
  "question": "What's the revenue per region for Medicaid?",
  "history": [
    {"role": "user", "content": "show me sales by product"},
    {"role": "assistant", "content": "here's revenue by brand..."}
  ]
}
```

`history` is optional (#1398). Every turn is passed to the LLM in
order so it can resolve follow-up references like "what about by
channel?".

Response:

```jsonc
{
  "question": "…",
  "connection": "…",
  "model": "…",
  "queryUsed": { /* the OssieAiQueryRequest the LLM produced */ },
  "rawLlmResponse": "{\"connection\":\"…\",\"rows\":[…]}",
  "response": { /* the full records-format execution result */ }
}
```

**How structured output is enforced** (#1397): the service forces the
LLM into a `tool_use` (Anthropic) / `tool_choice: function` (OpenAI)
call whose schema mirrors `OssieAiQueryRequest`. No JSON-in-text
parsing, no fence-stripping. Off-topic questions come back as
`{"error": "OFF_TOPIC", "message": "…"}` and surface as 400 with the
narration attached.

---

## MCP integration

The five Ossie tools are exposed via `POST /saiku/api/mcp` alongside
the six MDX tools. Each wraps the corresponding REST endpoint:

| MCP tool | REST equivalent |
| --- | --- |
| `list_ossie_models` | `GET /ai/ossie/models` |
| `describe_ossie_model` | `GET /ai/ossie/schema/{c}/{m}` |
| `search_field_values` | `GET /ai/ossie/values/search` |
| `run_ossie_query` | `POST /ai/ossie/query` |
| `preview_ossie_query` | `POST /ai/ossie/query/preview` |

Claude Desktop / Cursor / Cline pick these up automatically once the
saiku session is authenticated.

---

## A typical agent loop

1. `list_ossie_models` (or `GET /models`) — pick a model
2. `describe_ossie_model` (or `GET /schema/{c}/{m}`) — read datasets,
   fields, metrics, examples
3. If the user asks about a specific value the schema didn't sample —
   `search_field_values` (`GET /values/search`) to confirm spelling
4. Build a query body from `examples.simpleGroupBy` (or one of the
   other examples) as a template, substituting the user's dimensions
   and metrics
5. `run_ossie_query` (or `POST /query`) — if the response is a
   `VALIDATION_ERROR`, pick a candidate from `available` and retry
6. On a chart-request → repeat with `format: "matrix"`
7. On an "explain the trend" → `/anomaly` or `/forecast`
8. On "show me the underlying rows" → `/row-detail`

Or for natural-language flows: skip 4–7 entirely and use `/ask`.

---

## Cross-references

- Main issue: #1394 (R1–R5 delivery)
- Fuzz suite: `OssieFuzzIT` covers 6961 shelf-state combinations
  (49 hand-crafted + 6912 combinatorial) against a live Calcite +
  Ossie + H2 warehouse
- Role-based security follow-up: #1393
- Library extraction follow-up: #1396
- MDX AI Query API: [`AI-QUERY-API.md`](AI-QUERY-API.md)
