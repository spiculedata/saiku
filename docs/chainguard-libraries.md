# Chainguard Libraries for release builds (saiku#2463)

Tagged releases take their **Maven** dependencies from
[Chainguard Libraries](https://www.chainguard.dev/docs/chainguard/libraries/introduction/overview/)
first (organisation `concepttocloud.com`, Starter plan), falling back to Maven Central for anything
Chainguard has not built. Chainguard rebuilds packages from source, so the artifacts it serves are
source-built rather than the upstream binaries. npm in release builds is **not** routed through
Chainguard (see below). Day-to-day CI and Dependabot are unchanged.

## What is protected now

Only the Maven artifacts Chainguard has itself built (about 65% of the POMs in our tree, measured
2026-11-21). The rest still come from Maven Central exactly as before. Fallback is **client-side**
because the Starter plan cannot enable Chainguard's server-side upstream policy
(`chainguard_and_upstream`; `chainctl libraries entitlements create` is denied), so the JAVA and
JAVASCRIPT entitlements stay `CHAINGUARD` (Chainguard-built only). That also means no malware gate
or cooldown applies to the fallback artifacts: they are Central artifacts.

| Build | Now |
| --- | --- |
| `release.yml` `jar` job | Maven: Chainguard (`https://libraries.cgr.dev/java/`) added as a repository **and** pluginRepository in an always-active settings profile, so it is consulted first; Central, the GH Packages forks and the root pom's own repos stay reachable after it. npm (saiku-ui via frontend-maven-plugin): unchanged, npmjs.org |
| `release.yml` `npm-embed-build` job | Unchanged, npmjs.org with the committed lockfile. npm has no per-package fallback: a registry that does not serve a package 404s, and 261 of 560 saiku-ui packages are not Chainguard-built |
| `chainguard-coverage.yml` (weekly + manual) | Resolves both trees through Chainguard **only** (Maven mirror, no fallback; npm registry), so it keeps measuring real coverage |
| `ci.yml`, Dependabot, every other workflow | Unchanged |

Why release-only: the 7-day cooldown and a quota (Starter: 50,000 pulls a month) are a poor fit for
every PR build, while what we *ship* is where provenance matters. The weekly check keeps us
honest about coverage between releases.

Not covered, by design: the five GitHub Packages repos (`mondrian-saiku`, `olap4j`,
`olap4j-xmlaserver`, `saiku-query`, `ossie`), the in-tree `lib/repo`, the `apache` and `atlassian`
repositories declared in the root pom, the Node.js tarball frontend-maven-plugin downloads from
nodejs.org, the Maven plugins' own downloads of tools outside Maven (none today), and container
images (see the Dockerfile).

## Credentials

Pull tokens are **per ecosystem**, and are not interchangeable with the container pull token
(`CHAINGUARD_IDENTITY_ID` / `CHAINGUARD_TOKEN`, created with `--repository=oci`): each token gets
one role binding per repository type. Four repository secrets:

| Secret | Value |
| --- | --- |
| `CHAINGUARD_JAVA_IDENTITY_ID` / `CHAINGUARD_JAVA_TOKEN` | Username / password of a `java` pull token |
| `CHAINGUARD_JS_IDENTITY_ID` / `CHAINGUARD_JS_TOKEN` | Username / password of a `javascript` pull token |

Create them (needs the `libraries.<ecosystem>.pull_token_creator` or `owner` role):

```bash
chainctl auth pull-token create --repository=java       --parent=concepttocloud.com --ttl=720h --name=saiku-release-java
chainctl auth pull-token create --repository=javascript --parent=concepttocloud.com --ttl=720h --name=saiku-release-js
```

`Username` is the identity id, `Password` is the token. Starter tokens last at most 30 days, so
**rotate monthly**: re-run the commands and update the four secrets. An expired token shows up as
a 401 in the weekly coverage run (it fails with an auth error rather than a gap list), which is
the early warning before a release trips on it. `chainctl auth pull-token list --repository=java`
shows expiry.

## Fallback policy and how to read a release log

For Maven, a missing Chainguard artifact is not an error: Maven moves on to the next repository
and ends at Central. The order in the log proves it. For a fresh artifact you will see
`Downloading from chainguard-java: ...` first, then (only if that 404s) `project-lib`, `apache`,
`atlassian`, the `github-*` repos and `central`. To see which source served what, the release `jar` job ends with a "Maven artifact sources"
step (`scripts/chainguard/report-maven-sources.sh`). It reads the `_remote.repositories` file Maven
writes next to each cached artifact, so it works even though the build runs with `-ntp`, and prints a
table in the job summary: `chainguard-java` = Chainguard-built, `central` = fell back to Maven
Central. In a non-`-ntp` run (locally, or the coverage workflow) the same is visible as
`Downloaded from chainguard-java:` / `Downloaded from central:` lines:

```bash
grep -o 'Downloaded from [a-z0-9-]*:' mvn.log | sort | uniq -c
```

The weekly coverage run gives the authoritative list of what Chainguard does not serve.

What is **not** covered: npm in release builds; the five GitHub Packages forks, the in-tree
`lib/repo`, and the `apache`/`atlassian` repos in the root pom; the Node.js tarball downloaded by
frontend-maven-plugin; container images (see the Dockerfile).

### Switching to a Chainguard-mirrored Central (later)

If Chainguard enables `chainguard_and_upstream` for the org (which also brings the 7-day cooldown
and malware gate), change `scripts/chainguard/write-maven-settings.sh` to the mirror form, which
is already implemented for the coverage workflow: set `CHAINGUARD_ONLY=true` in the release
`Configure Maven settings.xml` step (a `mirrorOf` = `central` mirror, no client-side fallback). The
same entitlement change would let npm use the registry too; the `chainguard-npm` composite action
and the lockfile-hash step are ready for that.

## The 7-day cooldown and emergency security releases

The cooldown only applies when server-side upstream fallback is on, which it is not today, so
today a just-published upstream version is simply not on Chainguard and Maven takes it from
Central. If upstream fallback is enabled later (Starter: fixed 7-day cooldown, no per-package
overrides), a dependency version younger than 7-8 days would fail the build, and an emergency fix
needing it would have to wait or use the bypass.

### Emergency bypass

Run **release** from the Actions tab (`workflow_dispatch`), enter the tag, and tick
**`skip_chainguard`**: Maven then resolves straight from Maven Central, with a `::warning::` in the
log. A tag push can never bypass. The tag must still be on `main` (the `guard` job is unaffected).

## Caching

The release `jar` job deliberately does **not** use `cache: maven`. `ci.yml` fills that cache from
Central, and a restored cache would satisfy the build without consulting Chainguard first. The
coverage workflow starts from an empty local repository and npm cache for the same reason.

## npm lockfile hashes

Chainguard rebuilds npm packages, so their tarball hashes differ from the npmjs hashes in
`saiku-ui/package-lock.json` and plain `npm ci` fails with `EINTEGRITY`. The
`.github/actions/chainguard-npm` composite action (used by the **coverage workflow only**) therefore runs
`chainctl libraries update-hashes` on the **runner's copy** of the lockfile, which *appends* the
Chainguard hash beside the upstream one (npm accepts either). The committed lockfile is never
changed, and a temporary `.npmrc` outside the repository (path in `NPM_CONFIG_USERCONFIG`) carries
the credentials, so nothing sensitive is committed.

## Weekly coverage check

`.github/workflows/chainguard-coverage.yml` (Mondays 05:43 UTC, or run it by hand) resolves, Chainguard only with no fallback,
`mvn -pl '!saiku-proptest' dependency:go-offline` and `npm ci --ignore-scripts` for saiku-ui into
empty caches, Chainguard only, and writes a job summary: counts, the artifacts Chainguard could not
serve, and an estimated pull count. Measured on 2026-11-21 a clean run fetches about 3,400 Maven files
(POMs and JARs) and about 1,100 npm requests, so up to roughly 4,500 pulls a run and 20,000 a month
at weekly cadence if every file counts as a pull (a release adds about the same again). That is
the worst case against the 50,000 a month Starter allowance; if the quota gets tight, make the
cron fortnightly. It follows
`quality-report.yml`: gaps are reported, the run stays green. It fails only on authentication,
entitlement or configuration errors (401/403 from `libraries.cgr.dev`, a missing secret, or a
failure that is not a missing package).

## Reproducing locally

```bash
# add CHAINGUARD_ONLY=true to reproduce the coverage (no fallback) mode
SETTINGS_FILE=/tmp/cg-settings.xml GH_PACKAGES_USER=<gh user> GH_PACKAGES_TOKEN=<classic PAT> \
CHAINGUARD_JAVA_IDENTITY_ID=<id> CHAINGUARD_JAVA_TOKEN=<token> scripts/chainguard/write-maven-settings.sh
mvn -s /tmp/cg-settings.xml -Dmaven.repo.local=/tmp/cg-m2 -pl '!saiku-proptest' dependency:go-offline
```

Verify an artifact really came from Chainguard with
`chainctl libraries verify <path-to-jar>`.
