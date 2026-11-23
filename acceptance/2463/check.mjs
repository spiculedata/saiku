// Acceptance spec for spiculedata/saiku#2463. Text-based (no YAML dependency); exits non-zero with
// the broken clause on stderr.
import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (...p) => readFileSync(join(root, ...p), 'utf8');
const stripComments = (s) => s.split('\n').filter((l) => !/^\s*#/.test(l)).join('\n');

function jobs(text) {
  const lines = stripComments(text).split('\n');
  const at = lines.findIndex((l) => l === 'jobs:');
  assert.ok(at >= 0, 'jobs: exists');
  const out = new Map();
  let cur = null;
  for (const line of lines.slice(at + 1)) {
    const m = /^ {2}([A-Za-z][\w-]*):\s*$/.exec(line);
    if (m) { cur = m[1]; out.set(cur, []); } else if (cur) out.get(cur).push(line);
  }
  return new Map([...out].map(([k, v]) => [k, v.join('\n')]));
}

const releaseText = read('.github', 'workflows', 'release.yml');
const release = jobs(releaseText);
const jar = release.get('jar');
const npm = release.get('npm-embed-build');
assert.ok(jar && npm, 'release.yml has jar and npm-embed-build jobs');

// C1: Chainguard is a repository + pluginRepository consulted first (client-side fallback), not a mirror
const script = stripComments(read('scripts', 'chainguard', 'write-maven-settings.sh'));
assert.match(script, /libraries\.cgr\.dev\/java\//, 'C1: java endpoint');
assert.match(script, /<pluginRepositories><pluginRepository>/, 'C1: pluginRepository entry');
assert.match(script, /<repositories><repository>/, 'C1: repository entry');
assert.match(script, /<activeProfile>chainguard<\/activeProfile>/, 'C1: profile is active');
assert.ok(!/<mirrorOf>[^<]*[*!][^<]*<\/mirrorOf>/.test(script), 'C1: no wildcard mirror');
for (const id of ['github-mondrian-saiku', 'github-olap4j', 'github-olap4j-xmlaserver', 'github-saiku-query', 'github-ossie'])
  assert.ok(script.includes(`<id>${id}</id>`), `C1: keeps ${id} server`);
assert.match(jar, /write-maven-settings\.sh/, 'C1: jar job uses the settings writer');
assert.match(jar, /secrets\.CHAINGUARD_JAVA_IDENTITY_ID/, 'C1: java identity secret');
assert.match(jar, /secrets\.CHAINGUARD_JAVA_TOKEN/, 'C1: java token secret');
assert.ok(!/CHAINGUARD_ONLY/.test(jar), 'C1: release must keep Central fallback');

// C2: npm in release builds stays on npmjs (no per-package fallback); the composite is for the coverage job only
assert.ok(!/chainguard-npm|CHAINGUARD_JS/.test(releaseText), 'C2: release.yml does not route npm through Chainguard');
assert.ok(existsSync(join(root, '.github', 'actions', 'chainguard-npm', 'action.yml')), 'C2: composite action exists');
assert.match(read('scripts', 'chainguard', 'write-npmrc.sh'), /libraries\.cgr\.dev\/javascript\//, 'C2: js endpoint');
for (const f of ['.npmrc', 'saiku-ui/.npmrc']) {
  if (existsSync(join(root, f))) assert.ok(!/_auth|_password|authToken/.test(read(f)), `C2: ${f} carries credentials`);
}

// C3
assert.match(releaseText, /skip_chainguard:/, 'C3: skip_chainguard input');
assert.match(jar, /skip_chainguard/, 'C3: jar job honours the bypass');
assert.match(read('scripts', 'chainguard', 'write-maven-settings.sh'), /::warning/, 'C3: bypass logs a warning');
assert.ok(!/cache:\s*maven/.test(jar), 'C3: jar job must not cache ~/.m2');

// C4
const covPath = join(root, '.github', 'workflows', 'chainguard-coverage.yml');
assert.ok(existsSync(covPath), 'C4: chainguard-coverage.yml exists');
const cov = stripComments(readFileSync(covPath, 'utf8'));
assert.match(cov, /^ {2}schedule:/m, 'C4: scheduled');
assert.match(cov, /^ {2}workflow_dispatch:/m, 'C4: dispatchable');
assert.ok(!/pull_request/.test(cov), 'C4: no PR trigger');
assert.match(cov, /^permissions:\n {2}contents: read\s*$/m, 'C4: contents: read only at top level');
assert.ok(!/(?:contents|packages|id-token|pull-requests|issues): write/.test(cov), 'C4: no write permissions');
assert.match(cov, /npm ci[^\n]*--ignore-scripts/, 'C4: npm ci --ignore-scripts');
assert.match(cov, /CHAINGUARD_ONLY: "true"/, 'C4: maven coverage has no fallback');
assert.match(cov, /chainguard-npm/, 'C4: npm coverage uses the composite');
assert.match(cov, /dependency:go-offline|dependency:resolve/, 'C4: resolves the Maven tree');
assert.match(cov, /coverage-summary\.sh maven/, 'C4: maven summary step');
assert.match(cov, /coverage-summary\.sh npm/, 'C4: npm summary step');
assert.match(read('scripts', 'chainguard', 'coverage-summary.sh'), /GITHUB_STEP_SUMMARY/, 'C4: job summary');
assert.ok(!/repo1\.maven\.org|registry\.npmjs\.org/.test(cov), 'C4: no Central fallback');

// C5
assert.ok(!/chainguard|cgr\.dev/i.test(read('.github', 'workflows', 'ci.yml')), 'C5: ci.yml untouched');
for (const f of readdirSync(join(root, '.github', 'workflows')).filter((n) => n.endsWith('.yml'))) {
  for (const m of read('.github', 'workflows', f).matchAll(/^\s*-?\s*uses:\s*([^\s#]+)/gm)) {
    const ref = m[1];
    if (ref.startsWith('./') || ref.startsWith('docker://')) continue;
    assert.match(ref, /@[0-9a-f]{40}$/, `C5: ${f} action not SHA-pinned: ${ref}`);
  }
}

// C6
assert.ok(existsSync(join(root, 'docs', 'chainguard-libraries.md')), 'C6: docs page exists');
assert.match(read('docs', 'releasing.md'), /chainguard-libraries\.md/, 'C6: releasing.md links it');
console.log('acceptance/2463: all clauses hold');
