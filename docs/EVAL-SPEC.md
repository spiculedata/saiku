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
| `expectedRows`               | no       | array of maps     | Expected result rows for `QUERY`. See [Row comparison](#row-comparison) below.                   |
| `orderMatters`               | no       | boolean           | Defaults `true`. When `false`, both sides sort by keys before diff.                              |
| `expectedInsightContains`    | no       | array of strings  | Substrings the insight markdown must contain. Substring match, not exact.                        |
| `tolerance`                  | no       | `{absolute, relative}` | Numeric tolerance for row comparisons. Both default to `0.0` (exact match).                     |

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

## CI integration

The runner is a plain Java class:

```java
import org.saiku.service.olap.ai.eval.*;

EvalSuite suite = EvalYamlReader.read(new FileReader("saiku-home/evals/foodmart-sales.eval.yaml"),
    "foodmart-sales.eval.yaml");
EvalAskAdapter live = new LiveAskAdapter(askService, thinQueryService); // wire your ask + execute
EvalReport report = new AgentEvalRunner(live).run(suite);

System.out.println(EvalReportWriter.toText(report));
if (!report.allPassed()) {
  System.exit(1); // fail the CI job on any FAIL or DEGRADED outcome
}
```

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
