#!/usr/bin/env bash
# Rejects a commit that stages a file carrying something shaped like a live
# credential. Installed as a pre-commit hook by scripts/install-hooks.sh
# (saiku#1920 — SECURITY.md claimed this control existed; now it does).
#
# Scope and honesty about scope:
#   * This is a SHAPE check, not a scanner. It catches the common accident —
#     pasting a real key into a config, a test fixture, or a committed .env.
#     It will not find an exotic format, and it cannot tell a live key from a
#     well-known placeholder.
#   * The authoritative check is gitleaks over the full history in CI
#     (.github/workflows/security-scan.yml). That one has a real rule set and
#     an entropy model; this one is the fast local pre-flight.
#   * Because it is shape-based it MUST allow placeholders, or it would train
#     contributors to `--no-verify`. Every pattern below is followed by a
#     deliberate allowlist for the placeholder shapes used in this repo.
#
# Usage:
#   scripts/check-secrets.sh --staged    check the staged diff (pre-commit)
#   scripts/check-secrets.sh <paths...>  check the given files
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"

# Files whose whole job is to hold example/placeholder credentials. The scan
# skips them outright rather than trying to allowlist every line.
is_exempt() {
  case "$1" in
    scripts/check-secrets.sh) return 0 ;;
    */node_modules/*|*/target/*|*/dist/*) return 0 ;;
    *.example|*.sample|*.template) return 0 ;;
    */.env.example|*/.env.sample) return 0 ;;
    *) return 1 ;;
  esac
}

# Each pattern is `extended-regexp::label`. A match on a line that is a known
# placeholder is skipped by the allowlist below rather than by pattern surgery,
# so the pattern stays readable and the exception stays visible.
PATTERNS=(
  'AKIA[0-9A-Z]{16}::AWS access key id'
  'gh[pousr]_[A-Za-z0-9]{36,}::GitHub token'
  'github_pat_[A-Za-z0-9_]{22,}::GitHub fine-grained PAT'
  'sk-ant-[A-Za-z0-9_-]{24,}::Anthropic API key'
  'sk-[A-Za-z0-9]{32,}::OpenAI-style API key'
  'xox[abprs]-[A-Za-z0-9-]{10,}::Slack token'
  'AIza[0-9A-Za-z_-]{35}::Google API key'
  '-----BEGIN (RSA |EC |OPENSSH |PGP |DSA )?PRIVATE KEY-----::private key block'
  '(saiku\.embed\.jwt\.secret|SAIKU_EMBED_JWT_SECRET)[[:space:]]*[:=][[:space:]]*["'"'"']?[A-Za-z0-9+/_-]{32,}::embedded JWT secret'
)

# A line carrying one of these is a placeholder, not a live secret.
PLACEHOLDER='(your[-_ ]|example|sample|placeholder|dummy|fake|test[-_ ]|xxxx|\.\.\.|<[A-Za-z_-]+>|\$\{|\$\(|process\.env|ENV_TTL|tt[lL]Days|#[[:space:]]*noqa|//[[:space:]]*saiku)'

violations=""

scan_line() {
  local file="$1" lineno="$2" line="$3"
  for entry in "${PATTERNS[@]}"; do
    local re="${entry%%::*}" label="${entry##*::}"
    if printf '%s' "$line" | grep -Eq -- "$re"; then
      if printf '%s' "$line" | grep -Eqi -- "$PLACEHOLDER"; then
        continue
      fi
      violations+="${file}:${lineno}: possible ${label}"$'\n'
    fi
  done
}

scan_file() {
  local file="$1"
  [ -f "$file" ] || return 0
  is_exempt "$file" && return 0
  # Binary files carry no reviewable text; skip them rather than spew bytes.
  if ! grep -Iq . "$file"; then
    return 0
  fi
  local lineno=0
  while IFS= read -r line || [ -n "$line" ]; do
    lineno=$((lineno + 1))
    scan_line "$file" "$lineno" "$line"
  done < "$file"
}

case "${1:---staged}" in
  --staged)
    # Read ADDED lines only, so a pattern already in HEAD doesn't re-block every
    # subsequent commit. Strip the hunk header's leading '+' so the scan sees
    # the file text itself, not diff syntax.
    while IFS= read -r file; do
      [ -n "$file" ] || continue
      is_exempt "$file" && continue
      [ -f "$file" ] || continue
      lineno=0
      while IFS= read -r line; do
        lineno=$((lineno + 1))
        scan_line "$file" "+$lineno" "$line"
      done < <(git diff --cached -U0 -- "$file" | grep '^+' | grep -v '^+++' | sed -e 's/^+//')
    done < <(git diff --cached --name-only --diff-filter=ACM)
    ;;
  -*)
    echo "usage: $0 --staged | <paths...>" >&2
    exit 2
    ;;
  *)
    for f in "$@"; do
      scan_file "$f"
    done
    ;;
esac

if [ -n "$violations" ]; then
  cat >&2 <<EOF
Commit blocked: the staged content looks like it carries a credential.

$violations
If any of these is a placeholder, make that obvious in the line itself
(the check already skips lines marked example/test/placeholder/your-…), or
move it to a *.example file. If a real credential was committed, rotate it
first — history rewriting is not enough once it has been pushed.
EOF
  exit 1
fi

exit 0
