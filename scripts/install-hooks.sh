#!/usr/bin/env bash
# Installs a pre-commit hook that runs Spotless check + the licence-header check
# on staged Java files, and a credential-pattern check on every staged file.
# Run once per clone: ./scripts/install-hooks.sh
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
HOOK="$ROOT/.git/hooks/pre-commit"

cat > "$HOOK" <<'EOF'
#!/usr/bin/env bash
# Spotless check - fails the commit if any staged Java file is mis-formatted.
# Run `mvn spotless:apply` to fix.
# Licence-header check - fails if a Java file has no copyright/licence notice.
# Run `./scripts/check-licence-headers.sh --fix` to stamp them.
# Credential-pattern check (saiku#1920) - fails on a staged file that looks like
# it carries a live secret. SECURITY.md used to claim this hook existed; it now
# does. Keep it dependency-free (grep only) so it runs everywhere the hook is
# installed - the authoritative history-wide scan is gitleaks in CI
# (.github/workflows/security-scan.yml).
set -e
REPO_ROOT="$(git rev-parse --show-toplevel)"
if git diff --cached --name-only --diff-filter=ACM | grep -q '\.java$'; then
  mvn -q spotless:check
  "$REPO_ROOT/scripts/check-licence-headers.sh"
fi
"$REPO_ROOT/scripts/check-secrets.sh" --staged
EOF
chmod +x "$HOOK"
echo "Installed pre-commit hook at $HOOK"
