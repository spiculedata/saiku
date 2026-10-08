// Ported from spiculedata/saiku-cloud (#1374), adapted to this repository.
// Hermetic tests for the CI feedback builder, renderer, collector and comment upsert.
// No network, no clock, no environment: the GitHub API is a fake, the CLI is
// run with an empty environment. `node --test .github/scripts/`
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

import {
  buildFeedback,
  collectInput,
  COMMENT_MARKER,
  createApi,
  main,
  mdCode,
  mdFence,
  mdText,
  parseArgs,
  renderMarkdown,
  REPRO_BY_WORKFLOW,
  reproCommandFor,
  safeUrl,
  SCHEMA_VERSION,
  upsertComment
} from './ci-feedback.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, '..', '..');
const fixture = (name) => readFileSync(join(here, 'fixtures', 'ci-feedback', name), 'utf8');
const SHA = 'a'.repeat(40);
const GENERATED_AT = '2026-10-03T10:00:00.000Z';

const failingRun = (overrides = {}) => ({
  id: 101,
  name: 'ci',
  status: 'completed',
  conclusion: 'failure',
  url: 'https://github.com/o/r/actions/runs/101',
  jobs: [
    {
      id: 1,
      name: 'build (JDK 22, ubuntu-latest)',
      conclusion: 'failure',
      url: 'https://github.com/o/r/actions/runs/101/job/1',
      logText: fixture('surefire-test-error.txt')
    },
    { id: 2, name: 'dist', conclusion: 'success', url: 'https://github.com/o/r/actions/runs/101/job/2', logText: null },
    {
      id: 3,
      name: 'ci',
      conclusion: 'failure',
      url: 'https://github.com/o/r/actions/runs/101/job/3',
      logText: '##[error]build=failure\n##[error]Process completed with exit code 1.'
    }
  ],
  artifacts: [
    { name: 'playwright-test-results', url: 'https://github.com/o/r/actions/runs/101/artifacts/9', expired: false, sizeInBytes: 42 },
    { name: 'flake-records-build', url: 'https://github.com/o/r/actions/runs/101/artifacts/10', expired: false, sizeInBytes: null }
  ],
  ...overrides
});

const build = (extra = {}) =>
  buildFeedback({ repository: 'o/r', sha: SHA, branch: 'feature/x', pullRequest: 12, runs: [failingRun()], generatedAt: GENERATED_AT, ...extra });

// ------------------------------------------------------------ schema conformance

/** The subset of JSON Schema the checked-in schema uses: enough to catch drift in either direction. */
function validate(schema, value, path = '$') {
  const errors = [];
  const types = schema.type === undefined ? null : Array.isArray(schema.type) ? schema.type : [schema.type];
  if (types) {
    const actual = value === null ? 'null' : Array.isArray(value) ? 'array' : Number.isInteger(value) ? 'integer' : typeof value;
    const ok = types.some((t) => t === actual || (t === 'number' && typeof value === 'number'));
    if (!ok) return [`${path}: ${actual} is not ${types.join('|')}`];
  }
  if (schema.const !== undefined && value !== schema.const) errors.push(`${path}: not const ${schema.const}`);
  if (schema.enum && !schema.enum.includes(value)) errors.push(`${path}: ${value} not in ${schema.enum.join('|')}`);
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    for (const key of schema.required ?? []) if (!Object.hasOwn(value, key)) errors.push(`${path}: missing "${key}"`);
    for (const [key, child] of Object.entries(value)) {
      if (schema.properties && !schema.properties[key]) errors.push(`${path}: undeclared "${key}"`);
      else if (schema.properties) errors.push(...validate(schema.properties[key], child, `${path}.${key}`));
    }
  }
  if (Array.isArray(value) && schema.items) value.forEach((item, i) => errors.push(...validate(schema.items, item, `${path}[${i}]`)));
  return errors;
}

const schema = JSON.parse(readFileSync(join(repoRoot, '.github', 'ci-feedback.schema.json'), 'utf8'));

test('the schema is versioned in step with the emitter', () => {
  assert.equal(schema.properties.schemaVersion.const, SCHEMA_VERSION);
  assert.equal(build().schemaVersion, SCHEMA_VERSION);
});

