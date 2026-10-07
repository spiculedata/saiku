// The acceptance spec for spiculedata/saiku#2172, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2172`.
//
// #2172 is CI tooling, so there is no endpoint to call: the contract is that the
// gate holds a closing PR to the convention, that the runner authenticates the way
// Saiku does, and that a failed spec is recorded as a failure in ci-feedback.json.
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

const { check } = await import(join(scripts, 'acceptance-check.mjs'));
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

const spec = { issue: 99, title: 'a contract', kind: 'api', criteria: [{ id: 'C1', then: 'it holds' }], steps: [] };

const failures = [];
const claim = async (id, fn) => {
  try {
    await fn();
  } catch (err) {
    failures.push(`${id}: ${err.message}`);
  }
};

// C1 - a closing PR with no spec for it is held, and told what is missing.
await claim('C1', () => {
  const result = check({ repoRoot: repoWith(99, null), body: 'Closes #99', enforceFrom: 1 });
  assert.equal(result.ok, false);
  assert.match(result.problems.join('\n'), /closes #99 but adds no acceptance\/99\/spec.json/);
  // rollout safety: with no cutoff configured nothing is enforced
  const unset = check({ repoRoot: repoWith(99, null), body: 'Closes #99', enforceFrom: Number.POSITIVE_INFINITY });
  assert.equal(unset.ok, true);
});

// C2 - shipping the spec satisfies it; a verified docs-only waiver satisfies it too.
await claim('C2', () => {
  assert.equal(check({ repoRoot: repoWith(99, spec), body: 'Closes #99', enforceFrom: 1 }).ok, true);
  const waivedDocs = check({ repoRoot: repoWith(99, null), body: 'Closes #99', labels: ['acceptance-waived'], enforceFrom: 1, changedFiles: ['docs/x.md'] });
  assert.equal(waivedDocs.ok, true);
  const waivedProd = check({ repoRoot: repoWith(99, null), body: 'Closes #99', labels: ['acceptance-waived'], enforceFrom: 1, changedFiles: ['saiku-core/saiku-web/src/main/java/A.java'] });
  assert.equal(waivedProd.ok, false);
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
