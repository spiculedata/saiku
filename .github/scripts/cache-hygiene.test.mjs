// Workflow-shape test for saiku#2530: a run that holds a publishing credential must restore
// nothing that a less-trusted run could have written. Actions caches are scoped per branch,
// and a workflow_dispatch with `ref=<default branch>` reads AND saves the default-branch
// scope — which is exactly what docker.yml's PR-targeted dispatch does (PR content executed
// on the default-branch ref, preview-ctl.mjs sends ref=<default branch>). Maven does not
// re-verify artifacts already in ~/.m2, so a poisoned cache entry is code execution in any
// job that runs Maven with a restored cache — including release.yml's publish-only
// `deploy-packages` job (`packages: write`, `release` environment).
//
// Rules pinned here:
//   1. release.yml restores no Actions cache at all (setup-java/setup-node `cache:`,
//      `cache-jdk:`, `cache-from`, actions/cache) — releases are rare; a cold build is the
//      accepted trade.
//   2. A job with `environment: release` or a write scope (`packages:`/`id-token`) restores
//      NO cache — period, no PR-gated exemption. Expression-gating a restore on the
//      PR-dispatch output stops accidental reads only: PR code executed on the default-branch
//      ref can still write the default-branch scope (e.g. the Cacheract route via
//      ACTIONS_RUNTIME_TOKEN), so a publishing job cannot trust any restore it could read.
//   3. docker.yml: the PR-dispatch path gets no Maven cache and no BuildKit gha cache save —
//      the build job's (unprivileged) setup-java cache is gated on needs.target.outputs.pr,
//      and the privileged push job's cache-to is gated there while its cache-from is empty.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

/** Workflow text with full-line comments stripped (comments explain; assert on code). */
const load = (name) =>
  readFileSync(join(root, '.github', 'workflows', name), 'utf8')
    .split('\n')
    .filter((l) => !/^\s*#/.test(l))
    .join('\n');

/** job id -> text of its block (from `  <id>:` at two-space indent to the next job). */
function jobsOf(text) {
  const lines = text.slice(text.indexOf('\njobs:')).split('\n');
  const jobs = new Map();
  let current = null;
  for (const line of lines.slice(1)) {
    const m = /^ {2}([A-Za-z][\w-]*):\s*$/.exec(line);
    if (m) {
      current = m[1];
      jobs.set(current, []);
    } else if (current) jobs.get(current).push(line);
  }
  for (const [id, block] of jobs) jobs.set(id, block.join('\n'));
  return jobs;
}

/** Is this value an expression gated exactly on docker.yml's PR-dispatch output being empty
 *  (the precise form the workflows use: `needs.target.outputs.pr == '' && <value> || ''`)? */
const prGated = (line) => /\$\{\{\s*needs\.target\.outputs\.pr\s*==\s*''/.test(line);

/** An explicit empty value (`''` or `""`) declares no restore — e.g. docker.yml's push job
 *  sets `cache-from: ''` to keep the input shape while restoring nothing at all. */
const emptyValue = (line) => /:\s*(?:''|"")\s*$/.test(line);

/**
 * Cache-restore findings inside one job's text. A `cache:`/`cache-jdk:`/`cache-from` input whose
 * value is a plain literal counts as a restore; an empty value counts as nothing; an expression
 * counts as gated (and is then only legal for an unprivileged job via the rule-3 pin).
 */
function restores(jobText) {
  const found = [];
  for (const line of jobText.split('\n')) {
    const t = line.trim();
    if (/^uses: actions\/cache/.test(t)) found.push(t);
    else if (/^cache(-jdk)?:\s+\S/.test(t) && !prGated(t) && !emptyValue(t)) found.push(t);
    else if (/^cache-from:\s+\S/.test(t) && !prGated(t) && !emptyValue(t)) found.push(t);
  }
  return found;
}

const release = jobsOf(load('release.yml'));
const docker = jobsOf(load('docker.yml'));

test('release.yml restores no Actions cache in any job (saiku#2530)', () => {
  for (const [id, text] of release) {
    const found = restores(text);
    assert.deepEqual(found, [], `release.yml job ${id} must restore no cache (found: ${found.join(' | ')})`);
  }
});

test('a publishing job (environment: release, or a write scope) restores no cache at all — no PR-gated exemption', () => {
  for (const [name, jobs] of [['release.yml', release], ['docker.yml', docker]]) {
    for (const [id, text] of jobs) {
      const privileged = /environment:\s*release/.test(text) || /packages:\s*write/.test(text) || /id-token:\s*write/.test(text);
      if (!privileged) continue;
      assert.deepEqual(
        restores(text),
        [],
        `${name} job ${id} (privileged) must restore no cache — expression gating is accident-prevention, not a boundary against PR content`
      );
      assert.ok(!/^uses: actions\/cache/m.test(text), `${name} job ${id} (privileged) must not use actions/cache`);
    }
  }
});

test('docker.yml: the build job has no Maven cache on a PR dispatch (saiku#2530)', () => {
  const line = (docker.get('build') ?? '').split('\n').find((l) => /^\s+cache:/.test(l));
  assert.ok(line, 'build job declares a setup-java cache input');
  assert.match(line, /cache:\s*\$\{\{.*needs\.target\.outputs\.pr.*\}\}/, 'cache must be expression-gated on needs.target.outputs.pr');
  assert.ok(!/^\s+cache:\s+maven\s*$/m.test(docker.get('build') ?? ''), 'the cache value is not a plain literal');
});

test('docker.yml: the push job saves no BuildKit gha cache on a PR dispatch and restores nothing at all (saiku#2530)', () => {
  const text = docker.get('push') ?? '';
  const from = text.split('\n').find((l) => /^\s+cache-from:/.test(l));
  assert.ok(from, 'push job declares cache-from');
  assert.match(from, /^\s+cache-from:\s*(?:''|"")\s*$/, 'the privileged push job restores nothing — cache-from is an explicit empty value, not a gated expression');
  const to = text.split('\n').find((l) => /^\s+cache-to:/.test(l));
  assert.ok(to, 'push job declares cache-to');
  assert.match(to, /cache-to:\s*\$\{\{\s*needs\.target\.outputs\.pr\s*==\s*''/, 'cache-to must be expression-gated exactly on needs.target.outputs.pr');
});
