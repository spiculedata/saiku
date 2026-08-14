# Saiku on the lakehouse: MDX over Trino + Iceberg in ~10 minutes

Drag-and-drop OLAP pivots — real MDX, real cubes — over **Apache Iceberg**
tables, with **Trino** doing the SQL and **MinIO** holding the Parquet files.
No warehouse, no ETL, no new query language:

```
Saiku UI  →  Mondrian (MDX → SQL)  →  Calcite (TrinoSqlDialect)  →  Trino JDBC  →  Iceberg REST catalog  →  Parquet on MinIO
```

Every piece is open source, and the whole thing runs on your laptop. Saiku's
Calcite-based Mondrian backend already ships the Trino dialect, so there's
nothing to build — this walkthrough is pure assembly.

> **⚠️ Local only — no authentication.** This demo is deliberately wide open so
> it runs on a laptop with zero setup: the MinIO `warehouse` bucket is made
> **anonymously public**, the MinIO root and S3 access keys are the hardcoded
> `admin` / `password`, and the Trino coordinator + web UI (host `:8090`) run
> with **no password**. Treat every port here as untrusted. **Never bind these
> ports to a public interface, a shared host, or a corporate network, and never
> reuse these credentials.** Run it only on `localhost`, and tear the stack down
> (`docker compose down -v`) when you're done. It is a walkthrough, not a
> template for any deployment.

## What you need

- Docker (Compose v2)
- Java 21+ and a Saiku launcher JAR (or the Saiku Docker image for the
  UI-on-docker variant)
- ~5 GB free disk for images

## Step 1 — bring up the lakehouse

```bash
cd examples/lakehouse-demo
docker compose up -d
```

Three containers:

| Container | Role | Host port |
|---|---|---|
| `lakehouse-minio` | S3-compatible object store (the "lake") | 9000 API / 9001 console |
| `lakehouse-iceberg-rest` | Iceberg REST catalog (table metadata) | 8181 |
| `lakehouse-trino` | Query engine | **8090** (Saiku keeps 8080) |

**Why these three?** A lakehouse is three separable jobs: object storage holds
the Parquet files (**MinIO** here; S3/GCS/ADLS in the real world), a **catalog**
records which files make up which table and version (Iceberg's **REST catalog** —
lightweight, no database needed), and a **query engine** turns SQL into scans
over those files (**Trino**). Saiku talks only to Trino; Trino talks to the
catalog + storage. That separation is exactly why swapping the lakehouse later
is a config change, not a rebuild.

## Step 2 — seed real Iceberg tables

```bash
./seed.sh        # Windows: ./seed.ps1
```

This runs `seed.sql` through the Trino CLI: it CTAS-es (`CREATE TABLE … AS
SELECT`) Trino's built-in **TPC-H generator** — a synthetic sales dataset every
Trino ships — into **`iceberg.sales.fact_orders`**: 15,000 orders, denormalised
so each row already carries the customer's region/nation and the order date
split into `order_year` / `order_month`. Because the SELECT runs *through* the
`iceberg` connector, the result is a genuine Iceberg table — real Parquet data
files land in MinIO and the table metadata is registered in the REST catalog, so
this is a true lakehouse write, not a shortcut. **Why one flat table?** Keeping
the demo denormalised means the Mondrian schema stays a single `<Table>` with no
joins — easiest to read; your own schema can snowflake (see *Run it with your
own data*). **Why `CAST(… AS INTEGER)` on year/month?** So Mondrian's Time levels
get plain ints — an accidental float like `1992.0` breaks them. Check it:

```bash
docker exec lakehouse-trino trino --execute \
  "SELECT region_name, count(*) FROM iceberg.sales.fact_orders GROUP BY 1"
```

## Step 3 — wire Saiku to it

```bash
./setup.sh       # Windows: ./setup.ps1
```

A Saiku "home" is just a directory Saiku reads at boot for its plugins, schemas,
and datasource descriptors. `setup.sh` builds a dedicated `lakehouse-home/` so
this demo never touches your main install:

