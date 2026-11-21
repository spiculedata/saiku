# Chainguard Libraries for release builds (saiku#2463)

Tagged releases resolve their **Maven Central** and **npmjs.org** dependencies through
[Chainguard Libraries](https://www.chainguard.dev/docs/chainguard/libraries/introduction/overview/)
(organisation `concepttocloud.com`, Starter plan). Chainguard rebuilds packages from source,
scans upstream packages for malware, and withholds brand-new upstream versions for a fixed
cooldown. Day-to-day CI and Dependabot are unchanged.

## What changes

| Build | Before | Now |
| --- | --- | --- |
| `release.yml` `jar` job (fat JAR, dist zip, war, UI via frontend-maven-plugin) | Maven Central + GH Packages | Maven Central **mirrored to `https://libraries.cgr.dev/java/`** (`mirrorOf` = `central` only); GH Packages repos unchanged. npm for saiku-ui through `https://libraries.cgr.dev/javascript/` |
| `release.yml` `npm-embed-build` job | npmjs.org | `https://libraries.cgr.dev/javascript/` |
| `chainguard-coverage.yml` (weekly + manual) | n/a | Resolves both trees through Chainguard only and writes a coverage report |
| `ci.yml`, Dependabot, every other workflow | unchanged | unchanged |

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

## Fallback policy: what happens when Chainguard does not have a package

There is **no client-side fallback**. The Maven mirror is the only route to Central and `.npmrc`
has the only registry; if Chainguard does not serve something, the build fails loudly
(`Could not find artifact ... in chainguard`, npm `E404` / `403 POLICY_COOLDOWN`). It never
quietly reads Maven Central or npmjs.org.

What can happen on the **Chainguard** side is a per-ecosystem entitlement setting:

| Entitlement policy | Behaviour |
| --- | --- |
| `CHAINGUARD_AND_UPSTREAM` (default for new entitlements) | Chainguard-built packages first; anything it has not built is fetched from Maven Central / npmjs by Chainguard, malware-scanned and held for the cooldown. **This is the setting releases need.** |
| `CHAINGUARD` | Only Chainguard-built packages. No cooldown. Most of our tree is not built, so releases fail. |

As of this change the JAVA and JAVASCRIPT entitlements for `concepttocloud.com` are `CHAINGUARD`
only (`chainctl libraries entitlements list --parent=concepttocloud.com`). Measured against that
policy on 2026-11-21: the Maven build fails on its first lookup (`com.fasterxml.jackson:jackson-bom:2.22.3`
is not built by Chainguard); of the 2,134 Maven Central POMs in the full dependency + plugin tree
1,381 (65%) are served and 753 (35%, mostly `org.apache.maven`, `org.codehaus.plexus`,
`org.apache.commons`, Netty/Jackson BOMs) are not; and 261 of 560 saiku-ui lockfile packages are
not Chainguard-built (`npm ci` 404s on the first). To make releases work, switch them (changes can take
up to 30 minutes to apply):

```bash
chainctl libraries entitlements create --parent=concepttocloud.com --ecosystems=JAVA,JAVASCRIPT --policy=chainguard_and_upstream
```

The weekly `chainguard-coverage` run shows the effect: with fallback on, gaps shrink to versions
still inside the cooldown.

## The 7-day cooldown and emergency security releases

The Starter plan fixes the cooldown at 7 days, counted from the upstream publish time and
released at the next 00:00 UTC (so 7 to 8 days), and does not allow per-package overrides.
CVE-remediated (`-cgr.N`) versions are not available either. Consequences:

* A dependency version published fewer than 7-8 days ago cannot be built into a release. The
  build fails with a missing artifact (Maven) or `POLICY_COOLDOWN` (npm).
* An emergency fix that needs a just-published upstream version therefore cannot ship through
  Chainguard. Options, in order of preference: wait out the cooldown; ship the fix by pinning the
  *previous* patched version if one is old enough; upgrade the plan for a shorter cooldown and
  overrides; or use the bypass below.

### Emergency bypass

Run **release** from the Actions tab (`workflow_dispatch`), enter the tag, and tick
**`skip_chainguard`**. A tag push can never bypass; the box exists only on a manual dispatch, so
skipping Chainguard is always a conscious maintainer choice. With it set, Maven resolves straight
from Central and npm from npmjs.org, and both jobs emit a prominent `::warning::` annotation:
*the artifacts are not Chainguard-built or malware-gated for that release*. Say so in the release
notes, and re-release through Chainguard when the cooldown has passed. The tag must still be on
`main` (the `guard` job is unaffected).

## Caching

The release jobs deliberately do **not** use `cache: maven` / `cache: npm`. `ci.yml` fills those
caches from Central/npmjs, and a restored cache would satisfy the build without touching
Chainguard. The coverage workflow likewise starts from an empty local repository and npm cache.
Releases are infrequent enough that a cold cache is fine.

## npm lockfile hashes

Chainguard rebuilds npm packages, so their tarball hashes differ from the npmjs hashes in
`saiku-ui/package-lock.json` and plain `npm ci` fails with `EINTEGRITY`. The
`.github/actions/chainguard-npm` composite action therefore runs
`chainctl libraries update-hashes` on the **runner's copy** of the lockfile, which *appends* the
Chainguard hash beside the upstream one (npm accepts either). The committed lockfile is never
changed, and a temporary `.npmrc` outside the repository (path in `NPM_CONFIG_USERCONFIG`) carries
the credentials, so nothing sensitive is committed.

## Weekly coverage check

`.github/workflows/chainguard-coverage.yml` (Mondays 05:43 UTC, or run it by hand) resolves
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
SETTINGS_FILE=/tmp/cg-settings.xml GH_PACKAGES_USER=<gh user> GH_PACKAGES_TOKEN=<classic PAT> \
CHAINGUARD_JAVA_IDENTITY_ID=<id> CHAINGUARD_JAVA_TOKEN=<token> scripts/chainguard/write-maven-settings.sh
mvn -s /tmp/cg-settings.xml -Dmaven.repo.local=/tmp/cg-m2 -pl '!saiku-proptest' dependency:go-offline
```

Verify an artifact really came from Chainguard with
`chainctl libraries verify <path-to-jar>`. Maven's log prefix is `Downloaded from chainguard:`.
