#!/usr/bin/env bash
# Installs a pre-commit hook that runs Spotless check on staged Java files.
# Run once per clone: ./scripts/install-hooks.sh
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
HOOK="$ROOT/.git/hooks/pre-commit"

cat > "$HOOK" <<'EOF'
#!/usr/bin/env bash
# Spotless check - fails the commit if any staged Java file is mis-formatted.
# Run `mvn spotless:apply` to fix.
set -e
if git diff --cached --name-only --diff-filter=ACM | grep -q '\.java$'; then
  mvn -q spotless:check
fi
EOF
chmod +x "$HOOK"
echo "Installed pre-commit hook at $HOOK"
