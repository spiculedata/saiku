# Connecting Saiku to Google Sheets

The Saiku add-on for Google Sheets lets a spreadsheet query the Saiku
semantic layer directly: pick a cube, drop measures on a shelf, choose a row
axis, and write the result into the sheet — no JDBC driver, no CSV export, no
copy-paste. Refresh re-runs the query and rewrites the same block in place, so
your formatting survives.

It is the Sheets counterpart to the Excel Office.js add-in, and both talk to
the same typed REST surface described in
[`AI-QUERY-API.md`](./AI-QUERY-API.md): `GET /ai/cubes`,
`GET /ai/schema/{cubeId}`, `POST /ai/query`. Nothing in the add-on writes MDX,
so measure renames, synonyms and semantic annotations keep working without a
UI change.

> The add-on source lives in the Saiku repository at
> [`integrations/google-sheets/`](../integrations/google-sheets/) (spiculedata/saiku#1436).

## 1. Install the add-on

From the spreadsheet: **Extensions → Apps Script**, then paste/import the
add-on project from `integrations/google-sheets/`, or install the published
listing once it is available from the Google Workspace Marketplace.

Run `onInstall` once from the Apps Script editor (Apps Script shows a
confirmation prompt) and return to the sheet — a **Saiku** menu appears in the
toolbar with *Open Saiku sidebar*.

## 2. Connect to your Saiku server

Open the sidebar (**Saiku → Open Saiku sidebar**) and fill in:

| Field | Value |
| --- | --- |
| Server URL | The Saiku origin, e.g. `https://saiku.example.com`. A bare host is fine (`saiku.example.com` → `https://`). A context path is preserved (`https://host.example/bi`) for reverse-proxy deployments. |
| Username / Password | A Saiku user with access to the cubes you want. A read-only service account is ideal. |

Press **Connect**. The add-on calls `GET /rest/saiku/api/ai/cubes` and lists
every cube that user can see.

- **401 / 403** → the credentials are wrong or the user lacks cube access.
- **"Could not reach the server"** → self-hosted Saiku must be reachable from
  Google's Apps Script infrastructure (usually: public HTTPS, or an allowlist
  entry for Google's egress ranges).

Credentials are sent as an HTTP Basic `Authorization` header — the same
stateless auth the [MCP server](./MCP-SERVER-SPEC.md) and the Excel add-in use.
Saiku exempts requests carrying an `Authorization` header from CSRF, so no
XSRF token round-trip is needed.

## 3. Build the query

Choosing a cube loads its typed schema, which populates the two shelves:

- **Measures** — every measure in the cube. Annotated measures show their unit
  (`USD`, `%`, …); hovering shows the description. The cube's own default
  measure is pre-selected so **Insert as table** works immediately.
- **Rows** — the cube's dimension → hierarchy → level tree, flattened to
  `Dimension / Hierarchy / Level` (e.g. `Time / Time By / Quarter`). Pick
  *No row axis* for a single total row.
- **Row limit** — caps the result. Combined with an order it becomes a top-N;
  with no order it is a plain `HEAD`. `0` means no limit.
- **Values** — *Numbers* writes the raw values, so the block charts and sums
  like any other range. *Formatted text* writes Mondrian's display strings.

Press **Insert as table**. The result is written at the active cell: a header
row (row-axis caption first, then one column per measure) followed by one row
per record.

## 4. Refresh

Press **Refresh** to re-run the query behind the block and rewrite it in place.
Values change; number formats, fonts, colours and column widths you applied
stay. If the new result is narrower or shorter than the old one, the leftover
rows or columns are cleared, so a refresh never leaves stale data behind.

A block remembers the exact query that produced it, so you can point the
sidebar at a different cube afterwards and **Refresh** still re-runs the one
you inserted.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| `Unknown level` / `VALIDATION_ERROR` | The cube schema changed since the shelf was loaded. Re-pick the cube in the sidebar. The message names the offending field and lists valid candidates. |
| A cell shows `—` | Small-cell suppression. When `ai.kAnonymity` is set (default `5`), rows whose in-result count measure is below *k* have their measures masked. This is the privacy control doing its job — see the suppression section of [`AI-QUERY-API.md`](./AI-QUERY-API.md). |
| A column shows `(unavailable)` | The requested measure has no join path to the filtered/sliced dimension (saiku#1780). |
| "Nothing to refresh yet" | No block has been inserted by this user in this spreadsheet. |
| Refresh rewrites the wrong block | Blocks are tracked per user and per anchor cell. Insert again at the intended cell. |

## Security notes

- The add-on stores the connection config and last query in **user** properties
  (`PropertiesService.getUserProperties()`), not script properties: a shared
  spreadsheet must not let one user's refresh re-run or overwrite another
  user's block.
- Prefer a dedicated read-only service account over your personal login.
- Small-cell suppression is honoured end-to-end — the add-on writes the mask,
  never the underlying number, so a masked figure cannot be recovered from the
  sheet.
- The add-on makes no third-party calls: every request goes to the Saiku server
  URL you type.
