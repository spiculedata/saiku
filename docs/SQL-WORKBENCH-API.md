# Saiku SQL Workbench — Usage Guide

`/rest/saiku/sql-workbench/*` (saiku#1107, phase 1) lets a user holding the
`ROLE_SQL_EXEC` role — admins get it too — run **read-only** SQL directly
against a datasource's underlying JDBC connection, bypassing Mondrian/MDX
entirely. It's the row-layer companion to the cube layer: data-quality
probes, ad-hoc rollups Mondrian can't express, and a "view raw" companion to
drillthrough.

All routes require an authenticated session carrying `ROLE_SQL_EXEC` or
`ROLE_ADMIN` (form login at `POST /rest/saiku/session`, same auth as the
regular UI). The UI lives at `/ui/sql-workbench`.

## Endpoints

| Endpoint | What it does |
| --- | --- |
| `GET /rest/saiku/sql-workbench/datasources` | List datasources the current user's roles can see — `[{name, type}]`. Never includes credentials. |
| `POST /rest/saiku/sql-workbench/query` | Run one statement: `{datasource, sql, maxRows?}` → `{columns, rows, rowCount, truncated, durationMs}`. |
| `GET /rest/saiku/admin/sql-workbench-audit` | **Admin-only.** Paginated read of the audit log: `?limit=&offset=&user=` → `{entries, total, limit, offset}`. |

`maxRows` defaults to 500 and is clamped to 5000. Hitting the cap sets
`truncated: true` — a heuristic ("we got exactly `maxRows` rows back"), not a
guarantee there's more, but good enough for a "showing the first N rows"
banner without a second `COUNT(*)` round-trip.

## Read-only enforcement

Every submitted statement passes through `ReadOnlySqlGuard` before a
connection is even opened. The guard is **parse-enforced** (saiku#2318) and
**parses exactly the text that is executed** (saiku#2522): the statement is
parsed with Apache Calcite from the raw submitted string — never a
comment-stripped copy, which could silently disagree with the engine about
where a comment ends — so the leading keyword can no longer lie, and neither
can the text around it:

- only a top-level pure query is accepted — `SELECT`, set operations,
  `VALUES`, `TABLE`, `DESCRIBE`, optionally wrapped in `WITH` or `ORDER BY`;
- `EXPLAIN` is rejected outright (on PostgreSQL, H2, DuckDB and MySQL,
  `EXPLAIN ANALYZE <dml>` *executes* the statement it explains);
- every node in the tree is checked for DML/DDL, so a data-modifying CTE
  (`WITH d AS (DELETE FROM t RETURNING 1) SELECT * FROM d`) is rejected;
- known host-file / network built-ins are rejected wherever they appear:
  H2 `FILE_WRITE`/`FILE_READ`/`CSVREAD`/`CSVWRITE`/`LINK_SCHEMA`, DuckDB
  `read_csv`/`read_json`/`read_text`/`read_xlsx`/`read_blob`/`query()`,
  MySQL `LOAD_FILE`;
- **JDBC escapes are rejected outright** — any `{` outside a quote or
  comment (`{fn FILE_READ(…)}`, `{ts …}`, `{oj …}`). A parsed `{fn …}` is not
  the operator name the deny-list matches, and the driver rewrites the escape
  before executing, so the validated statement and the executed one would
  differ; the workbench statement is also opened with
  `Statement.setEscapeProcessing(false)` (fail closed) as an engine-side
  second layer;
- **executable conditional comments are rejected** — MySQL `/*!…*/` and
  `/*NNNNN…*/`, MariaDB `/*M!…*/`: Calcite skips them as comments, the engine
  runs them as code. Optimizer hints (`/*+ … */`) remain allowed;
- MySQL backtick identifiers are treated as quoted text by the lexical scan,
  so a backtick-quoted identifier cannot hide a block-comment opener;
- statements no available grammar can read at all are rejected (fail closed),
  including MySQL `SELECT … INTO OUTFILE` and bare network-scan targets
  (`SELECT * FROM 'http://…'`). The two mainstream introspection forms with
  no parser grammar — `SHOW …` and `DESC <table>` — keep a **strict**
  allowance (saiku#2522): a single, comment-free, `;`-free, quote-balanced
  statement starting with the keyword. A `SHOW`/`DESC` statement carrying a
  comment or a second statement is rejected;
- statements may not be `;`-stacked — the parser demands EOF after the first
  statement (unchanged).

This is one layer of a few:

1. `ReadOnlySqlGuard` rejects the statement before any driver is touched.
2. The connection is opened with `Connection.setReadOnly(true)` (best-effort —
   not every JDBC driver honours it), and on backends that have a session
   read-only switch the guard applies it **fail-closed**: PostgreSQL
   (`SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY`) and MySQL/MariaDB
   (`SET SESSION TRANSACTION READ ONLY`). If the datasource refuses, the
   caller's statement is never executed and the request fails with
   `QUERY_FAILED`. Backends without such a switch (H2, DuckDB) are covered by
   the parse guard's function denylist above.
3. The statement runs via `Statement.executeQuery()`, which most JDBC
   drivers already refuse for anything that isn't a query.
4. The target datasource is re-checked against the caller's roles inside the
   service — the same filtered map the picker offers — so a datasource the
   caller is not entitled to is reported as unknown, not merely hidden from
   the list. `RepositoryDatasourceManager.getDatasources(roles)` honours
   `roles`: a configured admin sees everything; a non-admin loses only
   security-enabled datasources whose `lookup` mapping grants them no
   Mondrian role, or whose `security.type` is unrecognised (the same
   fail-closed rule connection creation applies).

A rejected statement returns `400` with `{code: "READ_ONLY_VIOLATION",
message}` and never reaches a connection.

**Dedicated read-only credentials — and the trust that remains in
`ROLE_SQL_EXEC`.** The strongest layer remains the warehouse's own, and it is
the one that makes the rest advisory: register the datasource's workbench
access through a principal that lacks write and `FILE` privileges, distinct
from the write principal used for ETL/Mondrian, and treat `ROLE_SQL_EXEC` as
a grant to run that principal's queries — **not** as a guarantee that every
statement a user submits is harmless. The deny-list of side-effecting
functions cannot describe every engine's primitives (PostgreSQL
`pg_read_file`/`lo_export`, DuckDB `read_csv_auto`/`glob`/`read_parquet`,
ClickHouse `url()`/`file()` are not on it), and the guard does not model
dialects Saiku does not ship a driver for. A `ROLE_SQL_EXEC` holder can read
everything the workbench principal can read and, on a permissive warehouse
account, attempt to reach host files and network — the guard narrows that
surface, it does not close it. Use a read-only database account.

**Phase 3 of the issue** (an admin-configurable per-datasource read/write
toggle) is not implemented yet — every datasource is read-only today. DuckDB
connection-level confinement (`enable_external_access=false` etc.) is tracked
on saiku#2320.

## Error shape

A failed request returns `400` with `{code, message}`:

| `code` | Meaning |
| --- | --- |
| `INVALID_REQUEST` | Missing `sql` or `datasource` in the body. |
| `READ_ONLY_VIOLATION` | The statement failed `ReadOnlySqlGuard`. |
| `UNKNOWN_DATASOURCE` | No datasource matches the given name/id. |
| `QUERY_FAILED` | The statement reached the driver and failed — `message` is the driver's own error text (bounded to 500 chars), which is deliberately **not** scrubbed the way other resources scrub SQL/exception detail from responses (see `add-rest-endpoint.prompt.md`): a query tool that hides why a query failed isn't usable, and the caller already holds an elevated grant equivalent to direct warehouse access. It never carries a Java stack trace or class name. The same code is used when a backend with a session read-only switch refuses that session: the caller's statement was **not** executed. |

## Audit trail

Every run — allowed, rejected, or errored — is appended to
`${saiku.home}/logs/sql-workbench-audit.jsonl` via `SqlWorkbenchAuditLog`,
including the SQL text itself (unlike the AI audit log, which deliberately
withholds prompt content — the whole point of this trail is "what SQL ran
against the warehouse"). Disable with `-Dsqlworkbench.audit.enabled=false`;
override the file with `-Dsqlworkbench.audit.file=...`.

## Not yet implemented (tracked on saiku#1107)

- **Phase 2 — cube-aware autocomplete**: suggest a cube's underlying fact /
  dimension table names in the Monaco editor when a known cube is in scope.
- **Phase 3 — per-datasource read/write toggle**: an admin-configurable
  escape hatch to allow writes on a specific datasource.
