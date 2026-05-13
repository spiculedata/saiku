# Mondrian (Spicule fork) + Calcite backend

Saiku runs on the Spicule fork of Mondrian, which ships a Calcite-based SQL
generation backend alongside the legacy code path. The Calcite backend is the
default in the fork and therefore the default in Saiku.

## Source + install

- Upstream: https://github.com/spiculedata/mondrian-saiku
- Published artifact: `pentaho:mondrian:4.8.1.0` on GitHub Packages
  (`https://maven.pkg.github.com/spiculedata/mondrian-saiku`). The Saiku root
  pom registers this repo; you need a `<server id="github-mondrian-saiku">`
  entry in `~/.m2/settings.xml` with a GitHub username + PAT
  (scope: `read:packages`) for Maven to authenticate the download.
- Local builds against an unreleased Mondrian change: clone the repo and
  `mvn -DskipTests clean install` to populate `~/.m2`; Maven prefers the
  local cache over remote resolution.

## Backend selection

- Default: `calcite` (set by the fork, no Saiku config required).
- Force legacy: `-Dmondrian.backend=legacy`.

The legacy backend is still useful for databases where the Calcite dialect
mapping is incomplete. Current Calcite dialects shipped by the fork:

- H2
- HSQLDB
- Microsoft SQL Server
- MySQL / MariaDB
- Oracle
- PostgreSQL

Extending `mondrian.calcite.CalciteDialectMap` is the path for further
dialect coverage.

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
- `com.google.protobuf:protobuf-java:3.25.8` pinned. Avatica 1.27 needs
  protobuf 3.x (for `GeneratedMessageV3`); a transitive chain via
  `saiku-olap-util -> serenity-bdd -> operadriver` drags in 2.4.1, which
  would otherwise win Maven's nearest-wins mediation.

## Tests

Saiku's test suite uses an H2-backed FoodMart fixture and now runs against
the Calcite backend (H2 is mapped in `CalciteDialectMap`). No backend opt-out
is required.

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
