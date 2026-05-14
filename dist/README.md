# Saiku — runnable distribution

Self-contained Saiku build. Java 21+ is the only prerequisite.

## What's in the zip

```
saiku-<version>.jar    Fat JAR: Picocli CLI + embedded Jetty + the saiku
                       webapp + Calcite-backend Mondrian + a seeded H2
                       FoodMart dataset.
run.sh                 Convenience wrapper (macOS / Linux).
run.bat                Convenience wrapper (Windows).
README.md              This file.
```

The H2 FoodMart SQL fixture (~50 MB) lives **inside the JAR** as a seed
resource. On first launch the saiku-webapp's `Database.loadFoodmart` task
runs the script and writes a ~200 MB `foodmart.mv.db` into
`saiku-home/data/`. No manual DB setup is required.

## Quick start

```bash
unzip saiku-dist-<version>.zip
cd saiku-dist-<version>
./run.sh                       # or: run.bat on Windows
```

Open <http://localhost:8080/ui/> and sign in:

| Field    | Value |
|----------|-------|
| Username | `admin` |
| Password | `admin` |

A `foodmart` cube list appears on first query (initial load of the H2
fixture takes ~30 s — subsequent launches reuse the file).

## CLI options

```bash
java -jar saiku-<version>.jar serve --help
```

| Flag | Default | Notes |
|------|---------|-------|
| `-p`, `--port` | `8080` | HTTP port. |
| `-h`, `--host` | `0.0.0.0` | Bind host. |
| `--context` | `/` | Context path. |
| `--home` | `./saiku-home` | Saiku home (data, repository, logs, sessions). |

## Saiku home layout (auto-created on first run)

```
saiku-home/
  data/            H2 FoodMart DB + the FoodMart4.xml Mondrian schema.
  repository/      JCR-style content repo (queries, schemas, datasources).
    data/unknown/datasources/foodmart.sds   Seeded H2 datasource.
  sessions/        Jetty FileSessionDataStore (session timeout 7 days).
  logs/            Saiku application log.
  plugins/         Drop-in extension JARs.
  branding/        Brand customisation samples.
```

## Calcite vs legacy Mondrian backend

The bundled Mondrian fork (`pentaho:mondrian:4.8.1.2`) ships a Calcite-based
SQL planner alongside the original SqlQuery builder. Calcite is the
**default** backend. To force the legacy backend:

```bash
java -Dmondrian.backend=legacy -jar saiku-<version>.jar serve
```

CalciteDialectMap recognises HSQLDB and PostgreSQL natively. For other
databases (MySQL, Oracle, MS SQL Server, BigQuery, Snowflake, Redshift,
ClickHouse, DB2, Vertica, Hive, Spark, Derby, H2, DuckDB, …) it falls
through to Calcite's `SqlDialectFactoryImpl`. Unsupported products fall
back to legacy with a one-shot WARN line in the log.

## Adding a PostgreSQL datasource

1. Load the FoodMart Postgres fixture into your local Postgres (see
   `mondrian-saiku` for the SQL).
2. Drop a new `.sds` file into
   `saiku-home/repository/data/unknown/datasources/`, for example:

```xml
<dataSource>
  <driver>mondrian.olap4j.MondrianOlap4jDriver</driver>
  <id>00000000-0000-0000-0000-000000000002</id>
  <location>jdbc:mondrian:Jdbc=jdbc:postgresql://localhost:5432/foodmart;Catalog=file:.../FoodMart-pg.xml;JdbcDrivers=org.postgresql.Driver</location>
  <name>foodmart-postgres</name>
  <password>YOUR_PG_PASSWORD</password>
  <securityenabled>false</securityenabled>
  <type>OLAP</type>
  <username>YOUR_PG_USER</username>
</dataSource>
```

3. Restart saiku — the new datasource appears in the cube selector.

## Stopping

`Ctrl+C` in the launcher's terminal. Saiku flushes the FileSessionDataStore
on shutdown so a logged-in browser stays signed in across restarts (subject
to a 7-day idle timeout).

## Project links

- Saiku UI + REST: <https://github.com/spiculedata/saiku>
- Mondrian (Spicule fork, Calcite backend): <https://github.com/spiculedata/mondrian-saiku>
- olap4j (Spicule fork): <https://github.com/spiculedata/olap4j>
- olap4j-xmlaserver: <https://github.com/spiculedata/olap4j-xmlaserver>
- saiku-query: <https://github.com/spiculedata/saiku-query>
