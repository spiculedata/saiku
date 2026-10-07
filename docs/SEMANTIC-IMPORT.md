# Semantic Model Import (saiku#1730)

The import mirror of Semantic Layer Sync (#1427, which exports). Instead of discovering the
`ossie convert` CLI, an operator walks into **Admin → Datasources → Import model**, attaches the
export they already have, and gets a Saiku semantic model they can query in the workbench.

Nothing is persisted until the operator confirms, and no datasource is created by the importer —
it hands back a path that the existing OSSIE datasource form consumes, so there is exactly one
way to register a connection.

---

## The flow

| Step | Call | Persists? |
| --- | --- | --- |
| List importable formats | `GET /rest/saiku/api/ossie/import/formats` | no |
| Convert + validate the upload | `POST /rest/saiku/api/ossie/import` | **no** |
| Write the confirmed YAML | `POST /rest/saiku/api/ossie/import/save` | yes — one file |
| Register the connection | `POST /rest/saiku/admin/datasources` (existing API) | yes |

The split is the point: step 2 returns the model, its YAML and a per-element validation report,
and the operator decides whether it's worth keeping.

### Convert (multipart or JSON)

```bash
curl -sS -X POST http://localhost:8080/rest/saiku/api/ossie/import \
  -F format=lookml -F modelName=Sales \
  -F file=@orders.view -F file=@users.view
```

The browser posts JSON instead (`{"format":"lookml","modelName":"Sales","files":[{"name":"orders.view","content":"…"}]}`)
because it has already read the files locally — that avoids a nested base64 encoding for what
can be a 300 KB LookML project.

Response:

```json
{
  "formatId": "lookml",
  "modelName": "Sales",
  "yaml": "version: 0.2.0.dev0\nsemantic_model:\n- name: Sales\n…",
  "validation": {
    "datasetCount": 2, "fieldCount": 5, "metricCount": 1, "relationshipCount": 1,
    "errorCount": 0, "warningCount": 1, "infoCount": 0,
    "datasets": [{ "name": "orders", "source": "public.orders", "fieldCount": 3, "primaryKeyCount": 1 }],
    "diagnostics": [
      { "severity": "WARNING", "code": "DERIVED_TABLE", "element": "orders_current",
        "message": "`derived_table:` (a LookML SQL view) imports as a plain reference…" }
    ]
  }
}
```

### Save

```bash
curl -sS -X POST http://localhost/saiku/api/ossie/import/save \
  -H 'Content-Type: application/json' \
  -d '{"modelName":"Sales","yaml":"<the yaml step 2 returned>","overwrite":false}'
→ {"path":"/opt/saiku/saiku-home/semantic-models/sales.ossie.yaml"}
```

The server **re-validates** before writing — a client can't talk it into saving a model its own
report called broken. A model with errors is rejected 400 with the report attached; warnings save.
`overwrite: false` (the default) refuses to clobber an existing file: 409, pick a different model
name or pass `true`.

Then paste the path into **Admin → Datasources → Add** with Type = `Ossie (SQL semantic model)`
plus the warehouse JDBC URL. The import modal pre-fills the form with the path, the model name
and the OSSIE type for you.

---

## Formats

### LookML (Looker) — the new spoke

`view:` → dataset, `dimension:` → field, `measure:` → metric, `explore:` + `join_on:` →
relationship.

| LookML | Ossie | Notes |
| --- | --- | --- |
| `view: orders` | `datasets[]` | |
| `source_table` / `sql_table_name: { schema, table }` | `dataset.source` | |
| `derived_table: { sql: … }` | plain table reference | **Warning** — Saiku resolves dataset sources against the warehouse and doesn't materialise views. Create the view, or point at the base table. |
| `dimension:` + `sql: ${TABLE}.col` | `fields[]` | `${TABLE}` resolves to the bare column. |
| `dimension: x { type: timestamp }` | time dimension | `time` / `date` in the type. |
| `dimension: x { primary_key: yes }` | `dataset.primary_key` | |
| `measure: m { type: sum, sql: … }` | `metrics[]` | Namespaced: `orders_m`, because Ossie metrics are model-global. |
| `measure: m { type: count }` (no sql) | `COUNT(*)` | |
| `count_distinct` / `sum_distinct` | `COUNT(DISTINCT x)` / `SUM(DISTINCT x)` | |
| `measure: m { type: string_agg }` | — | **Warning**, not imported — no SQL aggregate equivalent. |
| `dimension: x { hidden: yes }` | — | **Info**, deliberately skipped. |
| `explore: e { join: x { join_on: ${a.id} = ${b.customer_id} } }` | `relationships[]` | Only simple column = column equality. |
| `join_on` with `sql_on` / `sql_join_one` | — | **Warning**, not imported — Ossie relationships are column-to-column. |
| `{{ liquid }}` | stripped | **Warning** — there's no Looker runtime to evaluate it offline. |
| `dimension_groups:`, `filter:`, `access:` | — | **Info** where harmless, **Warning** where it loses meaning. |

LookML is parsed by a small brace-structured reader
(`LookmlParser`), not by a YAML front-end: LookML's quoting, bare expressions and templating
don't line up with YAML, and a half-templated file has to degrade to warnings rather than abort.

### dbt (`target/manifest.json`)

`nodes[resource_type=model]` and `sources[]` → datasets, `columns` → fields (date/timestamp →
time dimensions), a `unique` test on a column → primary key, `metrics[]` → metrics, and each
`relationships` test is reported so the operator knows which relationship to declare by hand.

A metric whose expression is still a dbt template (`{{ ref('orders') }}`) is **not** imported —
there's no target to compile it against offline.

---

## The validation report

Every finding is anchored to an element (`orders.total_amount`, `relationship.orders_to_users`)
and carries a stable `code`, so a future version can group or suppress a class of findings
without the client string-matching prose.

**Errors** block the save — they mean the model can't work as written:

- a relationship pointing at a dataset that isn't in the model, or with no/mismatched join columns
- a metric expression referencing a dataset that isn't in the model
- duplicate dataset / field / metric / relationship names (the SQL aliases would collide)
- a generated YAML document that doesn't parse, or parses with no semantic model

**Warnings** save, and are the "degraded" list the issue asks for: derived tables, hidden
elements, unsupported measure types, skipped joins, leftover liquid, datasets with no source,
fields with no expression, joins to columns that don't exist on either side.

**Info** is the "we ignored this on purpose" list: `include:`, `filter:`, `hidden: yes`,
`dimension_groups:`, dbt relationship tests.

---

## Adding another format

The set of formats is an open list, not a switch statement — this is the "converter hub" seam
the issue asks for:

1. implement `VendorModelConverter` (`id`, `displayName`, `description`, `fileExtensions`,
   `convert(files, requestedModelName)`), returning an `OssieDocument` plus diagnostics;
2. add the bean to `vendorModelConverterRegistry` in `saiku-beans.xml`.

Nothing in the REST layer, the validation report or the UI changes: the picker reads
`GET /import/formats`, and the report is format-agnostic. The vendored `apache/ossie`
converters (snowflake, gooddata, polaris, …) can be wrapped the same way as they gain a
server-callable entry point; nothing in Saiku forks their conversion logic.

## Guard rails

- Admin-only: class-level `@RolesAllowed("ROLE_ADMIN")` plus an explicit `userService.isAdmin()`
  on the write path.
- Uploads are capped at 50 files and 25 MB total.
- Saved file names are slugged to `[a-z0-9_-]`, and the write target is re-checked for
  containment inside the model directory.

## Where things live

- `org.saiku.service.ossie.converter` (`saiku-service`) — converters, parser, validator, import service.
- `org.saiku.web.rest.resources.OssieImportResource` (`saiku-web`) — `@/saiku/api/ossie/import`.
- `saiku-ui/src/lib/modals/OssieImportModal.svelte` — the three-step dialog.
- `saiku-ui/src/lib/api/ossie.ts` — `listOssieImportFormats` / `importOssieModel` / `saveOssieImport`.
