# Testing

The test suite and how CI gates it. Read this before invasive changes — `mvn verify` is the
contract every PR must keep green.

## How to run

```bash
mvn verify                                    # compile + unit tests + spotless:check (the CI gate)
mvn -B -ntp -DskipITs=false verify            # what CI runs — also executes the saiku-launcher ITs
mvn spotless:apply                            # auto-format Java (Palantir Java Format) before committing
./scripts/install-hooks.sh                    # one-time: pre-commit hook runs spotless:check on staged .java
```

Targeting a module or a single class:

```bash
mvn -pl saiku-core/saiku-service -am test                              # one module's unit tests
mvn -pl saiku-core/saiku-service -am test -Dtest=AiSchemaConverterTest  # a single class
mvn -P integration verify                                              # run the saiku-launcher ITs locally
```

> **`-am` + `-Dtest` gotcha:** with `-am`, upstream modules that have no class matching `-Dtest`
> fail surefire with *"No tests matching pattern"*. Add `-Dsurefire.failIfNoSpecifiedTests=false`.
> (PowerShell: quote `-D` args, e.g. `"-Dtest=Foo"`.)

`mvn` needs `~/.m2/settings.xml` with a GitHub PAT (`read:packages`) for the four Spicule
GitHub-Packages artifacts (`mondrian-saiku`, `olap4j`, `olap4j-xmlaserver`, `saiku-query`) — see
`CLAUDE.md`. Locally pass `-s "<settings.xml>"` if it isn't at the default path.

### UI end-to-end (Playwright)

`saiku-ui/e2e/` holds the browser suite (`saiku-ui/playwright.config.ts`). The mocked-backend
specs intercept `/rest/saiku/*` and need no Java; CI's `ui` job runs them after the production
build (saiku#2009). `*.live.spec.ts` specs need a running launcher and stay out of CI.

```bash
cd saiku-ui
npx playwright install chromium   # one-time
npm run e2e                       # mocked backend: builds, then serves on :4173
npm run e2e:live                  # live backend on :8080 (RUN_LIVE_E2E=1)
```

## Current coverage

Roughly **990 `@Test` methods across ~155 test classes** (a long way past the Phase-0 baseline of 10).

| Module | Test classes | ~`@Test` | What it covers |
|---|---|---|---|
| `saiku-core/saiku-service` | ~94 | ~609 | OLAP query/AI-query conversion + MDX-injection defense (`olap/ai/`), AI anomaly/forecast detectors (`olap/ai/anomaly`, `olap/ai/forecast`), schema generation/inference (`schema/generate/`), cellset formatters, cache (`SaikuQueryCache`, `QueryCacheKey`), repository/ACL/path-traversal, datasource/crypto, comments/history, CSV/Excel export hardening, `SecureXml`. |
| `saiku-core/saiku-web` | ~37 | ~268 | JAX-RS REST resources (`AiQueryResource`, `Query2Resource`, `DataSourceResource`, `AdminResource`, `InfoResource`, demo gate, error-leak), serialization, `JdbcUrlValidator`. |
| `saiku-core/saiku-semantic` | 1 | ~14 | YAML semantic layer. |
| `saiku-launcher` | ~22 (all `*IT`) | ~99 | End-to-end integration: boots an in-process Jetty via `SaikuItHarness.shared()` and exercises the live REST surface (`/ai/*`, query2, drillthrough, datasources, repository, exporter). Run by failsafe; **skipped unless** `-DskipITs=false` (CI) or `-P integration` (local). |
| `saiku-core/saiku-olap-util` | 0 | 0 | No tests (olap4j helpers). |

### Test categories & conventions

- **Framework:** JUnit 4 (`org.junit.Test`) throughout (one JUnit 5 file, `SemanticLayerTest`).
  Plain JUnit assertions with descriptive messages; no AssertJ/Hamcrest. **Mockito is not on the
  saiku-core test classpath** — tests use hand-rolled stubs (the `Mock*` helpers, anonymous
  subclasses) or a real in-memory H2 result for `ResultSet`-shaped inputs.
- **Naming:** prose (`validatorRejectsCrossjoinInjection`, `whenBudgetExceeded_oldestEntryEvicted`).
- **Golden tests** (`*GoldenTest`, e.g. `MdxEchoGoldenTest`, `SchemaInferrerGoldenTest`): compare
  generated output against committed fixtures under `src/test/resources/`. Regenerate with
  `-Dsaiku.goldens.update=true` / `-Dschemagen.updateGolden=true` — the test then **fails on purpose**
  so the diff is reviewed before commit.
- **Characterization tests** (`*CharacterizationTest`): pin full output state as a refactor safety-net.
- **Property tests** (`*PropertyTest`): hand-rolled generator, fixed seed, invariant checks (no jqwik).
- **Reusable fixtures:** `QuirksTestFixture`, `FoodmartTestFixture`, `Mock*` (repository/connection/session).

## CI

`.github/workflows/ci.yml` runs **`mvn -B -ntp -DskipITs=false verify`** on **Ubuntu and macOS**
against **JDK 21**. A PR is green only when, on both OSes:

1. Compile + **surefire unit tests** pass.
2. **failsafe integration tests** (`saiku-launcher/**/*IT`) pass (CI sets `-DskipITs=false`).
3. **Spotless** (`spotless-maven-plugin` 2.46.1 / Palantir Java Format) reports clean — bound to the
   `verify` phase, so it runs *after* tests; `mvn spotless:apply` fixes violations.
4. **Per-module test-count floors** (`.github/test-floors.json`) are met — CI fails if a module's
   surefire total drops below its floor (`saiku-core/saiku-service`: 252, `saiku-core/saiku-web`: 44),
   catching accidental test deletions. Bump the floor when you add tests.
5. **Per-module line-coverage floors** (`.coverage-thresholds.json`, saiku#2011) are met. JaCoCo
   (bound in the root pom) writes `<module>/target/site/jacoco/jacoco.csv` during `verify`;
   `./scripts/check-coverage.sh` compares each module's line coverage to its floor. Run it locally
   after `mvn verify`; the HTML report is `<module>/target/site/jacoco/index.html`. Floors are
   ratchets — raise one when coverage rises. Surefire `argLine`s must start with `@{argLine}` so the
   JaCoCo agent attaches; `-Djacoco.skip=true` skips instrumentation.

A separate `docker` workflow builds the launcher image and does **not** gate the Maven build.

## Quarantined / flaky tests

None are quarantined. Two sets fail **only on local Windows** (environment, not code) — **CI on
Linux/macOS is authoritative**, do not chase them locally:

- **`*GoldenTest`** (e.g. `MdxEchoGoldenTest`): goldens are LF; Windows CRLF checkouts produce
  line-ending-only diffs. A `.gitattributes eol=lf` on the golden resources would remove the noise.
- **`SaikuQueryCacheTest`**: a couple of cases assert timing/file-system behaviour that is flaky on a
  loaded or slow filesystem.

## Environment prerequisites

- JDK 21 (Temurin, Corretto, Zulu).
- Maven 3.9+.
- `~/.m2/settings.xml` with a GitHub PAT (`read:packages`) — required to resolve the four Spicule
  GitHub-Packages artifacts even though they are public. One PAT covers all four. Local forks of
  `mondrian-saiku` / `olap4j` / `olap4j-xmlaserver` / `saiku-query` in `~/.m2` are preferred over the
  remote.
- First build populates `~/.m2` (needs Maven Central + Apache + Atlassian + GitHub Packages).