test('every document shape the builder can emit satisfies the checked-in schema', () => {
  const cases = {
    failing: build(),
    passing: buildFeedback({ runs: [failingRun({ conclusion: 'success', jobs: [{ id: 1, name: 'ok', conclusion: 'success' }] })], generatedAt: GENERATED_AT }),
    pending: buildFeedback({ runs: [{ id: 1, name: 'ci', status: 'in_progress', conclusion: null, jobs: [], artifacts: [] }], generatedAt: GENERATED_AT }),
    empty: buildFeedback({ generatedAt: GENERATED_AT }),
    noLog: build({ runs: [failingRun({ jobs: [{ id: 1, name: 'ci / x', conclusion: 'timed_out', url: null, logText: null }] })] })
  };
  for (const [name, doc] of Object.entries(cases)) assert.deepEqual(validate(schema, doc), [], name);
});

test('the validator itself rejects drift (so the check above means something)', () => {
  const doc = build();
  assert.ok(validate(schema, { ...doc, extra: 1 }).length > 0);
  const { status: _status, ...missing } = doc;
  assert.ok(validate(schema, missing).length > 0);
  assert.ok(validate(schema, { ...doc, status: 'weird' }).length > 0);
});

// ------------------------------------------------------------ build

test('acceptance 1: failing checks, first real error, test ids, repro, artifacts', () => {
  const doc = build();
  assert.equal(doc.status, 'failed');
  assert.deepEqual(doc.failingChecks.map((c) => c.name), ['build (JDK 22, ubuntu-latest)', 'ci']);
  const first = doc.failures[0];
  assert.match(first.firstError.excerpt, /SaikuOlapConnectionLocationTest\.xmlaLocationStillGetsThePropertyTerminator -- Time elapsed/);
  assert.equal(doc.failingTestIds.length, 2);
  assert.match(doc.reproCommand, /mvn -B -ntp -DskipITs=false verify/);
  assert.deepEqual(doc.evidenceArtifacts.map((a) => a.name), ['playwright-test-results']);
  assert.equal(doc.pullRequest.number, 12);
});

test('acceptance 3: deprecation notices never reach the primary error, but stay auditable', () => {
  const noisy = `##[warning]Node.js 20 is deprecated. The following actions target Node.js 20 but are being forced to run on Node.js 24: actions/checkout@v4.\n${fixture('maven-compile-error.txt')}`;
  const doc = build({ runs: [failingRun({ jobs: [{ id: 1, name: 'build (JDK 22, ubuntu-latest)', conclusion: 'failure', logText: noisy }] })] });
  assert.doesNotMatch(JSON.stringify(doc.failures), /Node\.js 20|DeprecationWarning|forced to run/);
  assert.ok(doc.filteredNoise.some((line) => /Node\.js 20 is deprecated/.test(line)));
  assert.doesNotMatch(renderMarkdown(doc).split('<details>')[0], /Node\.js 20 is deprecated/);
});

test('the `ci` rollup job sorts last and never supplies the headline repro', () => {
  const doc = build({ runs: [failingRun({ jobs: [failingRun().jobs[2], failingRun().jobs[0]] })] });
  assert.deepEqual(doc.failures.map((f) => f.rollup), [false, true]);
  assert.match(doc.reproCommand, /mvn -B -ntp/);
  // only the job named `ci` in the workflow named `ci` is the rollup
  const other = build({ runs: [{ ...failingRun(), name: 'docker', jobs: [{ id: 5, name: 'ci', conclusion: 'failure', logText: 'AssertionError: x' }] }] });
  assert.equal(other.failures[0].rollup, false);
});

test('a failing job without a log says so instead of inventing an error', () => {
  const doc = build({ runs: [failingRun({ jobs: [{ id: 1, name: 'ci / x', conclusion: 'failure', logText: '' }] })] });
  assert.equal(doc.failures[0].logAvailable, false);
  assert.equal(doc.failures[0].firstError, null);
  assert.match(renderMarkdown(doc), /job log was not available/);
});

test('a log holding only deprecation noise yields no error and says so', () => {
  const doc = build({
    runs: [failingRun({ jobs: [{ id: 1, name: 'ci / x', conclusion: 'failure', logText: '##[warning]Node.js 20 is deprecated. forced to run on Node.js 24: actions/checkout@v4' }] })]
  });
  assert.equal(doc.failures[0].firstError, null);
  assert.equal(doc.failures[0].logAvailable, true);
  assert.match(renderMarkdown(doc), /No error-shaped line/);
});

