# Saiku → New Relic

Route Saiku's OpenTelemetry signals into New Relic via its **direct OTLP
endpoint**. New Relic accepts OTLP natively — no collector required.

## Quickstart

Grab a New Relic **ingest license key** from
account.newrelic.com → API keys → **INGEST - LICENSE**. This is the key
Data ingestion uses; it's distinct from the User API key.

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT="https://otlp.nr-data.net"
export OTEL_EXPORTER_OTLP_PROTOCOL="grpc"
export OTEL_EXPORTER_OTLP_HEADERS="api-key=<your-license-key>"
export OTEL_SERVICE_NAME=saiku
export OTEL_RESOURCE_ATTRIBUTES="deployment.environment=production,service.version=4.6.0"

docker run -d --name saiku \
  -p 8080:8080 \
  -e OTEL_EXPORTER_OTLP_ENDPOINT \
  -e OTEL_EXPORTER_OTLP_PROTOCOL \
  -e OTEL_EXPORTER_OTLP_HEADERS \
  -e OTEL_SERVICE_NAME \
  -e OTEL_RESOURCE_ATTRIBUTES \
  ghcr.io/spiculedata/saiku:latest
```

## US vs EU endpoint

Pick the endpoint that matches your New Relic account's data region:

- **US:** `https://otlp.nr-data.net`
- **EU:** `https://otlp.eu01.nr-data.net`

Wrong endpoint → data is silently dropped. Check the container log for
`403 Forbidden` OTLP export lines if traces don't appear.

## Where things show up in New Relic

| Signal  | New Relic product           | Query                                                                    |
|---------|-----------------------------|--------------------------------------------------------------------------|
| Traces  | APM & Services              | Service `saiku` — Distributed Tracing view lights up automatically.       |
| Metrics | Metrics Explorer / NRQL     | `FROM Metric SELECT * WHERE service.name = 'saiku'`.                     |
| Logs    | Logs UI                     | `service.name:saiku` — trace-correlated via `trace.id`.                   |
| SQL     | Databases (via JDBC spans)  | Every Mondrian-emitted SQL statement appears as a database span.         |

## Sampling

New Relic APM data is priced by ingested GB. Real deployments should
sample:

```bash
export OTEL_TRACES_SAMPLER=parentbased_traceidratio
export OTEL_TRACES_SAMPLER_ARG=0.05
```

## Verifying it works

1. Start the container with the env vars set.
2. Query Saiku: `curl -u admin:admin http://localhost:8080/rest/saiku/info`.
3. In New Relic → APM & Services, `saiku` should appear as a service.
   First spans typically show up within a minute.

## Reference

- [New Relic: OpenTelemetry OTLP configuration](https://docs.newrelic.com/docs/opentelemetry/best-practices/opentelemetry-otlp/)
- [Saiku observability overview](../../docs/observability.md)
