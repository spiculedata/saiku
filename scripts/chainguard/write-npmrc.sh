#!/usr/bin/env bash
# Write a TEMPORARY user-level .npmrc that points npm at the Chainguard Libraries JavaScript
# registry (saiku#2463). Nothing is written inside the repository, so no credentials can be
# committed; the file lives under $RUNNER_TEMP (or the path in NPMRC_FILE) and is selected for
# later steps through NPM_CONFIG_USERCONFIG.
#
# Credentials are a Chainguard Libraries pull token for the JavaScript ecosystem
# (`chainctl auth pull-token --repository=javascript`), read from the environment:
#   CHAINGUARD_JS_IDENTITY_ID   pull-token username (identity id)
#   CHAINGUARD_JS_TOKEN         pull-token password
#
# Usage: write-npmrc.sh            # writes the file, appends NPM_CONFIG_USERCONFIG to $GITHUB_ENV
set -euo pipefail

: "${CHAINGUARD_JS_IDENTITY_ID:?CHAINGUARD_JS_IDENTITY_ID is empty - add the repo secret (see docs/chainguard-libraries.md)}"
: "${CHAINGUARD_JS_TOKEN:?CHAINGUARD_JS_TOKEN is empty - add the repo secret (see docs/chainguard-libraries.md)}"

REGISTRY_URL="https://libraries.cgr.dev/javascript/"
NPMRC_FILE="${NPMRC_FILE:-${RUNNER_TEMP:-$(mktemp -d)}/chainguard.npmrc}"

AUTH="$(printf '%s:%s' "$CHAINGUARD_JS_IDENTITY_ID" "$CHAINGUARD_JS_TOKEN" | base64 | tr -d '\n')"
# The derived value is not a registered secret, so mask it (Actions only; never echo it elsewhere).
if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::add-mask::${AUTH}"; fi

umask 077
{
  echo "registry=${REGISTRY_URL}"
  echo "//libraries.cgr.dev/javascript/:_auth=${AUTH}"
  # Lockfile `resolved` URLs name registry.npmjs.org; the default (npmjs) rewrites them to the
  # registry above. Stated explicitly so a future npm default change cannot send them to npmjs.
  echo "replace-registry-host=npmjs"
} >"$NPMRC_FILE"

if [ -n "${GITHUB_ENV:-}" ]; then
  echo "NPM_CONFIG_USERCONFIG=${NPMRC_FILE}" >>"$GITHUB_ENV"
fi
echo "npm registry -> ${REGISTRY_URL} (temporary .npmrc at ${NPMRC_FILE}; no npmjs.org fallback in the client)"