test('status: passed, pending and cancelled', () => {
  const ok = { id: 1, name: 'a', status: 'completed', conclusion: 'success', jobs: [{ id: 1, name: 'j', conclusion: 'success' }] };
  assert.equal(buildFeedback({ runs: [ok] }).status, 'passed');
  assert.equal(buildFeedback({ runs: [ok, { id: 2, name: 'b', status: 'in_progress', conclusion: null }] }).status, 'pending');
  assert.equal(buildFeedback({ runs: [{ ...ok, conclusion: 'cancelled', jobs: [{ id: 1, name: 'j', conclusion: 'cancelled' }] }] }).status, 'cancelled');
  // a failure wins over a still-running sibling
  assert.equal(build({ runs: [failingRun(), { id: 2, name: 'b', status: 'queued', conclusion: null }] }).status, 'failed');
});

test('failures are capped and the overflow is counted', () => {
  const jobs = Array.from({ length: 40 }, (_, i) => ({ id: i, name: `job ${i}`, conclusion: 'failure', logText: 'AssertionError: x' }));
  const doc = build({ runs: [failingRun({ jobs })] });
  assert.equal(doc.failures.length, 25);
  assert.equal(doc.omittedFailures, 15);
});

test('preview URL: explicit value wins, log scan is limited to this pull request', () => {
  const log = 'AssertionError: x\nsee https://pr-12.preview.saiku.bi/login and https://pr-99.preview.saiku.bi/';
  const runs = [failingRun({ jobs: [{ id: 1, name: 'ci / x', conclusion: 'failure', logText: log }] })];
  assert.equal(build({ runs }).previewUrl, 'https://pr-12.preview.saiku.bi/login');
  assert.equal(build({ runs, previewUrl: 'https://pr-12-api.preview.saiku.bi' }).previewUrl, 'https://pr-12-api.preview.saiku.bi');
  assert.equal(build({ runs, previewUrl: 'https://evil.example/' }).previewUrl, 'https://pr-12.preview.saiku.bi/login');
  assert.equal(build({ runs, pullRequest: null }).previewUrl, null);
});

test('repro commands: job-specific, workflow-level, and a rerun fallback', () => {
  assert.equal(reproCommandFor('ci', 'build (JDK 22, ubuntu-latest)', 5), './scripts/check-licence-headers.sh && mvn -B -ntp -DskipITs=false verify');
  assert.equal(reproCommandFor('ci', 'ci / flake-policy tests', 5), 'node --test ".github/scripts/*.test.mjs"');
  assert.equal(reproCommandFor('design-system', 'whatever', 5), REPRO_BY_WORKFLOW['design-system']);
  assert.equal(reproCommandFor('something / new', 'x', 42), 'gh run rerun 42 --failed');
  assert.equal(reproCommandFor('something / new', 'x', 'not a number; rm -rf /'), 'gh run view --log-failed');
});

test('every npm script a repro command names exists in saiku-ui/package.json', () => {
  const scripts = JSON.parse(readFileSync(join(repoRoot, 'saiku-ui', 'package.json'), 'utf8')).scripts;
  const source = readFileSync(join(here, 'ci-feedback.mjs'), 'utf8');
  const named = new Set([...source.matchAll(/npm run (?!--)([a-z][\w:-]*)/g)].map((m) => m[1]).filter((name) => name !== 'build'));
  assert.ok(named.size >= 3, [...named].join(','));
  for (const script of named) assert.ok(scripts[script], `saiku-ui/package.json has no "${script}" script`);
  assert.ok(scripts.build, 'saiku-ui has a build script');
});

// ------------------------------------------------------------ rendering / injection

