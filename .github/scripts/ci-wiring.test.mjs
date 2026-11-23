// Guards for how the CI tooling is wired into ci.yml. The `ci` job is the ONLY required
// status check, so a job that is not in its `needs` can go red without blocking a merge,
// and a job whose result the rollup does not read is not a gate at all.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const ci = readFileSync(join(root, '.github', 'workflows', 'ci.yml'), 'utf8');
const lines = ci.split('\n');

/** Top-level job ids under `jobs:` (two-space indent). */
const jobIds = [...ci.slice(ci.indexOf('\njobs:')).matchAll(/^ {2}([a-z][\w-]*):\s*$/gm)].map((m) => m[1]);

/** The text of one job: from its `  id:` line to the next job. */
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
  return lines.slice(start, end).join('\n');
}

const codeOnly = (text) => text.split('\n').filter((line) => !/^\s*#/.test(line)).join('\n');

test('the ci rollup needs every other job and reads each one result', () => {
  assert.ok(jobIds.includes('ci'), 'the aggregate job is named ci');
  const others = jobIds.filter((id) => id !== 'ci');
  assert.ok(others.length >= 6, `parsed jobs: ${jobIds.join(', ')}`);
  const rollup = jobBlock('ci');
  const needs = /needs:\s*\[([^\]]*)\]/.exec(rollup);
  assert.ok(needs, 'ci has an inline needs list');
  const listed = needs[1].split(',').map((entry) => entry.trim());
  assert.deepEqual(others.filter((id) => !listed.includes(id)), [], 'jobs missing from the ci rollup needs');
  for (const id of others) assert.match(rollup, new RegExp(`"${id}=\\$\\{\\{ needs\\.${id}\\.result \\}\\}"`), `${id}'s result is checked`);
  // a skipped job (path filter, push event) is fine; anything else is red
  assert.match(rollup, /success\|skipped\)/);
  assert.match(rollup, /if: always\(\)/);
});

test('the flake-policy job runs every script test and validates the quarantine list', () => {
  const block = jobBlock('flake-policy');
  assert.match(block, /name: ci \/ flake-policy tests/);
  assert.match(block, /node --test "\.github\/scripts\/\*\.test\.mjs"/);
  assert.match(block, /flake-ledger\.mjs quarantine --check/);
  assert.doesNotMatch(block, /\bif:/, 'the tooling tests are never skipped by a path filter');
});

test('the acceptance convention gate is the shared convention.yml, pinned, enforcing, named like before', () => {
  const block = jobBlock('acceptance-convention');
  // The rollup needs it by job id; the CHECK name `ci / acceptance convention` is `<caller name> / <called name>`
  // ("acceptance convention" upstream), so the caller's display name is `ci`.
  assert.match(block, /^ {4}name: ci$/m);
  // NOT `if:` on this job: a skipped calling job reports a bare check named `ci`, shadowing the required one
  // on push / merge_group. The shared workflow skips itself instead.
  assert.doesNotMatch(block, /^ {4}if:/m);
  assert.match(block, /skip-outside-pull-request: true/);
  const pin = block.match(/uses: spiculedata\/acceptance-spec\/\.github\/workflows\/convention\.yml@([0-9a-f]{40})$/m);
  assert.ok(pin, 'convention.yml is called at a full 40 character SHA');
  const all = [...ci.matchAll(/acceptance-spec\/\.github\/workflows\/([\w.-]+)@([0-9a-f]{40})/g)];
  assert.deepEqual(all.map((m) => m[1]).sort(), ['base-check.yml', 'convention.yml', 'run-specs.yml'], 'ci.yml calls all three shared workflows');
  assert.deepEqual([...new Set(all.map((m) => m[2]))], [pin[1]], 'all three shared workflows use the same acceptance-spec pin');
  assert.match(block, /mode: enforce/);
  assert.match(block, /enforce-from-issue: \$\{\{ vars\.ACCEPTANCE_ENFORCE_FROM_ISSUE \}\}/);
  // untrusted PR text is handled upstream (env, never interpolated); nothing of it appears here
  assert.doesNotMatch(block, /pull_request\.(body|title)/);
  assert.doesNotMatch(block, /secrets/);
});

