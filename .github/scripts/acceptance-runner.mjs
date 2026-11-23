#!/usr/bin/env node
// Runs the `acceptance/<issue>/` specs of a PR against a base URL and folds the
// result into ci-feedback.json. Ported from spiculedata/saiku-cloud (#1382) and adapted
// to Saiku's REST surface: relative paths resolve under /rest/saiku/api, and a spec can
// log in with a session (POST /rest/saiku/session) or HTTP basic, using
// SAIKU_ACCEPTANCE_USER / SAIKU_ACCEPTANCE_PASSWORD from the environment.
//
// A spec is a data file (acceptance/<issue>/spec.json, documented in
// acceptance/README.md and docs/acceptance-specs.md):
//
//   kind "api"  steps[] of { request: { method, path, headers, json }, expect }
//   kind "ui"   command[] (argv, never a shell) run in the spec directory with
//               SAIKU_BASE_URL in its environment
//
// Secrets never live in a spec: `{{env.NAME}}` in a header or body value is
// replaced from the environment, and an unresolved placeholder is an error.
//
//   acceptance-runner.mjs run --base-url https://pr-1412-api.preview.saiku.bi \
//                             --all --out acceptance-results.json \
//                             [--feedback ci-feedback.json]
//   acceptance-runner.mjs attach --results acceptance-results.json \
//                               --feedback ci-feedback.json --out ci-feedback.json
//
// Exit 0 when nothing failed (including "nothing could run"), 1 when a spec ran
// and failed, 2 on bad input. A spec that could not run is never a pass: it is
// recorded `not-run` with a reason, exactly like the validation report (#1385).

import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

import { pathToFileURL } from 'node:url';

import {
  DEFAULT_BASE_PATH,
  SESSION_PATH,
  SPEC_FILE,
  evaluateExpect,
  interpolate,
  validateSpec,
  verdictFor
} from './acceptance-spec.mjs';

/** Every issue number with a spec directory under the repo root. */
export function specIssues(repoRoot) {
  const base = join(repoRoot, 'acceptance');
  if (!existsSync(base)) return [];
  return readdirSync(base, { withFileTypes: true })
    .filter((e) => e.isDirectory() && /^\d+$/.test(e.name))
    .map((e) => Number(e.name))
    .sort((a, b) => a - b);
}

function readSpec(repoRoot, issue) {
  const file = join(repoRoot, 'acceptance', String(issue), SPEC_FILE);
  const text = readFileSync(file, 'utf8');
  const parsed = JSON.parse(text);
  const errors = validateSpec(parsed);
  return { parsed, errors };
}

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

/** Thrown when a spec asks for authentication the environment cannot provide. */
export class MissingCredentials extends Error {}

/**
 * The credentials the environment offers. They are only ever read from the
 * environment (`SAIKU_ACCEPTANCE_USER` / `SAIKU_ACCEPTANCE_PASSWORD`), never from a spec.
 */
export function credentialsFrom(env) {
  const username = env.SAIKU_ACCEPTANCE_USER;
  const password = env.SAIKU_ACCEPTANCE_PASSWORD;
  return username && password ? { username, password } : null;
}

/**
 * A tiny cookie-keeping HTTP client bound to ONE origin. It never follows a redirect
 * and never sends credentials or cookies anywhere but the base URL's origin, so a
 * PR-authored spec cannot use it to carry the acceptance account elsewhere.
 */
