#!/usr/bin/env python3
"""Tests for ghcr_tag_retention.py (saiku per-PR images).

The property that matters: the sweep only ever removes tags whose shape it
recognises AND that are past their retention window, and it never removes a
version that carries a tag a deploy could still point at. Both are pure
functions over a parsed version list, so the fixtures here are the GHCR shape
rather than a live registry.

Run:
    python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v
"""

from __future__ import annotations

import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from unittest import mock
from datetime import datetime, timedelta, timezone

SCRIPT = os.path.join(os.path.dirname(__file__), "ghcr_tag_retention.py")

_spec = importlib.util.spec_from_file_location("ghcr_tag_retention", SCRIPT)
assert _spec and _spec.loader
gtr = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = gtr
_spec.loader.exec_module(gtr)

NOW = datetime(2026, 10, 3, 12, 0, 0, tzinfo=timezone.utc)


def version(vid: int, tags: tuple[str, ...], age_days: float) -> gtr.Version:
    stamp = NOW - timedelta(days=age_days)
    return gtr.Version(id=vid, tags=tags, updated_at=stamp.isoformat().replace("+00:00", "Z"))


def ids(planned: list[gtr.Deletion]) -> list[int]:
    return sorted(d.version_id for d in planned)


def plan(versions, now, **kwargs):
    """plan_deletions with the newest-N safety off, so small fixtures can expire."""
    kwargs.setdefault("min_keep", 0)
    return gtr.plan_deletions(versions, now, **kwargs)


class PlanDeletionsTest(unittest.TestCase):
    def test_expired_pr_and_sha_tags_are_deleted(self):
        planned = plan(
            [version(1, ("a1b2c3d", "pr-1404"), 40), version(2, ("b2c3d4e",), 31)],
            NOW,
        )
        self.assertEqual(ids(planned), [1, 2])
        self.assertIn("a1b2c3d (kept 30d, age 40.0d)", planned[0].reason)
        self.assertIn("pr-1404 (kept 14d, age 40.0d)", planned[0].reason)

    def test_fresh_tags_are_kept(self):
        planned = plan([version(1, ("a1b2c3d",), 3)], NOW)
        self.assertEqual(ids(planned), [])

    def test_pr_tag_window_is_shorter_than_sha_window(self):
        planned = plan([version(1, ("pr-1404",), 20)], NOW)
        self.assertEqual(ids(planned), [1], "a 20-day-old pr tag is past its 14d window")
        planned = plan([version(1, ("pr-1404",), 10)], NOW)
        self.assertEqual(ids(planned), [], "a 10-day-old pr tag is still inside it")

    def test_moving_tag_on_an_expired_version_vetoes_deletion(self):
        for moving in ("development", "latest", "main"):
            with self.subTest(tag=moving):
                planned = plan(
                    [version(7, ("a1b2c3d", moving), 400)], NOW
                )
                self.assertEqual(ids(planned), [], f"{moving} must never be collected")

    def test_a_fresh_sibling_tag_vetoes_deletion_of_the_whole_version(self):
        # GHCR deletes by version id: deleting drops every tag on it.
        planned = plan([version(9, ("old1a2b", "fresh3c4d"), 1)], NOW)
        self.assertEqual(ids(planned), [])

    def test_unknown_tag_shape_is_not_collected(self):
        # A future workflow adding a new tag shape must update this script
        # first; until then its tags are left alone rather than guessed at.
        planned = plan(
            [version(3, ("a1b2c3d", "candidate-2026-10-03"), 900)], NOW
        )
        self.assertEqual(ids(planned), [])

    def test_untagged_version_is_left_alone(self):
        planned = plan([version(4, (), 900)], NOW)
        self.assertEqual(ids(planned), [])

    def test_windows_are_configurable(self):
        planned = plan(
            [version(1, ("a1b2c3d",), 10)], NOW, sha_keep_days=5
        )
        self.assertEqual(ids(planned), [1])


