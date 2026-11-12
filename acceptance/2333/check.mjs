// Acceptance spec for spiculedata/saiku#2333. Text-based (no YAML dependency). Exits non-zero with
// the broken clause on stderr.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const lines = readFileSync(join(root, '.github', 'workflows', 'release.yml'), 'utf8')
  .split('\n')
  .filter((l) => !/^\s*#/.test(l));
const jobsAt = lines.findIndex((l) => l === 'jobs:');
assert.ok(jobsAt >= 0, 'jobs: exists');

/** job id -> text of its block */
const jobs = new Map();
let current = null;
for (const line of lines.slice(jobsAt + 1)) {
  const m = /^ {2}([A-Za-z][\w-]*):\s*$/.exec(line);
  if (m) {
    current = m[1];
    jobs.set(current, []);
  } else if (current) jobs.get(current).push(line);
}
const text = (id) => (jobs.get(id) ?? []).join('\n');

/** Direct needs of a job: `needs: x`, `needs: [a, b]` or a block list. */
function needs(id) {
  const t = text(id);
  const inline = /^ {4}needs:\s*\[?([^\]\n]*)\]?\s*$/m.exec(t);
  if (inline && inline[1].trim()) return inline[1].split(',').map((s) => s.trim()).filter(Boolean);
  const block = /^ {4}needs:\s*\n((?: {6}- .*\n?)+)/m.exec(t);
  return block ? block[1].split('\n').map((l) => l.replace(/^\s*-\s*/, '').trim()).filter(Boolean) : [];
}
function dependsOnGuard(id, seen = new Set()) {
  if (seen.has(id)) return false;
  seen.add(id);
  return needs(id).some((n) => n === 'guard' || dependsOnGuard(n, seen));
}

// C1
assert.ok(jobs.has('guard'), 'C1: release.yml needs a `guard` job');
const guard = text('guard');
assert.match(guard, /merge-base --is-ancestor/, 'C1: guard must check ancestry of main');
assert.match(guard, /origin\/main/, 'C1: guard must compare against origin/main');
assert.match(guard, /rev-parse --verify[^\n]*\^\{commit\}/, 'C1: guard must resolve the tag to a commit');
assert.match(guard, /GITHUB_SHA/, 'C1: guard must compare the tag commit with the pushed commit');
assert.ok(!/secrets\./.test(guard), 'C1: guard must not touch secrets');
assert.ok(!/(packages|id-token|attestations|contents):\s*write/.test(guard), 'C1: guard holds no write scope');

// C2
for (const id of jobs.keys()) {
  if (id === 'guard') continue;
  assert.ok(dependsOnGuard(id), `C2: job ${id} must depend on guard`);
}

// C3
for (const id of ['jar', 'deploy-packages', 'docker', 'npm-embed-build']) {
  assert.ok(jobs.has(id), `${id} exists`);
  assert.ok(needs(id).includes('guard'), `C3: ${id} must list guard in needs (to read its outputs)`);
  const co = /uses: actions\/checkout@[^\n]*\n((?: {8,}.*\n?)*)/.exec(text(id));
  assert.ok(co, `${id} has a checkout`);
  assert.match(co[1], /ref:\s*\$\{\{\s*needs\.guard\.outputs\.sha\s*\}\}/, `C3: ${id} checkout must use needs.guard.outputs.sha`);
}
console.log('release.yml gates every job on a main-ancestry guard');
