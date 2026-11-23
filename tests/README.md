# Tests

Saiku's tests live next to the code they cover, one suite per module, rather than in this
directory. This page maps where each suite lives and how to run it. For counts, CI gates, and
conventions, see [`TESTING.md`](../TESTING.md), which is the source of truth.

| Suite | Location | Framework | Run |
|---|---|---|---|
| Service unit tests | `saiku-core/saiku-service/src/test` | JUnit (surefire) | `mvn -pl saiku-core/saiku-service -am test` |
| REST resource tests | `saiku-core/saiku-web/src/test` | JUnit (surefire) | `mvn -pl saiku-core/saiku-web -am test` |
| Semantic layer | `saiku-core/saiku-semantic/src/test` | JUnit (surefire) | `mvn -pl saiku-core/saiku-semantic -am test` |
| SQL layer | `saiku-core/saiku-sql/src/test` | JUnit (surefire) | `mvn -pl saiku-core/saiku-sql -am test` |
| End-to-end integration (`*IT`) | `saiku-launcher/src/test` | JUnit (failsafe), in-process Jetty | `mvn -pl saiku-launcher -am -DskipITs=false verify` |
| Property-based tests | `saiku-proptest/src/test` | Hegel (needs JDK 22) | `mvn -pl saiku-proptest -am test` |
| UI unit tests | `saiku-ui/src/**/*.test.ts` | Vitest | `cd saiku-ui && npm test` |
| UI end-to-end (mocked) | `saiku-ui/e2e` | Playwright | `cd saiku-ui && npm run e2e` |

Everything CI runs on the Java side:

```bash
mvn -B -ntp -DskipITs=false verify
```

Add a test inside the module it covers, not here. `.github/test-floors.json` fails CI if a
module's test count drops below its floor.
