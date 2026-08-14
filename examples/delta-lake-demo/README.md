# Saiku on Delta Lake: MDX over Trino + Delta in ~10 minutes

Last time it was **Apache Iceberg** ([`examples/lakehouse-demo/`](../lakehouse-demo/)).
This time it's **Delta Lake** — the table format Databricks runs on — and the
whole point is that **not a single line of Saiku, Mondrian, or Calcite
changes.** Trino's `delta_lake` connector fronts Delta exactly like its
`iceberg` connector fronts Iceberg, and Saiku's Calcite-based Mondrian backend
already speaks the Trino dialect. Same drag-and-drop OLAP pivots, same TPC-H
cube, same numbers — different lakehouse:

```
Saiku UI  →  Mondrian (MDX → SQL)  →  Calcite (TrinoSqlDialect)  →  Trino JDBC  →  Delta Lake (Hive Metastore)  →  Parquet + _delta_log on MinIO
```

The diff between this demo and the Iceberg one is essentially **one connector
line** in Trino's catalog config (`connector.name=delta_lake` instead of
`iceberg`, plus a Hive Metastore where Iceberg used a REST catalog). The
Mondrian schema (`LakehouseSales.xml`) is **byte-identical** to the Iceberg
demo's. That's the story.

> **⚠️ Local only — no authentication.** This demo is deliberately wide open so
> it runs on a laptop with zero setup: the MinIO `warehouse` bucket is made
> **anonymously public**, the MinIO root and S3 access keys are the hardcoded
> `admin` / `password`, the standalone Hive Metastore is unauthenticated, and
> the Trino coordinator + web UI (host `:8091`) run with **no password**. Treat
> every port here as untrusted. **Never bind these ports to a public interface,
> a shared host, or a corporate network, and never reuse these credentials.**
> Run it only on `localhost`, and tear the stack down (`docker compose down -v`)
> when you're done. It is a walkthrough, not a template for any deployment.

## What you need

- Docker (Compose v2)
- Java 21+ and a Saiku launcher JAR (or the Saiku Docker image for the
  UI-on-docker variant)
- ~5 GB free disk for images

You can run this **alongside** the Iceberg demo — the two stacks use different
container names, host ports (`:8091` vs `:8090`, MinIO `:9002/9003` vs
`:9000/9001`), and Saiku homes, so they don't collide. Great for a
side-by-side.

## Step 1 — bring up the Delta lakehouse

```bash
cd examples/delta-lake-demo
docker compose up -d
```

Four containers:

| Container | Role | Host port |
|---|---|---|
| `delta-minio` | S3-compatible object store (the "lake") | 9002 API / 9003 console |
| `delta-hive-metastore` | Hive Metastore — Delta's table catalog | 9083 |
| `delta-trino` | Query engine (`delta_lake` connector) | **8091** (Saiku keeps 8080) |
| `delta-minio-init` | one-shot bucket bootstrap, then exits | — |

Unlike Iceberg's REST catalog, Trino's Delta connector needs a **Hive
Metastore**. We run the standalone `apache/hive:4.0.0` metastore backed by
embedded Derby — no external database container, and the table metadata is
ephemeral by design (`down -v` wipes it, re-seed to recreate). Give the
metastore and Trino ~30-60s to go healthy on first boot.

## Step 2 — seed real Delta tables

```bash
./seed.sh        # Windows: ./seed.ps1
```

This runs `seed.sql` through the Trino CLI: it CTAS-es Trino's built-in
TPC-H generator into **`delta.sales.fact_orders`** — 15,000 orders,
denormalised with each customer's region/nation and the order date split
into year/month. Genuine Delta Lake: Parquet data files **and a
`_delta_log/` transaction log** land in MinIO, table metadata in the Hive
Metastore. Check it:

```bash
docker exec delta-trino trino --execute \
  "SELECT region_name, count(*) FROM delta.sales.fact_orders GROUP BY 1"
```

You should see the five TPC-H regions summing to 15,000 orders. Confirm it's
really Delta (not just Parquet) — the transaction log is the tell:

```bash
docker exec delta-minio sh -c \
  "mc alias set local http://localhost:9000 admin password >/dev/null; \
   mc ls -r local/warehouse/sales/ | grep _delta_log"
```

`seed.sql` is the Iceberg demo's seed with **one word changed** (`iceberg` →
`delta`) plus an explicit `s3://` location on the schema — year/month stay
`CAST(... AS INTEGER)` so Mondrian's Time levels don't hit a `1992.0` float.

## Step 3 — wire Saiku to it

```bash
./setup.sh       # Windows: ./setup.ps1
```

This prepares a dedicated `delta-home/` for Saiku:

