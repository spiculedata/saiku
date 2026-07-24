# saiku-proptest

Property-based tests for Saiku, using [Hegel](https://hegel.dev) (Hypothesis for
Java, with automatic shrinking to minimal counterexamples).

Instead of hand-picked example inputs, each test states a **property** that must
hold for *all* inputs; Hegel generates inputs to try to falsify it and, on a
failure, shrinks to the smallest input that still breaks it.

## Why this is its own module

Hegel requires **JDK 22+** (Foreign Function & Memory API + a bundled native
engine). Saiku's main code still targets `release 21`, so quarantining the
Java-22 requirement in one test-only module keeps the shipped runtime on 21
while letting the build toolchain move to 22. This module has **no main sources
and is never published**.

## Running

The whole reactor now builds on JDK 22, so `mvn verify` runs these in CI. To run
just this module locally:

```bash
# JAVA_HOME must point at a JDK 22+.
mvn -pl saiku-core/saiku-web,saiku-core/saiku-service -am -DskipTests install   # once, to stage deps
mvn -pl saiku-proptest test
```

If you don't have JDK 22, exclude the module: `mvn -pl '!saiku-proptest' verify`.

## What's tested

| Test | Property |
|------|----------|
| `JdbcUrlValidatorPropertyTest` | Every dangerous H2 URL (`INIT=RUNSCRIPT`, `CREATE ALIAS/TRIGGER`, `SHUTDOWN`) is rejected — even nested in a `jdbc:mondrian:Jdbc=…` wrapper — and benign URLs are never rejected. |
| `CryptoUtilPropertyTest` | `decrypt(encrypt(s)) == s` for every string `s`. |
| `DataSourceMapperRoundTripPropertyTest` | An Ossie datasource survives `DataSourceMapper → SaikuDatasource → DataSourceMapper` with its identifying fields intact (the server-side wire contract behind saiku#1529). |
| `KAnonymityFilterPropertyTest` | The k-anonymity gate masks exactly the disclosive region: known counts in `[1, k)` are suppressed, `k` and above are not (inclusive boundary), and unknown counts (`<= 0`) never are. |
| `KAnonymityFilterMatrixPropertyTest` | The real egress guarantee: after `applyToMatrix`, no row with a known sub-`k` count keeps an unmasked measure value, and rows at/above `k` are left untouched. |
| `CsvExporterPropertyTest` | CSV formula-injection: any value starting with `= + - @`/tab/CR (and not a number) is quote-prefixed; safe values are never altered; output is only ever the input or `'`+input. |
| `MdxParameterSubstitutorPropertyTest` | MDX injection: values with MDX-meta chars are rejected, and safe values are substituted literally (never re-interpreted as regex replacements like `$0`). |
| `TotalAggregatorPropertyTest` | Aggregation invariants: SUM is order-independent with identity `0`, MIN/MAX match `Collections.min/max` and bound every element. |
| `SaikuUniqueNameComparatorPropertyTest` | The `Comparator` contract: antisymmetry, transitivity, and consistency (`compare==0` iff unique names equal). |
| `AgentSkillParserPropertyTest` | Agent-skill frontmatter: valid skills round-trip, and any input is a total function — either a skill or a `ParseException`, never another throwable. |
| `AgentSpaceParserPropertyTest` | Agent-space JSON: total-function robustness — any string parses to a space or a `ParseException`, never an unstructured crash. |
| `DrillthroughUtilsPropertyTest` | `extractResultInfo` preserves token count, strips all `[ ]` brackets, and routes `Measures.*` to measure results. |
| `TimeCalcParserPropertyTest` | `extractCatalogUrl` round-trips the `Catalog=` value out of a connection string; absence → null. |
| `FormatUtilPropertyTest` | `getFormatString` is identity for unknown tokens and maps known tokens to their fixed translations. |
| `PgTypePropertyTest` | `PgType.fromJdbcType` is total (every `int` → a known OID, never throws), deterministic, and falls back to `TEXT`. |

`CryptoUtilPropertyTest` also asserts new saves are always AES-GCM `v2:` format and are nonce-unique (two encryptions differ) yet both decrypt back.

**40 properties across 15 classes.**

## Adding a property test

```java
import static dev.hegel.Generators.*;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;

class ThingTest {
  @HegelTest
  void someInvariant(TestCase tc) {
    var input = tc.draw(integers(), "n");   // label shows up in the shrunk counterexample
    // assert something that must hold for ALL input
  }
}
```

Good Saiku targets are **pure functions with clear invariants**: validators
(reject/accept), round-trips (encode/decode, serialise/deserialise — e.g. the
`DataSourceMapper` wire mapping), idempotent operations, and ordering/aggregation
utilities. See the [Hegel Javadoc](https://javadoc.io/doc/dev.hegel/hegel) for the
full generator, combinator, and control-function API.
