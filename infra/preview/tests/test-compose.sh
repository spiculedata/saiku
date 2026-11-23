#!/usr/bin/env bash
#
# Offline checks of the OSS preview compose file.
#
# Renders the stack for two different PR numbers with `docker compose config` (client
# side: no daemon, no images, nothing started) and asserts the properties a reviewer's
# eye would miss, via assert_compose.py. Then runs NEGATIVE CONTROLS: it deliberately
# breaks a copy of the file (publishes a port, pins a container name, turns demo mode
# on, routes a cloud-style hostname, ...) and requires the assertions to FAIL, so a green
# run proves the checks can actually fire.
#
# Runs on macOS and Linux; needs docker (compose >= 2.24), python3 and bash.
# Usage: infra/preview/tests/test-compose.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
cd "$ROOT"

PREVIEW=infra/preview/docker-compose.preview.yml
ASSERT=(python3 "$HERE/assert_compose.py")
SHA="0123456789abcdef0123456789abcdef01234567"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Wipe anything the caller exported that would win over the rendered env file.
unset COMPOSE_PROJECT_NAME COMPOSE_PROFILES SAIKU_ADMIN_PASSWORD PREVIEW_HOST

render_env() { # <pr> -> env file path
	"$ROOT/infra/preview/render-env.sh" "$1" --sha "$SHA" --out "$WORK/$1.env"
}

# compose_json <env> <out> <all|-> <files...>. The project directory is the repo root,
# exactly as preview-guard.mjs passes it (the self-check mounts ./infra/preview).
compose_json() {
	local env="$1" out="$2" pflag="$3"
	shift 3
	local -a files=() prof=()
	local f
	for f in "$@"; do files+=(-f "$f"); done
	[ "$pflag" = "-" ] || prof=(--profile '*')
	docker compose ${prof[@]+"${prof[@]}"} --project-directory "$ROOT" "${files[@]}" --env-file "$env" config --format json >"$out"
}

pass=0
fail=0
ok() { echo "ok   - $1"; pass=$((pass + 1)); }
bad() { echo "FAIL - $1"; fail=$((fail + 1)); }

expect_pass() { # <label> <cmd...>
	local label="$1"
	shift
	if "$@" >"$WORK/out.txt" 2>&1; then ok "$label"; else bad "$label"; sed 's/^/       /' "$WORK/out.txt"; fi
}
expect_fail() { # <label> <cmd...>   (negative control: the assertion MUST fire)
	local label="$1"
	shift
	if "$@" >"$WORK/out.txt" 2>&1; then
		bad "$label (assertion did not fire)"
		sed 's/^/       /' "$WORK/out.txt"
	else
		ok "$label"
		sed -n '1,2p' "$WORK/out.txt" | sed 's/^/       /'
	fi
}

ENV_A="$(render_env 101)"
ENV_B="$(render_env 202)"

compose_json "$ENV_A" "$WORK/a.json" - "$PREVIEW"
compose_json "$ENV_A" "$WORK/a-all.json" all "$PREVIEW"
compose_json "$ENV_B" "$WORK/b.json" - "$PREVIEW"
compose_json "$ENV_B" "$WORK/b-all.json" all "$PREVIEW"

expect_pass "PR 101 stack satisfies every invariant" "${ASSERT[@]}" single "$WORK/a.json" "$WORK/a-all.json"
expect_pass "PR 202 stack satisfies every invariant" "${ASSERT[@]}" single "$WORK/b.json" "$WORK/b-all.json"
expect_pass "PR 101 and PR 202 stacks are disjoint" "${ASSERT[@]}" disjoint "$WORK/a.json" "$WORK/b.json"

# --- negative controls -------------------------------------------------------
# Each broken overlay is layered LAST so it wins, then rendered like a real one.
neg() { # <name> <overlay-yaml> [env-file]
	local name="$1" yaml="$2" env="${3:-$ENV_A}"
	printf '%s\n' "$yaml" >"$WORK/neg-$name.yml"
	compose_json "$env" "$WORK/neg-$name.json" - "$PREVIEW" "$WORK/neg-$name.yml"
	compose_json "$env" "$WORK/neg-$name-all.json" all "$PREVIEW" "$WORK/neg-$name.yml"
	expect_fail "negative control: $name" "${ASSERT[@]}" single "$WORK/neg-$name.json" "$WORK/neg-$name-all.json"
}

