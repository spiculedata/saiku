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
SAIKU_DEMO=true ./run.sh        # first look: demo cube + admin/admin  (run.bat on Windows)
```

Open <http://localhost:8080/ui/> and sign in with `admin` / `admin`.

> Demo mode is only for a first look. For anything real, skip `SAIKU_DEMO=true`
> and set a password instead — `SAIKU_ADMIN_PASSWORD='a-strong-password' ./run.sh`
> — because Saiku **refuses to start** on the default `admin`/`admin` (see
> "Setting the admin password" below).

A `foodmart` cube list appears on first query (initial load of the H2
fixture takes ~30 s — subsequent launches reuse the file).

## Setting the admin password

Saiku ships with `admin` / `admin` and **refuses to start** once it's reachable
on a network while that default is unchanged (demo mode excepted). Set a real
password — **no rebuild required**:

```bash
# Docker
docker run -d -p 8080:8080 -e SAIKU_ADMIN_PASSWORD='a-strong-password' ghcr.io/spiculedata/saiku:<version>

# dist zip / fat-jar
SAIKU_ADMIN_PASSWORD='a-strong-password' ./run.sh
```

On boot Saiku bcrypt-hashes it and writes `<saiku-home>/users.properties`,
persisted on the volume. Precedence is **`SAIKU_ADMIN_PASSWORD` > an existing
`<saiku-home>/users.properties` > the WAR's baked default**, so while the
variable is set it is enforced on *every* boot: it rewrites the `admin` row and
overrides whatever that file already contains.

That makes the variable the rotation mechanism — change its value and restart,
and the old password stops working. It also means that while the variable stays
set, editing the `admin` row by hand is reverted on the next restart. Unset it
first if you want to manage that row yourself; the stored hash keeps working
without it.

To add more users or manage roles by hand, edit that file directly — rows other
than `admin` survive a rewrite of the `admin` row:

```properties
admin={bcrypt}$2y$12$....,ROLE_USER,ROLE_ADMIN
analyst={bcrypt}$2y$12$....,ROLE_USER
```

Generate a hash with `htpasswd -nbBC 12 <user> '<password>'` and reformat the
`user:$2y$...` output as `user={bcrypt}$2y$...,ROLE_USER`.

> **⚠️ The admin panel can't manage credentials.** The bundled auth reads
> `users.properties` (above), but the panel's user-management screens write to a
> separate store that auth never consults. **Changing a password there is refused
> (HTTP 501)**, with a message pointing back here — rotate with
> `SAIKU_ADMIN_PASSWORD` or by editing `users.properties` directly. Adding a user
> still succeeds, but the account is only a directory entry (it's what @-mentions
> in dashboard comments autocomplete against): it can't sign in until you add it
> to `users.properties` too. Tracked in
> [#1514](https://github.com/spiculedata/saiku/issues/1514).
>
> For real multi-user setups, point Saiku at LDAP / OAuth / SAML via
> `applicationContext-spring-security-memory.xml`.
> To boot with the default password anyway (local/dev only), set
> `SAIKU_ALLOW_DEFAULT_ADMIN=true`.

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

## Subcommands

The launcher is a Picocli multi-command. `serve` runs the web server; the
others are operational tools that talk to a *running* server.

```bash
java -jar saiku-<version>.jar <command> --help
```

| Command | Purpose |
|---------|---------|
| `serve` | Start the Saiku web server (see CLI options above). |
| `sql-serve` | Serve an Ossie/SQL semantic model without the full OLAP stack. |
| `eval` | Run the agent-eval accuracy suites against a running server and report pass-rate. Exit `0` = all passed, `1` = a suite regressed, `2` = transport/config error. See `docs/EVAL-SPEC.md`. |

`eval` is the CI/cron entry point for the AI accuracy monitor. It POSTs to
`/rest/saiku/admin/ai-evals/run` (admin Basic auth) and blocks until the sweep
finishes:

```bash
# against a locally-running server with default admin/admin
java -jar saiku-<version>.jar eval

# against a remote server, report-only (never non-zero exit)
java -jar saiku-<version>.jar eval \
  --server https://analytics.example.com \
  --username admin --password '<secret>' \
  --no-fail-on-regression
```

| Flag | Default | Notes |
|------|---------|-------|
| `-s`, `--server` | `http://localhost:8080` | Base URL of the running server. |
| `-u`, `--username` | `admin` | Admin username. |
| `-p`, `--password` | `admin` | Admin password. |
| `--no-fail-on-regression` | _(off)_ | Exit `0` even when a suite has failures (report-only). |
| `--timeout-minutes` | `15` | How long to wait for the sweep (LLM latency × cases). |

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

## OpenTelemetry (optional)

The `run.sh` / `run.bat` wrappers will attach the OpenTelemetry Java
agent automatically when **both** conditions are true:

1. `opentelemetry-javaagent.jar` is present next to the wrapper script.
2. `OTEL_EXPORTER_OTLP_ENDPOINT` is set in the environment.

Download the agent (this distribution doesn't ship it to avoid
inflating the zip with a ~22 MB jar that most users won't use):

```bash
curl -fsSLO https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/2.28.1/opentelemetry-javaagent-2.28.1.jar
mv opentelemetry-javaagent-2.28.1.jar opentelemetry-javaagent.jar
```

Then point it at your collector:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
./run.sh
```

When `OTEL_EXPORTER_OTLP_ENDPOINT` is unset, the agent is never loaded
— zero overhead, zero observability. See
[docs/observability.md](../docs/observability.md) for the full
configuration reference, what gets auto-instrumented, and sampling
guidance.

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