class GuardsTest(unittest.TestCase):
    def test_extra_protected_tag_vetoes_an_expired_version(self):
        # A SHA an environment is pinned to (pinned by a deployment) must survive.
        old = version(1, ("a1b2c3d",), 400)
        self.assertEqual(ids(gtr.plan_deletions([old], NOW, min_keep=0)), [1])
        planned = gtr.plan_deletions(
            [old], NOW, protected=gtr.DEFAULT_PROTECTED_TAGS | {"a1b2c3d"}, min_keep=0
        )
        self.assertEqual(ids(planned), [])

    def test_release_tag_vetoes(self):
        planned = gtr.plan_deletions(
            [version(1, ("a1b2c3d", "v1.2.3"), 400)], NOW, min_keep=0
        )
        self.assertEqual(ids(planned), [])

    def test_newest_versions_are_never_deleted(self):
        vs = [version(i, (f"{i:07x}",), 100 + i) for i in range(1, 6)]
        planned = gtr.plan_deletions(vs, NOW, min_keep=2)
        # ids 1 and 2 are the two most recently updated
        self.assertEqual(ids(planned), [3, 4, 5])

    def test_future_timestamp_is_kept(self):
        self.assertEqual(
            ids(gtr.plan_deletions([version(1, ("a1b2c3d",), -5)], NOW, min_keep=0)), []
        )

    def test_protection_is_case_insensitive(self):
        planned = gtr.plan_deletions(
            [version(1, ("a1b2c3d", "Development"), 400)], NOW, min_keep=0
        )
        self.assertEqual(ids(planned), [])


class SaikuPackageTest(unittest.TestCase):
    """Tag shapes pushed by .github/workflows/docker.yml (single package `saiku`)."""

    def test_rolling_sha_prefixed_tag_is_never_collected(self):
        # development/main pushes tag `sha-<short>`; those are pinnable and out of scope.
        planned = plan([version(1, ("sha-a1b2c3d",), 900)], NOW)
        self.assertEqual(ids(planned), [])

    def test_release_version_tags_veto(self):
        for tag in ("4.6.1", "v4.6.1", "4.6", "4"):
            with self.subTest(tag=tag):
                planned = plan([version(1, ("a1b2c3d", tag), 900)], NOW)
                self.assertEqual(ids(planned), [])

    def test_attestation_digest_tags_are_not_collected(self):
        planned = plan([version(1, ("sha256-" + "a" * 64,), 900)], NOW)
        self.assertEqual(ids(planned), [])

    def test_pr_image_pair_expires_together(self):
        # What docker.yml pushes for a PR: pr-<n> and the 7-hex head sha on one version.
        self.assertEqual(ids(plan([version(1, ("pr-2200", "a1b2c3d"), 15)], NOW)), [])
        self.assertEqual(ids(plan([version(1, ("pr-2200", "a1b2c3d"), 31)], NOW)), [1])

    def test_sha_only_version_uses_the_30_day_window(self):
        self.assertEqual(ids(plan([version(1, ("a1b2c3d",), 20)], NOW)), [])

    def test_default_package_is_saiku(self):
        self.assertEqual(gtr.DEFAULT_PACKAGE, "saiku")
        with mock.patch.object(gtr, "gh", return_value="") as stub:
            gtr.main(["fetch"])
        self.assertIn("/packages/container/saiku/versions", stub.call_args.args[-1])


class KeepDaysForTest(unittest.TestCase):
    def test_shapes(self):
        self.assertEqual(gtr.keep_days_for("a1b2c3d", 30, 14), 30)
        self.assertEqual(gtr.keep_days_for("pr-2169", 30, 14), 14)
        self.assertEqual(gtr.keep_days_for("PR-2169", 30, 14), 14)
        self.assertIsNone(gtr.keep_days_for("development", 30, 14))
        self.assertIsNone(gtr.keep_days_for("pr-", 30, 14))
        self.assertIsNone(gtr.keep_days_for("pr-13x", 30, 14))


class VersionsPathTest(unittest.TestCase):
    def test_org_and_user_routes(self):
        self.assertEqual(
            gtr.versions_path("spiculedata", "org", "p"),
            "/orgs/spiculedata/packages/container/p/versions",
        )
        self.assertEqual(
            gtr.versions_path("spiculedata", "user", "p"),
            "/users/spiculedata/packages/container/p/versions",
        )


def gh_record(vid: int, tags: list[str], age_days: float) -> dict:
    stamp = NOW - timedelta(days=age_days)
    return {"id": vid, "tags": tags, "updated_at": stamp.isoformat().replace("+00:00", "Z")}


