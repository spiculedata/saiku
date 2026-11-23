// The `ci` rollup decides whether acceptance blocks a merge. This runs the REAL script of its
// "Acceptance gate" step (extracted from ci.yml, so the test cannot drift from the workflow) against
// every outcome the shared workflows can produce. Hermetic: bash only.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const lines = readFileSync(join(root, '.github', 'workflows', 'ci.yml'), 'utf8').split('\n');

function gateScript() {
  const step = lines.findIndex((l) => l.includes('- name: Acceptance gate'));
  assert.ok(step > 0, 'the Acceptance gate step exists in the ci job');
  const run = lines.findIndex((l, i) => i > step && l.trim() === 'run: |');
  const indent = lines[run + 1].length - lines[run + 1].trimStart().length;
  const out = [];
  for (let i = run + 1; i < lines.length; i += 1) {
    if (lines[i].trim() !== '' && lines[i].length - lines[i].trimStart().length < indent) break;
    out.push(lines[i].slice(indent));
  }
  return out.join('\n');
}

const SCRIPT = gateScript();
const NONE = { java: 'false', ui: 'false', acceptance: 'false', specs: 'false' };

/** Run the gate; returns true when it would let `ci` go green. */
function gate({ event = 'pull_request', changed = {}, run = ['skipped', ''], first = ['success', 'skipped'] }) {
  const c = { ...NONE, ...changed };
  const env = {
    PATH: process.env.PATH,
    EVENT_NAME: event,
    CHANGED_JAVA: c.java,
    CHANGED_UI: c.ui,
    CHANGED_ACCEPTANCE: c.acceptance,
    CHANGED_SPECS: c.specs,
    RUN_SPECS_RESULT: run[0],
    RUN_SPECS_VERDICT: run[1],
    SPEC_FIRST_RESULT: first[0],
    SPEC_FIRST_VERDICT: first[1]
  };
  const r = spawnSync('bash', ['--noprofile', '--norc', '-e', '-o', 'pipefail', '-c', SCRIPT], { env, encoding: 'utf8' });
  return { ok: r.status === 0, out: r.stdout };
}

test('docs-only PR: specs are not re-run, nothing to prove, green', () => {
  assert.equal(gate({}).ok, true);
});

test('merge_group and push: the shared workflows do not run, so there is nothing to read, green', () => {
  assert.equal(gate({ event: 'merge_group', run: ['skipped', ''], first: ['skipped', ''] }).ok, true);
  assert.equal(gate({ event: 'push', run: ['skipped', ''], first: ['skipped', ''] }).ok, true);
});

test('code PR: the specs must have run and be green', () => {
  const changed = { java: 'true' };
  assert.equal(gate({ changed, run: ['success', 'pass'] }).ok, true);
  assert.equal(gate({ changed, run: ['success', 'skipped'] }).ok, true, 'a repo with no specs yet');
  assert.equal(gate({ changed, run: ['failure', 'fail'] }).ok, false, 'a failing spec blocks');
  assert.equal(gate({ changed, run: ['success', 'fail'] }).ok, false);
  assert.equal(gate({ changed, run: ['success', 'not-run'] }).ok, false, 'not-run is never a pass');
  assert.equal(gate({ changed, run: ['success', ''] }).ok, false, 'no verdict is no pass');
  assert.equal(gate({ changed, run: ['skipped', ''] }).ok, false, 'a skipped run on a code PR is not a pass');
  assert.equal(gate({ changed, run: ['cancelled', ''] }).ok, false);
  assert.equal(gate({ changed: { ui: 'true' }, run: ['success', 'fail'] }).ok, false);
  assert.equal(gate({ changed: { acceptance: 'true' }, run: ['success', 'fail'] }).ok, false);
});

test('spec PR: red on base and green on head passes; everything else is red', () => {
  const changed = { acceptance: 'true', specs: 'true' };
  const run = ['success', 'pass'];
  assert.equal(gate({ changed, run, first: ['success', 'red-on-base-green-on-head'] }).ok, true);
  assert.equal(gate({ changed, run, first: ['success', 'pass'] }).ok, true, 'excused by the spec-already-green label');
  assert.equal(gate({ changed, run, first: ['failure', 'passes-on-base'] }).ok, false);
  assert.equal(gate({ changed, run, first: ['success', 'passes-on-base'] }).ok, false);
  assert.equal(gate({ changed, run, first: ['failure', 'fail'] }).ok, false);
  assert.equal(gate({ changed, run, first: ['success', 'not-run'] }).ok, false, 'a fork PR cannot prove red-on-base');
  assert.equal(gate({ changed, run, first: ['success', 'skipped'] }).ok, false, 'detect missed the spec: not a pass');
  assert.equal(gate({ changed, run, first: ['skipped', ''] }).ok, false);
  assert.equal(gate({ changed, run: ['success', 'fail'], first: ['success', 'red-on-base-green-on-head'] }).ok, false, 'a red suite blocks even when the new spec is fine');
});

test('a PR that touches no spec is not held up by spec-first being quiet', () => {
  assert.equal(gate({ changed: { java: 'true' }, run: ['success', 'pass'], first: ['success', 'skipped'] }).ok, true);
  assert.equal(gate({ changed: { java: 'true' }, run: ['success', 'pass'], first: ['failure', 'fail'] }).ok, false);
});
