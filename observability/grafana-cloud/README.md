# Saiku → Grafana Cloud

Route Saiku's OpenTelemetry signals into Grafana Cloud via its **direct
OTLP endpoint**. Grafana Cloud accepts OTLP natively, so no collector is
required — Saiku's built-in OTel Java agent talks straight to the cloud.

## Quickstart

Grab an OTLP instance token from Grafana Cloud → **Connections → Data
sources → OpenTelemetry (OTLP)** — it renders the endpoint URL and the
`Authorization: Basic <base64>` header you'll paste below.

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT="https://otlp-gateway-<region>.grafana.net/otlp"
export OTEL_EXPORTER_OTLP_PROTOCOL="http/protobuf"
export OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic <the-base64-token>"
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

The full env-var block is in [env.example](env.example). Copy it to
`.env` and edit the values.

## Where things show up in Grafana Cloud

| Signal  | Grafana Cloud product | How to find it                                                                     |
|---------|-----------------------|------------------------------------------------------------------------------------|
| Traces  | Grafana Cloud Traces (Tempo) | Explore → Tempo → Service `saiku`.                                          |
| Metrics | Grafana Cloud Metrics (Mimir) | Explore → Prometheus → `service=saiku`.                                      |
| Logs    | Grafana Cloud Logs (Loki)    | Explore → Loki → `{service="saiku"}` — every log line carries `trace_id`.     |

## Starter dashboard

Import the [Saiku overview dashboard](../dashboards/grafana/saiku-overview.json):

1. Grafana → Dashboards → **Import**.
2. Upload `dashboards/grafana/saiku-overview.json`.
3. Pick the Grafana Cloud Prometheus data source when prompted.

The dashboard uses PromQL-compatible queries (Mimir speaks Prometheus)
so no rewriting is required.

## Sampling

Grafana Cloud Traces is priced by ingested trace volume. For anything
above demo scale, flip the sampler:

```bash
export OTEL_TRACES_SAMPLER=parentbased_traceidratio
export OTEL_TRACES_SAMPLER_ARG=0.05   # 5% root traces
```

## Verifying it works

1. Start Saiku with the env vars set.
2. Run a couple of queries: `curl -u admin:admin http://localhost:8080/rest/saiku/info`.
3. In Grafana Cloud → Explore → Tempo, search for service `saiku`. First
   spans should appear within 30 seconds.
4. Check the Saiku container logs for `OpenTelemetry SDK exporter` lines
   — they'll surface any auth failures.

## Reference

- [Grafana Cloud OTLP endpoint docs](https://grafana.com/docs/grafana-cloud/send-data/otlp/)
- [Saiku observability overview](../../docs/observability.md)