test('acceptance 2 (content): the comment carries the same facts as the JSON', () => {
  const md = renderMarkdown(build({ previewUrl: 'https://pr-12.preview.saiku.bi', feedbackUrl: 'https://github.com/o/r/actions/runs/7' }));
  assert.ok(md.startsWith(COMMENT_MARKER));
  assert.match(md, /\*\*failing\*\*/);
  assert.match(md, /build \\\(JDK 22, ubuntu\\-latest\\\)|build/);
  assert.match(md, /xmlaLocationStillGetsThePropertyTerminator/);
  assert.match(md, /mvn -B -ntp -DskipITs=false verify/);
  assert.match(md, /Preview environment:\*\* <https:\/\/pr-12\.preview\.saiku\.bi>/);
  assert.match(md, /playwright.*test.*results.*\]\(https:\/\/github\.com\/o\/r\/actions\/runs\/101\/artifacts\/9\)/);
  assert.match(md, /\[artifact on this run\]\(https:\/\/github\.com\/o\/r\/actions\/runs\/7\)/);
  assert.equal(md.split(COMMENT_MARKER).length, 2, 'the marker appears exactly once');
});

test('markdown helpers neutralise mentions, markup and links', () => {
  assert.doesNotMatch(mdText('@everyone and @org/team'), /@(?!​)/);
  assert.doesNotMatch(mdText('[click](https://evil.example)'), /\]\(/);
  assert.doesNotMatch(mdText('<img src=x onerror=alert(1)>'), /(?<!\\)</);
  assert.equal(mdCode('a`b'), "`a'b`");
  assert.equal(mdText('x'.repeat(500)).length, 200);
  const fence = mdFence('```\n## break out\n````');
  assert.ok(fence.startsWith('`````text'), 'fence outgrows the longest backtick run');
  assert.ok(fence.endsWith('\n`````'));
});

test('safeUrl accepts https only and nothing that can break out of markdown', () => {
  assert.equal(safeUrl('https://github.com/o/r/actions/runs/1'), 'https://github.com/o/r/actions/runs/1');
  for (const bad of ['http://x.com', 'javascript:alert(1)', 'https://x.com/a b', 'https://x.com/)[evil](', 'https://x.com/<script>', '', null, 'https://x.com/' + 'a'.repeat(600)]) {
    assert.equal(safeUrl(bad), null, String(bad).slice(0, 40));
  }
});