class CliTest(unittest.TestCase):
    """Drive main() with `gh` stubbed: no network, no CI env, no real deletes."""

    def run_plan(self, records, extra=(), gh_stub=None):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "versions.json")
            with open(path, "w", encoding="utf-8") as handle:
                json.dump(records, handle)
            err = io.StringIO()
            stub = gh_stub or mock.Mock(return_value="")
            with mock.patch.object(gtr, "gh", stub), redirect_stderr(err):
                code = gtr.main(
                    ["plan", "--package", "p", "--versions", path, "--min-keep", "0", *extra]
                )
            return code, err.getvalue(), stub

    def test_default_is_a_dry_run_and_never_calls_gh(self):
        code, err, stub = self.run_plan([gh_record(1, ["a1b2c3d"], 400)])
        self.assertEqual(code, 0)
        self.assertIn("would delete id=1", err)
        self.assertIn("dry run", err)
        stub.assert_not_called()

    def test_apply_deletes_only_the_selected_versions(self):
        records = [
            gh_record(1, ["a1b2c3d"], 400),
            gh_record(2, ["b2c3d4e", "development"], 400),
            gh_record(3, ["c3d4e5f"], 1),
        ]
        code, _, stub = self.run_plan(records, ["--apply"])
        self.assertEqual(code, 0)
        self.assertEqual(stub.call_count, 1)
        args = stub.call_args.args
        self.assertIn("DELETE", args)
        self.assertTrue(args[-1].endswith("/packages/container/p/versions/1"))

    def test_apply_failure_is_reported_and_the_sweep_continues(self):
        stub = mock.Mock(side_effect=RuntimeError("boom"))
        records = [gh_record(1, ["a1b2c3d"], 400), gh_record(2, ["b2c3d4e"], 400)]
        code, err, _ = self.run_plan(records, ["--apply"], gh_stub=stub)
        self.assertEqual(code, 1)
        self.assertEqual(stub.call_count, 2)
        self.assertIn("FAILED 1", err)

    def test_malformed_record_exits_2_without_deleting(self):
        code, err, stub = self.run_plan([{"id": 1}], ["--apply"])
        self.assertEqual(code, 2)
        self.assertIn("malformed", err)
        stub.assert_not_called()

    def test_zero_day_window_is_rejected(self):
        code, err, stub = self.run_plan([], ["--sha-keep-days", "0"])
        self.assertEqual(code, 2)
        stub.assert_not_called()

    def test_protect_tag_flag(self):
        code, err, _ = self.run_plan(
            [gh_record(1, ["a1b2c3d"], 400)], ["--protect-tag", "a1b2c3d"]
        )
        self.assertEqual(code, 0)
        self.assertIn("0 version(s) selected", err)

    def test_result_does_not_depend_on_ci_environment(self):
        for env in ({}, {"CI": "true", "GITHUB_ACTIONS": "true", "GH_TOKEN": "x"}):
            with self.subTest(env=sorted(env)), mock.patch.dict(os.environ, env):
                code, err, stub = self.run_plan([gh_record(1, ["a1b2c3d"], 400)])
                self.assertEqual((code, stub.call_count), (0, 0))
                self.assertIn("would delete id=1", err)


class FetchTest(unittest.TestCase):
    def test_paginated_output_is_one_record_per_line(self):
        pages = "\n".join(
            json.dumps(r)
            for r in (gh_record(1, ["a1b2c3d"], 5), gh_record(2, [], 6), gh_record(3, ["pr-9"], 7))
        )
        with mock.patch.object(gtr, "gh", return_value=pages + "\n") as stub:
            versions = gtr.fetch_versions("o", "org", "p")
        self.assertEqual([v.id for v in versions], [1, 2, 3])
        self.assertEqual(versions[1].tags, ())
        self.assertIn("--paginate", stub.call_args.args)

    def test_api_error_surfaces_as_exit_2(self):
        with mock.patch.object(gtr, "gh", side_effect=RuntimeError("HTTP 403")):
            err = io.StringIO()
            with redirect_stderr(err):
                code = gtr.main(["fetch", "--package", "p"])
        self.assertEqual(code, 2)
        self.assertIn("HTTP 403", err.getvalue())

    def test_duplicate_ids_abort(self):
        out = "\n".join(json.dumps(gh_record(1, ["a1b2c3d"], 5)) for _ in range(2))
        with mock.patch.object(gtr, "gh", return_value=out):
            with self.assertRaises(ValueError):
                gtr.fetch_versions("o", "org", "p")

    def test_missing_gh_binary_is_a_clean_error(self):
        with mock.patch.object(gtr.subprocess, "run", side_effect=FileNotFoundError("gh")):
            with self.assertRaises(RuntimeError):
                gtr.gh("api", "x")


if __name__ == "__main__":
    unittest.main()