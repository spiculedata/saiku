// Guards for how ci.yml calls the shared workflows in spiculedata/acceptance-spec.
// The logic (run the specs, red on base / green on head) is tested in that repository; what is
// Saiku's, and what must not drift here, is the pin, the enforcing mode, the check names other
// things key on (the hive label watcher reads `acceptance / specs`), the trust model and the
// Saiku specific build/start/REST settings. Hermetic: reads the workflow file only. Run by
// `ci / flake-policy tests` (node --test ".github/scripts/*.test.mjs").

import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';

const repoRoot = join(import.meta.dirname, '..', '..');
const workflow = readFileSync(join(repoRoot, '.github', 'workflows', 'ci.yml'), 'utf8');
const lines = workflow.split('\n');
const code = lines.filter((line) => !line.trim().startsWith('#')).join('\n');

const SHARED = 'spiculedata/acceptance-spec/.github/workflows';

/** The text of one top-level job in ci.yml. */
function jobBlock(id) {
  const start = lines.findIndex((line) => line === `  ${id}:`);
  assert.ok(start >= 0, `job ${id} exists`);
  let end = lines.length;
  for (let i = start + 1; i < lines.length; i += 1) {
    if (/^ {2}[a-z][\w-]*:\s*$/.test(lines[i])) {
      end = i;
      break;
    }
  }
  return lines.slice(start, end).filter((line) => !line.trim().startsWith('#')).join('\n');
}

/** The value of a block scalar input (`key: |`) inside a job block. */
function blockInput(block, key) {
  const all = block.split('\n');
  const start = all.findIndex((line) => line.trim() === `${key}: |`);
  assert.ok(start >= 0, `${key} is a block input`);
  const indent = all[start].length - all[start].trimStart().length;
  const out = [];
  for (let i = start + 1; i < all.length; i += 1) {
    const line = all[i];
    if (line.trim() !== '' && line.length - line.trimStart().length <= indent) break;
    out.push(line);
  }
  return out.join('\n');
}

test('the shared workflows are called at ONE full commit SHA, never a branch or a tag', () => {
  const uses = [...code.matchAll(/^\s*uses:\s*(\S+)/gm)].map((m) => m[1]);
  const shared = uses.filter((u) => u.startsWith(`${SHARED}/`));
  assert.deepEqual(
    shared.map((u) => u.slice(SHARED.length + 1, u.indexOf('@'))).sort(),
    ['base-check.yml', 'convention.yml', 'run-specs.yml'],
    'run-specs and base-check are called here, next to the convention gate (ci-wiring.test.mjs guards that one)'
  );
  const refs = new Set(shared.map((u) => u.slice(u.indexOf('@') + 1)));
  assert.equal(refs.size, 1, 'every call uses the same pin');
  for (const ref of refs) assert.match(ref, /^[0-9a-f]{40}$/, 'pinned to a full 40 character SHA');
});

test('both callers enforce, and keep the check names that other things key on', () => {
  for (const id of ['run-specs', 'spec-first']) {
    const block = jobBlock(id);
    assert.match(block, /^ {4}name: acceptance$/m, `${id}: caller job name is "acceptance" so called jobs read "acceptance / ..."`);
    assert.match(block, /mode: enforce/, `${id} fails the check, it does not just warn`);
    assert.doesNotMatch(block, /mode: report/);
  }
  // `acceptance / specs` is what quality-queue/label-watch.sh (EXTRA_CHECKS) and docs/ci-overview.md name
  const alias = jobBlock('specs');
  assert.match(alias, /name: acceptance \/ specs$/m);
  assert.match(alias, /needs: \[run-specs\]/);
  assert.match(alias, /if: always\(\)/, 'a failed or cancelled run-specs must still turn the alias red');
  assert.match(alias, /success\) ;;/);
  assert.match(alias, /pass\|skipped\) ;;/, 'only pass (or nothing to run) is green; not-run is red');
  assert.match(alias, /exit 1/);
});

test('the callers are pull_request only (the shared workflows refuse merge_group and push) and feed the ci rollup', () => {
  for (const id of ['run-specs', 'spec-first']) {
    const block = jobBlock(id);
    assert.match(block, /needs: changes/, `${id} waits for the path filter`);
    assert.match(block, /github\.event_name == 'pull_request'/, `${id} is skipped on merge_group and push`);
  }
  const run = jobBlock('run-specs');
  for (const f of ['java', 'ui', 'acceptance']) assert.match(run, new RegExp(`needs\\.changes\\.outputs\\.${f} == 'true'`), `run-specs runs when ${f} changed`);
  // docs-only must not pay for a Maven build, but a spec-less PR still gets its (seconds long) detect job
  assert.doesNotMatch(run, /outputs\.docs/);
  assert.doesNotMatch(jobBlock('spec-first'), /outputs\./, 'spec-first detects changed specs itself');
});