neg published-port $'services:\n  saiku:\n    ports:\n      - "8080:8080"'
neg selfcheck-published-port $'services:\n  preview-selfcheck:\n    ports:\n      - "9999:9999"'
neg fixed-container-name $'services:\n  saiku:\n    container_name: saiku'
neg missing-bind-source $'services:\n  preview-selfcheck:\n    volumes:\n      - ./does/not/exist:/selfcheck:ro'
neg bind-outside-repo $'services:\n  preview-selfcheck:\n    volumes: !override\n      - /etc:/selfcheck:ro'
neg docker-socket $'services:\n  saiku:\n    volumes:\n      - /var/run/docker.sock:/var/run/docker.sock'
neg no-memory-limit $'services:\n  saiku:\n    mem_limit: !reset null'
neg over-budget $'services:\n  saiku:\n    mem_limit: 8g'
neg demo-mode $'services:\n  saiku:\n    environment:\n      SAIKU_DEMO: "true"'
neg default-admin-allowed $'services:\n  saiku:\n    environment:\n      SAIKU_ALLOW_DEFAULT_ADMIN: "true"'
neg default-admin-password $'services:\n  saiku:\n    environment:\n      SAIKU_ADMIN_PASSWORD: admin'
neg seed-off $'services:\n  saiku:\n    environment:\n      SAIKU_SEED: "false"'
neg telemetry-on $'services:\n  saiku:\n    environment:\n      SAIKU_TELEMETRY: "on"'
neg selfcheck-not-gated $'services:\n  preview-selfcheck:\n    profiles: !override []'
neg selfcheck-other-password $'services:\n  preview-selfcheck:\n    environment:\n      SAIKU_ADMIN_PASSWORD: something-else'
neg no-healthcheck $'services:\n  saiku:\n    healthcheck:\n      disable: true'
neg privileged $'services:\n  saiku:\n    privileged: true'
neg host-network $'services:\n  saiku:\n    network_mode: host\n    networks: !reset null'
neg shared-network $'networks:\n  preview:\n    name: saiku-cloud'
neg shared-volume $'volumes:\n  saiku-home:\n    name: saiku-home'
neg cloud-style-hostname $'services:\n  saiku:\n    labels:\n      traefik.http.routers.saiku-oss-pr-101.rule: "Host(`pr-101.preview.saiku.bi`)"'
neg other-pr-hostname $'services:\n  saiku:\n    labels:\n      traefik.http.routers.saiku-oss-pr-101.rule: "Host(`oss-pr-202.preview.saiku.bi`)"'
neg plain-http-entrypoint $'services:\n  saiku:\n    labels:\n      traefik.http.routers.saiku-oss-pr-101.entrypoints: web'
neg no-tls $'services:\n  saiku:\n    labels:\n      traefik.http.routers.saiku-oss-pr-101.tls: "false"'
neg wrong-service-port $'services:\n  saiku:\n    labels:\n      traefik.http.services.saiku-oss-pr-101.loadbalancer.server.port: "3000"'
neg extra-router $'services:\n  saiku:\n    labels:\n      traefik.http.routers.extra.rule: "Host(`pr-1.preview.saiku.bi`)"'
neg wrong-traefik-network $'services:\n  saiku:\n    labels:\n      traefik.docker.network: saiku_default'
neg unpinned-image $'services:\n  saiku:\n    image: ghcr.io/spiculedata/saiku:latest'
neg third-service $'services:\n  extra:\n    image: busybox'

# A cloud-style project name must fail (rendered with a hand-edited env file).
sed 's/^COMPOSE_PROJECT_NAME=.*/COMPOSE_PROJECT_NAME=saiku-pr-101/' "$ENV_A" >"$WORK/cloudname.env"
compose_json "$WORK/cloudname.env" "$WORK/neg-cloudname.json" - "$PREVIEW"
compose_json "$WORK/cloudname.env" "$WORK/neg-cloudname-all.json" all "$PREVIEW"
expect_fail "negative control: a saiku-cloud project name" "${ASSERT[@]}" single "$WORK/neg-cloudname.json" "$WORK/neg-cloudname-all.json"

# The same project name for two PRs must be caught by `disjoint`.
cp "$WORK/a.json" "$WORK/a-copy.json"
expect_fail "negative control: identical stacks are not disjoint" "${ASSERT[@]}" disjoint "$WORK/a.json" "$WORK/a-copy.json"

echo
echo "passed=$pass failed=$fail"
[ "$fail" -eq 0 ]
