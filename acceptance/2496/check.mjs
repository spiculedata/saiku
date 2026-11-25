// The acceptance spec for spiculedata/saiku#2496, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2496`.
//
// #2496 lifts saiku-web and saiku-launcher line coverage to 70%. The coverage
// itself is enforced by the existing coverage gate (scripts/check-coverage.sh
// against mvn verify's jacoco.csv); what this spec holds is the contract that
// the ratchets were raised with it: the .coverage-thresholds.json floors sit
// just under the new measured values and the surefire floors in
// .github/test-floors.json were raised, so neither gate can be silently
// relaxed later. It is red on the base branch (floors 50/53 and 711/32) and
// green on the PR head (69/73 and 1700/140).
//
// Exit non-zero on the first broken clause, with the reason on stderr, so the
// runner's "exit code" detail says what broke.

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..', '..');

const thresholds = JSON.parse(readFileSync(join(root, '.coverage-thresholds.json'), 'utf8'));
const testFloors = JSON.parse(readFileSync(join(root, '.github', 'test-floors.json'), 'utf8'));

const webCoverageFloor = thresholds.modules['saiku-core/saiku-web'];
const launcherCoverageFloor = thresholds.modules['saiku-launcher'];
const webTestFloor = testFloors.modules['saiku-core/saiku-web'];
const launcherTestFloor = testFloors.modules['saiku-launcher'];

// C1: saiku-web >= 70% line coverage, ratchet raised to just under the
// measured 70.38 (floor 69). A floor below 69 means the gate no longer
// enforces the #2496 contract.
assert.ok(
  webCoverageFloor >= 69,
  `saiku-web coverage floor is ${webCoverageFloor}, expected >= 69 (just under the >= 70% contract of #2496)`
);

// C2: saiku-launcher >= 70% line coverage under the CI configuration,
// ratchet raised to just under the measured 74.25 (floor 73).
assert.ok(
  launcherCoverageFloor >= 73,
  `saiku-launcher coverage floor is ${launcherCoverageFloor}, expected >= 73 (just under the >= 70% contract of #2496)`
);

// C3: the surefire floors were raised with the added tests (web 1700,
// launcher 140) so no existing test can be dropped without tripping the gate.
assert.ok(
  webTestFloor >= 1700,
  `saiku-web test-count floor is ${webTestFloor}, expected >= 1700 (#2496 raised it with the added tests)`
);
assert.ok(
  launcherTestFloor >= 140,
  `saiku-launcher test-count floor is ${launcherTestFloor}, expected >= 140 (#2496 raised it with the added tests)`
);

console.log('ok: #2496 ratchets in place (web coverage 69, launcher coverage 73, test floors 1700/140)');
