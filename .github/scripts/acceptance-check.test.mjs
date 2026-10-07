// Unit tests for the acceptance-spec convention (ported from spiculedata/saiku-cloud#1382,
// adapted to Saiku: auth modes, base path, this repository's test layout). Hermetic: no network,
// no runner; temp directories only. Run by `ci / flake-policy tests`
// (node --test ".github/scripts/*.test.mjs").

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import { check, renderReport } from './acceptance-check.mjs';
import {
  assertValue,
  closingIssues,
  decideRequirement,
  evaluateExpect,
  interpolate,
  lookupJson,
  parseJsonPath,
  validateSpec,
  verdictFor,
  WAIVER_LABEL
} from './acceptance-spec.mjs';

function repo(specs) {
  const root = mkdtempSync(join(tmpdir(), 'acceptance-'));
  for (const [issue, spec] of Object.entries(specs)) {
    mkdirSync(join(root, 'acceptance', String(issue)), { recursive: true });
    writeFileSync(join(root, 'acceptance', String(issue), 'spec.json'), JSON.stringify(spec, null, 2));
  }
  return root;
}

const apiSpec = (issue = 1) => ({
  issue,
  title: 'a contract check',
  kind: 'api',
  criteria: [{ id: 'C1', given: 'a signed-in caller', then: 'the endpoint returns 200' }],
  steps: [
    {
      name: 'call the endpoint',
      criterion: 'C1',
      request: { method: 'GET', path: '/api/health' },
      expect: { status: 200 }
    }
  ]
});

// ------------------------------------------------------------ closing keywords

test('closingIssues reads the GitHub closing keywords only', () => {
  assert.deepEqual(closingIssues('Closes #12'), [12]);
  assert.deepEqual(closingIssues('fixes #7, resolves #3'), [3, 7]);
  assert.deepEqual(closingIssues('CLOSED #9 and Fixed #9'), [9]);
  assert.deepEqual(closingIssues('closes spiculedata/saiku-cloud#42'), [42]);
});

test('closingIssues ignores a mention that is not a closing claim', () => {
  assert.deepEqual(closingIssues('related to #7, see #7'), []);
  assert.deepEqual(closingIssues('unclosed #7'), []);
  assert.deepEqual(closingIssues('closes #'), []);
  assert.deepEqual(closingIssues(''), []);
  assert.deepEqual(closingIssues(undefined), []);
  assert.deepEqual(closingIssues(null), []);
});

test('closingIssues dedupes and bounds the list', () => {
  const many = Array.from({ length: 60 }, (_, i) => `closes #${i + 1}`).join(' ');
  assert.ok(closingIssues(many).length <= 20);
  assert.deepEqual(closingIssues('closes #5 closes #5'), [5]);
});

// ----------------------------------------------------------------- validation

test('a well formed api spec validates', () => {
  assert.deepEqual(validateSpec(apiSpec()), []);
});

test('validateSpec rejects a spec that cannot run', () => {
  assert.match(validateSpec({ issue: 0, title: '', kind: 'smoke' }).join('\n'), /"issue"/);
  assert.match(validateSpec({ ...apiSpec(), kind: 'api', criteria: [] }).join('\n'), /criteria/);
  assert.match(validateSpec({ ...apiSpec(), steps: [] }).join('\n'), /steps/);
  assert.match(
    validateSpec({ ...apiSpec(), steps: [{ name: '', request: { method: 'FETCH', path: 'x' } }] }).join('\n'),
    /method|path|criterion/
  );
  assert.match(validateSpec(null).join('\n'), /JSON object/);
});

test('validateSpec rejects two assertion operators on one path', () => {
  const spec = apiSpec();
  spec.steps[0].expect = { json: { 'a.b': { equals: 1, contains: 2 } } };
  assert.match(validateSpec(spec).join('\n'), /exactly one/);
});

test('validateSpec rejects an invalid regular expression and a bad type name', () => {
  const spec = apiSpec();
  spec.steps[0].expect = { json: { a: { matches: '([' } } };
  assert.match(validateSpec(spec).join('\n'), /regular expression/);
  spec.steps[0].expect = { json: { a: { type: 'int' } } };
  assert.match(validateSpec(spec).join('\n'), /type must be/);
});

