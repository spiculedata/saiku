# Testing

Phase 0's safety-net deliverables for `mvn verify` and the CI matrix. Start here before making invasive changes to the codebase.

## How to run

```bash
mvn verify
```

Runs compile + unit tests + Spotless check on the default reactor (`saiku-core`, `saiku-webapp`). Expects JDK 21 and Maven 3.9+. No network access required beyond Maven Central + Apache / Atlassian public repos.

To apply formatting before committing:

```bash
mvn spotless:apply
```

To install the pre-commit hook that runs `spotless:check` on staged Java files:

```bash
./scripts/install-hooks.sh
```

## Current coverage (baseline)

Existing unit tests in the repo at the time Phase 0 landed:

| Module | Test | What it covers |
|---|---|---|
| `saiku-core/saiku-service` | `NoReHashPasswordEncoderTest` | Password-encoder helper, 3 cases. |
| `saiku-core/saiku-service` | `RepositoryDatasourceManagerTest` | `RepositoryDatasourceManager` with `MockRepositoryManager` + `MockConnectionManager` + `MockHttpSession`, 7 cases. |

Total: **10 tests, all passing on JDK 21.**

## What is not covered

- **End-to-end MDX execution.** There is no live FoodMart + Mondrian + olap4j smoke test in the suite. Phase 0 drafted one (`MondrianFoodMartHarnessTest`) but the bundled HSQL FoodMart dump and the `FoodMart4.xml` schema declared in `util/` do not line up under the currently-installed `pentaho:mondrian:4.8.0.0-SAIKU` fork (`date_string` column missing in `time_by_day`, classic `FoodMart.xml` rejected by the Mondrian 4 loader). Resurrect in Phase 1 or Phase 3 with a schema/data pair known to match the Mondrian fork in use.
- **`OlapDiscoverService` integration test.** Gated on the MDX harness above.
- **Web layer (`saiku-core/saiku-web`).** REST resources have zero unit-test coverage. Phase 1's Spring Boot migration is a natural time to add slice tests.
- **Webapp (`saiku-webapp`).** No integration tests. Phase 1 should add at least a startup smoke test.
- **UI.** `saiku-ui` is excluded from the Phase 0 reactor; it will be rebuilt from scratch in Phase 4/6 with its own Playwright suite (see Task 0.8 in the implementation plan).
- **Multi-DB matrix.** Only HSQL is exercised via the legacy tests. Postgres / MySQL / SQL Server / DuckDB coverage lands in Phase 3 with the YAML semantic layer.

## Quarantined / flaky tests

None.

## Environment prerequisites

- JDK 21 (Temurin, Corretto, Zulu all fine).
- Maven 3.9+.
- First build populates `~/.m2` — requires internet access for Maven Central + Apache + Atlassian repos.
- Local forks of `olap4j`, `olap4j-xmlaserver`, `mondrian` (Spicule `4.8.0.0-SAIKU`), and `saiku-query` must be installed in `~/.m2`. See `docs/plans/2026-04-18-saiku-modernisation-design.md` for repo locations.

## CI

`.github/workflows/ci.yml` runs `mvn verify` + `spotless:check` on Linux and macOS against JDK 21. Pull requests must be green before merge.