1. **Downloads the Trino JDBC driver into `<home>/plugins/`.** Saiku's
   launcher puts every jar in `plugins/` on the webapp classpath at boot —
   the very same driver the Iceberg demo uses. No rebuild.
2. **Stages the Mondrian schema** (`LakehouseSales.xml`) — one cube,
   `Lakehouse Sales`, over the fact table: a Market hierarchy
   (Region → Nation), a Time hierarchy (Year → Month), order Status /
   Priority attributes, and three measures (Total Price, Order Count,
   Avg Order Value). **This file is byte-identical to the Iceberg demo's.**
3. **Stages the datasource** (`delta.sds`). The connect string is the whole
   demo in one line — the only Saiku-side edit vs Iceberg is the catalog name
   (`delta`) and port (`8091`):

   ```
   jdbc:mondrian:Jdbc=jdbc:trino://localhost:8091/delta/sales?user=saiku;Catalog=file:<home>/data/LakehouseSales.xml;JdbcDrivers=io.trino.jdbc.TrinoDriver
   ```

Then start Saiku:

```bash
java -Dsaiku.allowDefaultAdmin=true -jar saiku-<version>.jar serve --port 8080 --home ./delta-home
```

Watch for the boot line confirming the driver loaded:

```
Plugins on webapp classpath: .../plugins/trino-jdbc-476.jar
```

## Step 4 — drag, drop, drill

Open <http://localhost:8080/ui/> (admin / admin), pick the **Lakehouse
Sales** cube, and drag **Markets** onto rows, **Status** onto columns,
**Total Price** into measures. Sub-second pivots; expand a region to
drill into nations; flip to Chart view for the bar breakdown.

Behind the scenes, Mondrian is writing MDX, Calcite's `TrinoSqlDialect`
is turning it into Trino SQL, and Trino is scanning **Delta** Parquet on
MinIO. See it yourself in the Trino console at <http://localhost:8091/ui/>
(any username, no password) — filter Query details for `fact_orders`:

```sql
SELECT "region_name", "nation_name", "order_status",
       SUM("total_price") AS "m0", COUNT("order_key") AS "m1"
FROM "fact_orders"
GROUP BY "region_name", "nation_name", "order_status"
```

Clean aggregate pushdown — the OLAP engine asks the lakehouse for exactly
the rollup it needs, nothing more. **This is byte-for-byte the same SQL the
Iceberg demo pushes down** — because the Calcite dialect never changed.

## Bonus: the AI surface works too

Everything Saiku exposes over the AI Query API / MCP now speaks Delta.
With the server up (start it with `-Dai.policy=aggregated` or higher so the
API may return aggregated values):

```bash
curl -u admin:admin -H "Content-Type: application/json" \
  -X POST http://localhost:8080/rest/saiku/api/ai/query -d '{
    "cube": {"connectionName":"unknown_delta","catalog":"Lakehouse",
             "schema":"Lakehouse","cubeName":"Lakehouse Sales"},
    "measures":[{"name":"Total Price"},{"name":"Order Count"}],
    "rows":[{"dimension":"Market","hierarchy":"Markets","level":"Region"}]}'
```

Typed cells straight off Delta — e.g. `AFRICA → Total Price 445,136,670.46,
Order Count 3,115` — which means Claude Desktop / any MCP agent can query your
Delta lakehouse through the same 12 tools documented in
[`docs/mcp/`](../../docs/mcp/README.md). (Note the connection name is
`unknown_delta` here, vs `unknown_lakehouse` in the Iceberg demo.)

## Run it with your own data

This demo points at a throwaway TPC-H star on a local MinIO. To analyse **your
own** warehouse instead, you change three things — the connector, the schema,
and the data — and nothing in Saiku/Mondrian/Calcite. Here's what each one is
and how to swap it.

### 1. Point Trino at your catalog (the connector)

Everything Trino can query, Saiku can pivot. `trino/catalog/delta.properties` is
the only file that knows *where* the data lives:

```properties
connector.name=delta_lake
hive.metastore.uri=thrift://hive-metastore:9083   # your metastore
fs.s3.enabled=true
s3.endpoint=http://minio:9000                     # your object store
s3.aws-access-key=admin
s3.aws-secret-key=password
```

- **Already have a Delta lakehouse?** Repoint `hive.metastore.uri` at your Hive
  Metastore (or set `hive.metastore=glue` for AWS Glue) and the `s3.*` block at
  your bucket/endpoint (drop `s3.endpoint` for real AWS S3). Delete the seed
  step entirely — your tables are already registered.
