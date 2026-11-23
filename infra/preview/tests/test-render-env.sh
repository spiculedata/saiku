#!/usr/bin/env bash
#
# Offline tests for infra/preview/render-env.sh.
# Runs on macOS and Linux (bash 3.2+). Usage: infra/preview/tests/test-render-env.sh
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RENDER="$HERE/../render-env.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

pass=0
fail=0
ok() { echo "ok   - $1"; pass=$((pass + 1)); }
bad() { echo "FAIL - $1"; fail=$((fail + 1)); }
assert() { # <label> <command...>
	local label="$1"
	shift
	if "$@" >/dev/null 2>&1; then ok "$label"; else bad "$label"; fi
}
assert_not() {
	local label="$1"
	shift
	if "$@" >/dev/null 2>&1; then bad "$label"; else ok "$label"; fi
}
get() { grep -E "^$2=" "$1" | head -1 | cut -d= -f2-; }
mode() { stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1"; }

# Nothing the caller exported may win over the generated values.
unset PREVIEW_HOST PREVIEW_BASE_DOMAIN SAIKU_ADMIN_PASSWORD PREVIEW_SELFCHECK_ARGS GITHUB_SHA IMAGE_REGISTRY

SHA_A="$(printf 'a%.0s' $(seq 1 40))"
SHA_B="$(printf 'b%.0s' $(seq 1 40))"
SHA_C="$(printf 'c%.0s' $(seq 1 40))"

A="$("$RENDER" 101 --sha "$SHA_A" --out "$WORK/a.env" 2>"$WORK/a.err")"
assert "rendering prints nothing on stderr (a backtick in the heredoc would run a command)" test ! -s "$WORK/a.err"
B="$("$RENDER" 202 --sha "$SHA_B" --out "$WORK/b.env")"

assert "prints the path it wrote" test "$A" = "$WORK/a.env"
assert "env file is 0600" test "$(mode "$A")" = "600"

# A glued line still looks like KEY=value, so also reject a second assignment hiding inside the value.
lines_ok() { ! grep -vE '^(#.*|[A-Z][A-Z0-9_]*=.*|)$' "$1" && ! grep -qE '^[A-Z][A-Z0-9_]*=.*[A-Z][A-Z0-9_]{2,}=' "$1"; }
assert "every line is a comment, blank or a single KEY=value" lines_ok "$A"
printf 'TOKEN=abcSECRET_KEY=def\nOTHER=3\n' >"$WORK/glued.env"
assert_not "negative control: a glued line is detected" lines_ok "$WORK/glued.env"
assert "no duplicate keys" test -z "$(grep -vE '^(#|$)' "$A" | cut -d= -f1 | sort | uniq -d)"

# Isolation from the cloud previews on the same box.
assert "project name is saiku-oss-pr-<n>" test "$(get "$A" COMPOSE_PROJECT_NAME)" = "saiku-oss-pr-101"
assert "another PR gets another project" test "$(get "$B" COMPOSE_PROJECT_NAME)" = "saiku-oss-pr-202"
assert "the project name can never be a cloud one" bash -c "! grep -qE '^COMPOSE_PROJECT_NAME=saiku-pr-' '$A'"

# Hostnames: one label under the base domain, so the wildcard certificate covers it.
assert "host is oss-pr-<n>.preview.saiku.bi" test "$(get "$A" PREVIEW_HOST)" = "oss-pr-101.preview.saiku.bi"
assert "ORIGIN matches the host" test "$(get "$A" ORIGIN)" = "https://oss-pr-101.preview.saiku.bi"
assert "the host has exactly one label below the base domain" test "$(get "$A" PREVIEW_HOST | sed 's/\.preview\.saiku\.bi$//')" = "oss-pr-101"
C="$("$RENDER" 7 --sha "$SHA_A" --base-domain preview.example.test --out "$WORK/c.env")"
assert "--base-domain moves the host" test "$(get "$C" PREVIEW_HOST)" = "oss-pr-7.preview.example.test"
D="$(PREVIEW_BASE_DOMAIN=env.example.test "$RENDER" 7 --sha "$SHA_A" --out "$WORK/d.env")"
assert "PREVIEW_BASE_DOMAIN is the default for --base-domain" test "$(get "$D" PREVIEW_HOST)" = "oss-pr-7.env.example.test"
O="$(PREVIEW_HOST=custom.example.test "$RENDER" 8 --sha "$SHA_A" --out "$WORK/o.env")"
assert "an exported PREVIEW_HOST wins, and ORIGIN follows it" test "$(get "$O" ORIGIN)" = "https://custom.example.test"

# Image: the per-SHA tag, never a moving one.
assert "image defaults to the first 7 hex of the sha" test "$(get "$A" SAIKU_IMAGE)" = "ghcr.io/spiculedata/saiku:aaaaaaa"
assert "images differ between SHAs" test "$(get "$A" SAIKU_IMAGE)" != "$(get "$B" SAIKU_IMAGE)"
T="$("$RENDER" 9 --sha "$SHA_A" --image-tag ccccccc --out "$WORK/t.env")"
assert "--image-tag wins over the sha" test "$(get "$T" SAIKU_IMAGE)" = "ghcr.io/spiculedata/saiku:ccccccc"
# shellcheck disable=SC2016 # '$(id)' is a deliberately literal hostile value
for badtag in latest development develop pr-12 abcdef abcdef01 ABCDEF0 'abcdef0;id' '$(id)' ''; do
	assert_not "image tag '$badtag' is refused" "$RENDER" 9 --sha "$SHA_A" --image-tag "$badtag" --out "$WORK/x.env"
done
assert_not "a non-hex sha without --image-tag is refused, never guessed into 'develop'" "$RENDER" 9 --sha dev --out "$WORK/x.env"
assert_not "no sha and no tag is refused" "$RENDER" 9 --out "$WORK/x.env"
assert "no image reference carries a moving tag" test -z "$(grep -E '_IMAGE=.*:(pr-|latest|develop|development|main)' "$A")"

# PR number is the only input the project and host derive from.
# shellcheck disable=SC2016
for badpr in 0 01 -1 1.5 abc '1;id' '$(id)' 'Feature/My PR' 1234567890 ''; do
	assert_not "PR number '$badpr' is refused" "$RENDER" "$badpr" --sha "$SHA_A" --out "$WORK/x.env"
done
assert_not "a missing PR number is refused" "$RENDER" --sha "$SHA_A" --out "$WORK/x.env"
assert_not "a second positional argument is refused" "$RENDER" 1 2 --sha "$SHA_A" --out "$WORK/x.env"
assert_not "an unknown option is refused" "$RENDER" 1 --bogus --sha "$SHA_A" --out "$WORK/x.env"
# shellcheck disable=SC2016
for baddomain in 'x; id' 'localhost' 'UPPER.example.com' '-a.example.com' 'a..b' '$(id).example.com'; do
	assert_not "base domain '$baddomain' is refused" "$RENDER" 1 --sha "$SHA_A" --base-domain "$baddomain" --out "$WORK/x.env"
done

# Credentials: random per stack, never shared between PRs, in the shape the redaction knows.
assert "admin user is admin" test "$(get "$A" PREVIEW_ADMIN_USER)" = "admin"
assert "the admin password has the redactable shape" grep -qE '^SAIKU_ADMIN_PASSWORD=prevpw_[0-9a-f]{40}$' "$A"
assert "the admin password differs between PRs" test "$(get "$A" SAIKU_ADMIN_PASSWORD)" != "$(get "$B" SAIKU_ADMIN_PASSWORD)"
assert "the admin password is not the shipped default" test "$(get "$A" SAIKU_ADMIN_PASSWORD)" != "admin"

# A refresh re-renders over the same file: the password must survive it (a validator holds it),
# the image must move.
cp "$A" "$WORK/before.env"
"$RENDER" 101 --sha "$SHA_C" --out "$A" >/dev/null
assert "a re-render keeps the admin password" test "$(get "$A" SAIKU_ADMIN_PASSWORD)" = "$(get "$WORK/before.env" SAIKU_ADMIN_PASSWORD)"
assert "a re-render moves the image tag" test "$(get "$A" SAIKU_IMAGE)" = "ghcr.io/spiculedata/saiku:ccccccc"
assert "a re-render keeps the file 0600" test "$(mode "$A")" = "600"
rm -f "$A"
A="$("$RENDER" 101 --sha "$SHA_A" --out "$WORK/a.env")"
assert "after a teardown (file deleted) a new stack gets a new password" test "$(get "$A" SAIKU_ADMIN_PASSWORD)" != "$(get "$WORK/before.env" SAIKU_ADMIN_PASSWORD)"
K="$(SAIKU_ADMIN_PASSWORD=exported-password-1 "$RENDER" 9 --sha "$SHA_A" --out "$WORK/k.env")"
assert "an exported password wins over a generated one" test "$(get "$K" SAIKU_ADMIN_PASSWORD)" = "exported-password-1"

# The self-check gate is on by default and overridable.
assert "the self-check fails the bring-up by default" test "$(get "$A" PREVIEW_SELFCHECK_ARGS)" = "--require-all"
R="$(PREVIEW_SELFCHECK_ARGS='' "$RENDER" 9 --sha "$SHA_A" --out "$WORK/r.env")"
assert "report-only can be requested by exporting an empty value" test "$(get "$R" PREVIEW_SELFCHECK_ARGS)" = ""

echo
echo "passed=$pass failed=$fail"
[ "$fail" -eq 0 ]