1. **Downloads the Trino JDBC driver into `<home>/plugins/`.** Saiku's launcher
   puts every jar in `plugins/` on the webapp classpath at boot — so teaching
   Saiku to talk to a new database is *dropping a JDBC jar in a folder*, no
   rebuild. (The same trick works for Redshift, ClickHouse, anything with a
   JDBC driver.)
2. **Stages the Mondrian schema** (`LakehouseSales.xml`) — the file that turns
   raw columns into the OLAP vocabulary the UI shows. One cube, `Lakehouse
   Sales`, over the fact table: a Market hierarchy (Region → Nation), a Time
   hierarchy (Year → Month), order Status / Priority attributes, and three
   measures (Total Price, Order Count, Avg Order Value). Swap this file to model
   your own dimensions/measures (see *Run it with your own data*).
3. **Stages the datasource** (`lakehouse.sds`). One line is the whole demo —
   read left to right it's the entire chain:

   ```
   jdbc:mondrian:Jdbc=jdbc:trino://localhost:8090/iceberg/sales?user=saiku;Catalog=file:<home>/data/LakehouseSales.xml;JdbcDrivers=io.trino.jdbc.TrinoDriver
   ```

   - `jdbc:mondrian:` — Saiku speaks MDX to Mondrian, the OLAP engine.
   - `Jdbc=jdbc:trino://localhost:8090/iceberg/sales` — Mondrian's generated
     SQL goes to **Trino**, catalog `iceberg`, schema `sales`. (`user=saiku`
     because Trino wants a user even with auth off.)
   - `Catalog=file:…/LakehouseSales.xml` — the Mondrian schema from step 2.
   - `JdbcDrivers=io.trino.jdbc.TrinoDriver` — the driver from step 1.

   Change `iceberg/sales` + the schema file, and you've repointed Saiku at a
   different warehouse — that's the extent of the "wiring".

Then start Saiku:

```bash
java -Dsaiku.allowDefaultAdmin=true -jar saiku-<version>.jar serve --port 8080 --home ./lakehouse-home
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
is turning it into Trino SQL, and Trino is scanning Parquet on MinIO.
See it yourself in the Trino console at <http://localhost:8090/ui/>
(any username, no password) — filter Query details for `fact_orders`:

```sql
SELECT "region_name", "nation_name", "order_status",
       SUM("total_price") AS "m0", COUNT("order_key") AS "m1"
FROM "fact_orders"
GROUP BY "region_name", "nation_name", "order_status"
```

Clean aggregate pushdown — the OLAP engine asks the lakehouse for exactly
the rollup it needs, nothing more.

## Bonus: the AI surface works too

Everything Saiku exposes over the AI Query API / MCP now speaks lakehouse.
With the server up (start it with `-Dai.policy=aggregated` or higher so the
API may return aggregated values — the default `schema-only` policy withholds
them):

```bash
curl -u admin:admin -H "Content-Type: application/json" \
  -X POST http://localhost:8080/rest/saiku/api/ai/query -d '{
    "cube": {"connectionName":"unknown_lakehouse","catalog":"Lakehouse",
             "schema":"Lakehouse","cubeName":"Lakehouse Sales"},
    "measures":[{"name":"Total Price"}],
    "rows":[{"dimension":"Market","hierarchy":"Markets","level":"Region"}]}'
