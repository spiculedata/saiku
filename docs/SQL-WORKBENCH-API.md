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
connection is even opened: it must be a single statement (no `;`-stacking)
whose first keyword is `SELECT`, `WITH`, `SHOW`, `EXPLAIN`, or `DESCRIBE`/
`DESC`. This is a lexical allowlist, not a SQL parser, so it's one layer of a
few:

1. `ReadOnlySqlGuard` rejects the statement before any driver is touched.
2. The connection is opened with `Connection.setReadOnly(true)` (best-effort —
   not every JDBC driver honours it).
3. The statement runs via `Statement.executeQuery()`, which most JDBC
   drivers already refuse for anything that isn't a query.

A rejected statement returns `400` with `{code: "READ_ONLY_VIOLATION",
message}` and never reaches a connection.

**Phase 3 of the issue** (an admin-configurable per-datasource read/write
toggle) is not implemented yet — every datasource is read-only today.

## Error shape

A failed request returns `400` with `{code, message}`:

| `code` | Meaning |
| --- | --- |
| `INVALID_REQUEST` | Missing `sql` or `datasource` in the body. |
| `READ_ONLY_VIOLATION` | The statement failed `ReadOnlySqlGuard`. |
| `UNKNOWN_DATASOURCE` | No datasource matches the given name/id. |
| `QUERY_FAILED` | The statement reached the driver and failed — `message` is the driver's own error text (bounded to 500 chars), which is deliberately **not** scrubbed the way other resources scrub SQL/exception detail from responses (see `add-rest-endpoint.prompt.md`): a query tool that hides why a query failed isn't usable, and the caller already holds an elevated grant equivalent to direct warehouse access. It never carries a Java stack trace or class name. |

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
