#!/usr/bin/env python3
"""Plan (and optionally apply) GHCR tag deletion for per-PR images of `saiku`.

Why this exists: the docker workflow pushes `pr-<n>` and `<7-hex head sha>`
tags for same-repo PRs so a reviewer (or a preview) can pull exactly the image
a PR head built. The cost is accumulation: every PR push leaves a version in
GHCR. Without a policy the package grows without bound.

This script never touches a moving or release tag. Only two tag shapes are
eligible:

  * `pr-<n>`   - a PR tag. Kept `pr_keep_days` (default 14).
  * `<7+ hex>` - the bare head-SHA tag. Kept `sha_keep_days` (default 30).

A version is deleted only when EVERY tag on it is eligible and expired. A
version that also carries `development`, `main`, `latest`, a release version
(`4.6.1`), the rolling `sha-<short>` tag of a development push, or anything
else unrecognised is never deleted, whatever its age. Two further guards:

  * `--protect-tag T` (repeatable): extra tags that veto deletion.
  * `--min-keep N` (default 10): the N most recently updated versions are never
    deleted, whatever their tags, so a misconfigured window cannot empty the
    package.

Usage:
    ghcr_tag_retention.py fetch > versions.json
    ghcr_tag_retention.py plan --versions versions.json [--sha-keep-days N] \
                               [--pr-keep-days N] [--protect-tag T ...] \
                               [--min-keep N] [--apply]

`plan` is a DRY RUN unless `--apply` is passed: it prints what it would delete
and touches nothing. `fetch` and `--apply` go through the `gh api` client, so
the caller only needs a token with `packages:read` / `packages:write`.

Exit codes: 0 fine (including "nothing to do"), 1 deletions failed, 2 the script
itself could not run.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timezone

# A per-commit tag: the first 7 hex chars of the PR head SHA (bare, no prefix;
# the rolling `sha-<short>` tags pushed from development/main do NOT match). Matched case-insensitively but not anchored to a
# length, so a workflow that widens the short SHA later is still covered.
SHA_TAG_RE = re.compile(r"^[0-9a-f]{7,40}$")
# A preview tag for PR n.
PR_TAG_RE = re.compile(r"^pr-(?P<number>\d+)$", re.IGNORECASE)

DEFAULT_PACKAGE = "saiku"

# Never deleted, whatever their age: the moving tags a deploy may point at.
DEFAULT_PROTECTED_TAGS = frozenset(
    {"development", "main", "latest"}
)


@dataclass(frozen=True)
class Version:
    """One GHCR package version: an id plus the tags that point at it."""

    id: int
    tags: tuple[str, ...]
    updated_at: str  # ISO-8601, as GHCR reports it

    def age_days(self, now: datetime) -> float:
        stamp = datetime.fromisoformat(self.updated_at.replace("Z", "+00:00"))
        if stamp.tzinfo is None:
            stamp = stamp.replace(tzinfo=timezone.utc)
        return (now - stamp).total_seconds() / 86400.0


@dataclass(frozen=True)
class Deletion:
    """One version selected for deletion, with the reason it was eligible."""

    version_id: int
    tags: tuple[str, ...]
    age_days: float
    reason: str


def keep_days_for(tag: str, sha_keep_days: int, pr_keep_days: int) -> int | None:
    """Retention window for a tag, or None when the tag is not eligible."""
    if PR_TAG_RE.match(tag):
        return pr_keep_days
    if SHA_TAG_RE.match(tag.lower()):
        return sha_keep_days
    return None


def plan_deletions(
    versions: list[Version],
    now: datetime,
    sha_keep_days: int = 30,
    pr_keep_days: int = 14,
    protected: frozenset[str] = DEFAULT_PROTECTED_TAGS,
    min_keep: int = 10,
) -> list[Deletion]:
    """Select versions to delete: every tag eligible, every tag expired.

    A version is a unit because GHCR deletes by version id: deleting one
    removes all of its tags, including any tag we meant to keep. So a single
    protected or fresh tag vetoes the whole version.
    """
    protected = frozenset(t.lower() for t in protected)
    newest = sorted(versions, key=lambda v: v.updated_at, reverse=True)[
        : max(min_keep, 0)
    ]
    newest_ids = {v.id for v in newest}
    planned: list[Deletion] = []
    for version in versions:
        if version.id in newest_ids:
            continue
        tags = [t for t in version.tags if t]
        if not tags:
            # Untagged versions (e.g. a digest-only pull-through artefact) are
            # left alone: there is no evidence any preview references them.
            continue
        if any(t.lower() in protected for t in tags):
            continue

        age = version.age_days(now)
        reasons: list[str] = []
        for tag in tags:
            window = keep_days_for(tag, sha_keep_days, pr_keep_days)
            if window is None:
                # An unrecognised tag shape on an otherwise expiring version.
                # Refuse rather than guess: a future workflow that adds tags
                # must update this script before its tags get collected.
                reasons = []
                break
            if age < window:
                reasons = []
                break
            reasons.append(f"{tag} (kept {window}d, age {age:.1f}d)")
        if reasons:
            planned.append(
                Deletion(
                    version_id=version.id,
                    tags=tuple(tags),
                    age_days=age,
                    reason="; ".join(reasons),
                )
            )
    return planned


def gh(*args: str) -> str:
    """Run a gh subcommand and return stdout, failing loudly."""
    try:
        result = subprocess.run(
            ["gh", *args], capture_output=True, text=True, check=False
        )
    except OSError as exc:
        raise RuntimeError(f"cannot run gh: {exc}") from exc
    if result.returncode != 0:
        raise RuntimeError(f"gh {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def versions_path(owner: str, owner_type: str, package: str) -> str:
    """REST path for a package's versions (org and user routes differ)."""
    prefix = "orgs" if owner_type == "org" else "users"
    return f"/{prefix}/{owner}/packages/container/{package}/versions"