test('a ui spec must be argv, never a shell string', () => {
  const ui = { issue: 1, title: 'ui', kind: 'ui', criteria: [{ id: 'C1', then: 'the page shows x' }] };
  assert.match(validateSpec(ui).join('\n'), /command/);
  assert.deepEqual(validateSpec({ ...ui, command: ['npx', 'playwright', 'test'] }), []);
  assert.match(validateSpec({ ...ui, command: ['sh', '-c', 'npm test && echo ok'] }).join('\n'), /metacharacters/);
  assert.match(validateSpec({ ...ui, command: ['npx', 'playwright'], timeoutSeconds: 0 }).join('\n'), /timeoutSeconds/);
});

// ------------------------------------------------------------------ decision

test('a PR that closes an issue owes that issue a spec directory', () => {
  const decision = decideRequirement({ body: 'Closes #12', presentIssues: [] });
  assert.equal(decision.ok, false);
  assert.deepEqual(decision.required, [{ issue: 12, dir: 'acceptance/12', present: false }]);
});

test('a PR with the spec satisfies the convention', () => {
  assert.equal(decideRequirement({ body: 'Closes #12', presentIssues: [12] }).ok, true);
});

test('a PR that closes nothing is not required to add a spec', () => {
  const decision = decideRequirement({ body: 'Internal tidy-up', presentIssues: [] });
  assert.equal(decision.ok, true);
  assert.equal(decision.reason, 'no-closing-issue');
});

test('the waiver label waives the requirement, and the waiver is recorded', () => {
  const decision = decideRequirement({ body: 'Closes #12', labels: [WAIVER_LABEL], presentIssues: [] });
  assert.equal(decision.ok, true);
  assert.equal(decision.waived, true);
  assert.match(decision.reason, /acceptance-waived/);
});

test('every closed issue needs its own directory', () => {
  const decision = decideRequirement({ body: 'Closes #1 and closes #2', presentIssues: [1] });
  assert.equal(decision.ok, false);
  assert.deepEqual(decision.required.map((r) => r.present), [true, false]);
});

// ------------------------------------------------------------ rollout cutoff

test('issues below the enforcement cutoff are grandfathered, not required', () => {
  const decision = decideRequirement({ body: 'Closes #12', presentIssues: [], enforceFrom: 1430 });
  assert.equal(decision.ok, true);
  assert.deepEqual(decision.required, []);
  assert.deepEqual(decision.grandfathered, [12]);
  assert.match(decision.reason, /grandfathered/);
});

test('issues at or above the cutoff are still required', () => {
  const decision = decideRequirement({ body: 'Closes #1430', presentIssues: [], enforceFrom: 1430 });
  assert.equal(decision.ok, false);
  assert.deepEqual(decision.required.map((r) => r.issue), [1430]);
});

test('a PR closing an old and a new issue owes only the new one', () => {
  const decision = decideRequirement({ body: 'Closes #12 and closes #1431', presentIssues: [], enforceFrom: 1430 });
  assert.deepEqual(decision.required.map((r) => r.issue), [1431]);
  assert.deepEqual(decision.grandfathered, [12]);
  assert.equal(decision.ok, false);
});

test('check() with no cutoff configured enforces nothing and says so', () => {
  const result = check({ repoRoot: repo({}), body: 'Closes #1500', enforceFrom: Number.POSITIVE_INFINITY });
  assert.equal(result.ok, true);
  assert.deepEqual(result.problems, []);
  assert.match(renderReport(result), /grandfathered|not enforced/i);
});

// ------------------------------------------------------------ waiver is verified

test('the waiver holds for a docs-only change', () => {
  const decision = decideRequirement({
    body: 'Closes #1500',
    labels: [WAIVER_LABEL],
    presentIssues: [],
    changedFiles: ['docs/acceptance-specs.md', 'README.md', '.github/ISSUE_TEMPLATE/bug.yml']
  });
  assert.equal(decision.ok, true);
  assert.equal(decision.waived, true);
});

test('the waiver is refused when the PR touches anything but docs', () => {
  const decision = decideRequirement({
    body: 'Closes #1500',
    labels: [WAIVER_LABEL],
    presentIssues: [],
    changedFiles: ['docs/a.md', 'gateway/src/main/java/Foo.java']
  });
  assert.equal(decision.ok, false);
  assert.equal(decision.waived, false);
  assert.deepEqual(decision.waiverRejected, { files: ['gateway/src/main/java/Foo.java'] });
});

