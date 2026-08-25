#!/usr/bin/env bash
# Installs a pre-commit hook that runs Spotless check + the licence-header check
# on staged Java files.
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
set -e
if git diff --cached --name-only --diff-filter=ACM | grep -q '\.java$'; then
  mvn -q spotless:check
  "$(git rev-parse --show-toplevel)/scripts/check-licence-headers.sh"
fi
EOF
chmod +x "$HOOK"
echo "Installed pre-commit hook at $HOOK"
