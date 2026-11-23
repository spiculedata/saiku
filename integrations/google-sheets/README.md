# Saiku for Google Sheets

A first-party Google Sheets add-on that queries the
[Saiku semantic layer](../../docs/AI-QUERY-API.md) and writes the result into
the sheet — the Sheets counterpart to the Excel Office.js add-in
(spiculedata/saiku#1436).

| File | What it is |
| --- | --- |
| `appsscript.json` | Add-on manifest (V8 runtime, Sheets + container-UI OAuth scopes) |
| `Code.js` | Menu / sidebar entry points and the Apps Script glue |
| `SaikuClient.js` | URL + auth shaping, transport injection, error mapping |
| `SaikuQueryModel.js` | Pure shelf → `AiQueryRequest` and response → cell-grid logic |
| `sidebar.html` | The sidebar UI (plain HTML/CSS/JS, no build step) |
| `test/addon.test.js` | `node --test` unit tests over the pure logic |
| `.clasp.json.example` | Copy to `.clasp.json` and fill in your script id |

Everything with a test in it is deliberately free of `SpreadsheetApp` /
`UrlFetchApp` references, so the whole request-shaping and write-planning layer
loads into Node unchanged. The seam between pure and impure is a single
transport hook (`SaikuClient.setTransport`).

## What it does

1. **Connect** — server URL + Saiku username/password. Credentials travel as an
   HTTP Basic `Authorization` header (the same stateless auth the MCP server
   and the Excel add-in use). `Authorization`-bearing requests are exempt from
   Saiku's CSRF filter, so no XSRF round-trip is needed.
2. **Pick a cube** — `GET /rest/saiku/api/ai/cubes`.
3. **Pick measures and a row axis** — `GET /rest/saiku/api/ai/schema/{cubeId}`
   returns the cube's measures (with units and synonyms) and its
   dimension → hierarchy → level tree; both are flattened into shelves.
4. **Insert as table** — `POST /rest/saiku/api/ai/query`, written into the
   active sheet at the active cell.
5. **Refresh** — re-runs the stored query and rewrites the same block in place,
   so number formats, fonts and colours you applied survive. If the new result
   is smaller, the overhang is cleared so no stale rows survive.

Values are written as **numbers** by default (so Sheets can chart and sum them
directly); switch the sidebar's *Values* selector to Mondrian's pre-formatted
strings if you prefer display text.

## Install (developer / internal)

The add-on ships as an Apps Script project driven by
[`clasp`](https://github.com/google/clasp):

```bash
cd integrations/google-sheets
npm install -g @google/clasp
clasp login
# One-off: create the standalone script project and note the script id.
clasp create --type standalone --title "Saiku for Google Sheets"
# → writes .clasp.json with your scriptId

clasp push
```

To try it in a spreadsheet without publishing, create the project as a **Sheets
add-on** (`clasp create --type sheets addon`) and load it via
*Extensions → Apps Script*, then run `onInstall` once. Publishing to the
Workspace Marketplace needs OAuth verification and is a maintainer/marketing
step — see *Not in this repo* below.

## Tests

```bash
node --test integrations/google-sheets/test/*.test.js
```

CI runs the same command in the path-filtered `sheets` job.

## Data handling

* The add-on stores the connection config and the last query in **user**
  properties (`PropertiesService.getUserProperties()`), never script
  properties — a shared spreadsheet must not let one user's refresh re-run or
  rewrite another user's block.
* k-anonymity small-cell suppression is honoured: a `suppressed` cell is
  written as its mask (`—`), never as the underlying number.
* Self-hosted Saiku must allow Google's Apps Script egress range and be
  reachable over HTTPS from Google's infrastructure. A 401 means the
  credentials are wrong; a 0/unreachable error means the network path is
  closed — the sidebar says which.

## Not in this repo

The issue proposes extracting this into a standalone `spiculedata/saiku-sheets`
repository. Until that exists, the source lives here so it is versioned,
reviewed and tested alongside the API it consumes. Moving it later is a
file-move, not a rewrite. The Workspace Marketplace listing, the shareable
install link and the marketing screenshots are publishing/marketing steps that
need the Google account that owns the OAuth consent screen — they are tracked
on the issue rather than faked here.
