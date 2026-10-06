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

test('the acceptance convention job is a pull_request gate and keeps untrusted text out of the script', () => {
  const block = jobBlock('acceptance-convention');
  assert.match(block, /name: ci \/ acceptance convention/);
  assert.match(block, /if: github\.event_name == 'pull_request'/);
  assert.match(block, /fetch-depth: 2/, 'two commits so the waiver is verified against the PR change set');
  assert.match(block, /ACCEPTANCE_ENFORCE_FROM_ISSUE: \$\{\{ vars\.ACCEPTANCE_ENFORCE_FROM_ISSUE \}\}/);
  assert.match(block, /PR_BODY: \$\{\{ github\.event\.pull_request\.body \}\}/);
  assert.match(block, /changed-files/);
  // `${{ }}` only in env:/with:/if:, never inside a run: script (a PR body is attacker text)
  const scripts = [...codeOnly(block).matchAll(/^\s+run: (.+)$/gm)].map((m) => m[1]).join('\n');
  assert.doesNotMatch(scripts, /\$\{\{/);
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
  for (const file of ['ci-feedback.yml', 'acceptance.yml', 'flake-ledger.yml']) {
    const text = codeOnly(readFileSync(join(root, '.github', 'workflows', file), 'utf8'));
    assert.doesNotMatch(text, /pull_request_target/, file);
    assert.doesNotMatch(text, /ref: \$\{\{ github\.event\.(pull_request|workflow_run)\.head/, file);
  }
});
