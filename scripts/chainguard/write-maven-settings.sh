#!/usr/bin/env bash
# Write ~/.m2/settings.xml for release builds (saiku#2463): the GitHub Packages servers the
# Spicule forks need (identical to what release.yml/ci.yml wrote before) plus, unless bypassed,
# Chainguard Libraries for Java as a repository AND pluginRepository in an always-active profile.
#
# Fallback is CLIENT-side: Chainguard is consulted FIRST and Maven Central (the super-POM `central`,
# plus the root pom's own repos) is still reachable after it. We do this because the Starter plan
# cannot enable Chainguard's server-side upstream policy (chainguard_and_upstream), so Chainguard
# only serves artifacts it built itself. Anything it lacks resolves from Central as before.
# Log lines tell the two apart: `Downloaded from chainguard-java:` vs `Downloaded from central:`.
# Once Chainguard serves upstream, replace the profile with a `<mirrorOf>central</mirrorOf>` mirror
# to https://libraries.cgr.dev/java/ (one-line change, see docs/chainguard-libraries.md).
#
# Environment:
#   GH_PACKAGES_USER / GH_PACKAGES_TOKEN        required (read:packages PAT)
#   CHAINGUARD_JAVA_IDENTITY_ID / _TOKEN        required unless SKIP_CHAINGUARD=true
#   SKIP_CHAINGUARD=true                        emergency bypass: plain Maven, loud warning
#   CHAINGUARD_ONLY=true                        coverage mode: mirror central to Chainguard, NO fallback
#   SETTINGS_FILE                               default ~/.m2/settings.xml
set -euo pipefail

: "${GH_PACKAGES_USER:?GH_PACKAGES_USER is empty}"
: "${GH_PACKAGES_TOKEN:?GH_PACKAGES_TOKEN is empty - see AGENTS.md (GitHub Packages auth)}"
SETTINGS_FILE="${SETTINGS_FILE:-$HOME/.m2/settings.xml}"
CHAINGUARD_JAVA_URL="https://libraries.cgr.dev/java/"

mirror=""
profile=""
chainguard_server=""
if [ "${SKIP_CHAINGUARD:-false}" = "true" ]; then
  echo "::warning title=Chainguard Libraries BYPASSED::Maven resolves directly from Maven Central. Dependencies are NOT taken from Chainguard first. Use only for an emergency release (docs/chainguard-libraries.md)."
else
  : "${CHAINGUARD_JAVA_IDENTITY_ID:?CHAINGUARD_JAVA_IDENTITY_ID is empty - add the repo secret (see docs/chainguard-libraries.md)}"
  : "${CHAINGUARD_JAVA_TOKEN:?CHAINGUARD_JAVA_TOKEN is empty - add the repo secret (see docs/chainguard-libraries.md)}"
  chainguard_server="<server><id>chainguard-java</id><username>${CHAINGUARD_JAVA_IDENTITY_ID}</username><password>${CHAINGUARD_JAVA_TOKEN}</password></server>"
  if [ "${CHAINGUARD_ONLY:-false}" = "true" ]; then
    mirror="<mirrors><mirror><id>chainguard-java</id><name>Chainguard Libraries for Java</name><url>${CHAINGUARD_JAVA_URL}</url><mirrorOf>central</mirrorOf></mirror></mirrors>"
  else
    repo="<id>chainguard-java</id><url>${CHAINGUARD_JAVA_URL}</url><releases><enabled>true</enabled></releases><snapshots><enabled>false</enabled></snapshots>"
    profile="<activeProfiles><activeProfile>chainguard</activeProfile></activeProfiles><profiles><profile><id>chainguard</id><repositories><repository>${repo}</repository></repositories><pluginRepositories><pluginRepository>${repo}</pluginRepository></pluginRepositories></profile></profiles>"
  fi
fi

mkdir -p "$(dirname "$SETTINGS_FILE")"
umask 077
cat >"$SETTINGS_FILE" <<XML
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <servers>
    <server><id>github-mondrian-saiku</id><username>${GH_PACKAGES_USER}</username><password>${GH_PACKAGES_TOKEN}</password></server>
    <server><id>github-olap4j</id><username>${GH_PACKAGES_USER}</username><password>${GH_PACKAGES_TOKEN}</password></server>
    <server><id>github-olap4j-xmlaserver</id><username>${GH_PACKAGES_USER}</username><password>${GH_PACKAGES_TOKEN}</password></server>
    <server><id>github-saiku-query</id><username>${GH_PACKAGES_USER}</username><password>${GH_PACKAGES_TOKEN}</password></server>
    <server><id>github-ossie</id><username>${GH_PACKAGES_USER}</username><password>${GH_PACKAGES_TOKEN}</password></server>
    ${chainguard_server}
  </servers>
  ${mirror}
  ${profile}
</settings>
XML

if [ -n "$profile" ]; then
  echo "Maven: Chainguard (${CHAINGUARD_JAVA_URL}) is consulted first; Maven Central and GitHub Packages remain as fallback."
elif [ -n "$mirror" ]; then
  echo "Maven: Central mirrored to ${CHAINGUARD_JAVA_URL} (Chainguard-only, no fallback)."
fi