export function createSession({ baseUrl, env = process.env, timeoutMs = 10_000, fetchImpl = globalThis.fetch }) {
  const origin = new URL(baseUrl).origin;
  const jar = new Map();
  let loggedIn = false;
  /** A refused login is remembered: retrying per step would hammer the login rate limiter. */
  let loginFailure = null;

  const absorb = (response) => {
    const cookies = typeof response.headers.getSetCookie === 'function' ? response.headers.getSetCookie() : [];
    for (const cookie of cookies) {
      const [pair] = cookie.split(';');
      const at = pair.indexOf('=');
      if (at > 0) {
        const value = pair.slice(at + 1).trim();
        if (/;\s*max-age=0\b/i.test(cookie) || value === '') jar.delete(pair.slice(0, at).trim());
        else jar.set(pair.slice(0, at).trim(), value);
      }
    }
  };

  async function login() {
    if (loginFailure) throw loginFailure;
    const credentials = credentialsFrom(env);
    if (!credentials) {
      throw new MissingCredentials('SAIKU_ACCEPTANCE_USER / SAIKU_ACCEPTANCE_PASSWORD are not set, so the session login cannot run');
    }
    const response = await fetchImpl(`${origin}${SESSION_PATH}`, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded', accept: 'application/json' },
      body: new URLSearchParams(credentials).toString(),
      signal: AbortSignal.timeout(timeoutMs),
      redirect: 'manual'
    });
    absorb(response);
    await response.text();
    if (response.status !== 200) {
      loginFailure = new Error(`login to ${origin}${SESSION_PATH} was refused: HTTP ${response.status}`);
      throw loginFailure;
    }
    loggedIn = true;
  }

  /** Headers that carry the chosen authentication for one request. */
  async function authHeaders(mode, method) {
    if (mode === 'basic') {
      const credentials = credentialsFrom(env);
      if (!credentials) throw new MissingCredentials('SAIKU_ACCEPTANCE_USER / SAIKU_ACCEPTANCE_PASSWORD are not set, so basic auth cannot run');
      return { authorization: `Basic ${Buffer.from(`${credentials.username}:${credentials.password}`).toString('base64')}` };
    }
    if (mode !== 'session') return {};
    if (!loggedIn) await login();
    const headers = { cookie: [...jar].map(([name, value]) => `${name}=${value}`).join('; ') };
    // Spring's CSRF protection wants the XSRF-TOKEN cookie echoed on every non-safe call.
    if (!SAFE_METHODS.has(method) && jar.has('XSRF-TOKEN')) headers['x-xsrf-token'] = jar.get('XSRF-TOKEN');
    return headers;
  }

  return {
    origin,
    /** One request, `mode` being none | session | basic. Cookies set by the response are kept. */
    async request(url, init, mode = 'none') {
      if (new URL(url).origin !== origin) throw new Error(`refusing to send a request outside ${origin}`);
      const method = String(init.method ?? 'GET').toUpperCase();
      const auth = await authHeaders(mode, method);
      const response = await fetchImpl(url, {
        ...init,
        headers: { ...init.headers, ...auth },
        signal: AbortSignal.timeout(timeoutMs),
        redirect: 'manual'
      });
      if (mode === 'session') absorb(response);
      return response;
    }
  };
}

/** Run one api spec's steps against `baseUrl`. */
export async function runApiSpec(spec, { baseUrl, env = process.env, timeoutMs = 10_000, fetchImpl = globalThis.fetch }) {
  const checks = [];
  const session = createSession({ baseUrl, env, timeoutMs, fetchImpl });
  for (const step of spec.steps) {
    const started = Date.now();
    const failures = [];
    const mode = step.auth ?? spec.auth ?? 'none';
    let url;
    let init;
    try {
      url = resolveUrl(baseUrl, interpolate(step.request.path, env), spec.basePath ?? DEFAULT_BASE_PATH);
      init = {
        method: String(step.request.method).toUpperCase(),
        headers: { accept: 'application/json', ...(step.request.headers ? interpolate(step.request.headers, env) : {}) },
        ...(step.request.json !== undefined
          ? { body: JSON.stringify(interpolate(step.request.json, env)) }
          : {})
      };
      if (init.body !== undefined && !Object.keys(init.headers).some((name) => name.toLowerCase() === 'content-type')) {
        init.headers['content-type'] = 'application/json';
      }
    } catch (err) {
      failures.push({ assertion: 'request', ok: false, detail: err.message });
      checks.push({ name: step.name, criterion: step.criterion, verdict: 'fail', durationMs: Date.now() - started, failures });
      continue;
    }
    // `repeat` drives a request until it reaches `untilStatus` (the limiter
    // contract) or the count is spent, and asserts on the last response.
    const count = step.repeat?.count ?? 1;
    const untilStatus = step.repeat?.untilStatus;
    let attempts = 0;
    let response;
    let bodyText = '';
    let json;
    for (let i = 0; i < count; i += 1) {
      attempts = i + 1;
      try {
        response = await session.request(url, init, mode);
        bodyText = await response.text();
        try {
          json = JSON.parse(bodyText);
        } catch {
          json = undefined;
        }
      } catch (err) {
        // Credentials the environment does not offer are "could not run", not a verdict on the product.
        if (err instanceof MissingCredentials) throw err;
        failures.push({ assertion: mode === 'session' && /login/.test(err.message) ? 'login' : 'request', ok: false, detail: `${err.name}: ${err.message} (${url})` });
        break;
      }
      if (untilStatus === undefined || response.status === untilStatus) break;
    }
    if (failures.length === 0) {
      for (const outcome of evaluateExpect({
        status: response.status,
        headers: Object.fromEntries(response.headers.entries()),
        bodyText,
        json,
        expect: step.expect ?? {}
      })) {
        if (!outcome.ok) failures.push(outcome);
      }
    }
    checks.push({
      name: step.name,
      criterion: step.criterion,
      verdict: failures.length === 0 ? 'pass' : 'fail',
      durationMs: Date.now() - started,
      ...(attempts > 1 ? { attempts } : {}),
      ...(failures.length === 0 ? {} : { failures })
    });
  }
  return checks;
}