test('a refused waiver is explained in the problems and the report', () => {
  const result = check({
    repoRoot: repo({}),
    body: 'Closes #1500',
    labels: [WAIVER_LABEL],
    changedFiles: ['gateway/Foo.java']
  });
  assert.equal(result.ok, false);
  assert.match(result.problems.join('\n'), /acceptance-waived.*gateway\/Foo\.java/s);
});

test('the waiver is refused for a workflow or script change even if a doc also changed', () => {
  const decision = decideRequirement({
    body: 'Closes #1500',
    labels: [WAIVER_LABEL],
    presentIssues: [],
    changedFiles: ['docs/a.md', '.github/workflows/ci.yml']
  });
  assert.equal(decision.waived, false);
});

test('an empty change list cannot be waived on (fail closed)', () => {
  const decision = decideRequirement({ body: 'Closes #1500', labels: [WAIVER_LABEL], presentIssues: [], changedFiles: [] });
  assert.equal(decision.waived, false);
  assert.equal(decision.ok, false);
});

// ----------------------------------------------------------------- json paths

test('parseJsonPath and lookupJson walk objects and array indices', () => {
  assert.deepEqual(parseJsonPath('data[0].rows[1].name'), ['data', '0', 'rows', '1', 'name']);
  const doc = { data: [{ rows: [{ name: 'a' }, { name: 'b' }] }] };
  assert.deepEqual(lookupJson(doc, 'data[0].rows[1].name'), { found: true, value: 'b' });
  assert.equal(lookupJson(doc, 'data[0].rows[9].name').found, false);
  assert.equal(lookupJson(doc, 'data.rows').found, false);
  assert.equal(lookupJson(doc, 'data[0].rows[0].name.deeper').found, false);
});

// --------------------------------------------------------------- assertions

test('assertValue implements each operator, including a missing path', () => {
  assert.equal(assertValue(2, { equals: 2 }).ok, true);
  assert.equal(assertValue('abc', { contains: 'b' }).ok, true);
  assert.equal(assertValue([1, 2], { contains: '2' }).ok, true);
  assert.equal(assertValue('2026-10-01', { matches: '^\\d{4}-\\d{2}-\\d{2}$' }).ok, true);
  assert.equal(assertValue(null, { isNull: true }).ok, true);
  assert.equal(assertValue(1, { isNull: true }).ok, false);
  assert.equal(assertValue([1], { length: 1 }).ok, true);
  assert.equal(assertValue([1], { length: 2 }).ok, false);
  assert.equal(assertValue(1, { type: 'number' }).ok, true);
  assert.equal(assertValue(1, { type: 'string' }).ok, false);
  assert.equal(assertValue(1, { equals: 1 }, { exists: false }).ok, false);
  assert.match(assertValue(1, { equals: 2, because: 'the issue says 2' }).detail, /the issue says 2/);
});

test('evaluateExpect covers status, body, header and json in one response', () => {
  const outcomes = evaluateExpect({
    status: 429,
    headers: { 'retry-after': '30' },
    bodyText: '{"error":"rate limited"}',
    json: { status: 'limited', retryAfter: 30 },
    expect: {
      status: 429,
      bodyContains: 'rate limited',
      header: { name: 'Retry-After', equals: '30' },
      json: { status: { equals: 'limited' }, retryAfter: { type: 'number' }, missing: { exists: false } }
    }
  });
  assert.deepEqual(outcomes.map((o) => o.ok), [true, true, true, true, true, true]);
});

test('a failing assertion names what was expected and what arrived', () => {
  const [outcome] = evaluateExpect({ status: 500, json: { data: null }, expect: { status: 200 } });
  assert.equal(outcome.ok, false);
  assert.match(outcome.detail, /expected 200, got 500/);
  const [missing] = evaluateExpect({ json: { data: null }, expect: { json: { rows: { exists: true } } } });
  assert.equal(missing.ok, false);
  assert.match(missing.detail, /does not exist/);
});

test('interpolate substitutes env placeholders and leaves unknown ones alone', () => {
  const env = { SAIKU_API_KEY: 'k-1' };
  assert.equal(interpolate('Bearer {{env.SAIKU_API_KEY}}', env), 'Bearer k-1');
  assert.equal(interpolate('{{env.MISSING}}', env), '{{env.MISSING}}');
  assert.deepEqual(interpolate({ headers: { a: '{{env.SAIKU_API_KEY}}' } }, env), { headers: { a: 'k-1' } });
});

