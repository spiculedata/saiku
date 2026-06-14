<a href="#readme"></a>

<h1 align="center"><a href="https://saiku.bi">Saiku Analytics</a></h1>

<p align="center">
  Open-source Semantic Layer analytics for cubes — drag-and-drop in the
  browser, SQL through Mondrian + Calcite, and a typed REST surface so AI
  agents can query without ever seeing MDX.
</p>

<p align="center">
  <a href="https://saiku.bi"><b>saiku.bi</b></a> ·
  <a href="https://demo.saiku.bi"><b>Live demo</b></a> ·
  <a href="https://github.com/spiculedata/saiku/issues"><b>Issues</b></a> ·
  <a href="https://github.com/spiculedata/saiku/discussions"><b>Discussions</b></a>
</p>

***

## Try it in 30 seconds

```sh
docker run -d -p 8080:8080 --name saiku ghcr.io/spiculedata/saiku
```

Then open <http://localhost:8080/ui/> and log in with `admin` / `admin`. The
container ships with a self-contained H2 + FoodMart demo cube — drag fields
onto rows, columns, or filters and the SPA writes MDX for you.

A hosted instance is always live at <https://demo.saiku.bi> (auto-reset
nightly).

## What is Saiku

Saiku started in 2010 as an open-source OLAP browser for Mondrian. In 2026
it was rebuilt as a modern Semantic Layer on top of:

- **Mondrian 4.8.1.x (Spicule fork)** — with a Calcite-based SQL planner
  alongside the legacy `SqlQuery` builder. Calcite is the default; force
  legacy with `-Dmondrian.backend=legacy`.
- **Apache Arrow** wire format for cellsets, so the browser and any
  programmatic consumer share a zero-copy result envelope.
- **Jetty 12 EE10 + Jersey 3.1 + Spring 6 + Spring Security 6.5** in a
  single-JAR Picocli launcher.
- **SvelteKit 5 + Vite** for the SPA (separate repo, served from inside
  the same JAR at `/ui/`).

## AI Query API + MCP

Saiku 4.x exposes a typed REST surface — `/rest/saiku/api/ai/*` — designed
for LLM agents. Hierarchies, levels, measures and synonyms are discoverable
via `/ai/cubes` and `/ai/schema`, and a single `POST /ai/query` translates
a JSON description of a question into validated MDX, runs it, and returns
typed `{value, formatted, unit}` cells. Every validation failure carries
a `{status, field, available}` envelope so an agent can self-correct
without scraping logs.

The container also bundles **`saiku-mcp`**, a stdio
[Model Context Protocol](https://modelcontextprotocol.io) wrapper so Claude
Desktop / Cursor / Cline can wire to a running Saiku with one line of
config:

```json
{
  "command": "docker",
  "args":    ["exec", "-i", "saiku", "saiku-mcp"]
}
```

See [`docs/AI-QUERY-API.md`](docs/AI-QUERY-API.md) and
[`docs/schema-annotations.md`](docs/schema-annotations.md) for the typed
contract and the `saiku.semantic.*` annotation namespace cubes use to
describe themselves to agents.

## Dashboards

Build shareable dashboards of chart / table / KPI / text / image tiles over your
cubes — with cross-tile filters, click- and brush-cross-filtering,
drill-down/through, conditional formatting, combo charts, anomaly/forecast
overlays, auto-refresh, PDF/PNG export and read-only share links. See the
[`docs/dashboards-user-guide.md`](docs/dashboards-user-guide.md) for the full
walkthrough, and [`saiku-ui/src/embed/README.md`](saiku-ui/src/embed/README.md)
to embed a dashboard in your own app via the `<saiku-embed>` web component.

## Observability

Saiku ships **opt-in OpenTelemetry instrumentation** via the OTel Java
agent — zero code changes, zero overhead when off. Setting
`OTEL_EXPORTER_OTLP_ENDPOINT` activates auto-instrumentation for Jetty,
Jersey, JDBC (every Mondrian-emitted SQL becomes a child span),
outbound HTTP, JVM metrics, and DBCP2 connection pool metrics. Trace
context is injected into the Saiku log pattern automatically.

```sh
docker run -d -p 8080:8080 \
  -e OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318 \
  -e OTEL_SERVICE_NAME=saiku-prod \
  ghcr.io/spiculedata/saiku:latest
```

Without the endpoint env var the agent is never loaded. See
[`docs/observability.md`](docs/observability.md) for the full env-var
reference, sampling guidance, and what's not yet covered (Tier 2
custom spans for `ThinQueryService` etc.).

## Build from source

JDK 21 + Maven 3.9+ required.

```sh
# Compile, unit tests, Spotless format check (CI gate):
mvn verify

# Build the runnable fat-JAR:
mvn -pl saiku-launcher -am -Dmaven.test.skip=true package

# Run:
java -jar saiku-launcher/target/saiku-*.jar serve --port 8080 --home ./saiku-home
```

Whole-API integration tests (boots Jetty + the launcher's WAR in-process
against the seeded FoodMart H2 datasource):

```sh
mvn verify -P integration
```

See [`CLAUDE.md`](CLAUDE.md) for the full layout, the dependency catalog
(`saiku-bom`), and the GitHub Packages auth gotcha for local builds.

## Repository layout

```
saiku-bom/             # central dependency-version catalogue
saiku-core/
  saiku-olap-util/     # olap4j helpers
  saiku-service/       # Semantic Layer service, AI Query, schema gen, async, cache
  saiku-semantic/      # YAML semantic layer
  saiku-web/           # JAX-RS REST resources
saiku-webapp/          # Servlet webapp (Spring XML wiring)
saiku-launcher/        # Picocli CLI + embedded Jetty serving the WAR
saiku-mcp/             # stdio JSON-RPC MCP wrapper
saiku-ui/              # SvelteKit SPA (independent versioning)
```

The SvelteKit SPA lives in `saiku-ui/` and is built independently; the
launcher serves the static `dist/` under `/ui/`.

## Getting help

- **Bugs and feature requests**:
  [open an issue](https://github.com/spiculedata/saiku/issues/new/choose).
- **Questions, ideas, walkthroughs**:
  [GitHub Discussions](https://github.com/spiculedata/saiku/discussions).
- **Stack Overflow tag**:
  [`saiku`](https://stackoverflow.com/questions/tagged/saiku).
- **Commercial support, hosted, training**: <hello@saiku.bi>.

The old `groups.google.com/a/saiku.meteorite.bi` lists and `##saiku` IRC
channel are no longer monitored.

## Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md). Short version:

- `feature/<name>` branch off `development` — see the Gitflow note in
  [CLAUDE.md](CLAUDE.md).
- Run `mvn spotless:apply` before committing (a pre-commit hook is
  available via `./scripts/install-hooks.sh`).
- Tests live in `**/src/test/java/`; integration tests in
  `saiku-launcher/src/test/java/org/saiku/launcher/it/`.
- Open the PR against `development`, not `main`. `main` is the release
  branch — only release/* and hotfix/* PRs go there.

## License

Saiku is dual-licensed under **Apache 2.0** and **EPL 1.0**. See
[`LICENSE.txt`](LICENSE.txt). A summary lives at
<https://saiku.bi/#license>.

## History

The original Saiku project was started by Tom Barber and Paul Stoellberger
in 2010 at Meteorite BI and now lives at [Spicule](https://spicule.co.uk).
Contributors are listed on the
[GitHub contributors page](https://github.com/spiculedata/saiku/graphs/contributors).

For release notes see [Releases](https://github.com/spiculedata/saiku/releases).

**[⬆ back to top](#readme)**
