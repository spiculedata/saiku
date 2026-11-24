// Acceptance spec for spiculedata/saiku#2530. Text-based (no YAML dependency). Exits non-zero with
// the broken clause on stderr.
//
// A workflow run can restore the cache entries written in the default-branch scope, and Maven does
// not re-verify artifacts already in ~/.m2. `release.yml`'s publishing jobs hold `packages: write`
// behind the `release` environment while executing Maven/plugin code from a shared cache that
// less-trusted runs (docker.yml's PR-targeted dispatch builds PR content ON the development ref)
// can write. Publishing jobs must therefore restore nothing; docker.yml must not read or write the
// default-branch cache scope on the PR path.
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

/** Workflow text with full-line comments stripped. */
const workflow = (name) =>
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
  for (const [id, lines2] of jobs) jobs.set(id, lines2.join('\n'));
  return jobs;
}

/** Cache-restore findings inside one job's text. An expression value counts as gated, not restored. */
function cacheRestores(jobText) {
  const found = [];
  for (const line of jobText.split('\n')) {
    if (/^\s*uses: actions\/cache/.test(line)) found.push(line.trim());
    else if (/^\s+cache:\s+\S/.test(line) && !/cache:\s*\$\{\{.*needs\.target\.outputs\.pr/.test(line)) found.push(line.trim());
    else if (/^\s+cache-from:\s+\S/.test(line) && !/cache-from:\s*\$\{\{.*needs\.target\.outputs\.pr/.test(line)) found.push(line.trim());
  }
  return found;
}

const release = workflow('release.yml');
const docker = workflow('docker.yml');

// C1: no job in release.yml restores an Actions cache.
for (const [id, text] of jobsOf(release)) {
  const found = cacheRestores(text);
  assert.deepEqual(found, [], `C1: release.yml job ${id} must restore no Actions cache (found: ${found.join(' | ')})`);
}

// C2: docker.yml's build job — the setup-java cache is gated on the PR-dispatch output.
const buildSetupJava = jobsOf(docker).get('build') ?? '';
const cacheLine = buildSetupJava.split('\n').find((l) => /^\s+cache:/.test(l));
assert.ok(cacheLine, 'C2: docker.yml build job declares a setup-java cache input');
assert.match(
  cacheLine,
  /cache:\s*\$\{\{.*needs\.target\.outputs\.pr.*\}\}/,
  'C2: docker.yml build job cache must be an expression gated on needs.target.outputs.pr',
);

// C3: docker.yml's push job — cache-from AND cache-to are gated on the PR-dispatch output.
const push = jobsOf(docker).get('push') ?? '';
for (const key of ['cache-from', 'cache-to']) {
  const line = push.split('\n').find((l) => l.startsWith(`          ${key}:`));
  assert.ok(line, `C3: docker.yml push job declares ${key}`);
  assert.match(
    line,
    new RegExp(`${key}:\\s*\\$\\{\\{.*needs\\.target\\.outputs\\.pr.*\\}\\}`),
    `C3: docker.yml push job ${key} must be an expression gated on needs.target.outputs.pr`,
  );
}

// C4: a workflow-shape test in .github/scripts/ pins both rules.
const shapeTest = join(root, '.github', 'scripts', 'cache-hygiene.test.mjs');
assert.ok(existsSync(shapeTest), 'C4: .github/scripts/cache-hygiene.test.mjs exists');
const shapeText = readFileSync(shapeTest, 'utf8');
assert.match(shapeText, /release\.yml/, 'C4: the shape test asserts on release.yml');
assert.match(shapeText, /docker\.yml/, 'C4: the shape test asserts on docker.yml');
assert.match(shapeText, /cache/, 'C4: the shape test asserts on cache behaviour');

console.log('release.yml restores no cache; docker.yml gates all cache restore/save on the PR-dispatch output');