/**
 * Resolve a step path. A relative path ("/ai/cubes") goes under the spec's base path
 * (default /rest/saiku/api); a path that already starts with /rest/ ("/rest/saiku/info")
 * is used as-is; an absolute URL must stay on the base URL's origin.
 */
export function resolveUrl(baseUrl, path, basePath = DEFAULT_BASE_PATH) {
  const resolved = interpolate(String(path));
  if (/^https?:\/\//i.test(resolved)) {
    let url;
    try {
      url = new URL(resolved);
    } catch {
      throw new Error(`request.path is not a usable URL: ${resolved}`);
    }
    // saiku-cloud#1428: restrict absolute paths to the base URL's origin so a
    // PR-authored spec cannot redirect requests to an attacker-controlled server
    // and exfiltrate interpolated acceptance credentials.
    if (baseUrl) {
      const base = new URL(baseUrl);
      if (url.origin !== base.origin) {
        throw new Error(
          `request.path must be within the base URL origin (${base.origin}); an absolute path to a different origin is not allowed`,
        );
      }
    }
    return url.toString();
  }
  const base = new URL(baseUrl);
  if (!resolved.startsWith('/')) throw new Error(`request.path must start with "/" or be absolute, got "${resolved}"`);
  if (resolved.startsWith('//')) throw new Error(`request.path must not start with "//", got "${resolved}"`);
  const prefixed = resolved.startsWith('/rest/') ? resolved : `${String(basePath).replace(/\/$/, '')}${resolved}`;
  const url = new URL(prefixed, base);
  // A path like "/x/../../etc" or "/\\evil" must not escape the origin or the base path semantics.
  if (url.origin !== base.origin) throw new Error(`request.path resolves outside the base URL origin: ${resolved}`);
  return url.toString();
}

/**
 * What a spec command may see. The specs are authored in the PR under test, so
 * the runner's own environment (tokens, anything the workflow exported) is not
 * handed over: only what a browser/test command needs to run, plus the target
 * URL and the acceptance variables the workflow sets deliberately.
 */
const SPEC_ENV_ALLOW = [/^PATH$/, /^HOME$/, /^TMPDIR$/, /^LANG$/, /^LC_[A-Z]+$/, /^CI$/, /^NODE_OPTIONS$/, /^PLAYWRIGHT_[A-Z_]+$/, /^SAIKU_(ACCEPTANCE|BASE)_[A-Z_]+$/, /^ACCEPTANCE_[A-Z_]+$/];

export function specEnv(source = process.env, baseUrl = '') {
  const env = {};
  for (const [key, value] of Object.entries(source)) {
    if (value !== undefined && SPEC_ENV_ALLOW.some((re) => re.test(key))) env[key] = value;
  }
  return baseUrl ? { ...env, SAIKU_BASE_URL: baseUrl, ACCEPTANCE_BASE_URL: baseUrl } : env;
}

