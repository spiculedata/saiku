# 4.8.0 — DRAFT

> Draft. Version not bumped and nothing tagged — see *Before cutting* at the end.

Minor release. The Cube Designer can now take a cube from creation to a working
query without leaving the UI; query failures report proper HTTP status codes; and
OSS connection names lose the meaningless `unknown_` prefix.

Two of those are visible behaviour changes for API clients — see **Breaking**.

---

## Breaking

- **`POST /rest/saiku/api/query/execute` now returns 4xx/5xx on failure.**
  Every query failure previously came back as `200 OK` with the message in the
  envelope's `error` field, which is invisible to proxies, retry middleware,
  monitoring, and any client that treats 2xx as success.

  Failures are now **400** when the request named something that could not be
  resolved (unknown connection, cube, or measure — the caller can fix it) and
  **500** otherwise.

  **The response body is unchanged** — still a `QueryResult` carrying `error` —
  so a client that only reads `error` needs no change. A client that checks the
  status *before* reading the body will start seeing failures it was previously
  ignoring. That is the point, but it is a change.

  The same applies to the drillthrough path. The legacy
  `/rest/saiku/{username}/query` surface is unchanged. (saiku#1854)

- **OSS connection names no longer carry the `unknown_` prefix.**
  `foodmart`, not `unknown_foodmart`. The prefix was a multi-tenant artefact:
  with workspaces off — the only mode OSS runs in — the workspace directory is
  always the default `unknown`, so it carried no information, yet appeared in the
  connection list, every URL, every MDX unique name
  (`[unknown_foodmart].[FoodMart].[FoodMart].[Sales]`) and everything an agent
  saw.

  **Existing saved content keeps working.** Saved queries, dashboards and apps
  bake the prefixed name into both their connection field and their cube unique
  names; connection lookup and AI cube-reference matching now accept either
  spelling, so no migration is needed and nothing has to be rewritten.

  What *will* see the new name is anything doing its own string comparison on
  discover output — an external script pinning `unknown_foodmart` in its own
  database, for example. Restore the old behaviour with
  `-Dsaiku.datasources.workspacePrefix=true`. (saiku#1858, saiku#1860)

## Added

- **Cube Designer — query preview.** "Try a query" now runs against the schema
  you are editing, before it is saved. The proposed XML is held in memory and the
  connection reuses the datasource's own JDBC settings, so the preview hits the
  real warehouse; nothing is written to disk and the request cannot supply a JDBC
  URL. Admin-only. Previously this returned a 501 in OSS. (saiku#1861)
- **Per-user preferences.** `GET`/`PUT /rest/saiku/api/preferences` — a small
  account-level key/value store, keyed on the authenticated caller. The first
  consumer is the onboarding tour, which now stays dismissed per *person* rather
  than per browser. (saiku#1857)

## Fixed

### Cube Designer — creation to publish

- **Save now attaches the schema to the datasource** and refreshes the
  connection, so a designed cube is immediately queryable. Previously the XML was
  written to the repository and nothing pointed at it, and finishing the job
  meant knowing to paste `/datasources/<name>.xml` into the datasource by hand.
  (saiku#1853)
- **Editing a saved schema loads the real cube.** The workbench reads its cube
  model once on mount while the schema loads asynchronously, so opening a saved
  schema showed a blank "Cube 1". With save-and-attach wired, the next save would
  have published that blank over the real schema. (saiku#1853)
- **"Open in Saiku" works.** It was unreachable (the host never reported the
  schema as clean, so the control never enabled) and pointed at a Saiku Cloud
  route that does not exist in OSS. (saiku#1853)
- **Schemas are no longer called `Untitled`.** The schema name is now a visible,
  editable field, and it is what becomes the catalog name. (saiku#1853)
- **"Try a query" no longer demands a fact table you already picked.** Readiness
  only consulted the cube-level picker, not the per-measure-group binding the
  Mondrian 4 flow actually uses. (saiku#1853)
- **The cube designer is no longer offered for Ossie datasources**, which have
  nothing for it to edit. (saiku#1841)

### Datasources

- **Editing a datasource no longer creates a duplicate.** Names were decorated
  with the workspace on load but stored verbatim on save, so a read-modify-write
  wrote a *second* datasource under a doubly-prefixed name — same id, two names,
  two schemas — while the original kept serving the old catalog. Affected the
  Admin › Datasources edit form and the Cube Designer's save. (saiku#1854)
- **Admin › Datasources "Refresh" now refreshes.** It sent `PUT` to a `GET`-only
  endpoint (405 every time) and addressed the datasource by id where the server
  expects the connection name. (saiku#1854)
- **Edit datasource opens again.** The modal threw `props_invalid_value` and died
  silently, leaving only a console error; "+ Add datasource" was unaffected, which
  is why it went unnoticed. (saiku#1852)
- **An unknown connection reports what is wrong.** It surfaced as
  `Cannot invoke "OlapConnection.setCatalog(String)" because "con" is null`;
  it now names the connection and lists the ones that exist. (saiku#1853)

### Correctness and security

- **Drillthrough PII gate.** A column denied by `saiku.semantic.pii` could be
  retrieved by spelling it as a qualified MDX identifier. (saiku#1844)
- **Query cache keys no longer mutate the caller's query.** Computing a key
  reordered the user's member selection, changing the MDX that was emitted.
  (saiku#1844)
- **Schema file access is confined** to the Saiku data directories, so a
  datasource cannot be pointed at an arbitrary host file. Schemas kept outside
  saiku home need `-Dsaiku.schema.allowedRoots=<path>[,<path>]`. (saiku#1844)
- **`Catalog=mondrian://` resolves.** No admin-created cube could load, because
  that scheme had no handler in the OSS build. (saiku#1844)
- Three long-documented sorting/parsing hazards closed. (saiku#1844)

### Other

- **Telemetry no longer counts local development builds.** The existing guard
  only skipped IDE runs; a launcher built with `mvn package` reported as a real
  install, under a stable id. (saiku#1855)
- **App Builder — subtotal rows are visible.** The marking was mixed from a
  surface token that equals the card colour on a light preset, so it rendered
  white-on-white while still passing its test. (saiku#1826)

## Changed

- **Mondrian fork → 4.8.1.34.** Calcite planner-cache key redaction (JDBC URL
  secrets are no longer printed), a cardinality-probe fallback to legacy SQL on a
  qualified schema, and the BigQuery / Simba JDBC unblock. (saiku#1856)
- **Property-based test coverage 40 → 235** across 27 classes, including the two
  modules that previously had none. Six of the bugs above were found this way.

---

## Known issues

- **Every restart logs every user out.** Authenticated sessions are not persisted
  even though session persistence is configured and works for anonymous ones.
  Not a regression in this release, but a release is exactly when users meet it.
  Evidence and a concrete next step are in **issue saiku#1859**.

## Before cutting

1. Version is still `4.7.1` in the root pom — bump to `4.8.0`.
2. Decide whether the logout issue blocks.
3. This draft covers the work reviewed in detail; **191 non-merge commits** have
   landed since `v4.7.1`, so skim the remainder for anything user-facing that
   deserves a line here.
4. Delete this file once the note is transferred to the GitHub release.
