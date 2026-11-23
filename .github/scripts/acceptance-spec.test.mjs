// Unit tests for the local acceptance spec library (spec validation, assertions, JSON paths, verdicts),
// which the local runner (acceptance-runner.mjs) uses. Ported from spiculedata/saiku-cloud#1382, adapted to
// Saiku: auth modes, base path. The convention gate (closing keywords, the waiver) is NOT here any more:
// it is spiculedata/acceptance-spec's convention.yml, called from ci.yml; ci-wiring.test.mjs guards the
// call and the waivable-paths list. Hermetic: no network, no runner. Run by `ci / flake-policy tests`
// (node --test ".github/scripts/*.test.mjs").

import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';

import {
  assertValue,
  evaluateExpect,
  interpolate,
  lookupJson,
  parseJsonPath,
  validateSpec,
  verdictFor
} from './acceptance-spec.mjs';

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

test('the repository\'s own spec directories are valid', () => {
  const acceptanceDir = join(import.meta.dirname, '..', '..', 'acceptance');
  const problems = [];
  for (const entry of readdirSync(acceptanceDir, { withFileTypes: true })) {
    if (!entry.isDirectory() || !/^\d+$/.test(entry.name)) continue;
    const spec = JSON.parse(readFileSync(join(acceptanceDir, entry.name, 'spec.json'), 'utf8'));
    if (spec.issue !== Number(entry.name)) problems.push(`acceptance/${entry.name}: "issue" is ${spec.issue}`);
    for (const error of validateSpec(spec)) problems.push(`acceptance/${entry.name}: ${error}`);
  }
  assert.deepEqual(problems, [], problems.join('\n'));
});