test('hostile job, test and log text cannot ping, link, or forge the marker', () => {
  const hostile = [
    'AssertionError: @everyone ```',
    COMMENT_MARKER,
    '## fake heading',
    '[x](https://evil.example)',
    'DB_PASSWORD=hunter2hunter2',
    "FAILED tests/t.py::test_@team_[x](https://evil.example)_`code`"
  ].join('\n');
  const doc = build({
    runs: [
      failingRun({
        name: '@org/admins ](https://evil.example)',
        jobs: [{ id: 1, name: '@everyone `x` <b>bold</b>', conclusion: 'failure', url: 'https://evil.example/ x', logText: hostile }],
        artifacts: [{ name: '[a](https://evil.example)', url: 'javascript:alert(1)', expired: false }]
      })
    ],
    feedbackUrl: 'https://github.com/o/r/actions/runs/7'
  });
  const md = renderMarkdown(doc);
  assert.equal(md.split(COMMENT_MARKER).length, 2, 'only our own marker, at the top');
  assert.doesNotMatch(md, /hunter2/);
  assert.doesNotMatch(md, /javascript:/);
  const prose = md.replace(/(`{3,})text[\s\S]*?\n\1/g, '').replace(/`[^`\n]*`/g, '');
  assert.doesNotMatch(prose, /\]\(https:\/\/evil\.example/, 'no attacker-chosen link target outside code');
  assert.doesNotMatch(md, /<b>/);
  // The only `@` mentions left are inside code, where GitHub does not notify.
  const outsideFence = md.replace(/(`{3,})text[\s\S]*?\n\1/g, '').replace(/`[^`\n]*`/g, '');
  assert.doesNotMatch(outsideFence, /@(?!​)\w/);
});

test('the comment stays under the GitHub size limit even for a pathological input', () => {
  const jobs = Array.from({ length: 30 }, (_, i) => ({
    id: i,
    name: `job ${i} ${'n'.repeat(200)}`,
    conclusion: 'failure',
    logText: `AssertionError: ${'e'.repeat(900)}\n${Array.from({ length: 60 }, (_, k) => `FAILED tests/t.py::test_${i}_${k}_${'z'.repeat(250)}`).join('\n')}`
  }));
  const md = renderMarkdown(build({ runs: [failingRun({ jobs })] }));
  assert.ok(md.length <= 60000, `${md.length} chars`);
  assert.ok(md.startsWith(COMMENT_MARKER));
});

test('green and pending documents render short', () => {
  const green = renderMarkdown(buildFeedback({ runs: [{ id: 1, name: 'ci', status: 'completed', conclusion: 'success', jobs: [] }], generatedAt: GENERATED_AT }));
  assert.match(green, /Nothing to fix/);
  assert.ok(green.length < 900);
  const pending = renderMarkdown(buildFeedback({ runs: [{ id: 1, name: 'ci', status: 'in_progress', conclusion: null }], generatedAt: GENERATED_AT }));
  assert.match(pending, /Still running: ci/);
});

// ------------------------------------------------------------ collect (fake API)

function fakeApi({ runs, jobsByRun = {}, artifactsByRun = {}, logs = {}, failLog = new Set() }) {
  const calls = [];
  return {
    calls,
    async paginate(path, key) {
      calls.push(path);
      if (path.includes('/actions/runs?head_sha=')) return runs;
      const run = /runs\/(\d+)\/(jobs|artifacts)/.exec(path);
      if (run) return (run[2] === 'jobs' ? jobsByRun : artifactsByRun)[run[1]] ?? [];
      return [];
    },
    async jobLog(_repo, jobId) {
      calls.push(`log ${jobId}`);
      if (failLog.has(jobId)) throw new Error('boom');
      return logs[jobId] ?? null;
    }
  };
}

test('collectInput: newest run per workflow, own workflow excluded by path, logs only for failing jobs', async () => {
  const api = fakeApi({
    runs: [
      { id: 1, name: 'ci', run_number: 5, status: 'completed', conclusion: 'failure', html_url: 'https://github.com/o/r/actions/runs/1', path: '.github/workflows/ci.yml' },
      { id: 2, name: 'ci', run_number: 6, status: 'completed', conclusion: 'success', html_url: 'https://github.com/o/r/actions/runs/2', path: '.github/workflows/ci.yml' },
      { id: 3, name: 'ci feedback', run_number: 1, status: 'in_progress', conclusion: null, path: '.github/workflows/ci-feedback.yml' },
      // A PR may rename its own workflow to "ci feedback" to hide it: only the path identifies ours.
      { id: 4, name: 'ci feedback', run_number: 1, status: 'completed', conclusion: 'failure', html_url: 'https://github.com/o/r/actions/runs/4', path: '.github/workflows/sneaky.yml' }
    ],
    jobsByRun: {
      2: [{ id: 20, name: 'ok', conclusion: 'success' }],
      4: [{ id: 40, name: 'bad', conclusion: 'failure', html_url: 'https://github.com/o/r/actions/runs/4/job/40' }]
    },
    artifactsByRun: { 4: [{ id: 77, name: 'trace', expired: false, size_in_bytes: 5 }] },
    logs: { 40: 'AssertionError: nope' }
  });
  const input = await collectInput({ api, repo: 'o/r', sha: SHA, pullRequest: '3' });
  assert.deepEqual(input.runs.map((r) => r.id), [2, 4]);
  assert.deepEqual(api.calls.filter((c) => c.startsWith('log ')), ['log 40']);
  assert.equal(input.runs[1].artifacts[0].url, 'https://github.com/o/r/actions/runs/4/artifacts/77');
  const doc = buildFeedback(input);
  assert.equal(doc.status, 'failed');
  assert.match(doc.failures[0].firstError.excerpt, /nope/);
});

test('collectInput: a log that cannot be fetched does not hide the failure', async () => {
  const api = fakeApi({
    runs: [{ id: 4, name: 'ci', run_number: 1, status: 'completed', conclusion: 'failure', html_url: 'https://github.com/o/r/actions/runs/4' }],
    jobsByRun: { 4: [{ id: 40, name: 'bad', conclusion: 'failure' }] },
    failLog: new Set([40])
  });
  const doc = buildFeedback(await collectInput({ api, repo: 'o/r', sha: SHA }));
  assert.equal(doc.failures.length, 1);
  assert.equal(doc.failures[0].logAvailable, false);
});

test('collectInput: only the first 12 failing jobs get a log download', async () => {
  const jobs = Array.from({ length: 20 }, (_, i) => ({ id: 100 + i, name: `j${i}`, conclusion: 'failure' }));
  const api = fakeApi({ runs: [{ id: 4, name: 'ci', run_number: 1, status: 'completed', conclusion: 'failure' }], jobsByRun: { 4: jobs } });
  await collectInput({ api, repo: 'o/r', sha: SHA });
  assert.equal(api.calls.filter((c) => c.startsWith('log ')).length, 12);
});

test('collectInput rejects ids that could reshape an API path', async () => {
  const api = fakeApi({ runs: [] });
  await assert.rejects(collectInput({ api, repo: 'o/r/../x', sha: SHA }), /invalid --repo/);
  await assert.rejects(collectInput({ api, repo: 'o/r', sha: 'main' }), /invalid --sha/);
  await assert.rejects(collectInput({ api, repo: 'o/r', sha: `${SHA}?x=1` }), /invalid --sha/);
  assert.deepEqual(api.calls, []);
});

// ------------------------------------------------------------ sticky comment

function commentApi(existing) {
  const sent = [];
  return {
    sent,
    async paginate() {
      return existing;
    },
    async send(method, path, body) {
      sent.push({ method, path, body });
      return {};
    }
  };
}
const bot = { login: 'github-actions[bot]' };

test('acceptance 2: sticky comment is created on failure and updated on each later run', async () => {
  const failed = build();
  const created = commentApi([]);
  assert.equal(await upsertComment({ api: created, repo: 'o/r', pr: 12, feedback: failed }), 'created');
  assert.equal(created.sent[0].method, 'POST');
  assert.equal(created.sent[0].path, '/repos/o/r/issues/12/comments');
  assert.ok(created.sent[0].body.body.startsWith(COMMENT_MARKER));

  const stale = commentApi([{ id: 55, user: bot, body: `${COMMENT_MARKER}\nold` }]);
  assert.equal(await upsertComment({ api: stale, repo: 'o/r', pr: 12, feedback: failed }), 'updated');
  assert.deepEqual([stale.sent[0].method, stale.sent[0].path], ['PATCH', '/repos/o/r/issues/comments/55']);

  const same = commentApi([{ id: 55, user: bot, body: renderMarkdown(failed) }]);
  assert.equal(await upsertComment({ api: same, repo: 'o/r', pr: 12, feedback: failed }), 'unchanged');
  assert.equal(same.sent.length, 0);
});

test('a green commit refreshes an existing comment but never creates one', async () => {
  const green = buildFeedback({ runs: [{ id: 1, name: 'ci', status: 'completed', conclusion: 'success', jobs: [] }], generatedAt: GENERATED_AT });
  const quiet = commentApi([]);
  assert.equal(await upsertComment({ api: quiet, repo: 'o/r', pr: 12, feedback: green }), 'skipped');
  assert.equal(quiet.sent.length, 0);
  const existing = commentApi([{ id: 9, user: bot, body: `${COMMENT_MARKER}\nfailing` }]);
  assert.equal(await upsertComment({ api: existing, repo: 'o/r', pr: 12, feedback: green }), 'updated');
  assert.match(existing.sent[0].body.body, /Nothing to fix/);
});

test('a person quoting the marker is never edited', async () => {
  const api = commentApi([
    { id: 1, user: { login: 'someone' }, body: `${COMMENT_MARKER}\nquoted` },
    { id: 2, user: bot, body: 'unrelated bot comment' }
  ]);
  assert.equal(await upsertComment({ api, repo: 'o/r', pr: 12, feedback: build() }), 'created');
  assert.equal(api.sent[0].method, 'POST');
});

test('upsertComment validates the pull request number and repository', async () => {
  await assert.rejects(upsertComment({ api: commentApi([]), repo: 'o/r', pr: '12/../x', feedback: build() }), /invalid --pr/);
  await assert.rejects(upsertComment({ api: commentApi([]), repo: 'nope', pr: 12, feedback: build() }), /invalid --repo/);
});

// ------------------------------------------------------------ HTTP client

test('jobLog follows the redirect without sending the token to the blob host and keeps the tail', async () => {
  const requests = [];
  const big = Buffer.concat([Buffer.alloc(9 * 1024 * 1024, 'a'), Buffer.from('THE-END')]);
  const fetchImpl = async (url, options = {}) => {
    requests.push({ url, auth: options.headers?.Authorization ?? null });
    if (url.startsWith('https://api.github.com/')) {
      return { ok: false, status: 302, headers: { get: () => 'https://blob.example/log?sig=abc' } };
    }
    return { ok: true, status: 200, arrayBuffer: async () => big.buffer.slice(big.byteOffset, big.byteOffset + big.length) };
  };
  const api = createApi({ token: 'secret-token', fetchImpl });
  const text = await api.jobLog('o/r', 5);
  assert.equal(requests[0].auth, 'Bearer secret-token');
  assert.equal(requests[1].auth, null, 'the pre-signed URL must not receive the token');
  assert.ok(text.endsWith('THE-END'));
  assert.equal(text.length, 8 * 1024 * 1024);
});

test('jobLog refuses a non-https redirect and treats an error status as no log', async () => {
  const http = createApi({ fetchImpl: async () => ({ ok: false, status: 302, headers: { get: () => 'http://169.254.169.254/' } }) });
  assert.equal(await http.jobLog('o/r', 5), null);
  const gone = createApi({ fetchImpl: async () => ({ ok: false, status: 410, headers: { get: () => null } }) });
  assert.equal(await gone.jobLog('o/r', 5), null);
});

// ------------------------------------------------------------ CLI

test('parseArgs reads flags and the positional mode', () => {
  const args = parseArgs(['collect', '--repo', 'o/r', '--pr', '', '--md']);
  assert.equal(args._[0], 'collect');
  assert.equal(args.repo, 'o/r');
  assert.equal(args.pr, '');
  assert.equal(args.md, true);
});

test('main collect writes the JSON and markdown through the injected writer', async () => {
  const api = fakeApi({
    runs: [{ id: 4, name: 'ci', run_number: 1, status: 'completed', conclusion: 'failure', html_url: 'https://github.com/o/r/actions/runs/4' }],
    jobsByRun: { 4: [{ id: 40, name: 'ui (svelte-check + vitest + build + e2e)', conclusion: 'failure' }] },
    logs: { 40: fixture('prettier-failure.txt') }
  });
  const written = {};
  const code = await main(['collect', '--repo', 'o/r', '--sha', SHA, '--pr', '12', '--out', 'f.json', '--md', 'f.md'], {
    env: {},
    api,
    write: (path, text) => {
      written[path] = text;
    }
  });
  assert.equal(code, 0);
  const doc = JSON.parse(written['f.json']);
  assert.deepEqual(validate(schema, doc), []);
  assert.match(doc.failures[0].firstError.excerpt, /Code style issues found/);
  assert.match(doc.reproCommand, /npm run lint/);
  assert.ok(written['f.md'].startsWith(COMMENT_MARKER));
});

test('CLI render works with an empty environment (no CI variables, no token)', () => {
  const dir = mkdtempSync(join(tmpdir(), 'ci-feedback-'));
  try {
    writeFileSync(join(dir, 'f.json'), JSON.stringify(build()));
    const result = spawnSync(process.execPath, [join(here, 'ci-feedback.mjs'), 'render', '--feedback', join(dir, 'f.json')], {
      env: { PATH: process.env.PATH },
      encoding: 'utf8'
    });
    assert.equal(result.status, 0, result.stderr);
    assert.ok(result.stdout.startsWith(COMMENT_MARKER));
    const usage = spawnSync(process.execPath, [join(here, 'ci-feedback.mjs')], { env: {}, encoding: 'utf8' });
    assert.equal(usage.status, 2);
    const bad = spawnSync(process.execPath, [join(here, 'ci-feedback.mjs'), 'render'], { env: {}, encoding: 'utf8' });
    assert.equal(bad.status, 1);
    assert.match(bad.stderr, /render needs --feedback/);
    assert.deepEqual(readdirSync(dir), ['f.json'], 'render must not write files');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// ------------------------------------------------------------ workflow wiring

const workflowsDir = join(repoRoot, '.github', 'workflows');
const workflowName = (text) => (/^name:\s*(.+?)\s*$/m.exec(text) ?? [])[1]?.replace(/^['"]|['"]$/g, '');

/**
 * Workflows that run on pull requests/pushes but are bot tooling, reviews or releases, not CI
 * gates, so they are deliberately not observed. Everything else with a `pull_request` trigger
 * or a `push` trigger on `development` must be listed in ci-feedback.yml.
 */
const DELIBERATELY_UNOBSERVED = new Set([
  'auto-review',
  'Labeler',
  'Tier Classifier',
  'PR Metrics',
  'quality-report',
  'nightly-compliance',
  'flake-ledger',
  'Claude Code',
  'Claude Code Review',
  'release',
  'ci feedback'
]);
/** PR-gating workflows that live on open PR branches and are listed ahead of their merge. */
const NOT_YET_ON_DEVELOPMENT = new Set(['security-scan', 'model-diff']);

const triggers = (text) => text.slice(text.search(/^on:/m)).split(/\n(?=[a-z])/)[0];
const gatesPullRequests = (on) => /^ {2}pull_request:/m.test(on);
const gatesDevelopmentPushes = (on) => /^ {2}push:\n(?: {4}.*\n)*? {4}branches:.*\bdevelopment\b/m.test(on);

test('ci-feedback.yml observes every PR-gating workflow (add new gates to its list)', () => {
  const feedback = readFileSync(join(workflowsDir, 'ci-feedback.yml'), 'utf8');
  const listed = [...feedback.slice(feedback.indexOf('workflows:'), feedback.indexOf('types:')).matchAll(/^\s+- (.+?)\s*$/gm)].map((m) => m[1]);
  assert.ok(listed.length >= 4, 'the observed list was not parsed');
  const gating = [];
  for (const file of readdirSync(workflowsDir).filter((f) => f.endsWith('.yml'))) {
    const text = readFileSync(join(workflowsDir, file), 'utf8');
    const on = triggers(text);
    const name = workflowName(text);
    if ((gatesPullRequests(on) || gatesDevelopmentPushes(on)) && !DELIBERATELY_UNOBSERVED.has(name)) gating.push(name);
  }
  // the workflows the task names must really be recognised as gating, not silently skipped
  for (const expected of ['ci', 'docker', 'design-system']) assert.ok(gating.includes(expected), `${expected} should be detected as PR/push gating`);
  const missing = gating.filter((name) => !listed.includes(name));
  assert.deepEqual(missing, [], 'workflows with a pull_request / development push trigger that ci-feedback.yml does not observe');
  const allNames = new Set(readdirSync(workflowsDir).filter((f) => f.endsWith('.yml')).map((f) => workflowName(readFileSync(join(workflowsDir, f), 'utf8'))));
  assert.deepEqual(
    listed.filter((name) => !allNames.has(name) && !NOT_YET_ON_DEVELOPMENT.has(name)),
    [],
    'listed workflows that do not exist (renamed?)'
  );
});

test('ci-feedback.yml security posture: default-branch checkout, minimal permissions, no interpolation into scripts', () => {
  const text = readFileSync(join(workflowsDir, 'ci-feedback.yml'), 'utf8');
  const code = text.split('\n').filter((line) => !/^\s*#/.test(line)).map((line) => line.replace(/\s+#.*$/, '')).join('\n');
  assert.match(code, /ref: \$\{\{ github\.event\.repository\.default_branch \}\}/);
  assert.doesNotMatch(code, /ref: \$\{\{ github\.event\.workflow_run/, 'never check out the run under observation');
  assert.doesNotMatch(code, /pull_request_target/);
  assert.doesNotMatch(code, /actions\/download-artifact|gh run download/, 'PR artifacts are attacker data and are never downloaded');
  assert.match(code, /persist-credentials: false/);
  assert.match(code, /permissions:\n {2}actions: read\n {2}contents: read\n {2}pull-requests: write\n/);
  assert.doesNotMatch(code, /(?:contents|issues|actions|checks|statuses): write/);
  // `${{ ... }}` is only allowed in env:/with:/if:/concurrency, never inside a run: script.
  const runScripts = [...code.matchAll(/^\s+run: \|\n((?:\s{10,}.*\n?)+)/gm)].map((m) => m[1]).join('\n');
  assert.ok(runScripts.length > 0);
  assert.doesNotMatch(runScripts, /\$\{\{/);
});