/** Run one ui spec's argv in its own directory. */
export function runUiSpec(spec, { baseUrl, dir, timeoutSeconds = 300 }) {
  const started = Date.now();
  const proc = spawnSync(spec.command[0], spec.command.slice(1), {
    cwd: dir,
    encoding: 'utf8',
    timeout: timeoutSeconds * 1000,
    // A command spec does not need a base URL (a Playwright suite does); when
    // there is none, nothing is exported rather than an empty string, so a spec
    // can tell "not configured" from "configured as empty".
    env: specEnv(process.env, baseUrl)
  });
  const failures = [];
  if (proc.error) {
    failures.push({ assertion: 'command', ok: false, detail: `${proc.error.name}: ${proc.error.message}` });
  } else if (proc.status !== 0) {
    const tail = `${proc.stdout ?? ''}${proc.stderr ?? ''}`.trim().split('\n').slice(-20).join('\n');
    failures.push({
      assertion: 'exit code',
      ok: false,
      detail: `${spec.command.join(' ')} exited ${proc.status}${tail ? `: ${tail.slice(0, 2000)}` : ''}`
    });
  }
  return [
    {
      name: spec.title ?? 'ui spec',
      criterion: spec.criteria?.[0]?.id ?? null,
      verdict: failures.length === 0 ? 'pass' : 'fail',
      durationMs: Date.now() - started,
      ...(failures.length === 0 ? {} : { failures })
    }
  ];
}

/**
 * Execute specs and return the results document.
 * @param {{repoRoot: string, baseUrl?: string, issues?: number[], env?: object}} args
 */
export async function runSpecs({ repoRoot, baseUrl, issues, env = process.env }) {
  const wanted = issues ?? specIssues(repoRoot);
  const specs = [];
  for (const issue of wanted) {
    let parsed;
    let errors;
    try {
      ({ parsed, errors } = readSpec(repoRoot, issue));
    } catch (err) {
      specs.push({ issue, kind: null, verdict: 'not-run', reason: `unreadable spec: ${err.message}`, checks: [] });
      continue;
    }
    const base = { issue, kind: parsed.kind, title: parsed.title, checks: [] };
    if (errors.length > 0) {
      specs.push({ ...base, verdict: 'not-run', reason: `invalid spec: ${errors[0]}`, checks: [] });
      continue;
    }
    if (parsed.kind === 'api' && !baseUrl) {
      specs.push({ ...base, verdict: 'not-run', reason: 'no base URL configured', checks: [] });
      continue;
    }
    const started = Date.now();
    try {
      const checks =
        parsed.kind === 'api'
          ? await runApiSpec(parsed, { baseUrl, env: specEnv(env, baseUrl) })
          : runUiSpec(parsed, { baseUrl, dir: join(repoRoot, 'acceptance', String(issue)) });
      const verdict = checks.some((c) => c.verdict === 'fail') ? 'fail' : 'pass';
      specs.push({ ...base, verdict, durationMs: Date.now() - started, checks });
    } catch (err) {
      const reason = err instanceof MissingCredentials ? `credentials not configured: ${err.message}` : `runner error: ${err.message}`;
      specs.push({ ...base, verdict: 'not-run', reason, checks: [] });
    }
  }
  return {
    schemaVersion: 1,
    generatedAt: new Date().toISOString(),
    baseUrl: baseUrl ?? null,
    verdict: verdictFor(specs),
    specs
  };
}

/** The shape ci-feedback.json gains: small enough to read in one glance. */
export function summariseResults(results) {
  return {
    verdict: results.verdict,
    baseUrl: results.baseUrl,
    generatedAt: results.generatedAt,
    specs: (results.specs ?? []).map((s) => ({
      issue: s.issue,
      kind: s.kind,
      verdict: s.verdict,
      ...(s.reason ? { reason: s.reason } : {}),
      ...((s.checks ?? []).some((c) => c.verdict === 'fail')
        ? { failedChecks: (s.checks ?? []).filter((c) => c.verdict === 'fail').map((c) => c.name) }
        : {})
    }))
  };
}

