# Observability adaptor bundles

Pre-baked configurations for pointing Saiku's OpenTelemetry instrumentation
at named observability providers. Saiku's [OTel plumbing](../docs/observability.md)
is provider-neutral — every bundle here is a thin envelope around the same
`OTEL_EXPORTER_OTLP_*` environment variables the OTel SDK reads directly.

## Providers

| Provider              | Path                       | Ships to                                                                    | Auth        |
|-----------------------|----------------------------|-----------------------------------------------------------------------------|-------------|
| **Datadog**           | [datadog/](datadog/)               | Datadog Agent (OTLP receiver) → Datadog                                     | API key     |
| **Grafana Cloud**     | [grafana-cloud/](grafana-cloud/)   | Direct OTLP → Grafana Cloud                                                 | Instance token |
| **New Relic**         | [new-relic/](new-relic/)           | Direct OTLP → New Relic                                                     | License key |
| **AWS CloudWatch**    | [cloudwatch/](cloudwatch/)         | ADOT collector → CloudWatch Metrics + X-Ray traces + CloudWatch Logs        | IAM role    |
| **Self-hosted OTel Collector** | (see [docs/observability.md](../docs/observability.md)) | Any of the above via the standard OpenTelemetry Collector | Depends on downstream |

Each subdirectory has a `README.md` with a copy-paste quickstart and a
`docker-compose.yml` (or `env.example`) that boots the pipeline.

## Dashboards

- [dashboards/grafana/saiku-overview.json](dashboards/grafana/saiku-overview.json)
  — Grafana starter dashboard covering HTTP request rate + latency,
  Mondrian SQL emission, JVM heap, DBCP2 pool utilization, and the AI
  Query token-usage tiles. Import via Grafana → Dashboards → Import →
  Upload JSON.

## Which bundle should I pick?

- **You're already paying for Datadog / New Relic / Grafana Cloud** — use
  that provider's bundle. The pipeline is one collector + your existing
  auth; there's nothing to build.
- **You're on AWS and don't want another vendor** — use the CloudWatch
  bundle. ADOT emits into CloudWatch Metrics + X-Ray natively.
- **You run your own Prometheus / Tempo / Loki stack** — you don't need
  a bundle. Point `OTEL_EXPORTER_OTLP_ENDPOINT` at your existing OTel
  Collector and follow [docs/observability.md](../docs/observability.md).
- **You just want to poke around locally** — the parent doc has a
  ten-second `docker run otel/opentelemetry-collector` snippet that
  logs received spans to stdout. No account required.

## What Saiku already emits

Auto-instrumented by the OTel Java agent (Tier 1, always on when the
agent is attached):

- **HTTP request spans** for every `/rest/**` endpoint — method, route,
  status code, latency.
- **JDBC child spans** for every SQL statement Mondrian generates —
  `db.system`, `db.statement`, duration. The big win: every slow MDX
  query is decomposed into its SQL.
- **`java.net.http.HttpClient` outbound spans** — Anthropic / OpenAI /
  any downstream HTTP call appears as a child of the parent request.
- **JVM metrics** — heap, GC pause, thread count, class loading.
- **DBCP2 pool metrics** — active / idle connections, wait times.
- **Log records** with `trace_id` / `span_id` in MDC — every log line
  correlates back to a trace.

Custom domain spans (per-MDX-query attribution, per-ai-ask token usage)
are Tier 2 and ship in a follow-up.
