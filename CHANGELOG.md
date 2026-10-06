# Changelog

All notable changes to Saiku are documented here. This project follows
[Semantic Versioning](https://semver.org/).

## Unreleased

### Added

- **`ai.provider=ollama` — local/self-hosted model support for the AI ask layer
  and schema-generation enrichment.** Both now accept `ollama` as a first-class
  provider value alongside `anthropic`/`openai`: it talks to a local Ollama
  instance's OpenAI-compatible endpoint (`http://localhost:11434/v1/chat/completions`
  by default) and needs **no API key**, unlike `openai`/`azure-openai` — Ollama
  doesn't check one. For PII-strict deployments this means the model never
  leaves the operator's own trust boundary; the only operator-visible
  difference from a hosted provider is the URL. Schema-generation enrichment
  (`saiku.schemagen.llm.provider`) also gained plain `openai` support as part
  of this, previously Anthropic-only. New `GET /saiku/info/diagnostics`
  reports each stack's configured provider/model/endpoint plus a live
  reachability probe — never the API key — so an operator can confirm the
  wiring without running a query. (saiku#904)

### Fixed

- **XMLA: Excel / MSOLAP could not connect at all after the #1905 auth gate.**
  `/xmla` was moved behind a dedicated stateless secured chain, which is the
  right call, but it inherited the SPA's `HttpStatusEntryPoint(401)` entry
  point — the one added in #878 so the browser would not pop a native auth
  dialog over the SPA's routine XHR 401s. That entry point emits **no**
  `WWW-Authenticate` header, and challenge-driven clients only ever send
  credentials *in response to* a challenge: Excel/MSOLAP over WinHTTP (the
  endpoint's `web.xml` mapping literally ships `Source=Excel`) sends an
  anonymous request, waits for `401 WWW-Authenticate: Basic`, then retries with
  credentials. With the header suppressed they never got past step one.
  Pre-emptive-Basic clients (olap4j with credentials in the connect string,
  `curl -u`, most Python/Java XMLA libraries) were unaffected, which is why the
  breakage was invisible to them. `/xmla/**` now uses its own
  `BasicAuthenticationEntryPoint` (`realm="Saiku XMLA"`) so challenge-driven
  clients can negotiate, while the SPA chain keeps its bare-401 entry point —
  the two chains pick per surface, because sharing one entry point would
  either resurrect the browser dialog or strip the challenge back off XMLA.
  Auth policy is unchanged: still `isFullyAuthenticated()`, still CSRF-off,
  still the shared per-IP login rate limiter, still stateless. Only the 401's
  headers differ. (saiku#1950)

### Security

- **Bare saved-query embeds scope guest slicer overrides to the saved
  query's own FILTER axis (CWE-863, presentation scope, saiku#1946).** A
  `kind=query` embed (`POST /saiku/api/embed/query/{path}`) has no filter
  panel and no filter tiles, so — unlike the dashboard / app tile paths fixed
  by saiku#1911 — nothing stopped a guest from re-pointing an arbitrary
  non-forced hierarchy at arbitrary members, or adding a deeper level beside an
  authored rows level, surfacing finer-grain rows than the author published
  (e.g. individual customer names under an authored country roll-up). Forced
  RLS filters were still enforced, so this was a presentation-scope
  over-exposure bounded by the owner's `runAs` scope, not an RLS bypass — but
  it bit hardest for public grants and pre-#1104 opaque tokens, which carry no
  forced filters at all. Guest overrides are now reduced by
  `SavedQueryFilterScope` to hierarchies the saved query already carries on its
  FILTER axis, at an authored level, with the client members intersected with
  the authored members. Anything else (unknown axis, different level, a
  non-`in` operator, an entirely out-of-scope selection) is dropped, so the
  query runs as authored rather than failing open; an MDX-mode, unreadable, or
  unparseable saved query authorises no overrides at all.
- **The SPA ships a default CSP and `frame-ancestors` (CWE-693 / CWE-1021,
  saiku#1917).** `SecurityHeadersFilter` emitted *no* framing headers unless
  `-Dsaiku.security.frameAncestors` was set, and a full CSP only under
  `-Dsaiku.security.csp` — so out of the box `/ui/**` (a `security="none"`
  Spring chain, which is why Spring's own `X-Frame-Options: DENY` never reached
  the SPA HTML) was frameable by any origin and script execution was
  unconstrained. Defaults are now:
  - `Content-Security-Policy: frame-ancestors 'self'` + `X-Frame-Options: SAMEORIGIN`
    on every response, closing the clickjacking path to delete / share-link
    creation / admin toggles.
  - `Content-Security-Policy-Report-Only` carrying the documented policy
    (`default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline';
    img-src 'self' data: blob: https:; font-src 'self' data:; worker-src 'self'
    blob:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors
    'self'`). Report-only, so nothing breaks while a deployment validates it in
    a browser; `script-src 'self'` is the directive that neutralises the
    inline-handler payloads from the XSS findings once enforced.
  - An operator-supplied `saiku.security.csp` that omits `frame-ancestors` now
    gets the directive appended, so a hand-written policy can't silently
    re-open the clickjacking hole.

  **Upgrade action — iframe embedders.** If you frame `/ui/?embed=1` from a
  foreign origin, widen the allow-list:
  `-Dsaiku.security.frameAncestors="'self' https://wiki.example.com"`.
  `X-Frame-Options` is then omitted (it cannot express a list) and the CSP
  `frame-ancestors` directive carries the list. `-Dsaiku.security.frameAncestors=off`
  restores the old, fully framable behaviour. The web-component embed
  (`docs/embed/quickstart.md`) is unaffected. To enforce the CSP instead of
  reporting it, set `-Dsaiku.security.csp` to the policy above; to silence the
  report-only header, set `-Dsaiku.security.cspReportOnly=off`.

- **Hive/Hadoop are no longer bundled; the Hive JDBC driver is an opt-in
  `plugins/` drop-in** (CWE-1104 / CWE-1395, saiku#1916). `hive-cli`,
  `hive-jdbc` and `hadoop-common` pulled ~150 Hadoop/Hive/YARN/ZooKeeper server
  jars into the WAR purely so `jdbc:hive2://` URLs would load — among them
  ZooKeeper 3.4.6, a full Jetty 9.4 stack, YARN ResourceManager and LLAP
  classes, a test jar, gson 2.2.4, avro 1.7.7, jackson-mapper-asl 1.9.13 and
  jsch 0.1.55. The WAR also carried `log4j-1.2.x` next to the `log4j-1.2-api`
  bridge (both define `org.apache.log4j`, so which class loaded depended on jar
  order) plus extra slf4j bindings; `log4j:log4j` is now excluded from
  `olap4j-xmlaserver` as well, which only needs `Logger` from the bridge. A
  `maven-enforcer` `bannedDependencies` rule in the root pom fails the build if
  any of it comes back.

  **Upgrade action — Hive / Spark Thrift users.** Copy the standalone Hive
  JDBC driver (`hive-jdbc-<version>-standalone.jar`) into
  `saiku-home/plugins/` and restart; see *Adding a Hive / Spark Thrift
  datasource* in `dist/README.md`. **SQL Server users**: the old
  `mssql-jdbc` 6.2.1 driver was only on the classpath as a side effect of the
  Hive tree, so drop a current `mssql-jdbc` jar into `plugins/` too.

- **Docker image now runs as a non-root user** (`saiku`, uid/gid `10001:10001`;
  CWE-250, saiku#1989). Previously the JVM ran as uid 0 with write access to the
  mounted `saiku-home` volume — which holds `conf/secret.key` (the AES key that
  decrypts every stored datasource password), `users.properties`, and the
  auto-loaded `plugins/` directory. This is the defense-in-depth layer under the
  authenticated-user RCE chain closed in 4.8.0. The base image is now
  digest-pinned, a `HEALTHCHECK` hits the anonymous `/rest/saiku/info` endpoint,
  and the JVM runs with `-XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=75`.

  **Upgrade action — any pre-existing `saiku-home` (named volume OR bind mount).**
  Docker only seeds image ownership into a *fresh, empty* volume. A `saiku-home`
  written by an older root container — whether a named/anonymous volume or a
  bind-mounted host directory — stays root-owned, and the non-root container can
  no longer read `conf/secret.key` or write the home. Re-own it to `10001:10001`
  **once** before starting the upgraded image:

  ```sh
  # Bind-mounted host directory:
  sudo chown -R 10001:10001 /path/to/your/saiku-home

  # Named Docker volume (run once, as root, using the image itself):
  docker run --rm -v saiku-home:/app/saiku-home --user 0 \
    --entrypoint chown ghcr.io/spiculedata/saiku:<version> \
    -R 10001:10001 /app/saiku-home
  ```

  For the demo box (`demo.saiku.bi`, bind mount `/opt/saiku/home`) this is
  `sudo chown -R 10001:10001 /opt/saiku/home`.

  Skipping this no longer fails silently: the entrypoint runs a pre-flight and
  **refuses to start with a `FATAL:` message** (naming the exact `chown`) if the
  home is unwritable or `secret.key` is unreadable — so you can't accidentally
  rotate the AES key and orphan stored datasource passwords. A genuinely fresh,
  empty home still boots normally.

  **Kubernetes:** PVCs don't inherit image ownership either — set
  `securityContext: { runAsUser: 10001, runAsGroup: 10001, fsGroup: 10001 }` on
  the pod so the mounted volume is group-owned by the runtime user.

## 4.8.0 — 2026-09-15

Minor release, and a **security release** — nine hardening fixes close an
authenticated-user RCE chain, an XXE in the XMLA endpoint, a row-level-security
bypass on the embed surface, three stored-XSS vectors in custom dashboard tiles,
and a set of path/ACL isolation holes in user home folders. **Anyone running
4.7.x or either 4.8.0 release candidate should upgrade.**

Beyond that: the Cube Designer can now take a cube from creation to a working
query without leaving the UI; query failures report proper HTTP status codes; and
OSS connection names lose the meaningless `unknown_` prefix.

Supersedes the `4.8.0-RC1` and `4.8.0-RC2` pre-releases, which were never
promoted — everything in them is included here.

Two changes are visible behaviour changes for API clients — see **Breaking**.

### Security

- **Authenticated-user datasource RCE chain closed.** A user able to create or
  edit a datasource could reach code execution through the JDBC URL. The
  `JdbcUrlPolicy` property scan split the URL on `;&?#` and inspected only the
  *first* `=` per token, so a denied key that was not first in its token was
  never seen — reachable via Connector/J host-list and address-group forms and
  Teradata's comma-separated parameters. Property scanning is now exhaustive,
  and two write-guard holes found in the same review are closed.
  (saiku#1902, saiku#1903, saiku#1904)
- **XMLA endpoint: XXE fixed and authentication enforced.** The XMLA SOAP parser
  accepted external entities, and `/xmla` sat outside the secured chain. It now
  has its own authenticated chain, carrying the same per-IP login rate limiter
  the main chain uses — previously an attacker locked out on `/rest` could move
  to `/xmla` and keep guessing. (saiku#1905)
- **Embed surface: row-level security could be bypassed.** Forced RLS and
  declared filter targets are now enforced on the embed read path, including the
  saved-query execution route. (saiku#1911)
- **Stored XSS in custom dashboard tiles (three vectors).** The `echarts-option`
  tile's safe-subset validator blocked function formatters but let a *string*
  `tooltip.formatter` through; ECharts' default HTML render mode inserted that
  template straight into `innerHTML`, so an app author could store markup
  carrying an event handler. Neutralised, along with two scheme-detection
  bypasses where an embedded control character (or a CSS numeric escape
  decoding to one) split a `javascript:` scheme so the anchored scheme regex
  missed it while a real browser's URL parser stripped it and executed.
  (saiku#1909, saiku#1937, saiku#1940, saiku#1942)
- **User home folders are properly isolated.** A username-case desync, a
  `moveFile` ACL bypass, and an incomplete username guard are fixed. The guard
  rejected too little: a trailing-dot username normalises to another user's name
  on Win32, so `createUser` could rewrite that user's `acl.json` owner — a home
  takeover plus owner lockout. Colons, a `home:` prefix, and blank input also
  slipped through. (saiku#1906, saiku#1907, saiku#1934)
- **Datasource names can no longer traverse paths.** (saiku#1906)
- **The CSV-datasource Calcite model JSON is now escaped and
  path-contained.** (saiku#1932) `getCSVJson` interpolated the datasource name
  and a `location`-derived path into a hand-built model string with no
  escaping — a `'` closed the quoted operand and the remainder was read as
  further model keys — and the path was concatenated onto the datadir with no
  containment check, so a `..` segment pointed the CSV read outside the repo
  root. Both values are JSON-escaped now, and the path is resolved through the
  same `resolveWithinDatadir` containment rule the rest of the repository write
  layer uses (a path that normalises outside the datadir is rejected). The
  branch is dormant in the shipped build — `JdbcUrlPolicy` (saiku#1902) denies
  the `calcite` scheme — but it is now safe at the source rather than by
  reliance on an upstream validator.

### Breaking

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

### Added

- **Cube Designer — query preview.** "Try a query" now runs against the schema
  you are editing, before it is saved. The proposed XML is held in memory and the
  connection reuses the datasource's own JDBC settings, so the preview hits the
  real warehouse; nothing is written to disk and the request cannot supply a JDBC
  URL. Admin-only. Previously this returned a 501 in OSS. (saiku#1861)
- **Per-user preferences.** `GET`/`PUT /rest/saiku/api/preferences` — a small
  account-level key/value store, keyed on the authenticated caller. The first
  consumer is the onboarding tour, which now stays dismissed per *person* rather
  than per browser. (saiku#1857)

### Fixed

#### Cube Designer — creation to publish

- **Save now attaches the schema to the datasource** and refreshes the
  connection, so a designed cube is immediately queryable. Previously the XML was
  written to the repository and nothing pointed at it, and finishing the job
  meant knowing to paste `/datasources/<name>.xml` into the datasource by hand.
  (saiku#1853)
- **Editing a saved schema loads the real cube.** The workbench read its cube
  model once on mount while the schema loaded asynchronously, so opening a saved
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

#### Datasources

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

#### Queries

- **Queries saved by Saiku 2.x load again.** Levels and members written by the
  2.x serialiser were not matched by the current deserialiser, so an imported
  query failed to open. (saiku#1885)
- **Null hierarchy slots no longer break query conversion or totals.**
  `QueryConverter` now resolves the hierarchy via metadata and skips
  filter-only/empty dimensions, and `AxisInfo` tolerates null hierarchy slots in
  axis metadata. Both surfaced as NPEs on otherwise valid queries.
  (saiku#1883, saiku#1884)

#### Correctness and security (pre-RC)

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

#### Other

- **Telemetry no longer counts local development builds.** The existing guard
  only skipped IDE runs; a launcher built with `mvn package` reported as a real
  install, under a stable id. (saiku#1855)
- **App Builder — subtotal rows are visible.** The marking was mixed from a
  surface token that equals the card colour on a light preset, so it rendered
  white-on-white while still passing its test. (saiku#1826)

### Changed

- **Mondrian fork → 4.8.1.34.** Calcite planner-cache key redaction (JDBC URL
  secrets are no longer printed), a cardinality-probe fallback to legacy SQL on a
  qualified schema, and the BigQuery / Simba JDBC unblock. (saiku#1856)
- **Property-based test coverage 40 → 235** across 27 classes, including the two
  modules that previously had none. Six of the bugs above were found this way.
- **Licensing and dead-code audit.** Removed 1,459 lines of third-party code
  carrying a proprietary "Unauthorized Duplication Prohibited" header from an
  Apache-2.0 tree, replacing the hand-rolled 3DES implementation with the JDK's
  own `javax.crypto` at an identical wire format, so stored datasource passwords
  still decrypt. Added a `NOTICE` file, stamped Apache headers on 226 source
  files with a CI gate to keep them, and removed ~1,500 lines of dead code and
  ~79MB of orphaned test fixtures. (saiku#1888, saiku#1889)
- **Dependency updates** across the Maven and npm trees — Jackson 2.22.2,
  Guava 33.6.0, commons-dbcp2 2.14.0, picocli 4.7.7, Spotless 3.9.0,
  OWASP dependency-check 13.0.0, plus the SvelteKit build and Storybook
  toolchains.

### Known issues

- **Every restart logs every user out.** Authenticated sessions are not persisted
  even though session persistence is configured and works for anonymous ones.
  Not a regression in this release, but a release is exactly when users meet it.
  Evidence and a concrete next step are in **saiku#1859**.

## 4.7.1 — 2026-08-02

Patch release — Cube Designer fixes, a UI theme refresh aligned with Saiku
Cloud, and a demo-mode lockdown.

### Fixed
- **Cube Designer — edit an existing cube.** Opening the designer on a datasource
  that already has a Mondrian schema now loads that schema onto the canvas
  instead of showing a blank one. A new `GET /rest/saiku/admin/cube-designer/
  schema/{dataSourceId}` endpoint resolves the attached catalog (external
  `file:`, classpath `res:`, or repository) to its XML. (saiku#1634)
- **Datasources admin — correct driver/schema/JDBC URL.** Mondrian datasources
  whose inner `Jdbc=` URL carries its own `;`-params (e.g. H2 `;MODE=MySQL`) were
  mis-parsed, showing the catalog file as the driver and a stray param as the
  schema. Parsing is now key-based and order/param tolerant. (saiku#1634)
- **Cube Designer — drag-to-canvas.** Dropping a table now lands it under the
  cursor after the canvas has been panned or zoomed (drop coordinates are mapped
  into flow space). (saiku#1634)
- **UI — flat buttons.** Raw `<button>`s no longer fall back to the browser
  default (a 2px outset border on a grey face); the base reset that Tailwind
  preflight would apply is now in place.

### Changed
- **UI theme aligned with Saiku Cloud.** Adopted the shared three-tier design
  tokens: Saiku-red brand, warm-neutral light / cool-neutral dark surfaces,
  subtle border ramp, and layered dark elevation — replacing the previous
  indigo-accented, harsh-bordered theme.
- **Cube Designer header.** Mode tabs restyled to a segmented group with a
  ringed active tab, matching the Cloud designer.

### Added
- **Demo-mode lockdown (`SAIKU_DEMO`).** On a public demo, visitors can no longer
  create/edit/delete datasources or save a schema from the Cube Designer, so a
  broken connection or schema can't take the shared instance down. Read-only
  actions (Refresh, opening the designer) still work.
- **Bombadil UI fuzz harness** (`saiku-ui/bombadil/`, dev-only, not shipped in
  the runtime) — property-based headless-browser fuzzing of the UI.

## 4.7.0 — 2026-08-01

Feature release.

### Added
- **Visual Cube Designer in the admin panel** — the interactive schema/cube
  designer is now open source and built into Saiku's admin UI
  (`Admin › Datasources › Design cube`). Profile a datasource, lay tables and
  joins out on a canvas, build dimensions/hierarchies/levels and facts/measures,
  and emit Mondrian 4 schemas directly — no hand-written XML. It replaces the
  old text-based schema generator. Backed by new REST endpoints under
  `/rest/saiku/admin/cube-designer/*` (introspect, sample, convert) and a
  faithful Mondrian 3→4 converter (`RolapSchemaUpgrader`).
- **AI-assisted schema authoring (optional)** — with an `ANTHROPIC_API_KEY`
  configured (`saiku.ai.ask.*`), the designer's DimSum assistant can propose
  dimensions, hierarchies and measures conversationally against the profiled
  warehouse. Fails closed (503) when no key is set.
- **App Builder graphical authoring** — theme-token foundation, brand & theme
  inspector, tile inspector with selection + field editing, and ECharts tile
  Trend/Breakdown toggles for building dashboard apps.

### Changed
- Chart & dashboard polish: readable value-axis tick density, legend placement
  below the title band, grid spacing for rotated category labels, and a
  decluttered toolbar with an overflow menu.

## 4.6.4 — 2026-07-30

Security patch release.

### Security
- **Inline-tile embed RLS bypass** — an embed iframe guest could read the row-level-security
  scope from their own token's `saiku.filters` claim and send a crafted client filter override
  that **widened** (added hidden members) or **stripped** the forced RLS filter on an inline
  dashboard tile. The inline path applied the forced filters first and then let client overrides
  remove/replace them. Forced filters are now applied **last and authoritatively**: on every
  forced axis the effective member set is always a subset of the forced set — a client can only
  narrow within the forced scope, never widen, strip, or change the operator. The saved/reference
  tile path was not affected (it uses the fail-closed `forcedFilters` channel).

## 4.6.3 — 2026-07-20

Patch release.

### Fixed
- **Admin datasource save returned 400** — adding or editing a datasource in the admin
  panel failed with `Save failed  /datasources -> 400`, and the datasource list showed
  blank Type/Schema columns. The SvelteKit admin UI posted camelCase field names
  (`name`, `location`, `schemaName`, `type`) that don't exist on the server's
  `DataSourceMapper`; Jackson rejected the first unknown field as a 400. The UI now
  translates to the server's field contract at the API boundary. Editing a datasource
  without retyping the password no longer wipes the stored credential. (saiku#1529)

## 4.6.2 — 2026-07-16

Patch release.

### Added
- **`SAIKU_ADMIN_PASSWORD`** — set the admin password without rebuilding the image.
  The launcher bcrypt-hashes it into a persisted external `<saiku-home>/users.properties`
  (or supply that file yourself), so a rotated password boots without
  `SAIKU_ALLOW_DEFAULT_ADMIN`. Fixes the self-host password-rotation gap — the
  previously-documented `saiku-rotate-admin` command never existed. See
  `dist/README.md` for details.

## 4.6.1 — 2026-07-13

Patch release fixing version reporting and install telemetry.

### Fixed
- **Version reporting** — deployed instances reported their version as `dev` because the
  fat-JAR manifest never stamped `Implementation-Version`, so `getImplementationVersion()`
  was always null. This broke the `/info` version, the update check, and install telemetry
  (every 4.6.0 instance pinged as "dev"). Releases now report their real version.
- **Install telemetry** — the heartbeat counts real releases only: dev/CI/IDE builds are
  skipped client-side and excluded server-side, so the active-install count is accurate.

## 4.6.0 — 2026-07-13

The headline release of the year: **Saiku becomes a semantic layer that AI agents can
query directly** — no MDX, no SQL. Alongside that, a full embedding SDK, a
privacy/governance layer, and a large batch of security hardening. 381 commits since 4.5.2.

### 🧠 The Ossie semantic layer (new)
One semantic model — datasets, fields, metrics, relationships — served to dashboards,
Excel, and AI agents at once.
- **Ossie model support** — describe your data once in a portable YAML/OSI document (or
  generate it from a Mondrian schema) and query it everywhere.
- **Calcite-based SQL adapter** — Saiku plans clean SQL for the model against almost any warehouse.
- **Ontology + AI-context surface** — the model carries synonyms, display names and context
  so agents understand it.
- **Graph traversal + signals endpoints** — walk relationships (e.g. ownership chains) and pull
  risk/screening summaries, not just aggregates.
- **DuckDB/Quack support** with a dialect fix so filtered queries work correctly.

### 🤖 AI & agents
- **Ask in plain English** — natural-language questions translate to a validated query and
  return typed results.
- **Agent Spaces** — named admin-authored personas that scope an ask (system prompt +
  cube/skill allowlists), enforced server-side.
- **Agent Skills** — reusable markdown workflows agents can invoke.
- **SSE streaming** for ask responses (classic + space-scoped).
- **Bring-your-own-LLM adapters** (Anthropic, OpenAI, Azure OpenAI).
- **Agent evaluation framework** — CLI, REST endpoints, a results store and a dashboard for
  scoring agent answers.
- **AI audit log** and safer policy defaults, plus MCP structured-content support.

### 🔌 Embedding SDK
- **React embed SDK**, **web-component embed**, and token-based embedding.
- **Row-level security** (including JWT-driven RLS) and a **PII gate** for embeds.
- **Chart theming**, embed options, and a force-on header for locked-down deployments.
- Embed package renamed to **`@concepttocloud/saiku-embed`** (now on npm).

### 🔒 Privacy & governance
- **PII annotation** on schema fields, with a per-column inspector.
- **k-anonymity** enforcement on results (matrix + query paths).
- AI audit logging and tightened AI data-policy defaults.

### 📊 Charts & UI
- **Combo charts** and **dual-Y-axis**, measure-group tree with dimension applicability,
  richer time filters, and numerous chart-affordance fixes.

### 📈 Install analytics (new)
- **Anonymous, opt-out install telemetry** — counts active installs (not downloads) via a
  daily heartbeat, backed by a zero-cost Cloudflare collector. Disable with
  `SAIKU_TELEMETRY=off` / `DO_NOT_TRACK=1`.

### 🔭 Observability
- Opt-in **OpenTelemetry** bundles (Jetty, Jersey, JDBC, JVM) via the OTel agent.

### 🧪 Demos & content
- New bundled demos: **TPC-DS**, **Flights**, **Pharma**, **Bank showcase**, and a
  **FoodMart embed** demo — Ossie datasources ready to poke on first boot.

### 🛡️ Security
- CVE/dependency fixes across **Jackson**, **Spring + Log4j**, **Vite/DOMPurify**, **Batik**,
  **OpenPDF** (swapped in for iText), and **jsPDF**.

### ⬆️ Dependencies
- **Jetty 12.1.10**, **Log4j 2.26.0**, **Caffeine 3.2.4**, Spotless 3.7.0, slf4j 2.0.18, and
  routine GitHub Actions bumps.

**Artifacts:** fat-jar + dist zip on the [GitHub release](https://github.com/spiculedata/saiku/releases/tag/v4.6.0),
Docker image at `ghcr.io/spiculedata/saiku:4.6.0`, module jars on GitHub Packages,
and `@concepttocloud/saiku-embed` on npm.
