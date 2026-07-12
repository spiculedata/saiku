# Agent-eval framework — YAML ground-truth harness (`saiku-home/evals/`)

Ships in saiku v4.7 as [saiku#1424](https://github.com/spiculedata/saiku/issues/1424).

The eval framework runs a suite of ground-truth cases against the ask
surface and reports where the LLM diverged from expectations. Every
case is a **YAML file** committed alongside the semantic model. CI
runs the suite and fails the build when a regression lands — the same
review discipline the semantic layer itself gets.

Deliberate design: **no LLM-as-judge.** Cube's Evals bills its harness
as "deterministic result-set diff" for a reason — LLM-as-judge is
expensive, non-reproducible, and lets subtle wrong answers slip
through. Saiku's runner does the same job the hard way: direct value
comparison with numeric tolerance and explicit key normalisation.

## On-disk shape

Suites live as YAML files under `saiku-home/evals/`. One suite per
file. Each suite targets one cube; every case in the suite runs
against it.

```yaml title="saiku-home/evals/foodmart-sales.eval.yaml"
name: foodmart-sales-evals
description: Ground-truth cases for the FoodMart Sales cube
cube:
  connectionName: unknown_foodmart
  catalog: FoodMart
  schema: FoodMart
  cubeName: Sales

cases:
  # QUERY case — expected rows compared with tolerance.
  - name: sales-by-country
    question: show me store sales by country
    expectedIntent: QUERY
    expectedRows:
      - {country: "USA",    storeSales: 565238.13}
      - {country: "Canada", storeSales:  79063.11}
      - {country: "Mexico", storeSales:  51298.13}
    tolerance:
      relative: 0.001    # 0.1% relative — masks sub-cent warehouse drift
    orderMatters: true

  # INSIGHT case — insight markdown must contain each string.
  - name: trend-analysis
    question: are sales trending up week-on-week?
    expectedIntent: INSIGHT
    expectedInsightContains:
      - Store Sales
      - week-on-week

  # REFUSED case — model must decline off-topic with a matching reason.
  - name: refuse-off-topic
    question: what's the weather in Paris?
    expectedIntent: REFUSED
    expectedRefusalContains: cube

  # Multi-turn case — history seeds the ask.
  - name: follow-up-turn
    question: now break it down by state
    history:
      - {role: user,      content: show store sales by country}
      - {role: assistant, content: 3 rows returned.}
    expectedIntent: QUERY
    expectedRows:
      - {state: "CA", storeSales: 214893.10}
      - {state: "OR", storeSales: 108737.55}
```

## Fields

### Suite-level

| Field         | Required | Type              | Notes                                                            |
|---------------|----------|-------------------|------------------------------------------------------------------|
| `name`        | yes      | string            | Displayed in the report; used as the CI job's summary identifier |
| `description` | no       | string            | Free-text shown in the report header                             |
| `cube`        | yes      | object            | Cube ref every case in the suite targets                         |
| `cases`       | yes      | array             | Non-empty list of ground-truth cases                             |

### Case-level

| Field                        | Required | Type              | Notes                                                                                            |
|------------------------------|----------|-------------------|--------------------------------------------------------------------------------------------------|
| `name`                       | yes      | string            | Unique within the suite. Used in the mismatch path.                                              |
| `question`                   | yes      | string            | Natural-language ask fed to `POST /ai/ask`                                                       |
| `history`                    | no       | array of `{role, content}` | Prior turns to seed the ask. Empty for single-shot cases.                                    |
| `expectedIntent`             | no       | string            | `QUERY` \| `INSIGHT` \| `VIEW_CHANGE` \| `REFUSED` (case-insensitive)                             |
| `expectedRefusalContains`    | no       | string            | Substring the refusal reason must contain. Only meaningful with `expectedIntent: REFUSED`.       |
| `expectedRows`               | no       | array of maps     | Expected result rows for `QUERY`, as **frozen literals**. See [Row comparison](#row-comparison). |
| `referenceQuery`             | no       | object (AiQueryRequest) | A trusted query whose **live** result-set is the ground truth. Drift-proof. See [Reference-query ground truth](#reference-query-ground-truth). Takes precedence over `expectedRows`. |
| `orderMatters`               | no       | boolean           | Defaults `true`. When `false`, both sides sort by keys before diff.                              |
| `expectedInsightContains`    | no       | array of strings  | Substrings the insight markdown must contain. Substring match, not exact.                        |
| `tolerance`                  | no       | `{absolute, relative}` | Numeric tolerance for row comparisons. Both default to `0.0` (exact match).                     |

## Reference-query ground truth

`expectedRows` freezes the answer as literal numbers — which is fine for a
**static demo cube**, but wrong the moment you point a suite at a customer's
**live, evolving warehouse**: yesterday's `565238.13` is today's different
number, and every case false-fails on data drift rather than on a real
regression.

`referenceQuery` fixes this. Instead of a frozen number, you author the
**trusted way to answer the question** — a normal AI Query API request
(measures / rows / columns / filters). At run time the harness executes *both*
the NL-generated query **and** the reference query against the same cube at the
same moment, and diffs the two result-sets:

```yaml
- name: store-sales-by-country
  question: Show store sales broken down by country.
  expectedIntent: QUERY
  referenceQuery:
    measures:
      - {name: Store Sales}
    rows:
      - {dimension: Store, hierarchy: Store, level: Store Country}
  tolerance: { relative: 0.001 }
  orderMatters: false
```

This is what makes the harness a **live-accuracy monitor**, not just a
build-time regression gate: the case asks "does the NL query the model
*generated* return the same answer as the query a human *trusts*?" — which stays
a meaningful question as the data changes underneath it. The `cube` is optional
in the block (the suite's cube is used); the reference query runs through the
exact same convert → execute → flatten pipeline as the NL answer, so the two are
compared on identical footing.

- **Precedence:** when both `referenceQuery` and `expectedRows` are set, the
  reference query wins.
- **Authoring errors are distinct:** a reference query that won't execute is
  reported on its own `referenceQuery` mismatch path (a suite bug), never
  conflated with the model producing a wrong answer.
- **Same tolerance + `orderMatters`** apply to the reference diff.

## Row comparison

### Key normalisation

Column keys compare case-insensitively with whitespace, underscores, and
hyphens stripped:

- `Store Sales`
- `storeSales`
- `store_sales`
- `STORE-SALES`

All normalise to the same key. Prevents a rename in the schema-projector's
output shape from failing every eval that references the column.

### Numeric parsing

Expected values that parse as numbers are compared numerically with
tolerance. The parser is forgiving about how eval authors write numbers:

- `500.0`, `500`, `500.00` — all parse as 500
- `"$565,238.13"` — currency prefixes and thousands separators stripped
- `"(500)"` — parenthesised negatives → -500
- `"12%"` — trailing % stripped; compared as 12

Non-numeric expected values are compared as strings (whitespace-trimmed
on both sides).

### Tolerance

```yaml
tolerance:
  absolute: 0.01     # cell passes if |actual - expected| <= 0.01
  relative: 0.001    # cell passes if |actual - expected| / |expected| <= 0.001
```

A cell passes if **either** tolerance is satisfied — set both when you
want "5 cents absolute OR 0.1% relative, whichever is looser".

Absolute and relative both default to zero (exact match).

### Missing keys are asymmetric

- An expected key not present in the actual row is a **mismatch** — the
  operator authored an expectation that the schema doesn't produce.
- An actual key **not** in the expectation is **NOT** — expectations are
  additive so growing the schema with a new column doesn't break every
  eval.

## Reports

The runner emits an {@link EvalReport} carrying one outcome per case
plus a one-line CI summary:

```
Suite: foodmart-sales-evals
Description: Ground-truth cases for the FoodMart Sales cube
9/10 passed, 1 failed, 0 degraded, 0 skipped (elapsed 12483ms)

PASS: sales-by-country (854ms, intent=QUERY, model=claude-sonnet-4-6)
PASS: trend-analysis (742ms, intent=INSIGHT, model=claude-sonnet-4-6)
PASS: refuse-off-topic (312ms, intent=REFUSED, model=claude-sonnet-4-6)
FAIL: top-3-product-families (1024ms, intent=QUERY, model=claude-sonnet-4-6)
  rows[0].productFamily: expected "Food", got "Drink"
  rows[1].productFamily: expected "Non-Consumable", got "Food"
  rows[2].productFamily: expected "Drink", got "Non-Consumable"
```

Rendered via [`EvalReportWriter.toText`](../saiku-core/saiku-service/src/main/java/org/saiku/service/olap/ai/eval/EvalReportWriter.java).
Also available as pretty-printed JSON via `toJson` for CI archives.

## Error codes

Structured YAML-parse failures surface with a stable code so CI can
distinguish "author wrote a broken YAML" from "the ask surface produced
a wrong answer":

| Code                | When                                                                     |
|---------------------|--------------------------------------------------------------------------|
| `MALFORMED_YAML`    | The YAML parser rejected the file.                                       |
| `MISSING_FIELD`     | A required field is absent (`name`, `cube`, `cases`, `question`).        |
| `BLANK_FIELD`       | A required string field is present but empty.                            |
| `TYPE_MISMATCH`     | A field has the wrong type (e.g. `cases` is a mapping, not an array).    |
| `INVALID_TOLERANCE` | `tolerance.absolute` or `tolerance.relative` is negative.                |

## Non-goals for v1

- **REST endpoint** to trigger runs — the runner is programmatic in v1.
  CLI + REST wrappers are a follow-up.
- **`saiku eval` CLI subcommand** — deferred.
- **Recording adapter** — an adapter that writes each live response to
  a fixture file so the fixture adapter can replay deterministically
  without spending LLM budget. Design ready; implementation deferred.
- **Cross-run comparison** — "is today's eval worse than yesterday's?"
  Requires report archiving. Follow-up.
- **Structural diff on the emitted AiQueryRequest** (as opposed to the
  executed row-set) — multiple structurally-different requests can
  produce the same rows, so row-diff is a better ground truth.
- **Matrix-mode + measures-only cellset extraction in the live adapter.**
  The current flattening handles the records-with-row-headers shape
  every real query produces; two edge cases from the resource's
  `buildResponse` (matrix mode; single-row measures-only queries) are
  deliberately skipped as v1 scope. Follow-up if a real suite hits
  either shape.

## CI integration

The runner is a plain Java class. The production adapter that plugs
into `AiAskService` + query execution ships as
[`LiveEvalAskAdapter`](../saiku-core/saiku-service/src/main/java/org/saiku/service/olap/ai/eval/LiveEvalAskAdapter.java):

```java
import org.saiku.service.olap.ai.eval.*;

EvalSuite suite = EvalYamlReader.read(new FileReader("saiku-home/evals/foodmart-sales.eval.yaml"),
    "foodmart-sales.eval.yaml");

// Production adapter — call AiAskService, execute any produced query, flatten to records.
EvalAskAdapter live = new LiveEvalAskAdapter(
    askService,          // org.saiku.service.olap.ai.ask.AiAskService
    metadataService,     // org.saiku.service.olap.ai.AiCubeMetadataService
    converter,           // org.saiku.service.olap.ai.AiSchemaConverter
    thinQueryService);   // org.saiku.service.olap.ThinQueryService

EvalReport report = new AgentEvalRunner(live).run(suite);

System.out.println(EvalReportWriter.toText(report));
if (!report.allPassed()) {
  System.exit(1); // fail the CI job on any FAIL or DEGRADED outcome
}
```

The adapter routes each intent to the right output:

| Intent | What lands in `EvalAskResult`                                                                     |
|-------|-----------------------------------------------------------------------------------------------------|
| QUERY | Ask → convert → execute → flatten cellset to `List<Map<String, Object>>` for the row comparator.    |
| INSIGHT | Ask outcome's `insight.markdown` as-is for substring match.                                        |
| VIEW_CHANGE | Ask outcome's `viewChange.viewMode`.                                                            |
| REFUSED | `OFF_TOPIC:` reason mapped back to `EvalAskResult.forRefusal(...)`.                                 |
| DEGRADED | Provider failures + adapter-thrown exceptions surface as degraded outcomes; the run continues.     |

The cellset → records flattening deliberately skips the resource's
matrix mode + measures-only special case + k-anonymity suppression —
the eval framework wants the raw ground truth, and k-anonymity is a
response-shaping concern for user-facing responses, not eval CI.

A GitHub Actions workflow that runs on PR:

```yaml title=".github/workflows/agent-evals.yml"
name: agent-evals
on: [pull_request]
jobs:
  evals:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }
      - name: Run eval suite
        env:
          ANTHROPIC_API_KEY: ${{ secrets.ANTHROPIC_API_KEY }}
        run: |
          # Boot the launcher, run the eval suite, fail the job on any regression.
          ./scripts/run-evals.sh saiku-home/evals/
```