function identity({ repo, sha, branch, pr, runUrl }) {
  return {
    repository: repo ?? null,
    commit: { sha: sha ?? null, branch: branch ?? null },
    pullRequest: Number.isInteger(pr) && pr > 0 ? { number: pr } : null,
    feedbackUrl: runUrl ?? null
  };
}

/**
 * Fold results into a ci-feedback document. Adds the optional `acceptance`
 * field (schemaVersion stays 1: an added optional field is not a breaking
 * change, see docs/ci-feedback.md) and, when specs failed, makes the document
 * read as a failure so a worker sees it in the same place as everything else.
 */
export function attachResults(feedback, results, ident) {
  const summary = summariseResults(results);
  const failed = (results.specs ?? []).filter((s) => s.verdict === 'fail');
  const firstIssue = failed[0]?.issue ?? results.specs?.[0]?.issue ?? 0;
  const id = identity(ident);

  const doc = isFeedbackDocument(feedback)
    ? structuredClone(feedback)
    : {
        schemaVersion: 1,
        generatedAt: new Date().toISOString(),
        status: 'passed',
        ...id,
        runs: [],
        pendingRuns: [],
        failingChecks: [],
        failures: [],
        omittedFailures: 0,
        failingTestIds: [],
        reproCommand: null,
        artifacts: [],
        evidenceArtifacts: [],
        previewUrl: results.baseUrl ?? null,
        feedbackUrl: id.feedbackUrl,
        filteredNoise: []
      };

  doc.acceptance = summary;
  if (summary.verdict !== 'fail') return doc;

  const check = `acceptance / specs (acceptance/${firstIssue})`;
  const url = doc.feedbackUrl ?? null;
  doc.failingChecks = [
    ...(doc.failingChecks ?? []).filter((c) => c.check !== check),
    { name: check, workflow: 'acceptance', conclusion: 'failure', url, rollup: false }
  ];
  doc.failures = [
    ...(doc.failures ?? []).filter((f) => f.check !== check),
    {
      check,
      workflow: 'acceptance',
      conclusion: 'failure',
      url,
      rollup: false,
      logAvailable: false,
      firstError: {
        line: 0,
        excerpt: failed
          .flatMap((s) =>
            (s.checks ?? [])
              .filter((c) => c.verdict === 'fail')
              .map((c) => `acceptance/${s.issue}: ${c.name} — ${(c.failures ?? []).map((f) => f.detail).join('; ')}`)
          )
          .join('\n')
          .slice(0, 2000),
        scope: 'log'
      },
      failingTestIds: failed.map((s) => `acceptance/${s.issue}`),
      reproCommand: `node .github/scripts/acceptance-runner.mjs run --dir acceptance/${firstIssue} --base-url ${results.baseUrl ?? '<base-url>'}`
    }
  ];
  doc.failingTestIds = [...new Set([...(doc.failingTestIds ?? []), ...failed.map((s) => `acceptance/${s.issue}`)])];
  doc.reproCommand = doc.failures.find((f) => !f.rollup)?.reproCommand ?? doc.reproCommand;
  doc.status = 'failed';
  return doc;
}

function isFeedbackDocument(value) {
  return (
    value !== null &&
    typeof value === 'object' &&
    value.schemaVersion === 1 &&
    Array.isArray(value.runs) &&
    Array.isArray(value.failures)
  );
}

function parseArgs(argv) {
  const opts = { cmd: argv[0], repoRoot: resolve(process.cwd()), out: null, json: null };
  for (let i = 1; i < argv.length; i += 1) {
    const arg = argv[i];
    const next = () => argv[++i];
    if (arg === '--base-url') opts.baseUrl = next();
    else if (arg === '--dir') (opts.dirs ??= []).push(next());
    else if (arg === '--results') opts.results = next();
    else if (arg === '--repo-root') opts.repoRoot = resolve(next());
    else if (arg === '--out') opts.out = next();
    else if (arg === '--feedback') opts.feedback = next();
    else if (arg === '--json') opts.json = next();
    else if (arg === '--all') opts.all = true;
    else usage(`unknown argument "${arg}"`);
  }
  return opts;
}

