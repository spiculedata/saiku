// The acceptance spec for spiculedata/saiku#2396, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2396`
// (and by the shared red-on-base / green-on-head gate via spec.json's command).
//
// #2396 is a documentation fix: TESTING.md described a CI baseline (JDK 21,
// Ubuntu + macOS), test-count floors (252/44) and a four-repository GitHub
// Packages setup that had all drifted. The contract is that the page states
// the current baseline, points at the checked-in floor files instead of
// copying counts, names all five package repositories and stops claiming a
// macOS CI lane. There is no endpoint to call, so each criterion asserts the
// text a reader of TESTING.md needs. Exit non-zero on the first broken
// clause, with the reason on stderr, so the runner's "exit code" detail says
// what broke.

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { validateSpec } from '../../.github/scripts/acceptance-spec.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, '..', '..');
const doc = readFileSync(join(repoRoot, 'TESTING.md'), 'utf8');

const failures = [];
const claim = (id, fn) => {
  try {
    fn();
  } catch (err) {
    failures.push(`${id}: ${err.message}`);
  }
};

// C1 — the CI section states the JDK 22 / ubuntu-latest baseline, and the
// environment prerequisites ask for the same JDK locally.
claim('C1', () => {
  assert.match(doc, /`\.github\/workflows\/ci\.yml` runs .+ on \*\*Ubuntu\*\*[\s\S]{0,200}?JDK 22/, 'the CI section must state the Ubuntu + JDK 22 baseline');
  assert.match(doc, /ubuntu-latest/, 'the CI section must name the ubuntu-latest runner');
  assert.match(doc, /JDK 22 \(Temurin/, 'the environment prerequisites must ask for JDK 22 locally');
  assert.doesNotMatch(doc, /JDK 21/, 'the stale JDK 21 baseline must be gone');
});

// C2 — the page points at the checked-in floor files instead of copying
// test counts, and none of the drifted numbers survive.
claim('C2', () => {
  assert.match(doc, /\.github\/test-floors\.json/, 'the surefire floor file must be the referenced authority');
  assert.match(doc, /\.coverage-thresholds\.json/, 'the coverage floor file must be referenced too');
  for (const stale of ['252', '990', '~155 test classes']) {
    assert.doesNotMatch(doc, new RegExp(stale.replace(/[~*]/g, '\\$&')), `the obsolete snapshot "${stale}" must not survive`);
  }
  assert.doesNotMatch(doc, /`saiku-core\/saiku-web`: 44/, 'the obsolete saiku-web floor 44 must not survive');
});

// C3 — all five GitHub Packages repositories are named, including ossie.
claim('C3', () => {
  for (const repo of ['github-mondrian-saiku', 'github-olap4j', 'github-olap4j-xmlaserver', 'github-saiku-query', 'github-ossie']) {
    assert.ok(doc.includes(repo), `the Maven prerequisite must name ${repo}`);
  }
  assert.doesNotMatch(doc, /\bfour\b/, 'the stale four-repository wording must be gone');
});

// C4 — the local Windows guidance no longer claims a macOS CI lane.
claim('C4', () => {
  assert.doesNotMatch(doc, /Linux\/macOS is authoritative/, 'the stale Linux/macOS CI claim must be gone');
  assert.match(doc, /no Windows or macOS lane/, 'the Windows guidance must state that CI has no macOS lane');
});

// This spec is itself a spec: it must satisfy the format it teaches.
claim('format', () => {
  assert.deepEqual(validateSpec(JSON.parse(readFileSync(join(here, 'spec.json'), 'utf8'))), []);
});

if (failures.length > 0) {
  process.stderr.write(`${failures.join('\n')}\n`);
  process.exit(1);
}
process.stdout.write('acceptance/2396: TESTING.md states the JDK 22 / Ubuntu baseline, defers counts to the floor files, names all five package repositories and drops the macOS lane claim\n');
