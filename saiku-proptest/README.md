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
