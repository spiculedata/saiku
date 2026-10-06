#!/usr/bin/env python3
"""Guard: every hex-looking `uses:` pin in the workflows is a full 40-char SHA.

A pin that cannot be resolved fails the whole job at "Set up job", before any
step runs, and actionlint does not look at the ref. Past incidents: an action
pinned to another action's SHA, and truncated SHAs. Tags and branches
(`@v4`, `@main`) are left alone; this only checks refs that are hex.

Run:
    python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

GITHUB_DIR = Path(__file__).resolve().parent.parent
USES_RE = re.compile(r"^\s*(?:-\s*)?uses:\s*(?P<action>[\w.-]+/[\w./-]+)@(?P<ref>[0-9a-fA-F]{7,64})(?![\w.-])")
FULL_SHA_RE = re.compile(r"^[0-9a-f]{40}$")


def yaml_files() -> list[Path]:
    files: list[Path] = []
    for sub in ("workflows", "actions"):
        base = GITHUB_DIR / sub
        if base.is_dir():
            files += sorted(p for p in base.rglob("*") if p.suffix in (".yml", ".yaml"))
    return files


def hex_pins(text: str):
    """Yield (line number, action, ref) for every hex-looking pin in `text`."""
    for number, line in enumerate(text.splitlines(), start=1):
        match = USES_RE.match(line)
        if match:
            yield number, match["action"], match["ref"]


class WorkflowPinsTest(unittest.TestCase):
    def test_workflows_exist(self):
        self.assertTrue(yaml_files(), "no workflow files found; the guard would pass vacuously")

    def test_every_hex_pin_is_a_full_40_char_sha(self):
        bad = [
            f"{path.relative_to(GITHUB_DIR)}:{number} {action}@{ref} ({len(ref)} chars)"
            for path in yaml_files()
            for number, action, ref in hex_pins(path.read_text(encoding="utf-8"))
            if not FULL_SHA_RE.match(ref)
        ]
        self.assertEqual(bad, [])

    def test_one_sha_is_not_pinned_for_two_different_actions(self):
        owners: dict[str, str] = {}
        clashes: list[str] = []
        for path in yaml_files():
            for _, action, ref in hex_pins(path.read_text(encoding="utf-8")):
                if not FULL_SHA_RE.match(ref):
                    continue
                repo = "/".join(action.split("/")[:2])
                if owners.setdefault(ref, repo) != repo:
                    clashes.append(f"{ref} pinned for {owners[ref]} and {repo}")
        self.assertEqual(clashes, [])


class GuardSelfTest(unittest.TestCase):
    def test_detects_truncated_sha(self):
        pins = list(hex_pins("      - uses: actions/checkout@3d3c42e5aac5 # v7"))
        self.assertEqual(len(pins), 1)
        self.assertFalse(FULL_SHA_RE.match(pins[0][2]))

    def test_ignores_tags_and_branches(self):
        self.assertEqual(list(hex_pins("  uses: actions/checkout@v4\n  uses: foo/bar@main")), [])

    def test_accepts_full_sha(self):
        pins = list(hex_pins("- uses: a/b@" + "ab12" * 10))
        self.assertTrue(FULL_SHA_RE.match(pins[0][2]))


if __name__ == "__main__":
    unittest.main()