function usage(msg) {
  process.stderr.write(`acceptance-runner: ${msg}\n`);
  process.stderr.write(
    'usage: acceptance-runner.mjs run [--base-url URL] [--dir acceptance/N] [--all] [--out FILE] [--feedback FILE]\n' +
      '       acceptance-runner.mjs attach --results FILE [--feedback FILE] [--out FILE]\n'
  );
  process.exit(2);
}

async function main(argv) {
  const opts = parseArgs(argv);
  const baseUrl = opts.baseUrl ?? process.env.ACCEPTANCE_BASE_URL ?? process.env.SAIKU_BASE_URL ?? '';

  let results;
  if (opts.cmd === 'run') {
    if (!/^https?:\/\//i.test(baseUrl) && baseUrl !== '') {
      process.stderr.write('acceptance-runner: --base-url must be an http(s) URL\n');
      return 2;
    }
    const issues = [];
    for (const dir of opts.dirs ?? []) {
      const m = /acceptance\/(\d+)$/.exec(dir);
      if (!m) {
        process.stderr.write(`acceptance-runner: --dir must look like acceptance/N, got "${dir}"\n`);
        return 2;
      }
      issues.push(Number(m[1]));
    }
    const wanted = issues.length > 0 ? [...new Set(issues)] : opts.all === false ? [] : specIssues(opts.repoRoot);
    results = await runSpecs({ repoRoot: opts.repoRoot, baseUrl: baseUrl || undefined, issues: wanted });
    if (opts.out) writeFileSync(opts.out, `${JSON.stringify(results, null, 2)}\n`);
    if (opts.feedback) {
      const existing = readJson(opts.feedback);
      writeFileSync(
        opts.feedback,
        `${JSON.stringify(
          attachResults(existing, results, {
            repo: process.env.GITHUB_REPOSITORY,
            sha: process.env.HEAD_SHA,
            branch: process.env.HEAD_BRANCH,
            pr: Number(process.env.PR_NUMBER) || undefined,
            runUrl: process.env.FEEDBACK_URL
          }),
          null,
          2
        )}\n`
      );
    }
    for (const spec of results.specs) {
      process.stdout.write(`acceptance/${spec.issue}: ${spec.verdict}${spec.reason ? ` (${spec.reason})` : ''}\n`);
      for (const c of spec.checks ?? []) {
        if (c.verdict === 'fail') {
          for (const f of c.failures ?? []) process.stdout.write(`  x ${c.name} — ${f.assertion}: ${f.detail ?? ''}\n`);
        }
      }
    }
    if (results.specs.length === 0) process.stdout.write('no acceptance specs found\n');
    return results.verdict === 'fail' ? 1 : 0;
  }

  if (opts.cmd === 'attach') {
    const source = opts.results ?? opts.json ?? opts.out;
    if (!source) usage('attach needs --results FILE');
    const raw = readJson(source);
    if (!isResultsDocument(raw)) {
      process.stderr.write(`acceptance-runner: ${source} is not an acceptance results document\n`);
      return 2;
    }
    const feedbackPath = opts.feedback ?? 'ci-feedback.json';
    const doc = attachResults(readJson(feedbackPath), raw, {
      repo: process.env.GITHUB_REPOSITORY,
      sha: process.env.HEAD_SHA,
      branch: process.env.HEAD_BRANCH,
      pr: Number(process.env.PR_NUMBER) || undefined,
      runUrl: process.env.FEEDBACK_URL
    });
    writeFileSync(feedbackPath, `${JSON.stringify(doc, null, 2)}\n`);
    process.stdout.write(`ci-feedback.json now carries acceptance verdict "${doc.acceptance.verdict}"\n`);
    return 0;
  }

  usage('expected "run" or "attach"');
  return 2;
}

/** The shape runSpecs emits; anything else is bad input, not a crash. */
function isResultsDocument(value) {
  return (
    value !== null &&
    typeof value === 'object' &&
    ['pass', 'fail', 'not-run'].includes(value.verdict) &&
    Array.isArray(value.specs)
  );
}

function readJson(file) {
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch {
    return null;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) process.exit(await main(process.argv.slice(2)));