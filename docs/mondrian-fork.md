# Mondrian (Spicule fork) + Calcite backend

Saiku runs on the Spicule fork of Mondrian, which ships a Calcite-based SQL
generation backend alongside the legacy code path. The Calcite backend is the
default in the fork and therefore the default in Saiku.

## Source + install

- Upstream: https://github.com/spiculedata/mondrian
- Local checkout: `~/Projects/mondrian` (same pattern as `olap4j` and
  `olap4j-xmlaserver`).
- Install into the local Maven repo:

  ```
  cd ~/Projects/mondrian
  mvn -DskipTests clean install
  ```

  This produces `pentaho:mondrian:4.8.1.0-SAIKU-jakarta`, which is the GAV Saiku
  depends on. The artifact is not published to a remote repo, so the local
  install is required before building Saiku.

## Backend selection

- Default: `calcite` (set by the fork, no Saiku config required).
- Force legacy: `-Dmondrian.backend=legacy`.

The legacy backend is still useful for databases where the Calcite dialect
mapping is incomplete. Current Calcite dialects shipped by the fork:

- HSQLDB
- PostgreSQL

Extending `mondrian.calcite.CalciteDialectMap` is the path for additional
dialect coverage (e.g. H2, MySQL, Oracle).

## Classpath impact

Un-excluding Calcite from `saiku-webapp/pom.xml` means the runtime classpath
now carries:

- `org.apache.calcite:calcite-core:1.41.0`
- `org.apache.calcite:calcite-linq4j:1.41.0`
- `org.apache.calcite.avatica:avatica-core:1.27.0`
- `org.apache.calcite.avatica:avatica-metrics:1.27.0`
- `com.google.guava:guava:33.4.8-jre` (pinned; Calcite requires a newer API
  than Mondrian's historical Guava 18.0).
- `eigenbase:eigenbase-{properties,xom,resgen}` pinned to the versions
  available in Maven Central (Calcite's declared 1.1.4 of eigenbase-properties
  is not published; mediation picks the older version but the descriptor POM
  still has to resolve).

## Tests

Saiku's test suite uses an H2-backed FoodMart fixture. H2 isn't a supported
Calcite dialect, so `saiku-web` surefire forces
`-Dmondrian.backend=legacy` for the test JVM. Production still runs with the
Calcite default. If you add a dialect to the fork, drop the opt-out from
`saiku-core/saiku-web/pom.xml`.

## Ops notes

- Fat-jar: `mvn -pl saiku-launcher -am clean package -DskipTests` (after the
  Mondrian fork is installed).
- Fat-jar verification:

  ```
  unzip -p saiku-launcher/target/saiku-*.jar webapp/saiku.war | \
    unzip -l /dev/stdin | grep -iE 'calcite|guava'
  ```

  Expect `calcite-core`, `calcite-linq4j`, `guava-33.4.8-jre`.

- JDBC `ServiceLoader`: Calcite ships `META-INF/services/java.sql.Driver`
  inside `calcite-core`. Nothing in Saiku autowires it; if you ever see a
  stray Calcite JDBC URL resolved in place of H2, prefer explicit
  `Class.forName(...)` registration.