- **Different engine, not Delta?** Drop in a different Trino catalog file:
  `iceberg.properties` (see the [Iceberg demo](../lakehouse-demo/)),
  `postgresql.properties`, `mysql.properties`, `bigquery.properties`,
  `snowflake.properties`, … — [any Trino connector](https://trino.io/docs/current/connector.html).
  **This is the whole point of fronting your warehouse with Trino:** Saiku's
  Calcite `TrinoSqlDialect` never changes, so swapping the lakehouse is a
  connector file, not a code change. Update the catalog name in the datasource
  URL (below) to match.
- **Skip Trino, connect Saiku straight to a JDBC warehouse?** You can — point
  the datasource `Jdbc=` at Postgres/MySQL/Snowflake/etc. directly and drop the
  matching JDBC driver in `<home>/plugins/`. Trino only earns its keep when you
  want ONE SQL surface over many stores, or lakehouse formats (Delta/Iceberg)
  that your BI-side JDBC driver can't read natively.

### 2. Describe your cube (the Mondrian M4 schema)

`saiku/LakehouseSales.xml` is a [Mondrian 4 schema](https://mondrian.pentaho.com/documentation/schema.php):
it maps your physical tables/columns to OLAP **dimensions**, **hierarchies**,
**levels**, and **measures** — the drag-and-drop vocabulary the UI shows. To
model your own star:

- Repoint `<Table name='fact_orders'>` at **your** fact table, and set its
  `<Key>` to your grain column.
- For each thing you slice **by** (region, product, date, customer…) add a
  `<Dimension>` with `<Attribute>`s bound to your columns, and a `<Hierarchy>`
  ordering the levels (e.g. `Year → Quarter → Month`, or `Category → Product`).
- For each thing you **measure** add a `<Measure>` with your numeric column and
  an `aggregator` (`sum`, `count`, `avg`, `min`, `max`, `distinct-count`) plus a
  `formatString`.
- Keep integer keys **integer** in the warehouse (or `CAST(... AS INTEGER)` in
  your load): a float year like `1992.0` breaks Mondrian's Time levels — that's
  why the seed casts. Snowflaked dims (a separate `dim_*` table) work too — add
  more `<Table>`s and join them; this demo is deliberately one denormalised
  table to stay simple.

The schema is where "your dimensions and measures" live; it references only
column names, so it's engine-agnostic — the same XML works over Delta, Iceberg,
Postgres, or anything else Trino/JDBC exposes with those columns.

### 3. Bring your own data (instead of TPC-H)

The seed is only here to *create* demo tables — if your tables already exist,
**you don't seed at all**; you just make sure they're registered in the
metastore your catalog points at.

- **Existing Delta/Iceberg tables in a lakehouse:** nothing to seed — repoint
  the connector (step 1) and go.
- **Files you want to register:** `CREATE TABLE … WITH (location='s3://…')` (Trino
  writes the `_delta_log`), or register externally-written Delta via Trino's
  `register_table` procedure.
- **Loading from elsewhere:** the seed shows the pattern — `CREATE TABLE … AS
  SELECT` from any Trino-reachable source (another catalog, a CSV via the `hive`
  connector, `tpch`/`tpcds` generators). Swap the `SELECT` for yours.

Then update the datasource so it names **your** catalog/schema and loads **your**
Mondrian file. The one line in `saiku/delta.sds` (setup substitutes the home
path):

```
jdbc:mondrian:Jdbc=jdbc:trino://localhost:8091/<your_catalog>/<your_schema>?user=saiku;Catalog=file:<home>/data/<YourSchema>.xml;JdbcDrivers=io.trino.jdbc.TrinoDriver
```

Restart Saiku; your cube appears in the picker. Nothing about the UI, Mondrian,
or Calcite changed — you swapped a connector, a schema, and a table.

## Teardown

```bash
docker compose down -v      # removes containers + the MinIO volume + metastore
```

## Troubleshooting

- **Cube missing from the picker** — check `<home>/logs/saiku.log` for the
  datasource load; the usual suspects are a wrong absolute path in
  `delta.sds` or the Trino / Hive Metastore containers not yet healthy.
- **`No suitable driver`** — the trino-jdbc jar isn't in `<home>/plugins/`
  or the boot line above didn't print; re-run setup and restart.
- **Seed fails `Failed to create external path s3://...`** — the Hive
  Metastore couldn't reach MinIO. Give it ~60s on first boot, then re-run
  `./seed.sh`; `docker logs delta-hive-metastore` for anything persistent.
- **Seed fails `Failed to write Delta Lake transaction log entry`** — a
  transient MinIO hiccup; the catalog already disables S3 conditional writes
  for MinIO. Re-run `./seed.sh` (it's idempotent).
- **Trino unhealthy** — give it ~60s on first boot; `docker logs
  delta-trino` for anything persistent.
- **Dialect weirdness on exotic queries** — force the legacy SQL generator
  with `-Dmondrian.backend=legacy` and please open an issue with the MDX.