test('the convention waiver list is Saiku\'s exact list, not the shared built-in one', () => {
  const block = jobBlock('acceptance-convention').split('\n').filter((line) => !line.trim().startsWith('#'));
  const start = block.findIndex((line) => line.trim() === 'waivable-paths: |');
  assert.ok(start >= 0, 'waivable-paths is passed');
  const patterns = [];
  for (let i = start + 1; i < block.length && block[i].trim() !== ''; i += 1) patterns.push(new RegExp(block[i].trim()));
  assert.equal(patterns.length, 9);
  const waived = (path) => patterns.some((re) => re.test(path));
  for (const path of ['docs/a.md', 'README.md', 'README.MD', '.github/ISSUE_TEMPLATE/bug.yml', 'saiku-core/x/src/test/java/A.java', 'saiku-ui/src/__tests__/a.ts', 'saiku-ui/e2e/login.ts', 'tests/run.sh', 'a/b.test.ts', 'a/b.spec.mjs', 'a/b.e2e.ts']) {
    assert.ok(waived(path), `${path} is waivable`);
  }
  // waivable by the shared built-in list, deliberately NOT here (product code could hide there)
  for (const path of ['saiku-core/e2e/x.js', 'e2e/x.js', 'test/x.java', 'saiku-ui/src/main.ts', '.github/workflows/ci.yml', 'saiku-core/src/main/java/A.java']) {
    assert.ok(!waived(path), `${path} must not be waivable`);
  }
});

test('the flake verdict steps capture exit codes instead of masking steps with continue-on-error', () => {
  // A continue-on-error step still prints the runner's `Process completed with exit code`
  // trailer, which ci-feedback.mjs reads as "the step that failed the job". Capturing the
  // code keeps the verdict step the only failing step.
  assert.doesNotMatch(codeOnly(ci), /continue-on-error/);
  for (const needle of ['--source "$sources"', '--source1', 'test-results/flake-report.json']) {
    assert.ok(ci.includes(needle), `ci.yml records ${needle}`);
  }
  assert.match(ci, /-Dsurefire\.rerunFailingTestsCount=1/);
  assert.match(ci, /-Dmaven\.test\.failure\.ignore=true/);
  // the Maven verdict fails the job on ANY non-zero mvn exit, whatever the test results say
  assert.match(ci, /\[ "\$MVN_EXIT_CODE" != 0 \] && flags\+=\(--build-failed\)/);
});

test('the existing test floors and coverage floors still gate the Maven job', () => {
  assert.match(ci, /Verify test floors/);
  assert.match(ci, /\.github\/test-floors\.json/);
  assert.match(ci, /scripts\/check-coverage\.sh/);
  const floors = JSON.parse(readFileSync(join(root, '.github', 'test-floors.json'), 'utf8')).modules;
  for (const [module, floor] of Object.entries({ 'saiku-core/saiku-service': 552, 'saiku-core/saiku-web': 684, 'saiku-launcher': 32 })) {
    assert.ok(floors[module] >= floor, `${module} floor must not drop below ${floor}`);
  }
});

test('the playwright config retries once in CI and writes the report the policy reads', () => {
  const config = readFileSync(join(root, 'saiku-ui', 'playwright.config.ts'), 'utf8');
  assert.match(config, /retries: process\.env\.CI \? 1 : 0/);
  assert.match(config, /outputFile: 'test-results\/flake-report\.json'/);
});

test('new workflows never check out or run the code of a pull_request_target event', () => {
  for (const file of ['ci-feedback.yml', 'ci.yml', 'flake-ledger.yml']) {
    const text = codeOnly(readFileSync(join(root, '.github', 'workflows', file), 'utf8'));
    assert.doesNotMatch(text, /pull_request_target/, file);
    assert.doesNotMatch(text, /ref: \$\{\{ github\.event\.(pull_request|workflow_run)\.head/, file);
  }
});
