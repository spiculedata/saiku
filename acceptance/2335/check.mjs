// Acceptance spec for spiculedata/saiku#2335. Text-based (no YAML dependency); exits non-zero with
// the broken clause on stderr.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const lines = readFileSync(join(root, '.github', 'workflows', 'design-system.yml'), 'utf8')
  .split('\n')
  .filter((l) => !/^\s*#/.test(l));
const jobsAt = lines.findIndex((l) => l === 'jobs:');
assert.ok(jobsAt >= 0, 'jobs: exists');
const jobs = new Map();
let current = null;
for (const line of lines.slice(jobsAt + 1)) {
  const m = /^ {2}([A-Za-z][\w-]*):\s*$/.exec(line);
  if (m) {
    current = m[1];
    jobs.set(current, []);
  } else if (current) jobs.get(current).push(line);
}
const all = [...jobs].map(([id, l]) => [id, l.join('\n')]);

// C1: every job that installs or builds is unprivileged and installs with scripts off.
const builders = all.filter(([, t]) => /npm ci|npm run/.test(t));
assert.ok(builders.length > 0, 'a job builds the package');
for (const [id, t] of builders) {
  assert.ok(!/id-token:\s*write/.test(t), `C1: ${id} runs npm but holds id-token: write`);
  assert.ok(!/NODE_AUTH_TOKEN|secrets\.NPM_TOKEN/.test(t), `C1: ${id} runs npm but sees the npm token`);
  assert.ok(!/registry-url/.test(t), `C1: ${id} runs npm but configures registry-url`);
  assert.match(t, /npm ci[^\n]*--ignore-scripts/, `C1: ${id} must run npm ci --ignore-scripts`);
}

// C2: the signing job is artefact-only.
const signers = all.filter(([, t]) => /id-token:\s*write/.test(t));
assert.equal(signers.length, 1, 'C2: exactly one job holds id-token: write');
const [signerId, signer] = signers[0];
assert.ok(!/actions\/checkout/.test(signer), `C2: ${signerId} must not check out the repository`);
assert.ok(!/npm ci|npm run/.test(signer), `C2: ${signerId} must not install or build`);
const publish = /npm publish[^\n]*/.exec(signer);
assert.ok(publish, `C2: ${signerId} publishes`);
assert.match(publish[0], /--ignore-scripts/, 'C2: npm publish needs --ignore-scripts');
assert.match(publish[0], /--provenance/, 'C2: npm publish needs --provenance');
assert.match(publish[0], /\.tgz/, 'C2: npm publish must publish a tarball path');

// C3
assert.match(signer, /environment:\s*npm-publish/, 'C3: publish job must run in the npm-publish environment');
assert.match(signer, /!inputs\.dry_run|inputs\.dry_run\s*==\s*false/, 'C3: publish must be skipped on dry_run');
console.log('design-system.yml separates the unprivileged build from the artefact-only publish');
