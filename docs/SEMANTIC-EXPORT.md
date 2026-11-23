# Semantic Layer Sync — Tableau / Superset export

Renders an Ossie semantic model into a downloadable file for another BI
tool, so a team that already standardised on Tableau or Superset doesn't
have to hand-redeclare datasets, columns, and metrics that already exist
in the Ossie YAML. Ships in issue #1427.

```
GET /saiku/api/ai/ossie/export/{connection}/{tool}
```

- `connection` — an Ossie-typed connection name (same value `GET
  /saiku/api/ai/ossie/models` lists).
- `tool` — `tableau` or `superset` (case-insensitive).

Returns the file inline with `Content-Disposition: attachment`, so a
browser hitting the URL downloads it directly.

## Tableau (`tool=tableau`)

Produces a `.tds` (Tableau Datasource) XML file:

- One `<relation type="table">` per Ossie dataset.
- Datasets connected via the model's `relationships:` fold into an
  inner-join tree (in relationship order); a dataset not reachable from
  another via a relationship is emitted as an independent sibling
  relation instead of being silently dropped.
- One dimension `<column>` per field.
- One measure `<column>` with a `<calculation>` per metric — the
  metric's SQL expression is rewritten from `"dataset"."field"` /
  `dataset.field` references into Tableau's `[dataset].[field]` bracket
  syntax.

**What it does not do:** the `<connection class="genericodbc">` block
is a placeholder — `server`/`dbname` are left blank. Saiku's Ossie
warehouse URL is an arbitrary JDBC URL (Postgres, DuckDB/Quack, H2, …)
with no general mapping onto Tableau's native connector list, so wiring
a live connection (and, further out, driving a `.hyper` extract from
it) is connector-specific follow-up work, not something this endpoint
attempts. Open the `.tds` in Tableau Desktop and point the connection
at your warehouse to go live.

## Superset / Preset (`tool=superset`)

Produces a `.zip` in the same shape `superset import-datasources`
consumes:

```
metadata.yaml
databases/<connection>.yaml
datasets/<connection>/<dataset-1>.yaml
datasets/<connection>/<dataset-2>.yaml
...
```

- Each dataset YAML carries `columns` (one per field, `is_dttm` set
  from the field's `time:` flag) and `metrics` (one per Ossie metric
  whose expression resolves to that dataset — see below).
- A metric's expression is unwrapped relative to its own dataset (`SUM("fact_pharma"."NETREVENUE")`
  becomes `SUM(NETREVENUE)` inside `datasets/.../fact_pharma.yaml`) since a Superset dataset is a
  single table. A metric referencing a *different* dataset is left as `dataset.field` in the
  expression — Superset would need that second dataset joined into a virtual SQL dataset, which
  this exporter doesn't attempt.

**What it does not do:** `databases/<connection>.yaml` ships a
placeholder `sqlalchemy_uri` (same reasoning as Tableau's blank
connection above) — replace it with your warehouse's real SQLAlchemy
URI before running `superset import-datasources`.

## Metric-to-dataset attribution

`OssieModelDto.Metric` doesn't carry an explicit owning-dataset field —
only a SQL `expression`. Both exporters recover the link by scanning
the expression for the first `dataset.field` reference that names a
real dataset in the model (falling back to the model's "fact dataset"
heuristic — first dataset named `fact*`, else the first dataset
declared — when the expression doesn't resolve to one). This is a
best-effort heuristic: a ratio metric spanning two datasets attributes
to whichever qualified reference appears first in the expression.

## Known gaps vs. the parent issue

Tracked as follow-up on saiku#1427, not attempted here:

- `.hyper` extract generation (needs the Tableau connector mapping above first).
- `saiku export ossie <conn> --tool=… --out=…` CLI command — the REST endpoint above covers the
  same rendering; a CLI wrapper needs to load a datasource by name from `saiku-home` offline,
  which is a separate piece of plumbing.
- Round-trip verification against a live Tableau Desktop / Superset import (needs the connector
  work above to have real data to compare against).
