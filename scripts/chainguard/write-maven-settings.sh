#!/usr/bin/env bash
# Write ~/.m2/settings.xml for release builds (saiku#2463): the GitHub Packages servers the
# Spicule forks need (identical to what release.yml/ci.yml wrote before) plus, unless bypassed,
# a Chainguard Libraries mirror for Maven Central ONLY.
#
#   <mirrorOf>central</mirrorOf>  - deliberately not `*`: the five GitHub Packages repos, the
#   in-tree lib/repo and the apache/atlassian repos in the root pom must keep resolving from
#   where they are. Plugins resolve through the same mirror (Maven's `central` is both).
#
# There is no client-side fallback to Maven Central. Whether a missing artifact is served from
# upstream is Chainguard's decision (entitlement policy + 7-day cooldown); if Chainguard cannot
# serve it, Maven fails with "Could not find artifact" - loudly, never silently from Central.
#
# Environment:
#   GH_PACKAGES_USER / GH_PACKAGES_TOKEN        required (read:packages PAT)
#   CHAINGUARD_JAVA_IDENTITY_ID / _TOKEN        required unless SKIP_CHAINGUARD=true
#   SKIP_CHAINGUARD=true                        emergency bypass: plain Maven Central, loud warning
#   SETTINGS_FILE                               default ~/.m2/settings.xml
set -euo pipefail

: "${GH_PACKAGES_USER:?GH_PACKAGES_USER is empty}"
: "${GH_PACKAGES_TOKEN:?GH_PACKAGES_TOKEN is empty - see AGENTS.md (GitHub Packages auth)}"
SETTINGS_FILE="${SETTINGS_FILE:-$HOME/.m2/settings.xml}"
CHAINGUARD_JAVA_URL="https://libraries.cgr.dev/java/"

mirror=""
chainguard_server=""
if [ "${SKIP_CHAINGUARD:-false}" = "true" ]; then
  echo "::warning title=Chainguard Libraries BYPASSED::Maven resolves directly from Maven Central. Dependencies are NOT Chainguard-built or malware-gated and the 7-day cooldown does NOT apply. Use only for an emergency release (docs/chainguard-libraries.md)."
else
  : "${CHAINGUARD_JAVA_IDENTITY_ID:?CHAINGUARD_JAVA_IDENTITY_ID is empty - add the repo secret (see docs/chainguard-libraries.md)}"
  : "${CHAINGUARD_JAVA_TOKEN:?CHAINGUARD_JAVA_TOKEN is empty - add the repo secret (see docs/chainguard-libraries.md)}"
  mirror="<mirrors><mirror><id>chainguard</id><name>Chainguard Libraries for Java</name><url>${CHAINGUARD_JAVA_URL}</url><mirrorOf>central</mirrorOf></mirror></mirrors>"
  chainguard_server="<server><id>chainguard</id><username>${CHAINGUARD_JAVA_IDENTITY_ID}</username><password>${CHAINGUARD_JAVA_TOKEN}</password></server>"
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
</settings>
XML

if [ -n "$mirror" ]; then
  echo "Maven Central -> ${CHAINGUARD_JAVA_URL} (mirrorOf central only; GitHub Packages repos unchanged; no Central fallback)"
fi