test('the Saiku specific settings: REST paths, loopback launcher, Maven token, fork not-run, JDK 22', () => {
  for (const id of ['run-specs', 'spec-first']) {
    const block = jobBlock(id);
    assert.match(block, /base-path: \/rest\/saiku\/api$/m);
    assert.match(block, /session-path: \/rest\/saiku\/session$/m);
    assert.match(block, /base-url: http:\/\/127\.0\.0\.1:18080$/m);
    assert.match(block, /ready-url: http:\/\/127\.0\.0\.1:18080\/rest\/saiku\/info$/m);
    assert.match(block, /skip-without-build-token: true/, 'fork PRs record api specs not-run instead of failing the build');
    assert.match(block, /java-version: "22"/);
    assert.match(block, /timeout-minutes: 25/);
    assert.match(block, /BUILD_TOKEN: \$\{\{ secrets\.GH_PACKAGES_TOKEN \}\}/);
    assert.match(block, /ACCEPTANCE_USER: admin$/m);
    assert.match(block, /ACCEPTANCE_PASSWORD: admin$/m);
    const build = blockInput(block, 'build-command');
    assert.match(build, /mvn -B -ntp -pl saiku-launcher -am -Dmaven\.test\.skip=true package/);
    for (const server of ['github-mondrian-saiku', 'github-olap4j', 'github-olap4j-xmlaserver', 'github-saiku-query', 'github-ossie']) {
      assert.ok(build.includes(`<id>${server}</id>`), `${id}: settings.xml server ${server}`);
    }
    const start = blockInput(block, 'start-command');
    assert.match(start, /SAIKU_DEMO=true/);
    assert.match(start, /--host 127\.0\.0\.1 --port 18080/, 'loopback only');
  }
});

test('head and base are built and started identically; the base launcher is cached by the shared workflow', () => {
  const run = jobBlock('run-specs');
  const first = jobBlock('spec-first');
  assert.equal(blockInput(first, 'build-command'), blockInput(run, 'build-command'));
  assert.equal(blockInput(first, 'start-command'), blockInput(run, 'start-command'));
  assert.match(blockInput(first, 'base-cache-paths'), /^\s*acceptance-launcher\.jar\s*$/);
  assert.match(blockInput(run, 'build-command'), /cp "\$JAR" acceptance-launcher\.jar/);
  assert.match(blockInput(run, 'start-command'), /-jar acceptance-launcher\.jar serve/);
});

test('trust model: pull_request only, read-only token, the Maven token is the only real secret', () => {
  assert.doesNotMatch(code, /pull_request_target|workflow_run|workflow_dispatch/);
  assert.match(code, /^on:\n(?: {2}.*\n)*? {2}pull_request:/m);
  assert.match(code, /^permissions:\n {2}contents: read$/m);
  assert.doesNotMatch(code, /(?:contents|issues|pull-requests|actions|checks|statuses|id-token): write/);
  assert.doesNotMatch(code, /GITHUB_TOKEN|github\.token|secrets: inherit/);
  const secrets = [...code.matchAll(/secrets\.([A-Z_]+)/g)].map((m) => m[1]);
  assert.ok(secrets.length > 0);
  assert.deepEqual([...new Set(secrets)], ['GH_PACKAGES_TOKEN']);
  // untrusted PR text never reaches a run: script
  assert.doesNotMatch(code, /run:[^\n]*\$\{\{\s*github\.event\.pull_request\.(title|body)/);
});

// The two guards live in their own workflow files, NOT in ci.yml: the thin-caller gate runs in report mode and
// must not be a `ci` dependency, shadow `ci`, or make a docs-only PR wait.
const guardCode = (file) =>
  readFileSync(join(repoRoot, '.github', 'workflows', file), 'utf8')
    .split('\n')
    .filter((line) => !line.trim().startsWith('#'))
    .join('\n');

test('the guard workflows pin the SAME acceptance-spec SHA as the callers in ci.yml', () => {
  const [ciPin] = new Set([...code.matchAll(/acceptance-spec\/\.github\/workflows\/[\w.-]+@([0-9a-f]{40})/g)].map((m) => m[1]));
  assert.match(ciPin, /^[0-9a-f]{40}$/);
  for (const [file, shared] of [['thin-caller-gate.yml', 'thin-caller-gate.yml'], ['acceptance-pin-health.yml', 'pin-health.yml']]) {
    assert.match(guardCode(file), new RegExp(`uses: ${SHARED}/${shared.replace('.', '\\.')}@${ciPin}$`, 'm'), `${file} pins ${ciPin}`);
  }
});

test('thin-caller-gate: report mode, pull_request only, read-only, not wired into ci', () => {
  const gate = guardCode('thin-caller-gate.yml');
  assert.match(gate, /^on:\n {2}pull_request:\n/m);
  assert.doesNotMatch(gate, /merge_group|push:|pull_request_target|paths:/);
  assert.match(gate, /mode: report$/m, 'report first; flipping to enforce is a deliberate change that updates this test');
  assert.match(gate, /^permissions:\n {2}contents: read$/m);
  assert.doesNotMatch(gate, /: write/);
  assert.doesNotMatch(gate, /^ {4}name:/m, 'no job name: it must not be called `ci`');
  assert.doesNotMatch(jobBlock('ci'), /thin-caller-gate/);
  assert.doesNotMatch(code, /thin-caller-gate/, 'ci.yml does not call the gate');
  // the allow-list names exactly the local copies that exist and are used by acceptance/2172
  const allowed = gate.match(/allow-local-scripts: \|\n((?: {8}.+\n)+)/)[1].split('\n').map((s) => s.trim()).filter(Boolean);
  assert.deepEqual(allowed, ['.github/scripts/acceptance-runner.mjs', '.github/scripts/acceptance-spec.mjs']);
  for (const path of allowed) assert.ok(existsSync(join(repoRoot, path)), `${path} exists; delete the entry when the file goes`);
});

test('acceptance-pin-health: schedule and manual only, never opens an issue yet, never a PR check', () => {
  const health = guardCode('acceptance-pin-health.yml');
  assert.match(health, /^on:\n {2}schedule:\n {4}- cron: "[^"]+"\n {2}workflow_dispatch:\n/m);
  assert.doesNotMatch(health, /pull_request|push:|merge_group/);
  assert.match(health, /open-issue: false$/m);
  assert.match(health, /^ {6}issues: write$/m, 'the caller job must hold issues: write even when open-issue is false');
});
