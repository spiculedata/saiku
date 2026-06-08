# Observability

Saiku ships with **opt-in OpenTelemetry instrumentation** via the
[OpenTelemetry Java agent](https://github.com/open-telemetry/opentelemetry-java-instrumentation).
The agent is side-loaded — it is only attached at JVM start when an
OTLP endpoint is configured, so deployments that don't want
observability pay no cost (no agent loaded, no startup delay, no
runtime overhead).

This is **Tier 1**: zero-code instrumentation. Custom spans for
`ThinQueryService`, AI Query API, and cellset cache (Tier 2) layer on
top of the same SDK once we know what's worth tracing — tracked under
a follow-up issue.

## What gets instrumented automatically

The agent's bytecode-injected instrumentation covers everything Saiku
runs on:

| Component | What you get |
|---|---|
| **Jetty 12 (EE10)** | Server span per HTTP request, populated with `http.method`, `http.route`, `http.status_code`, `url.path`. |
| **Jersey 3.1** | Resource method attribution on the request span (`code.namespace`, `code.function`). |
| **JDBC** | A child span per SQL statement Mondrian emits, with `db.system` and `db.statement`. This is the big win: every slow MDX query is decomposed into the SQL it generated. |
| **java.net.http.HttpClient** | Outbound calls to AI providers (Anthropic, OpenAI, etc.) and any other downstream HTTP service appear as child spans. |
| **Log4j 2** | Trace context (`trace_id`, `span_id`) injected into MDC. The Saiku log4j pattern renders these as `[trace_id=… span_id=…]` when present and elides the bracket entirely when absent. Logs can also be exported as OTLP log records. |
| **JVM** | Process metrics: heap usage, GC pause duration, thread count, class loading. |
| **DBCP2** | Connection pool metrics (active, idle, waits). |

## Enabling it

### Docker

The published `ghcr.io/spiculedata/saiku` image bakes the agent into
`/opt/saiku/otel/opentelemetry-javaagent.jar`. The entrypoint attaches
it only when `OTEL_EXPORTER_OTLP_ENDPOINT` is set:

```bash
docker run -d \
  --name saiku \
  -p 8080:8080 \
  -e OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318 \
  -e OTEL_SERVICE_NAME=saiku-prod \
  -e OTEL_RESOURCE_ATTRIBUTES=deployment.environment=production \
  ghcr.io/spiculedata/saiku:latest
```

### Standalone distribution

Download the agent jar manually and drop it next to `run.sh` /
`run.bat`:

```bash
curl -fsSLO https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/2.28.1/opentelemetry-javaagent-2.28.1.jar
mv opentelemetry-javaagent-2.28.1.jar opentelemetry-javaagent.jar
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
./run.sh
```

The wrapper attaches the agent only when both the jar AND the endpoint
env var are present.

### Hand-rolled (bare `java -jar`)

```bash
java -javaagent:/path/to/opentelemetry-javaagent.jar \
  -jar saiku-<version>.jar serve
```

## Configuration reference

All settings are standard OpenTelemetry SDK environment variables —
Saiku passes them straight through to the agent. The full list is in
the [OTel SDK env-var spec](https://opentelemetry.io/docs/specs/otel/configuration/sdk-environment-variables/);
the ones you'll actually want:

| Variable | Default | Purpose |
|---|---|---|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | _unset_ | OTLP collector URL. **Setting this is what activates the agent.** Supports both gRPC (`:4317`) and HTTP/protobuf (`:4318`). |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `grpc` | `grpc` or `http/protobuf`. Pick whichever your collector speaks. |
| `OTEL_SERVICE_NAME` | `saiku` | Service identifier in your tracing UI. Saiku sets this default when the agent activates; override per environment. |
| `OTEL_RESOURCE_ATTRIBUTES` | _unset_ | Comma-separated `key=value` pairs. Typical: `deployment.environment=demo,service.version=4.3.4`. |
| `OTEL_TRACES_EXPORTER` | `otlp` | `otlp` / `none`. |
| `OTEL_METRICS_EXPORTER` | `otlp` | `otlp` / `prometheus` / `none`. |
| `OTEL_LOGS_EXPORTER` | `otlp` | `otlp` / `none`. |
| `OTEL_TRACES_SAMPLER` | `parentbased_always_on` | Sampling strategy. See sampling below. |
| `OTEL_TRACES_SAMPLER_ARG` | _unset_ | Sampler argument (e.g. ratio for `traceidratio`). |
| `OTEL_EXPORTER_OTLP_HEADERS` | _unset_ | For SaaS providers needing auth: `api-key=…`. |

## Sampling

The default `parentbased_always_on` sampler captures every trace —
fine for development and the demo, **not** what you want under
production load. For a real deployment:

```bash
# Capture 5% of root traces; child spans follow the root's decision.
export OTEL_TRACES_SAMPLER=parentbased_traceidratio
export OTEL_TRACES_SAMPLER_ARG=0.05
```

If you ingest into a SaaS provider with usage-based billing, lean
toward 1–5%. Self-hosted (Tempo, Jaeger) can comfortably handle 25–100%
on Saiku's typical query volume.

## Verifying it works

Spin up a quick collector via Docker to sanity-check end-to-end:

```bash
docker run --rm -p 4318:4318 \
  otel/opentelemetry-collector:latest \
  --config=/etc/otelcol/config.yaml
```

Default collector config logs received spans to stdout. Send a query
to Saiku and you should see spans named `GET /rest/saiku/info`,
`POST /rest/saiku/api/query/execute`, and JDBC child spans for the
Mondrian-generated SQL.

Log lines for the same request will now include the trace ID so you
can correlate logs ↔ traces:

```
2026-06-03T12:34:56.789 INFO  [ThinQueryService] [trace_id=4bf92f3577b34da6a3ce929d0e0e4736 span_id=00f067aa0ba902b7] Executing MDX …
```

## What this doesn't cover

Tier 1 stops at framework-level auto-instrumentation. The following
require custom spans (Tier 2):

- Per-MDX-query attribution (cube name, axis structure, cell count)
- AI Query API request metadata (model, provider, token usage)
- Cellset cache hit/miss attribution
- Mondrian Calcite-vs-legacy backend selection events
- Schema discovery vs query-execute attribution beyond the HTTP route

These ship in a follow-up. Tier 1 gives us the request/SQL/JVM
visibility that 80% of operational questions need; Tier 2 fills in the
domain-specific gaps once we know which gaps actually matter in
production.
