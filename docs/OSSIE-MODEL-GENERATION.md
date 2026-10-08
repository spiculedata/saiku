# Ossie model generation — build the semantic layer from a warehouse

One REST call turns a warehouse connection into a starting Ossie semantic
model plus an audit document explaining every decision the generator made.

Ships in issue [#1439](https://github.com/spiculedata/saiku/issues/1439).

The positioning: Saiku doesn't only *serve* a semantic layer, it can *build*
one. Point it at a warehouse, get a model that a human can then argue with —
and a rationale file that tells them what to argue about.

---

## What it actually does

Most of the pipeline already existed in `org.saiku.service.schema.generate`
(JDBC introspection, fact/dimension classification, measure inference, LLM
enrichment). What was missing was the last mile and the audit trail. This
feature wires the whole chain end to end and adds two things that didn't
exist before: a validation gate and a rationale document.

```
INTROSPECTING   JdbcIntrospector        JDBC metadata → DbModel
INFERRING       SchemaInferrer          DbModel → DraftSchema (deterministic rules)
ENRICHING       LlmEnricher             + PiiFilter → SuggestionSet
APPLYING        OpApplier               suggestions applied to the draft
CONVERTING      MondrianSchemaWriter    DraftSchema → Mondrian XML
                MondrianToOssieConverter Mondrian XML → OssieDocument
VALIDATING      OssieModelValidator     structural + referential checks
                OssieYamlReader         strict read-back of the emitted YAML
WRITING         GeneratedModelStore     → <name>.generated.yaml
                                           + <name>.generated.rationale.md
```

Every stage is the same class the interactive schema generator uses, so a
change to fact/dimension classification or to the enrichment prompt shows up
in both surfaces rather than forking.

## REST

```
POST /saiku/admin/ossie-generate                  → 202 { jobId, dataSourceId, modelName, stage }
GET  /saiku/admin/ossie-generate/{jobId}          → stage, counts, artefact paths, delta counts
```

Request body — `modelName` is optional and defaults to the data-source id:

```json
{ "dataSourceId": "foodmart", "modelName": "foodmart" }
```

`POST` returns **202 Accepted with a job id**, not 200. A 47-table warehouse
takes 30–60 s, dominated by LLM round-trips; holding an HTTP request open
across them is how the cube-designer's generate button started timing out.
Clients poll the `GET` until `stage` reaches `COMPLETED` or `FAILED`.

Poll response:

```json
{
  "jobId": "b3add260-…", "dataSourceId": "foodmart", "modelName": "foodmart",
  "stage": "COMPLETED", "failureMessage": null,
  "yamlPath": "/datasources/foodmart.generated.yaml",
  "rationalePath": "/datasources/foodmart.generated.rationale.md",
  "semanticModelCount": 1, "datasetCount": 3, "fieldCount": 4,
  "metricCount": 3, "relationshipCount": 2, "appliedSuggestionCount": 11,
  "degraded": false, "skippedCubes": [],
  "newElementCount": 0, "unchangedElementCount": 0, "removedUpstreamElementCount": 0
}
```

### Why `/saiku/admin/`, not `/saiku/api/`

The issue proposed `POST /saiku/api/ossie/generate`. The path shipped under
`/saiku/admin/` on purpose: the Spring Security chain in
`applicationContext-saiku.xml` gates `/rest/saiku/admin/**` at
`hasRole('ADMIN')` but leaves `/rest/saiku/api/**` at
`isFullyAuthenticated()` — any logged-in analyst. Generation reads warehouse
credentials and writes into the repository, so it belongs behind the role
gate, next to its sibling `SchemaGeneratorResource` which already generates
schemas under `/saiku/admin/`. The resource also carries an inline
`userService.isAdmin()` check as a second, code-level gate.

## The rationale document

`<name>.generated.rationale.md` is the enterprise-credibility half of the
feature. Someone who did not write the warehouse schema needs to answer
"why is this measure called Revenue and not `sum_amt_2`" without reading
Java.

```markdown
# Generation rationale — foodmart

- **Data source:** `foodmart`
- **Generated:** 2026-03-04 10:15:30 UTC
- **Enrichment:** LLM-backed (or offline rules, if no provider is configured)
- **Emitted model:** `/datasources/foodmart.generated.yaml`
- **Shape:** 1 semantic model(s), 3 dataset(s), 4 field(s), 3 metric(s), 2 relationship(s)

## LLM decisions

### 1. Rename
- **Target:** `cubes/ORDERS/measures/AMOUNT`
- **Confidence:** 0.60
- **Why:** Title-cased from raw identifier
- **Change:** `AMOUNT` → `Amount`

## PII

Columns matching the PII deny list. Their sample values were withheld from
the LLM provider before every call (see `PiiFilter`); only the column
metadata reached the vendor.

- `CUSTOMER.EMAIL`

## Deterministic inference
…
```

Sections: provenance header, **one entry per applied suggestion** (quoting the
op's own rationale), **PII** columns withheld from the vendor, the
**deterministic inference** the rules produced, the **delta** against the
previous generation, and any **cubes the converter could not map**.

Rendering is deterministic for a given input — the timestamp comes from an
injected `Clock`, never `Instant.now()` — so the file diffs cleanly between
regenerations.

## Validation

Nothing reaches the repository unvalidated. Two layers:

1. **`OssieModelValidator`** — referential integrity (every relationship must
   name two datasets that exist in the same model, with columns on each side)
   plus Ossie's structural minimums (a model needs ≥1 dataset, every dataset a
   `source`, every field and metric an expression). It reports *every* problem
   it finds, so one regeneration surfaces every broken join at once rather than
   one per attempt.
2. **`OssieYamlReader` in strict-version mode** — the emitted YAML is parsed
   back by the same library that will later serve it. Catches a writer/reader
   skew the structural check can't see.

A model that fails either is **not written**. An empty Ossie document committed
to the repository is a silent data-loss outcome; a failed job is a visible one.

## Regeneration

Re-running against a warehouse that already has a generated model reconciles
the fresh draft against the baseline sidecar and reports a delta
(`newElementCount` / `unchangedElementCount` / `removedUpstreamElementCount`),
which the rationale renders as a "Changes since the previous generation"
section.

**Regeneration never deletes.** Elements the warehouse dropped upstream are
reported as `REMOVED_UPSTREAM` and left in the emitted model — silently
dropping a dimension an analyst already built a dashboard on is not a decision
a generator should make.

## Security notes

- **PII.** Column samples are stripped by `PiiFilter` before *every* provider
  call, and a separate egress guard (`ai.llm.egress`, fail-closed) can withhold
  all sample values entirely. The rationale names the columns that were
  withheld, so an operator can confirm what did not leave the building. The
  deny list lives in `PiiFilter` and is shared with the rationale via
  `PiiFilter.isPiiColumn` rather than being re-declared.
- **Path traversal.** `modelName` arrives from a REST body. It's sanitised to a
  single path segment with no separators and no dot-runs, so `../../webapps/ROOT`
  cannot write outside `/datasources/` — which, in a WAR layout, is the deployed
  webapp.
- **Authorisation.** Admin-gated at the Spring Security chain *and* inline in
  the resource.

## Not in scope here

- **UI.** The admin-datasources "Generate Ossie model" button and the diff/apply
  modal are follow-up work; this issue delivers the backend and its contract.
- **Other LLM providers.** `LlmProviderFactory` currently resolves `noop` and
  `anthropic`. Bedrock and Azure land with the BYOLLM adapter work (#1431);
  `LlmProvider` is the seam they plug into.
- **Schema validation against `osi-schema.json` in production.** That resource
  lives in the test classpath; the strict read-back plus the structural
  validator cover the same ground for generated output. `OssieYamlWriterTest`
  remains the JSON-Schema conformance check.

## Configuration

Enrichment is opt-in and config-driven, same as the interactive generator:

```properties
saiku.schemagen.llm.provider=anthropic
saiku.schemagen.llm.anthropic.apiKey=…
# or export ANTHROPIC_API_KEY
```

With no provider configured the pipeline runs the offline rule-based
enricher and sets `degraded=false` in the job — everything still generates,
nothing goes to a vendor.
