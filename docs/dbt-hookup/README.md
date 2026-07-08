# dbt / MetricFlow → Saiku hookup

Point Saiku at your existing dbt project's semantic model. The
converter here reads MetricFlow YAML (the shape dbt has used since
1.7) and emits [Apache Ossie](https://github.com/apache/ossie) YAML
that Saiku loads through its normal datasource registration.

Once wired, everything Saiku exposes over its Ossie model — the
workbench, `/rest/saiku/api/ai/ossie/*` REST surface, the 5 MCP tools,
the `/ai/ossie/ask` natural-language layer, anomaly + forecast
endpoints — works against your dbt semantic layer with zero
re-modelling.

## Why not `dbt --osi` directly?

dbt Core v1.12 will emit OSI JSON natively via
[`target/osi_document.json`](https://docs.getdbt.com/docs/build/osi-semantic-models),
but v1.12 hasn't shipped to PyPI yet (as of July 2026 the latest
release is 1.11.12). Meanwhile most dbt projects in the wild have
MetricFlow semantic models today, and dbt has been emitting OSI
v0.1.x — Saiku follows v0.2.0.dev0 (the current draft). Either way,
a version-adapter is a real need — this converter is that adapter.

Once dbt 1.12 ships and stabilises on the same spec version as
Saiku, this converter becomes a thin CLI over "read the JSON dbt
already wrote."

## Ingredients

- `orders_semantic.yml` — a sample MetricFlow YAML (semantic_models +
  metrics)
- `metricflow_to_ossie.py` — the converter (~200 lines, pure Python +
  pyyaml)
- `orders-seed.sql` — an H2 fixture that mirrors what the dbt project's
  `fct_orders` / `dim_customers` / `dim_locations` models would produce

## One-shot walkthrough

```bash
# 1. Convert MetricFlow YAML → Ossie YAML.
cd docs/dbt-hookup
pip install pyyaml
python3 metricflow_to_ossie.py orders_semantic.yml \
  --model-name Orders \
  --description "Orders demo from dbt MetricFlow" \
  > orders.ossie.yaml

# 2. Seed the warehouse — H2 for the local smoke; swap for your real
#    warehouse's JDBC URL when running for keeps.
H2_JAR=$(find ~/.m2 -name 'h2-2.3.232.jar' | head -1)
java -cp "$H2_JAR" org.h2.tools.RunScript \
  -url "jdbc:h2:$PWD/orders;MODE=PostgreSQL" \
  -user sa -password '' \
  -script orders-seed.sql

# 3. Register with Saiku. Drop this .sds template into
#    saiku-home/repository/data/unknown/datasources/ with @SAIKU_HOME@
#    substituted (same shape as tpcds-ossie.sds / flights-ossie.sds).
cat > /tmp/orders-ossie.sds <<EOF
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<dataSource>
    <id>orders-ossie-01</id>
    <name>Orders</name>
    <type>OSSIE</type>
    <ossieYaml>${PWD}/orders.ossie.yaml</ossieYaml>
    <location>jdbc:h2:${PWD}/orders;MODE=PostgreSQL;AUTO_SERVER=TRUE</location>
    <schema>Orders</schema>
    <username>sa</username>
    <password></password>
    <advanced>false</advanced>
    <enabled>true</enabled>
</dataSource>
EOF

# 4. Point Saiku at it (either by copying the .sds file into your
#    running launcher's saiku-home/repository/data/unknown/datasources/
#    or via the admin API), then verify:
curl -s http://localhost:8080/rest/saiku/api/ai/ossie/models \
    -b /tmp/saiku-cookies.txt | jq '.[] | select(.modelName == "Orders")'
```

Expected output:

```json
{
  "connectionName": "unknown_Orders",
  "modelName": "Orders",
  "description": "Orders demo from dbt MetricFlow",
  "factDataset": "orders",
  "datasetCount": 3,
  "metricCount": 3
}
```

## What the converter maps

| MetricFlow shape | Ossie shape |
| --- | --- |
| `semantic_models[*]` | `datasets[*]` (name + description + fields + primary_key) |
| `.model: ref('fct_orders')` | `dataset.source: FCT_ORDERS` |
| `.entities[type=primary]` | `dataset.primary_key` |
| `.entities[type=foreign]` | Ossie `relationships` (matched to primary entities on other datasets by name) |
| `.dimensions[*]` (label + expr) | `dataset.fields[*]` — label field, ANSI expression |
| `.measures[*]` | Building blocks for metrics — not surfaced directly (matches Ossie's shape) |
| `metrics[type=simple]` | `metrics[*]` — expression composed from `agg(expr)` on the source dataset |
| `metrics[type=ratio]` | `metrics[*]` — expression `(SUM(num)) / NULLIF(SUM(denom), 0)` |
| `metrics[type=cumulative]` | Warning + skip (out of scope; file an issue if you need it) |
| `metrics[type=derived]` | Warning + skip (same) |

## What you get on the Saiku side

Same surface as any other Ossie model:

- **Workbench UI** — drag-drop shelves, undo/redo, sort/limit,
  aggregation override, right-click filter, crosstab pivot, chart view
- **AI Query API** at `/rest/saiku/api/ai/ossie/*` — /models, /schema,
  /query, /query/preview, /values/search, /query/execute-async,
  /anomaly, /forecast, /row-detail, /ask (see
  [`AI-OSSIE-API.md`](../AI-OSSIE-API.md))
- **MCP tools** at `/rest/saiku/api/mcp` — `list_ossie_models`,
  `describe_ossie_model`, `search_field_values`, `run_ossie_query`,
  `preview_ossie_query`
- **k-anonymity + PII redaction** — configure at the launcher, applies
  to all Ossie models uniformly

## What's not translated (yet)

- **`metrics[type=cumulative]`** — MetricFlow's rolling / period-to-date
  metrics need Ossie window-function support that we don't emit today.
- **`metrics[type=derived]`** — arbitrary expressions over other
  metrics. Doable, but the R1 converter only walks one level for
  ratios; deeper nesting would need iteration.
- **Custom aggregations** — `median`, `percentile_cont`, etc. work
  when the underlying warehouse supports them (Postgres, DuckDB,
  Snowflake, BigQuery all do; H2 depends on version). No behaviour
  difference from any other Ossie metric expression — the converter
  just spells them out.
- **`agg_time_dimension` inference** — MetricFlow uses it to auto-add
  time filters; Ossie leaves that to the caller. Set `timeAxis`
  explicitly on `/anomaly` + `/forecast` requests.

## Feedback + issues

Improvements to the converter live under the parent tracker
[#1394](https://github.com/spiculedata/saiku/issues/1394). If dbt v1.12
lands before we drop this shim, we'll switch to reading
`target/osi_document.json` directly and this file becomes a fallback
for older dbt projects.