# One compact JSON object per version, one per line. `gh api --paginate` on a
# bare array prints each page's array back to back (`[..][..]`), which is not
# one JSON document; `--jq` runs per page and emits lines, which is.
FETCH_JQ = (
    ".[] | {id: .id, updated_at: .updated_at, "
    "tags: (.metadata.container.tags // [])}"
)


def parse_version(raw: dict) -> Version:
    """Validate one version record; a malformed one aborts rather than guesses."""
    try:
        return Version(
            id=int(raw["id"]),
            tags=tuple(str(t) for t in (raw.get("tags") or ())),
            updated_at=str(raw["updated_at"]),
        )
    except (KeyError, TypeError, ValueError) as exc:
        raise ValueError(f"malformed version record {raw!r}: {exc}") from exc


def fetch_versions(owner: str, owner_type: str, package: str) -> list[Version]:
    """Read every version of one GHCR package through the gh CLI (paginated)."""
    out = gh(
        "api",
        "--paginate",
        "--jq",
        FETCH_JQ,
        f"{versions_path(owner, owner_type, package)}?per_page=100",
    )
    versions = [parse_version(json.loads(line)) for line in out.splitlines() if line.strip()]
    if len({v.id for v in versions}) != len(versions):
        raise ValueError("duplicate version ids in API output; refusing to continue")
    return versions


def cmd_fetch(args: argparse.Namespace) -> int:
    versions = fetch_versions(args.owner, args.owner_type, args.package)
    json.dump(
        [
            {"id": v.id, "tags": list(v.tags), "updated_at": v.updated_at}
            for v in versions
        ],
        sys.stdout,
        indent=2,
    )
    sys.stdout.write("\n")
    return 0


def cmd_plan(args: argparse.Namespace) -> int:
    with open(args.versions, encoding="utf-8") as handle:
        raw = json.load(handle)
    if isinstance(raw, dict):
        raw = [raw]
    if not isinstance(raw, list):
        raise ValueError("--versions must hold a JSON array from `fetch`")
    versions = [parse_version(v) for v in raw]
    if args.sha_keep_days < 1 or args.pr_keep_days < 1:
        raise ValueError("retention windows must be at least 1 day")
    now = datetime.now(timezone.utc)
    planned = plan_deletions(
        versions,
        now,
        sha_keep_days=args.sha_keep_days,
        pr_keep_days=args.pr_keep_days,
        protected=DEFAULT_PROTECTED_TAGS | frozenset(t.lower() for t in args.protect_tag),
        min_keep=args.min_keep,
    )
    print(f"scanned {len(versions)} versions", file=sys.stderr)
    for deletion in planned:
        print(
            f"  {'DELETE' if args.apply else 'would delete'} id={deletion.version_id} "
            f"tags={','.join(deletion.tags)} - {deletion.reason}",
            file=sys.stderr,
        )
    print(f"{len(planned)} version(s) selected", file=sys.stderr)

    if not args.apply:
        print("dry run - pass --apply to delete", file=sys.stderr)
        return 0

    failures = 0
    for deletion in planned:
        try:
            gh(
                "api",
                "--method",
                "DELETE",
                f"{versions_path(args.owner, args.owner_type, args.package)}"
                f"/{deletion.version_id}",
            )
        except RuntimeError as exc:  # keep going; one stuck tag must not block the sweep
            print(f"  FAILED {deletion.version_id}: {exc}", file=sys.stderr)
            failures += 1
    return 1 if failures else 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    fetch = sub.add_parser("fetch", help="dump a package's versions as JSON via gh")
    fetch.add_argument("--package", default=DEFAULT_PACKAGE)
    fetch.add_argument("--owner", default="spiculedata")
    fetch.add_argument("--owner-type", choices=("org", "user"), default="org")
    fetch.set_defaults(func=cmd_fetch)

    plan = sub.add_parser("plan", help="select (and with --apply delete) expired tags")
    plan.add_argument("--package", default=DEFAULT_PACKAGE)
    plan.add_argument("--owner", default="spiculedata")
    plan.add_argument("--owner-type", choices=("org", "user"), default="org")
    plan.add_argument("--versions", required=True, help="JSON from `fetch`")
    plan.add_argument("--sha-keep-days", type=int, default=30)
    plan.add_argument("--pr-keep-days", type=int, default=14)
    plan.add_argument(
        "--protect-tag",
        action="append",
        default=[],
        help="extra tag that vetoes deletion of any version carrying it (repeatable)",
    )
    plan.add_argument("--min-keep", type=int, default=10)
    plan.add_argument("--apply", action="store_true", help="really delete (default: dry run)")
    plan.set_defaults(func=cmd_plan)

    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except Exception as exc:  # noqa: BLE001 - the CLI reports, it does not traceback
        print(f"ghcr_tag_retention: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())