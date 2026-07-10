#!/usr/bin/env bash
# One-time GitHub shopfront cleanup for spiculedata/saiku.
#
# Fixes two things the competitive review flagged:
#   1. Repo description still reads "The Worlds Greatest Open Source OLAP Browser"
#      (old positioning, plus the typo) — replaced with the semantic-layer pitch,
#      homepage URL, and search topics.
#   2. The Releases page shows the 2014 "2.5" release as "Latest" because no
#      GitHub Release exists for any 4.x tag — creates one for the newest 4.x tag.
#
# Requirements: `gh` CLI authenticated (gh auth login) with push access.
# Run from anywhere inside the saiku repo clone:  ./scripts/fix-github-shopfront.sh
set -euo pipefail

REPO="spiculedata/saiku"

echo "==> Updating repo description, homepage, and topics"
gh repo edit "$REPO" \
  --description "Open-source semantic layer: one cube for Excel (MDX/XMLA), dashboards, and AI agents (MCP). Mondrian + Apache Calcite." \
  --homepage "https://saiku.bi" \
  --add-topic semantic-layer \
  --add-topic olap \
  --add-topic mondrian \
  --add-topic mdx \
  --add-topic xmla \
  --add-topic mcp \
  --add-topic business-intelligence \
  --add-topic apache-calcite \
  --add-topic analytics

echo "==> Finding newest 4.x tag"
git fetch --tags --quiet
LATEST_TAG=$(git tag --sort=-v:refname | grep -E '^v?4\.' | head -n1)
if [ -z "$LATEST_TAG" ]; then
  echo "ERROR: no 4.x tag found — cut a tag first, then re-run." >&2
  exit 1
fi
echo "    newest 4.x tag: $LATEST_TAG"

if gh release view "$LATEST_TAG" --repo "$REPO" >/dev/null 2>&1; then
  echo "==> Release for $LATEST_TAG already exists — marking it Latest"
  gh release edit "$LATEST_TAG" --repo "$REPO" --latest
else
  echo "==> Creating GitHub Release for $LATEST_TAG (marked Latest)"
  gh release create "$LATEST_TAG" --repo "$REPO" \
    --title "Saiku $LATEST_TAG" \
    --latest \
    --notes "Saiku $LATEST_TAG.

Full release notes: https://docs.saiku.bi/help/changelog

Run it:
\`\`\`sh
docker run -d -p 8080:8080 ghcr.io/spiculedata/saiku
\`\`\`"
fi

echo "==> Done. Check https://github.com/$REPO and https://github.com/$REPO/releases"
echo "    Going forward, cut a GitHub Release per 4.x tag so 'Latest' stays current."
