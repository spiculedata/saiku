# Saiku → AWS CloudWatch

Route Saiku's OpenTelemetry signals into AWS CloudWatch via the **AWS
Distro for OpenTelemetry (ADOT) collector**. Metrics land in CloudWatch
Metrics, traces in AWS X-Ray, and logs in CloudWatch Logs — all with
IAM role authentication, no long-lived keys required if you're running
on EC2 / ECS / EKS.

## Quickstart

```bash
docker compose up -d
```

The compose bundle spins up:

- The **ADOT collector** with receivers for OTLP (gRPC 4317, HTTP 4318)
  and exporters for CloudWatch EMF (metrics), AWS X-Ray (traces), and
  CloudWatch Logs.
- **Saiku** pointed at the collector.

## Prerequisites

The ADOT collector needs AWS credentials to write to CloudWatch and
X-Ray. Best options in order of preference:

- **EC2 / ECS / EKS with instance role** — leave the compose bundle
  alone; ADOT picks up the ambient credentials.
- **AWS Vault / short-lived session** — mount your creds:
  ```bash
  aws-vault exec my-profile -- docker compose up -d
  ```
- **Explicit access keys** — set `AWS_ACCESS_KEY_ID` +
  `AWS_SECRET_ACCESS_KEY` + `AWS_REGION` in the environment before
  running compose. Not recommended for production.

The IAM policy needs:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "cloudwatch:PutMetricData",
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents",
        "logs:DescribeLogStreams",
        "logs:DescribeLogGroups",
        "xray:PutTraceSegments",
        "xray:PutTelemetryRecords",
        "xray:GetSamplingRules",
        "xray:GetSamplingTargets",
        "xray:GetSamplingStatisticSummaries"
      ],
      "Resource": "*"
    }
  ]
}
```

## Where things show up in AWS

| Signal  | AWS service           | How to find it                                                                     |
|---------|-----------------------|------------------------------------------------------------------------------------|
| Metrics | CloudWatch → Metrics  | Namespace `Saiku` (override via `AWS_EMF_NAMESPACE`). Filter by `service=saiku`.    |
| Traces  | X-Ray → Traces        | Service `saiku`. The service map lights up automatically.                          |
| Logs    | CloudWatch → Log groups | `/aws/otel/saiku/logs` (configurable in `otel-collector-config.yaml`).           |
| SQL     | X-Ray → subsegments   | Every Mondrian-emitted SQL statement is a `db.statement`-annotated subsegment.     |

## Sampling

X-Ray charges per trace. Configure sampling with X-Ray's central rule
manager, OR at the OTel SDK level:

```yaml
environment:
  OTEL_TRACES_SAMPLER: parentbased_traceidratio
  OTEL_TRACES_SAMPLER_ARG: "0.05"
```

## Verifying it works

1. `docker compose up -d`.
2. Query Saiku: `curl -u admin:admin http://localhost:8080/rest/saiku/info`.
3. In AWS Console → X-Ray → Traces, `saiku` should appear within a
   couple of minutes.
4. In CloudWatch → Metrics → Custom namespaces, `Saiku` should be
   listed with JVM / DBCP / HTTP series populating.

If nothing shows up, tail the collector:

```bash
docker compose logs otel-collector | tail
```

You want to see `CloudWatchLogsExporter`, `AWSXRayExporter`, and
`AWSEMFExporter` all reporting successful exports.

## Reference

- [AWS Distro for OpenTelemetry](https://aws-otel.github.io/)
- [ADOT collector config reference](https://aws-otel.github.io/docs/setup/build-collector-with-scripts)
- [Saiku observability overview](../../docs/observability.md)
