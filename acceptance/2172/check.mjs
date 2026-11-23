// The acceptance spec for spiculedata/saiku#2172, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2172`.
//
// #2172 is CI tooling, so there is no endpoint to call: the contract is that the
// gate holds a closing PR to the convention, that the runner authenticates the way
// Saiku does, and that a failed spec is recorded as a failure in ci-feedback.json.
// The gate itself is spiculedata/acceptance-spec's convention.yml, called from ci.yml (its decision
// logic and tests live there), so C1/C2 assert what THIS repository owns: that ci.yml calls it pinned,
// enforcing, with the cutoff variable and Saiku's exact waivable-paths list.
// Exit non-zero on the first broken clause, with the reason on stderr, so the
// runner's "exit code" detail says what broke. Ported in spirit from
// spiculedata/saiku-cloud acceptance/1382.

import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const scripts = join(here, '..', '..', '.github', 'scripts');

const { attachResults, runSpecs } = await import(join(scripts, 'acceptance-runner.mjs'));
const { validateSpec } = await import(join(scripts, 'acceptance-spec.mjs'));

/** A throwaway checkout holding one issue's spec (or deliberately none). */
function repoWith(issue, spec) {
  const root = mkdtempSync(join(tmpdir(), 'acceptance-2172-'));
  if (spec) {
    mkdirSync(join(root, 'acceptance', String(issue)), { recursive: true });
    writeFileSync(join(root, 'acceptance', String(issue), 'spec.json'), JSON.stringify(spec, null, 2));
  }
  return root;
}

/** The text of ci.yml's `acceptance-convention` job (the caller of the shared convention gate). */
function conventionJob() {
  const lines = readFileSync(join(here, '..', '..', '.github', 'workflows', 'ci.yml'), 'utf8').split('\n');
  const start = lines.findIndex((line) => line === '  acceptance-convention:');
  assert.ok(start >= 0, 'ci.yml has an acceptance-convention job');
  let end = lines.length;
  for (let i = start + 1; i < lines.length; i += 1) {
    if (/^ {2}[a-z][\w-]*:\s*$/.test(lines[i])) {
      end = i;
      break;
    }
  }
  return lines.slice(start, end).filter((line) => !line.trim().startsWith('#')).join('\n');
}

/** Saiku's waivable-paths list, compiled the way the shared gate compiles it (one JS regex per line). */
function waivablePaths(job) {
  const all = job.split('\n');
  const start = all.findIndex((line) => line.trim() === 'waivable-paths: |');
  assert.ok(start >= 0, 'the gate passes waivable-paths');
  const indent = all[start].length - all[start].trimStart().length;
  const patterns = [];
  for (let i = start + 1; i < all.length; i += 1) {
    if (all[i].trim() !== '' && all[i].length - all[i].trimStart().length <= indent) break;
    if (all[i].trim() !== '') patterns.push(new RegExp(all[i].trim()));
  }
  return patterns;
}

const failures = [];
const claim = async (id, fn) => {
  try {
    await fn();
  } catch (err) {
    failures.push(`${id}: ${err.message}`);
  }
};

// C1 - a closing PR with no spec for it is held; with no cutoff configured nothing is enforced.
await claim('C1', () => {
  const job = conventionJob();
  assert.match(job, /uses: spiculedata\/acceptance-spec\/\.github\/workflows\/convention\.yml@[0-9a-f]{40}$/m, 'the shared gate, at a full SHA');
  assert.match(job, /mode: enforce/, 'a missing spec fails the job');
  assert.match(job, /enforce-from-issue: \$\{\{ vars\.ACCEPTANCE_ENFORCE_FROM_ISSUE \}\}/, 'the cutoff stays a repository variable (unset enforces nothing)');
  assert.match(job, /name: ci$/m, 'renders as the check `ci / acceptance convention`, inside the ci rollup');
  assert.match(job, /skip-outside-pull-request: true/, 'the called job skips itself on push / merge_group');
  assert.doesNotMatch(job, /^ {4}if:/m, 'no job-level condition: a skipped caller reports a bare `ci` check that could shadow the required one');
});

