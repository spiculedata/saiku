// The acceptance spec for spiculedata/saiku#2441, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2441`
// (and by the shared red-on-base / green-on-head gate via spec.json's command).
//
// #2441 is a local-tooling contract, so there is no endpoint to call: the
// contract is that scripts/check-secrets.sh no longer exempts a whole line
// because a placeholder MARKER sits somewhere on it. A synthetic, credential-
// shaped value sharing the line with an unrelated `process.env` reference (or
// a trailing "test fixture" comment) must be rejected, while a value that is
// itself a placeholder keeps the documented allowance.
//
// Every token-shaped fixture below is generated at run time: no credential-
// shaped literal lives in this file, so the very hook under test stays green
// on the files that test it. Exit non-zero on the first broken clause, with
// the reason on stderr, so the runner's "exit code" detail says what broke.

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { validateSpec } from '../../.github/scripts/acceptance-spec.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, '..', '..');
const hook = join(repoRoot, 'scripts', 'check-secrets.sh');

/** Marker words a value may carry and still read as a placeholder (saiku#2441). */
const MARKERS = ['your', 'example', 'sample', 'placeholder', 'dummy', 'fake', 'test', 'xxxx'];
const looksPlaceholder = (s) => MARKERS.some((m) => s.toLowerCase().includes(m));

/** A synthetic GitHub-token-shaped value: matches gh[pousr]_[A-Za-z0-9]{36,}, carries no marker. */
function synthToken() {
  for (;;) {
    const body = Array.from(randomBytes(36), (b) => 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'[b % 62]).join('');
    if (!looksPlaceholder(body)) return `ghp_${body}`;
  }
}

/** Run the hook in path mode over one fixture file. */
function scan(content) {
  const dir = mkdtempSync(join(tmpdir(), 'acceptance-2441-'));
  const file = join(dir, 'fixture.txt'); // a plain name: no *.example exemption
  writeFileSync(file, content);
  try {
    return spawnSync('bash', [hook, file], { cwd: repoRoot, encoding: 'utf8' });
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

const failures = [];
const claim = (id, fn) => {
  try {
    fn();
  } catch (err) {
    failures.push(`${id}: ${err.message}`);
  }
};

// C1 — the mixed line: a credential-shaped value next to an unrelated marker
// used to be exempted whole-line; it must now be rejected.
claim('C1', () => {
  const token = synthToken();
  const mixed = `const fallback = process.env.FALLBACK; const token = '${token}';\n`;
  const run = scan(mixed);
  assert.equal(run.status, 1, `a token sharing a line with process.env.FALLBACK must be rejected (exit ${run.status}: ${run.stderr})`);
  assert.match(run.stderr, /GitHub token/);

  const commented = `rotateme = '${synthToken()}'  # test fixture, replace before shipping\n`;
  assert.equal(scan(commented).status, 1, 'a token under a trailing placeholder-word comment must be rejected');

  assert.equal(scan(`${synthToken()}\n`).status, 1, 'a bare credential-shaped value stays rejected');
});

// C2 — the documented allowance survives, narrowed to the value itself.
claim('C2', () => {
  assert.equal(scan(`const token = 'ghp_${'x'.repeat(40)}';\n`).status, 0, 'a value padded with xxxx is a placeholder and stays allowed');
  assert.equal(scan(`aws_access_key_id = AKIAIOSFODNN7EXAMPLE  # docs example\n`).status, 0, 'the AWS documentation example key stays allowed');
  assert.equal(scan(`SAIKU_EMBED_JWT_SECRET=your_jwt_secret_here_${'x'.repeat(36)}\n`).status, 0, 'a your_… JWT secret stays allowed');
});

// C3 — the regression test lives where the existing CI job already looks.
claim('C3', () => {
  const ci = readFileSync(join(repoRoot, '.github', 'workflows', 'ci.yml'), 'utf8');
  assert.match(ci, /run: node --test "\.github\/scripts\/\*\.test\.mjs"/, 'the flake-policy job runs the .test.mjs glob');
  assert.ok(
    ['flake-policy'].some((job) => ci.includes(`  ${job}:`)),
    'the job running the glob exists'
  );
});

// This spec is itself a spec: it must satisfy the format it teaches.
claim('format', () => {
  assert.deepEqual(validateSpec(JSON.parse(readFileSync(join(here, 'spec.json'), 'utf8'))), []);
});

if (failures.length > 0) {
  process.stderr.write(`${failures.join('\n')}\n`);
  process.exit(1);
}
process.stdout.write('acceptance/2441: the secret hook judges the credential-shaped value, not the rest of the line\n');
