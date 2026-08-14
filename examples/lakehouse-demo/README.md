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

## Step 2 — seed real Iceberg tables

```bash
./seed.sh        # Windows: ./seed.ps1
```

This runs `seed.sql` through the Trino CLI: it CTAS-es Trino's built-in
TPC-H generator into **`iceberg.sales.fact_orders`** — 15,000 orders,
denormalised with each customer's region/nation and the order date split
into year/month. Genuine Iceberg: Parquet data files land in MinIO, table
metadata in the REST catalog. Check it:

```bash
docker exec lakehouse-trino trino --execute \
  "SELECT region_name, count(*) FROM iceberg.sales.fact_orders GROUP BY 1"
```

## Step 3 — wire Saiku to it

```bash
./setup.sh       # Windows: ./setup.ps1
```

This prepares a dedicated `lakehouse-home/` for Saiku:

1. **Downloads the Trino JDBC driver into `<home>/plugins/`.** Saiku's
   launcher puts every jar in `plugins/` on the webapp classpath at boot —
   drop-in driver support, no rebuild. (Works for Redshift, ClickHouse,
   anything with a JDBC driver.)
2. **Stages the Mondrian schema** (`LakehouseSales.xml`) — one cube,
   `Lakehouse Sales`, over the fact table: a Market hierarchy
   (Region → Nation), a Time hierarchy (Year → Month), order Status /
   Priority attributes, and three measures (Total Price, Order Count,
   Avg Order Value).
3. **Stages the datasource** (`lakehouse.sds`). The connect string is the
   whole demo in one line:

   ```
   jdbc:mondrian:Jdbc=jdbc:trino://localhost:8090/iceberg/sales?user=saiku;Catalog=file:<home>/data/LakehouseSales.xml;JdbcDrivers=io.trino.jdbc.TrinoDriver
   ```

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
With the server up:

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