```

Typed cells straight off Iceberg — which means Claude Desktop / any MCP
agent can query your lakehouse through the same 12 tools documented in
[`docs/mcp/`](../../docs/mcp/README.md).

## Run it with your own data

This demo points at a throwaway TPC-H star on a local MinIO. To analyse **your
own** warehouse instead, you change three things — the connector, the schema,
and the data — and nothing in Saiku/Mondrian/Calcite. Here's what each one is
and how to swap it.

### 1. Point Trino at your catalog (the connector)

Everything Trino can query, Saiku can pivot. `trino/catalog/iceberg.properties`
is the only file that knows *where* the data lives:

```properties
connector.name=iceberg
iceberg.catalog.type=rest
iceberg.rest-catalog.uri=http://iceberg-rest:8181   # your catalog
fs.native-s3.enabled=true
s3.endpoint=http://minio:9000                        # your object store
s3.aws-access-key=admin
s3.aws-secret-key=password
```

- **Already have an Iceberg lakehouse?** Repoint `iceberg.rest-catalog.uri` at
  your catalog (or switch `iceberg.catalog.type` to `hive`/`glue`/`nessie` and
  give it the matching URI) and the `s3.*` block at your bucket/endpoint (drop
  `s3.endpoint` for real AWS S3). Skip the seed — your tables are already
  registered.
- **Different engine, not Iceberg?** Drop in a different Trino catalog file:
  `delta.properties` (see the [Delta demo](../delta-lake-demo/) — Delta needs a
  Hive Metastore rather than a REST catalog), `postgresql.properties`,
  `mysql.properties`, `bigquery.properties`, `snowflake.properties`, … —
  [any Trino connector](https://trino.io/docs/current/connector.html). **This is
  the whole point of fronting your warehouse with Trino:** Saiku's Calcite
  `TrinoSqlDialect` never changes, so swapping the lakehouse is a connector file,
  not a code change. Update the catalog name in the datasource URL (below) to
  match.
- **Skip Trino, connect Saiku straight to a JDBC warehouse?** You can — point
  the datasource `Jdbc=` at Postgres/MySQL/Snowflake/etc. directly and drop the
  matching JDBC driver in `<home>/plugins/`. Trino only earns its keep when you
  want ONE SQL surface over many stores, or lakehouse formats (Iceberg/Delta)
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
column names, so it's engine-agnostic — the same XML works over Iceberg, Delta,
Postgres, or anything else Trino/JDBC exposes with those columns.

### 3. Bring your own data (instead of TPC-H)

The seed is only here to *create* demo tables — if your tables already exist,
**you don't seed at all**; you just make sure they're registered in the catalog
your connector points at.

- **Existing Iceberg tables in a catalog:** nothing to seed — repoint the
  connector (step 1) and go.
- **Files you want to register:** `CREATE TABLE … AS SELECT …` writes a fresh
  Iceberg table through Trino, or register externally-written data via Trino's
  `CALL iceberg.system.register_table(...)` procedure.
- **Loading from elsewhere:** the seed shows the pattern — `CREATE TABLE … AS
  SELECT` from any Trino-reachable source (another catalog, a CSV via the `hive`
  connector, the `tpch`/`tpcds` generators). Swap the `SELECT` for yours.

Then update the datasource so it names **your** catalog/schema and loads **your**
Mondrian file. The one line in `saiku/lakehouse.sds` (setup substitutes the home
path):

```
jdbc:mondrian:Jdbc=jdbc:trino://localhost:8090/<your_catalog>/<your_schema>?user=saiku;Catalog=file:<home>/data/<YourSchema>.xml;JdbcDrivers=io.trino.jdbc.TrinoDriver
```

Restart Saiku; your cube appears in the picker. Nothing about the UI, Mondrian,
or Calcite changed — you swapped a connector, a schema, and a table.

## Teardown

```bash
docker compose down -v      # removes containers + the MinIO volume
```

## Troubleshooting

- **Cube missing from the picker** — check `<home>/logs/saiku.log` for the
  datasource load; the usual suspects are a wrong absolute path in
  `lakehouse.sds` or the Trino container not yet healthy.
- **`No suitable driver`** — the trino-jdbc jar isn't in `<home>/plugins/`
  or the boot line above didn't print; re-run setup and restart.
- **Trino unhealthy** — give it ~60s on first boot; `docker logs
  lakehouse-trino` for anything persistent.
- **Dialect weirdness on exotic queries** — force the legacy SQL generator
  with `-Dmondrian.backend=legacy` and please open an issue with the MDX.