// ------------------------------------------------------------------ verdict

test('verdictFor never turns a spec that could not run into a pass', () => {
  assert.equal(verdictFor([]), 'not-run');
  assert.equal(verdictFor([{ verdict: 'not-run' }]), 'not-run');
  assert.equal(verdictFor([{ verdict: 'not-run' }, { verdict: 'pass' }]), 'pass');
  assert.equal(verdictFor([{ verdict: 'pass' }, { verdict: 'fail' }]), 'fail');
});

// ------------------------------------------------------------- the CLI check

test('check fails a closing PR with no spec directory', () => {
  const root = repo({});
  const result = check({ repoRoot: root, body: 'Closes #1382', labels: [] });
  assert.equal(result.ok, false);
  assert.match(result.problems[0], /closes #1382 but adds no acceptance\/1382\/spec.json/);
  assert.match(renderReport(result), /acceptance-waived/);
});

test('check fails on a directory whose spec does not parse or does not match its issue', () => {
  const root = repo({});
  mkdirSync(join(root, 'acceptance', '7'), { recursive: true });
  writeFileSync(join(root, 'acceptance', '7', 'spec.json'), '{ not json');
  const broken = check({ repoRoot: root, body: 'Closes #7' });
  assert.equal(broken.ok, true, 'the closing keyword and a directory are the gate, not the JSON');
  assert.match(broken.problems.join('\n'), /not valid JSON/);

  const mismatched = repo({ 7: apiSpec(9) });
  assert.match(check({ repoRoot: mismatched, body: 'Closes #7' }).problems.join('\n'), /"issue" is 9/);
});

test('check ignores a spec directory with no spec.json', () => {
  const root = repo({});
  mkdirSync(join(root, 'acceptance', '3'), { recursive: true });
  const result = check({ repoRoot: root, body: 'Closes #3' });
  assert.equal(result.ok, false);
});

test('check passes a closing PR that ships a valid spec, and a waived docs-only PR', () => {
  const root = repo({ 1382: apiSpec(1382) });
  const good = check({ repoRoot: root, body: 'Closes #1382', labels: [] });
  assert.deepEqual(good.problems, []);
  assert.equal(good.ok, true);

  const docs = check({ repoRoot: repo({}), body: 'Closes #1382', labels: [WAIVER_LABEL, 'documentation'] });
  assert.equal(docs.ok, true);
  assert.match(renderReport(docs), /waived/);
});

test('the repository\'s own spec directories are valid', () => {
  const repoRoot = join(import.meta.dirname, '..', '..');
  const problems = check({ repoRoot, body: '' }).problems;
  assert.deepEqual(problems, [], problems.join('\n'));
});
// ------------------------------------------------- waiver: tests-only changes

test('the waiver also holds for a tests-only change (no product behaviour to specify)', () => {
  const decision = decideRequirement({
    body: 'Closes #1500',
    labels: [WAIVER_LABEL],
    presentIssues: [],
    changedFiles: [
      'saiku-ui/src/lib/stores/queryErrors.test.ts',
      'saiku-core/saiku-web/src/test/java/org/saiku/web/rest/FooTest.java',
      'saiku-ui/e2e/workspace.spec.ts',
      '.github/scripts/ci-feedback.test.mjs',
      'docs/a.md'
    ]
  });
  assert.equal(decision.waived, true);
});

test('a test file next to a production change does not qualify for the waiver', () => {
  const decision = decideRequirement({
    body: 'Closes #1500',
    labels: [WAIVER_LABEL],
    presentIssues: [],
    changedFiles: ['saiku-core/saiku-web/src/main/java/Foo.java', 'saiku-core/saiku-web/src/test/java/FooTest.java']
  });
  assert.equal(decision.waived, false);
  assert.deepEqual(decision.waiverRejected.files, ['saiku-core/saiku-web/src/main/java/Foo.java']);
});

test('paths that merely look like tests are not waived (fixtures and scripts that ship)', () => {
  for (const file of ['saiku-ui/src/lib/latest.ts', 'saiku-core/saiku-web/src/main/resources/tests.yml', 'scripts/contest.sh', 'src/protest/x.ts', '.github/workflows/ci.yml', 'saiku-ui/src/lib/e2e-helpers.ts']) {
    const decision = decideRequirement({ body: 'Closes #1500', labels: [WAIVER_LABEL], presentIssues: [], changedFiles: [file] });
    assert.equal(decision.waived, false, file);
  }
});

// ------------------------------------------------- Saiku additions

test('lookupJson: `$` is the document itself, so an array body can be asserted on', () => {
  assert.deepEqual(lookupJson([1, 2], '$'), { found: true, value: [1, 2] });
  assert.equal(assertValue([1, 2], { type: 'array' }, { exists: true }).ok, true);
  const results = evaluateExpect({ status: 200, json: [{ cubeName: 'Sales' }], expect: { json: { $: { type: 'array' }, '[0].cubeName': { equals: 'Sales' } } } });
  assert.ok(results.every((r) => r.ok), JSON.stringify(results));
  // a key containing a space is addressable (measure names are)
  assert.equal(lookupJson({ measures: { 'unit sales': { description: 'x' } } }, 'measures.unit sales.description').found, true);
});

test('auth modes and base path are validated; a credential in a spec is not a thing', () => {
  const base = apiSpec(3);
  assert.deepEqual(validateSpec({ ...base, auth: 'session', basePath: '/rest/saiku/api' }), []);
  assert.deepEqual(validateSpec({ ...base, auth: 'basic', steps: [{ ...base.steps[0], auth: 'none' }] }), []);
  assert.match(validateSpec({ ...base, auth: 'bearer' }).join('\n'), /"auth" must be one of none, session, basic/);
  assert.match(validateSpec({ ...base, steps: [{ ...base.steps[0], auth: 'oauth' }] }).join('\n'), /steps\[0\]\.auth/);
  assert.match(validateSpec({ ...base, basePath: 'rest/saiku' }).join('\n'), /basePath/);
  assert.match(validateSpec({ ...base, basePath: '/rest/../etc' }).join('\n'), /basePath/);
});

test('the changed-file list is read one path per line: a comma is part of a name, not a separator', () => {
  const root = repo({});
  const dir = mkdtempSync(join(tmpdir(), 'acceptance-files-'));
  const list = join(dir, 'files.txt');
  // `docs/a.md,saiku-core/Main.java` is ONE file under docs/; split on the comma it would be two.
  writeFileSync(list, 'docs/a.md,saiku-core/Main.java\nsaiku-core/Other.java\n');
  const run = spawnSync(process.execPath, [join(import.meta.dirname, 'acceptance-check.mjs'), '--repo-root', root, '--changed-files', list], {
    encoding: 'utf8',
    env: { PATH: process.env.PATH, PR_BODY: 'Closes #50', PR_LABELS: WAIVER_LABEL, ACCEPTANCE_ENFORCE_FROM_ISSUE: '1' }
  });
  assert.equal(run.status, 1, run.stdout + run.stderr);
  assert.match(run.stdout, /saiku-core\/Other\.java/);
});

test('the CLI enforces nothing when ACCEPTANCE_ENFORCE_FROM_ISSUE is unset or not a number', () => {
  const root = repo({});
  for (const value of [undefined, '', 'soon', '-5']) {
    const env = { PATH: process.env.PATH, PR_BODY: 'Closes #50', PR_LABELS: '' };
    if (value !== undefined) env.ACCEPTANCE_ENFORCE_FROM_ISSUE = value;
    const run = spawnSync(process.execPath, [join(import.meta.dirname, 'acceptance-check.mjs'), '--repo-root', root], { encoding: 'utf8', env });
    assert.equal(run.status, 0, `${value}: ${run.stdout}${run.stderr}`);
    assert.match(run.stdout, /not enforced/);
  }
  const enforced = spawnSync(process.execPath, [join(import.meta.dirname, 'acceptance-check.mjs'), '--repo-root', root], {
    encoding: 'utf8',
    env: { PATH: process.env.PATH, PR_BODY: 'Closes #50', PR_LABELS: '', ACCEPTANCE_ENFORCE_FROM_ISSUE: '50' }
  });
  assert.equal(enforced.status, 1);
  assert.match(enforced.stdout, /acceptance\/50\/spec\.json MISSING|adds no acceptance\/50\/spec\.json/);
});
