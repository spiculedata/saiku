---
title: "One YAML, five surfaces: Saiku's Open Semantic Interchange story"
subtitle: "How we shipped OSI support across the workbench, the AI Query API, and MCP — and why your dbt project can now drive it all."
author: "Tom Barber"
date: "2026-07-08"
tags: [ossie, semantic-layer, ai, dbt, mcp, agents]
canonical: https://saiku.bi/blog/one-yaml-five-surfaces-ossie
excerpt: |
  Snowflake launched the Open Semantic Interchange initiative to give every
  BI, AI, and analytics tool a shared vocabulary. Nine months in, the
  spec is finalised and two things stand out: nobody has really shipped
  it yet, and MetricFlow is the only serious reference implementation.
  Today we're changing both of those. Here's what we've built, why it
  matters, and how to point Saiku at your existing dbt project in about
  five minutes.
---

# One YAML, five surfaces: Saiku's Open Semantic Interchange story

In September 2025, Snowflake launched the [Open Semantic
Interchange](https://open-semantic-interchange.org/) — a collaboration
with Salesforce, dbt Labs, BlackRock, RelationalAI, and a growing list
of others aimed at giving every BI, AI, and analytics tool a shared
vocabulary for describing metrics, dimensions, and business logic. The
[v1.0 specification finalised in January
2026](https://www.snowflake.com/en/blog/open-semantic-interchanges-specs-finalized/).

Nine months later, two things stand out.

First: **almost nobody has actually shipped it yet.** The initiative
has 50+ member organisations. Cortex Analyst, dbt Labs, Salesforce,
Databricks, ThoughtSpot, Atlan, Alation, Denodo — all founding members
or supporters. Snowflake's own product blog last week: *"Phase 2
adoption and native platform support are roadmapped for Q2–Q4 2026."*
So the spec exists; the products don't.

Second: **the tool that HAS shipped serious OSI support is
[MetricFlow](https://github.com/dbt-labs/metricflow)**, dbt Labs'
semantic-layer engine, open-sourced under Apache 2.0 last October
specifically to align with OSI. Every dbt project's semantic model
YAML is now one `dbt compile` away from an OSI document.

Today Saiku joins the very short list. We've shipped Open Semantic
Interchange support end-to-end: the workbench UI, a typed REST API
for agents, five MCP tools, a natural-language ask layer, anomaly and
forecast endpoints, and — because this is where OSI's promise
crystallises — a working bridge from any existing dbt project into
the Saiku workbench.

You can try all of it right now at
[demo.saiku.bi](https://demo.saiku.bi) (admin/admin). Three sample
Ossie models are already provisioned; the section below walks you
through pointing it at your own.

## Why this matters

Every organisation that runs analytics ends up with the same problem:
"net revenue" is defined differently in dbt, in the BI tool, in the
custom Python notebook, in the exec dashboard. Semantic drift. The
usual fix — pick one tool as the source of truth — only works until
someone spins up another tool.

OSI's pitch is different: **the YAML is the source of truth; every
tool works from it.** Define the metric once as an OSI document,
version-control it, and any consumer that reads OSI gets the same
answer. dbt does the modelling, Saiku does the interactive querying,
an LLM agent asks natural-language questions — all pointing at the
same declared metrics.

For AI in particular this is load-bearing. Turning a foundation model
loose against a raw warehouse schema gets you plausible SQL that's
often subtly wrong. Feeding it a governed semantic layer — declared
metrics, sample values, validation — turns the same model into a
trustworthy analyst. Snowflake's whole "agentic AI" framing rests on
this observation. OSI is what makes it portable across tools.

## What we built

Saiku's Ossie surface (we've been calling it "Ossie" since we first
adopted the spec in Q4 2025, before the OSI branding stuck) rolled
out across five phases this month. Every piece works today at
[demo.saiku.bi](https://demo.saiku.bi).

**R1 — the three-endpoint core.** `GET /ai/ossie/models` lists every
semantic model the caller can query. `GET /ai/ossie/schema/{c}/{m}`
returns the full self-describing view of one model: datasets, fields
(with sample values, cardinality estimates via APPROX_COUNT_DISTINCT,
labels), metrics (with their expression + supported aggregation
overrides), relationships, JSON Schema of the request body, and
ready-made example bodies for the common shapes (simple group-by,
crosstab, top-N). `POST /ai/ossie/query` executes a typed shelf-state
request and returns records-format results with typed metric cells.
Every dataset, field, and metric name is validated against the live
model — mistakes come back as `VALIDATION_ERROR` responses with the
candidate list agents need to self-correct.

**R2 — the safety and comfort layer.** Preview endpoint that shows
the emitted SQL without executing (agents can audit before they
run). Values search for the "does this field have 'Medicare' in it?"
lookup. Matrix format for downstream consumers that want
position-indexed results. Async execute/status/result/cancel with
per-user ownership so a foreign session can never poll another
user's queryId. K-anonymity suppression: rows whose count-shaped
metric falls below the configured threshold get masked, and the
response records how many rows were suppressed.

**R3 — MCP tools.** Five new tools sitting alongside our existing six
OLAP MCP tools: `list_ossie_models`, `describe_ossie_model`,
`search_field_values`, `run_ossie_query`, `preview_ossie_query`.
Point Claude Desktop, Cursor, or Cline at
`https://demo.saiku.bi/rest/saiku/api/mcp` and any Ossie model
becomes queryable via natural conversation.

**R4 — analytics.** `POST /ai/ossie/anomaly` runs the query and
flags outlier points using z-score, MAD, or STL detectors. `POST
/ai/ossie/forecast` extends the series with ETS, ARIMA, or Prophet
projections plus confidence intervals. `POST /ai/ossie/row-detail`
re-runs the shelf as a raw rowset — the Ossie answer to drillthrough.

**R5 — natural-language ask.** `POST /ai/ossie/ask` takes plain
English, feeds it plus the resolved schema to Anthropic or OpenAI,
gets back a structured query via `tool_use` / `tool_choice`
(structured output, no fence-stripping), executes it, and returns
the executed result plus a copy of the query the LLM produced. Multi-
turn conversations threaded via `history[]`. If the question is
off-topic, the LLM emits an `OFF_TOPIC` envelope and the endpoint
returns 400 with the reason attached.

Under all of this, the workbench UI you already know: drag-drop
shelves, undo/redo, sort/limit, aggregation override on the fly,
right-click filter menus, crosstab pivot, chart view, CSV export,
Show-SQL modal. Nothing about the interactive experience changed
when we added Ossie — the same UI drives OLAP cubes and Ossie models
side-by-side, discriminated by the datasource type.

## The 6,961-case fuzz

Somewhere around R2 we noticed we were writing translator code that
felt hand-tuned to our three sample YAMLs and worried what would
happen when someone dropped a differently-shaped model in. So we
built a fuzz suite.

`OssieFuzzIT` executes 49 hand-crafted cases with expected values
computed by hand from a Pharma-shaped H2 fixture, plus 6,912
generated cases enumerated across every combination of 1–2 rows ×
0–1 columns × 1–2 metrics × 6 aggregation overrides × 8 filter
shapes × 3 metric picks × 6 dimension rotations. First run flagged
1,536 generated cases as failures with the same shape:

```
SELECT ..., SUM(*) AS "line_count" FROM ...
→ Unknown identifier '*'
```

Our translator's `swapAggregation` was greedily rewriting
`COUNT(*)` to `SUM(*)` when a user picked SUM as an aggregation
override on a count metric. Every parser rejects `SUM(*)`. The fix
was a two-line special case; the fuzz saved us from shipping it.

After the fix: 6,961 / 6,961. And the whole suite runs on every PR.

## The dbt connection

Here's the piece that makes this concrete. dbt Core v1.12 will
consume and emit OSI documents natively via `target/osi_document.json`
after `dbt compile`. But v1.12 hasn't hit PyPI yet (as of writing,
1.11.12 is latest), so we didn't want to wait. Instead we shipped a
small converter that reads MetricFlow YAML — the shape dbt has used
since 1.7 — and emits Ossie YAML that Saiku loads through its normal
datasource registration.

Once dbt 1.12 ships, this converter becomes a two-line CLI over
"read the JSON dbt already wrote." Until then, it works today
against any dbt project.

The full walkthrough lives in the repo at
[docs/dbt-hookup/](https://github.com/spiculedata/saiku/tree/development/docs/dbt-hookup)
and in Saiku's [documentation
site](https://docs.saiku.bi/api/dbt-hookup/). Short version:

```bash
# 1. Convert your MetricFlow YAML → Ossie YAML.
pip install pyyaml
python3 metricflow_to_ossie.py orders_semantic.yml \
  --model-name Orders \
  --description "Orders demo from dbt MetricFlow" \
  > orders.ossie.yaml

# 2. Point Saiku at it — a small .sds file registers the model
#    against your warehouse.
cat > saiku-home/repository/data/unknown/datasources/orders-ossie.sds <<EOF
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<dataSource>
    <name>Orders</name>
    <type>OSSIE</type>
    <ossieYaml>/path/to/orders.ossie.yaml</ossieYaml>
    <location>jdbc:postgresql://your-warehouse:5432/analytics</location>
    <schema>Orders</schema>
    <username>saiku_reader</username>
    <password>...</password>
</dataSource>
EOF

# 3. Verify — the model shows up in the AI Query API immediately.
curl -s -b cookies.txt http://localhost:8080/rest/saiku/api/ai/ossie/models
```

What comes back:

```json
[
  {
    "connectionName": "unknown_Orders",
    "modelName": "Orders",
    "description": "Orders demo from dbt MetricFlow",
    "factDataset": "orders",
    "datasetCount": 3,
    "metricCount": 3
  }
]
```

Every workbench feature, every REST endpoint, every MCP tool now
works against your dbt semantic model. No re-modelling, no shadow
metrics, no "we'll re-declare it in Saiku." The dbt YAML is the
source of truth; Saiku is one more consumer of it.

### What the converter maps

| MetricFlow shape | Ossie shape |
| --- | --- |
| `semantic_models[*]` | `datasets[*]` — name, description, fields, primary_key |
| `.model: ref('fct_orders')` | `dataset.source: FCT_ORDERS` |
| `.entities[type=primary]` | `dataset.primary_key` |
| `.entities[type=foreign]` | Ossie relationships (matched by entity name) |
| `.dimensions[*]` (labels carry through) | `dataset.fields[*]` — human labels preserved |
| `metrics[type=simple]` | Ossie metrics — `agg(expr)` composed on the source dataset |
| `metrics[type=ratio]` | Ossie metrics — `(SUM(num)) / NULLIF(SUM(denom), 0)` |
| `metrics[type=cumulative]` | Skipped with a warning (out of scope for R1) |

MetricFlow's join semantics come across cleanly: dataset A's foreign
entity `customer_id` matches dataset B's primary entity
`customer_id`, and the converter emits an Ossie relationship. At
query time, Saiku's Calcite auto-join rule uses those declarations
to inject the JOIN predicates automatically. You get proper
cross-dataset queries without writing any JOIN SQL.

### Where we're ahead of the spec

The Ossie spec at v0.2.0.dev0 declares one label field on
`Field.label`. We wire that through the schema view and the query
response columns so `NETREVENUE` shows up as "Net Revenue"
everywhere. Dataset and metric labels aren't in the spec yet — we've
filed those upstream and hidden the interim solution behind a
`custom_extensions.saiku.display` block.

MCP tools + typed VALIDATION_ERROR + k-anonymity + PII redaction:
none of these are in the OSI core spec today. They're consumer
concerns — how the tool that reads the semantic model should behave
— and we're publishing our answers as an implementation reference.
If any of them prove genuinely reusable, they graduate to spec
proposals.

## Where you can play

**[demo.saiku.bi](https://demo.saiku.bi)** — admin/admin. Three
Ossie models live at `/rest/saiku/api/ai/ossie/models`:

- **Pharma** — 4 datasets, 3 metrics. Small Rx-sales fixture with
  regions, payers, brands. Good for exercising all five R1-R5
  endpoints; the deliberately seasonal revenue pattern makes for
  clean anomaly / forecast demos.
- **TPC-DS retail** — 5 datasets, 5 metrics. Trimmed from
  [apache/ossie's TPC-DS
  example](https://github.com/apache/ossie/blob/main/examples/tpcds_semantic_model.yaml).
  Store sales, customers, products, stores, calendar dim. Full
  cross-dataset joins.
- **Flights** — 4 datasets, 5 metrics. Trimmed from
  [apache/ossie's flights
  example](https://github.com/apache/ossie/blob/main/examples/flights.yaml)
  and demonstrates our support for OSI's `ontology_mappings` envelope
  (a knowledge-graph-shaped variant of the spec). The delay ramp
  Nov–Dec is a good target for the forecast endpoint.

**MCP** — point Claude Desktop / Cursor / Cline at
`https://demo.saiku.bi/rest/saiku/api/mcp`. You'll see six OLAP
tools plus five Ossie tools. Ask "what Ossie models are available?"
and let the agent take it from there.

**Saiku Cloud** — the same Ossie surface ships in [Saiku
Cloud](https://cloud.saiku.bi/) alongside the OLAP tooling. Point it
at your dbt project, or write your own Ossie YAML from scratch, and
you get the whole workbench + AI query + MCP surface out of the box.

## What's next

We've got a handful of follow-ups in the tracker
([#1394](https://github.com/spiculedata/saiku/issues/1394) is the
parent):

- **`ai_context.synonyms`** support — the spec lets a metric declare
  `["revenue", "turnover", "top-line"]` as alternative names.
  Publishing those in the AI schema + rewriting requests in the
  validator gives agents a lot more slack on how they phrase things.
- **`custom_extensions`** pass-through — the spec's extension point.
  Once we surface it, tool-specific metadata (`dbt.*`, `saiku.*`,
  `snowflake.*`) can round-trip without collision.
- **`ontology:` block parsing** — the knowledge-graph half of the
  spec. A dedicated `/ai/ossie/ontology` endpoint would let agents
  navigate by concept ("show me every dataset joined to Aircraft")
  rather than by dataset. Concept-driven schema browsing is
  something no BI tool does today; the ontology block gives us the
  vocabulary.
- **Role-based security** — Mondrian-parity roles for Ossie models.
  YAML gets a `roles:` block; the shelf-to-SQL translator picks up
  the caller's Spring Security authorities and injects WHERE
  predicates before executing.
- **Spin `ossie-sql` out as a library** — the JVM/Calcite adapter
  bits are generic enough to work outside Saiku. Publishing them
  as a standalone artifact would give Superset, Metabase, Trino, and
  friends a reference OSI adapter to build against. MetricFlow fills
  this slot for Python/dbt; we'd fill it for JVM.

## Beyond the tech

The reason we jumped on OSI early is boring in a good way: it's the
first cross-vendor standard we've seen that doesn't get bogged down
in surface politics. The spec is Apache 2.0. The reference
implementations are Apache 2.0. Snowflake, Salesforce, dbt Labs,
and the working group are all publishing in public. When we filed a
spec-gap issue upstream last week, we got a maintainer response the
same day.

Semantic layers have been a Trojan horse for vendor lock-in for the
last decade — everyone wanted to be the one place your metrics
lived, so nobody could ever leave. OSI is genuinely trying to
subvert that pattern. We think it deserves working implementations
sooner rather than later, so we've built one.

If you're running dbt today, you can try the hookup in about five
minutes. If you're building an LLM agent that needs to query
warehouse data safely, the MCP endpoint at
[demo.saiku.bi](https://demo.saiku.bi/rest/saiku/api/mcp) is running
live. And if you're working on OSI compatibility on the other end
of the wire, we'd love to hear about it — issues to
[spiculedata/saiku](https://github.com/spiculedata/saiku/issues), or
find us in the Ossie working group.

---

**Try it now:**
- [Saiku Cloud](https://cloud.saiku.bi) — production, your data
- [demo.saiku.bi](https://demo.saiku.bi) — live demo, three Ossie models, no signup
- [docs.saiku.bi/api/ai-ossie](https://docs.saiku.bi/api/ai-ossie/) — full API reference
- [docs.saiku.bi/api/dbt-hookup](https://docs.saiku.bi/api/dbt-hookup/) — dbt integration guide
- [github.com/spiculedata/saiku](https://github.com/spiculedata/saiku) — the code

**Read more:**
- [OSI spec on Apache Ossie](https://github.com/apache/ossie)
- [OSI announcement — Snowflake blog](https://www.snowflake.com/en/blog/open-semantic-interchange-ai-standard/)
- [dbt Labs open-sourcing MetricFlow](https://www.getdbt.com/blog/open-source-metricflow-governed-metrics)