// C2 - a docs-or-tests-only change may take the waiver; a production file never does.
await claim('C2', () => {
  const patterns = waivablePaths(conventionJob());
  const waivable = (path) => patterns.some((re) => re.test(path));
  for (const ok of ['docs/x.md', 'README.MD', 'saiku-core/src/test/java/A.java', 'saiku-ui/e2e/a.spec.ts', 'tests/x.sh', 'a.test.ts']) {
    assert.equal(waivable(ok), true, `${ok} is docs or tests`);
  }
  for (const no of ['saiku-core/saiku-web/src/main/java/A.java', 'saiku-core/e2e/x.js', 'test/x.java', '.github/workflows/ci.yml', 'scripts/run.sh']) {
    assert.equal(waivable(no), false, `${no} is not waivable`);
  }
});

// C3 - session login: log in once, then send the cookie. No credentials means not-run.
await claim('C3', async () => {
  const seen = [];
  const server = createServer((req, res) => {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      seen.push({ method: req.method, url: req.url, cookie: req.headers.cookie, body });
      if (req.method === 'POST' && req.url === '/rest/saiku/session') {
        const ok = new URLSearchParams(body).get('password') === 'pw';
        res.writeHead(ok ? 200 : 401, ok ? { 'set-cookie': ['JSESSIONID=s1; Path=/; HttpOnly', 'XSRF-TOKEN=x1; Path=/'] } : {});
        return res.end();
      }
      const authed = /JSESSIONID=s1/.test(req.headers.cookie ?? '');
      res.writeHead(authed ? 200 : 401, { 'content-type': 'application/json' });
      res.end(JSON.stringify(authed ? [{ cubeName: 'Sales' }] : { error: 'unauthorized' }));
    });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    const baseUrl = `http://127.0.0.1:${server.address().port}`;
    const sessionSpec = {
      issue: 98,
      title: 'cubes need a session',
      kind: 'api',
      auth: 'session',
      criteria: [{ id: 'C1', then: 'the cube list is served to a logged-in user' }],
      steps: [{ name: 'cubes', criterion: 'C1', request: { method: 'GET', path: '/ai/cubes' }, expect: { status: 200 } }]
    };
    const root = repoWith(98, sessionSpec);
    const ran = await runSpecs({ repoRoot: root, baseUrl, env: { SAIKU_ACCEPTANCE_USER: 'admin', SAIKU_ACCEPTANCE_PASSWORD: 'pw' } });
    assert.equal(ran.verdict, 'pass', JSON.stringify(ran.specs));
    assert.ok(seen.some((c) => c.method === 'GET' && c.url === '/rest/saiku/api/ai/cubes' && /JSESSIONID=s1/.test(c.cookie)));
    const noCredentials = await runSpecs({ repoRoot: root, baseUrl, env: {} });
    assert.equal(noCredentials.verdict, 'not-run');
  } finally {
    server.close();
  }
});

// C4 - a failed spec is a failure in ci-feedback.json, at the same schema version.
await claim('C4', () => {
  const results = {
    schemaVersion: 1,
    generatedAt: '2026-10-04T00:00:00.000Z',
    baseUrl: 'https://acceptance.example',
    verdict: 'fail',
    specs: [
      {
        issue: 99,
        kind: 'api',
        verdict: 'fail',
        checks: [{ name: 'the check', criterion: 'C1', verdict: 'fail', failures: [{ assertion: 'status', ok: false, detail: 'expected 200, got 500' }] }]
      }
    ]
  };
  const doc = attachResults(null, results, { repo: 'spiculedata/saiku', pr: 1 });
  assert.equal(doc.schemaVersion, 1, 'an added optional field is not a version bump');
  assert.equal(doc.acceptance.verdict, 'fail');
  assert.equal(doc.status, 'failed');
  assert.deepEqual(doc.failingTestIds, ['acceptance/99']);
  assert.match(doc.reproCommand, /acceptance-runner\.mjs run --dir acceptance\/99/);
  assert.match(doc.failures[0].firstError.excerpt, /expected 200, got 500/);

  const passing = attachResults(null, { ...results, verdict: 'pass', specs: [{ ...results.specs[0], verdict: 'pass', checks: [] }] }, {});
  assert.equal(passing.status, 'passed');
});

// This spec is itself a spec: it must satisfy the format it teaches.
await claim('format', () => {
  const own = JSON.parse(readFileSync(join(here, 'spec.json'), 'utf8'));
  assert.deepEqual(validateSpec(own), []);
});

if (failures.length > 0) {
  process.stderr.write(`${failures.join('\n')}\n`);
  process.exit(1);
}
process.stdout.write('acceptance/2172: the convention holds a closing PR, the runner logs in, and a failed spec reads as failed\n');
