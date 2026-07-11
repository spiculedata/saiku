# Saiku → Datadog

Route Saiku's OpenTelemetry traces, metrics, and logs into Datadog via
the Datadog Agent's built-in OTLP receiver. No separate OTel Collector
required — the Datadog Agent speaks OTLP natively.

## Quickstart

Set your Datadog API key and pull down the compose bundle:

```bash
export DD_API_KEY=<your-datadog-api-key>
export DD_SITE=datadoghq.com     # datadoghq.eu / us3.datadoghq.com / etc.
docker compose up -d
```

## What this deploys

- **Datadog Agent** with the OTLP receiver enabled on ports 4317 (gRPC)
  and 4318 (HTTP/protobuf). The agent forwards traces to APM, metrics
  to the Metrics API, and logs to the Logs Ingestion pipeline.
- **Saiku** with the OTel Java agent attached and the OTLP endpoint
  pointed at the Datadog Agent container.

## Where things show up in Datadog

| Signal  | Datadog product                   | How to find it                                                            |
|---------|-----------------------------------|---------------------------------------------------------------------------|
| Traces  | APM → Service Map                 | Service name `saiku` (override with `OTEL_SERVICE_NAME`).                 |
| Metrics | Metrics → Explorer                | Filter by `service:saiku`. Every JVM / DBCP2 / HTTP metric surfaces here. |
| Logs    | Logs → Live Tail                  | `service:saiku` — each log line is trace-correlated via `trace_id`.        |
| SQL     | APM → Database Monitoring (via JDBC spans) | Filter APM spans by `span.type:sql`; the auto-instrumented JDBC layer exposes every Mondrian-emitted statement. |

## Sampling

The default `parentbased_always_on` sampler in `docker-compose.yml`
captures every trace — fine for the demo, not production. Under real
load flip to:

```yaml
environment:
  OTEL_TRACES_SAMPLER: parentbased_traceidratio
  OTEL_TRACES_SAMPLER_ARG: "0.05"   # 5% root traces
```

Datadog APM's usage-based billing rewards low sampling ratios — 1-5%
is typical.

## Overriding the target Datadog site

`DD_SITE` picks the Datadog region:

- `datadoghq.com` — US1 (default)
- `datadoghq.eu` — EU1
- `us3.datadoghq.com` — US3
- `us5.datadoghq.com` — US5
- `ap1.datadoghq.com` — AP1
- `ddog-gov.com` — US1-FED

## Verifying it works

1. Boot the stack: `docker compose up -d`.
2. Wait ~30 seconds for the first traces to flush.
3. Query Saiku: `curl -u admin:admin http://localhost:8080/rest/saiku/info`.
4. In Datadog → APM → Service Map, `saiku` should appear as a service
   with an inbound edge from the HTTP `GET /rest/saiku/info` route.

If nothing shows up, check the Datadog Agent's logs:

```bash
docker compose logs datadog-agent | grep -i otlp
```

You want to see the OTLP receiver bound on `:4317` and `:4318`.

## Reference

- [Datadog: OpenTelemetry with the Datadog Agent](https://docs.datadoghq.com/opentelemetry/collector_exporter/otlp_receiver/)
- [Saiku observability overview](../../docs/observability.md)
- [Grafana starter dashboard](../dashboards/grafana/saiku-overview.json)
  — the same metrics render in Datadog too; the dashboard JSON is a
  reference for which series to build widgets from.
