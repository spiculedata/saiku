#!/usr/bin/env node
// Ported from spiculedata/saiku-cloud (.github/scripts/ci-feedback.mjs, #1374) and
// adapted to this repository (job names, repro commands, rollup job `ci`).
// See docs/ci-feedback.md ("Provenance").
//
// Structured CI failure feedback for automated fixers.
//
// For one commit, gathers every GitHub Actions run, its jobs, the logs of the failing
// jobs and the artifacts, and writes ONE machine-readable `ci-feedback.json` plus the
// markdown of a sticky PR comment. A worker (human or agent) reads that one file and
// knows what broke and how to reproduce it without scraping a 4000-line log.
//
// Why it exists: Hive re-dispatched a fix six times because the only failure text it
// saw was a Node 20 deprecation notice; the real error sat elsewhere in the log. So the
// log analysis (`ci-feedback-log.mjs`) drops runtime/deprecation notices and searches
// the failing step for the first real error, instead of trusting the first error-shaped
// line.
//
// Trust model (read this before changing anything). The only caller is
// `.github/workflows/ci-feedback.yml`, which runs from the DEFAULT branch under
// `workflow_run` with a token that can comment on PRs. Everything it reads about the
// run (log text, job names, artifact names, branch names) is controlled by whoever
// opened the PR, so it is all UNTRUSTED:
//   * it is never executed, shell-expanded, or put in a command line; the only network
//     calls are fixed GitHub API paths built from validated ids,
//   * every log-derived string is redacted for secrets and truncated (`ci-feedback-log`),
//   * every string rendered into markdown is escaped (`mdText`/`mdCode`/`mdFence`), so a
//     job called `@org/admins` or a test named `](http://evil)` cannot ping or link,
//   * links come from a validated allow-list shape (`safeUrl`), never from log text.
//
// Dependency-free, Node >= 18. Schema + version policy: docs/ci-feedback.md and
// .github/ci-feedback.schema.json.
//
// Usage:
//   ci-feedback.mjs collect --repo OWNER/REPO --sha SHA [--pr N] [--self-path PATH]
//                           [--preview-url URL] [--feedback-url URL]
//                           [--out ci-feedback.json] [--md ci-feedback.md]
//                           (GH_TOKEN in the environment)
//   ci-feedback.mjs render  --feedback ci-feedback.json
//   ci-feedback.mjs comment --feedback ci-feedback.json --repo OWNER/REPO --pr N
//                           (GH_TOKEN in the environment)
// Exit codes: 0 ok, 1 runtime/bad input, 2 usage.

import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  findFirstRealError,
  findPreviewUrl,
  findTestIds,
  isPreviewUrl,
  noiseSamples,
  redact,
  toLines
} from './ci-feedback-log.mjs';

/** Bumped on a breaking change to the emitted JSON. Policy in docs/ci-feedback.md. */
export const SCHEMA_VERSION = 1;

/** First line of the sticky comment; also how a previous comment is found. */
export const COMMENT_MARKER = '<!-- ci-feedback -->';

export const MAX_FAILURES = 25;
export const MAX_ARTIFACTS = 40;
export const MAX_COMMENT_CHARS = 60000; // GitHub rejects bodies over 65536
const MAX_RENDERED_FAILURES = 8;
const MAX_LOG_JOBS = 12;
const MAX_LOG_BYTES = 8 * 1024 * 1024; // keep the TAIL: the failing step is at the end
const MAX_RUNS = 60;

const FAILING_CONCLUSIONS = new Set(['failure', 'timed_out', 'startup_failure', 'action_required']);
/** Names of artifacts worth surfacing first: traces, screenshots, container logs, reports. */
const EVIDENCE_ARTIFACT = /(trace|screenshot|playwright|lighthouse|report|container|log|coverage|har|results)/i;
/** The aggregate required check: the job named `ci` in the `ci` workflow (or any `... / gate`). */
const isRollupJob = (workflowName, jobName) =>
  (String(workflowName) === 'ci' && String(jobName) === 'ci') || /(?:^|\/\s*)gate$/i.test(String(jobName ?? ''));

/** One-line local repro, keyed `workflow :: job` first, then by workflow name. */
const UI_REPRO = 'cd saiku-ui && npm ci && npm run check && npx vitest run && npm run lint && npm run build && npm run e2e';
const REPRO_BY_JOB = {
  'ci :: build (JDK 22, ubuntu-latest)':
    './scripts/check-licence-headers.sh && mvn -B -ntp -DskipITs=false verify',
  'ci :: ui (svelte-check + vitest + build + e2e)': UI_REPRO,
  'ci :: bundle (saiku-dist zip)': 'mvn -B -ntp -pl saiku-launcher -am -Dmaven.test.skip=true package',
  'ci :: ci scripts (python unittest)': "python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v",
  'ci :: ci / flake-policy tests': 'node --test ".github/scripts/*.test.mjs"',
  'ci :: ci / acceptance convention':
    'PR_BODY="Closes #<issue>" PR_LABELS="" node .github/scripts/acceptance-check.mjs'
};
export const REPRO_BY_WORKFLOW = {
  'design-system': 'cd saiku-ui && npm ci && npm run build --workspace @concepttocloud/saiku-design-system',
  'release-prep': 'mvn -B -ntp -DskipITs=false verify',
  acceptance: 'node .github/scripts/acceptance-runner.mjs run --all --base-url <url>'
};

/** One-line repro for a failing job; falls back to a rerun of the failed jobs. */
export function reproCommandFor(workflowName, jobName, runId) {
  const exact = REPRO_BY_JOB[`${workflowName} :: ${jobName}`];
  if (exact) return exact;
  const byWorkflow = REPRO_BY_WORKFLOW[String(workflowName ?? '')];
  if (byWorkflow) return byWorkflow;
  return /^\d{1,20}$/.test(String(runId ?? '')) ? `gh run rerun ${runId} --failed` : 'gh run view --log-failed';
}

/** An `https:` URL with nothing that could break out of markdown or an HTML context. */
export function safeUrl(value) {
  const text = String(value ?? '');
  if (!/^https:\/\/[A-Za-z0-9.-]+(?::\d{1,5})?(?:[/?#][A-Za-z0-9._~:/?#[\]@!$&'*+,;=%-]*)?$/.test(text)) return null;
  return text.length <= 500 ? text : null;
}

const isFailing = (job) => FAILING_CONCLUSIONS.has(String(job?.conclusion ?? ''));

/**
 * Build the feedback document. Pure: all I/O happens in `collectInput`.
 *
 * @param {object} input
 * @param {string} [input.repository]   OWNER/REPO
 * @param {string} [input.sha]
 * @param {string} [input.branch]
 * @param {number|string|null} [input.pullRequest]
 * @param {Array}  input.runs           runs with `jobs` (each may carry `logText`) and `artifacts`
 * @param {string|null} [input.previewUrl]  already-validated preview URL; wins over the log scan
 * @param {string|null} [input.feedbackUrl] link to the run that holds `ci-feedback.json`
 * @param {string} [input.generatedAt]  ISO timestamp, for deterministic tests
 */
export function buildFeedback({
  repository = null,
  sha = null,
  branch = null,
  pullRequest = null,
  runs = [],
  previewUrl = null,
  feedbackUrl = null,
  generatedAt = new Date().toISOString()
} = {}) {
  const failures = [];
  const allLines = [];
  for (const run of runs) {
    for (const job of run.jobs ?? []) {
      if (!isFailing(job)) continue;
      const hasLog = typeof job.logText === 'string' && job.logText.length > 0;
      const lines = hasLog ? toLines(job.logText) : [];
      allLines.push(...lines);
      failures.push({
        check: String(job.name ?? 'unknown job'),
        workflow: String(run.name ?? 'unknown workflow'),
        conclusion: String(job.conclusion),
        url: safeUrl(job.url),
        rollup: isRollupJob(run.name, job.name),
        logAvailable: hasLog,
        firstError: hasLog ? findFirstRealError(lines) : null,
        failingTestIds: hasLog ? findTestIds(lines) : [],
        reproCommand: reproCommandFor(run.name, job.name, run.id)
      });
    }
  }
  // The rollup (the `ci` job) only restates what the cells below it already say.
  failures.sort((a, b) => Number(a.rollup) - Number(b.rollup));
  const shown = failures.slice(0, MAX_FAILURES);

  const testIds = [];
  for (const failure of shown) {
    for (const id of failure.failingTestIds) if (!testIds.includes(id)) testIds.push(id);
  }

  const artifacts = runs
    .flatMap((run) =>
      (run.artifacts ?? []).map((artifact) => ({
        name: String(artifact.name ?? ''),
        workflow: String(run.name ?? ''),
        url: safeUrl(artifact.url),
        expired: Boolean(artifact.expired),
        sizeInBytes: Number.isInteger(artifact.sizeInBytes) ? artifact.sizeInBytes : null
      }))
    )
    .filter((artifact) => artifact.name);
  const shownArtifacts = artifacts.slice(0, MAX_ARTIFACTS);

  const running = runs.filter((run) => run.status && run.status !== 'completed');
  let status = 'passed';
  if (failures.length > 0) status = 'failed';
  else if (running.length > 0) status = 'pending';
  else if (runs.length > 0 && runs.every((run) => run.conclusion === 'cancelled')) status = 'cancelled';

  const primary = shown.find((failure) => !failure.rollup) ?? shown[0];
  const prNumber = /^\d{1,9}$/.test(String(pullRequest ?? '')) ? Number(pullRequest) : null;
  const resolvedPreview =
    (isPreviewUrl(previewUrl) ? previewUrl : null) ?? (prNumber ? findPreviewUrl(allLines, prNumber) : null);

  return {
    schemaVersion: SCHEMA_VERSION,
    generatedAt,
    status,
    repository,
    commit: { sha, branch: branch ? redact(String(branch)).slice(0, 200) : null },
    pullRequest: prNumber ? { number: prNumber } : null,
    runs: runs.map((run) => ({
      id: run.id ?? null,
      name: String(run.name ?? 'unknown workflow'),
      status: run.status ?? null,
      conclusion: run.conclusion ?? null,
      url: safeUrl(run.url)
    })),
    pendingRuns: running.map((run) => String(run.name ?? 'unknown workflow')),
    failingChecks: shown.map((f) => ({
      name: f.check,
      workflow: f.workflow,
      conclusion: f.conclusion,
      url: f.url,
      rollup: f.rollup
    })),
    failures: shown,
    omittedFailures: Math.max(0, failures.length - shown.length),
    failingTestIds: testIds.slice(0, 25),
    reproCommand: primary?.reproCommand ?? null,
    artifacts: shownArtifacts,
    evidenceArtifacts: shownArtifacts.filter((a) => EVIDENCE_ARTIFACT.test(a.name)).map((a) => ({ name: a.name, url: a.url })),
    previewUrl: resolvedPreview,
    feedbackUrl: safeUrl(feedbackUrl),
    filteredNoise: noiseSamples(allLines)
  };
}

// ---------------------------------------------------------------- markdown

const ZERO_WIDTH_SPACE = '\u200b';

/** Defuse HTML comments so log text can never forge the sticky-comment marker. */
const scrub = (text) => String(text).replace(/<!--/g, `<!${ZERO_WIDTH_SPACE}--`);

/** Plain text for headings and link labels: no markdown, no mentions, no HTML. */
export function mdText(value, max = 200) {
  const flat = scrub(redact(String(value ?? ''))).replace(/\s+/g, ' ').trim().slice(0, max);
  return flat.replace(/[\\`*_{}[\]()#+!|<>~&]/g, '\\$&').replace(/@/g, `@${ZERO_WIDTH_SPACE}`);
}

/** Inline code: backticks cannot be escaped inside a span, so they are replaced. */
export function mdCode(value, max = 300) {
  const flat = redact(String(value ?? '')).replace(/\s+/g, ' ').trim().slice(0, max);
  return `\`${flat.replace(/`/g, "'")}\``;
}

/** A fenced block whose fence is longer than any backtick run inside it. */
export function mdFence(value) {
  const body = scrub(String(value ?? ''));
  const longest = Math.max(0, ...(body.match(/`+/g) ?? []).map((run) => run.length));
  const fence = '`'.repeat(Math.max(3, longest + 1));
  return [`${fence}text`, body.replace(/\u0000/g, ''), fence].join('\n');
}

function mdLink(label, url) {
  const safe = safeUrl(url);
  return safe ? `[${mdText(label)}](${safe})` : mdText(label);
}

function renderFailure(failure, index) {
  const lines = [`### ${index + 1}. ${mdText(failure.check)}`, ''];
  lines.push(`Workflow: ${mdText(failure.workflow)} (${mdText(failure.conclusion)})${failure.rollup ? ' - rollup of the checks above' : ''}`);
  if (safeUrl(failure.url)) lines.push(`Job log: <${safeUrl(failure.url)}>`);
  if (failure.reproCommand) lines.push(`Reproduce: ${mdCode(failure.reproCommand, 400)}`);
  lines.push('');
  if (failure.firstError) {
    lines.push('First real error:', '', mdFence(failure.firstError.excerpt), '');
  } else if (!failure.logAvailable) {
    lines.push('_The job log was not available (expired, or the job never started); open the job link above._', '');
  } else {
    lines.push('_No error-shaped line was found in this job log (it may hold only runtime notices); open the job link above._', '');
  }
  const ids = failure.failingTestIds ?? [];
  if (ids.length > 0) {
    lines.push(`Failing tests (${ids.length}):`, '', ...ids.map((id) => `- ${mdCode(id)}`), '');
  }
  return lines;
}

/** The sticky-comment body: the same facts as the JSON, readable without tooling. */
export function renderMarkdown(feedback, { maxChars = MAX_COMMENT_CHARS } = {}) {
  for (let detail = MAX_RENDERED_FAILURES; detail >= 0; detail -= 2) {
    const body = renderWithDetail(feedback, detail);
    if (body.length <= maxChars) return body;
  }
  const hard = renderWithDetail(feedback, 0);
  return `${hard.slice(0, maxChars - 40)}\n\n_(truncated)_\n`;
}

function renderWithDetail(feedback, detailedFailures) {
  const out = [COMMENT_MARKER, '', '## CI feedback', ''];
  const label = { passed: 'passing', failed: 'failing', pending: 'still running', cancelled: 'cancelled' }[feedback?.status] ?? 'unknown';
  const bits = [`**${label}**`];
  if (feedback?.commit?.sha) bits.push(mdCode(String(feedback.commit.sha).slice(0, 7)));
  out.push(bits.join(' · '));
  out.push(
    `Generated ${mdText(feedback?.generatedAt ?? 'unknown time')}. Machine-readable copy: \`ci-feedback.json\` ` +
      `(schema v${Number(feedback?.schemaVersion) || SCHEMA_VERSION}, see \`docs/ci-feedback.md\`)` +
      `${safeUrl(feedback?.feedbackUrl) ? ` - [artifact on this run](${safeUrl(feedback.feedbackUrl)})` : ''}.`
  );
  out.push('');
  if (feedback?.previewUrl && isPreviewUrl(feedback.previewUrl)) {
    out.push(`**Preview environment:** <${feedback.previewUrl}>`, '');
  }
  if ((feedback?.pendingRuns ?? []).length > 0) {
    out.push(`Still running: ${feedback.pendingRuns.map((name) => mdText(name)).join(', ')}`, '');
  }

  const failures = feedback?.failures ?? [];
  if (feedback?.status === 'passed') out.push('Nothing to fix: every check in this commit concluded successfully.', '');
  else if (feedback?.status === 'cancelled') out.push('The runs were cancelled; no failing check to report.', '');
  else if (failures.length === 0) out.push('No failing check yet.', '');

  const detailed = failures.slice(0, detailedFailures);
  detailed.forEach((failure, index) => out.push(...renderFailure(failure, index)));
  const rest = failures.slice(detailedFailures);
  if (rest.length > 0 || feedback?.omittedFailures > 0) {
    out.push(`Other failing checks (${rest.length + (feedback?.omittedFailures ?? 0)}, details in \`ci-feedback.json\`):`, '');
    out.push(...rest.map((failure) => `- ${mdText(failure.check)} (${mdText(failure.workflow)})`));
    if (feedback?.omittedFailures > 0) out.push(`- ...and ${feedback.omittedFailures} more`);
    out.push('');
  }

  const evidence = new Set((feedback?.evidenceArtifacts ?? []).map((a) => a.name));
  const artifacts = (feedback?.artifacts ?? []).filter((a) => !a.expired);
  if (feedback?.status === 'failed' && artifacts.length > 0) {
    out.push('### Artifacts', '');
    const ordered = [...artifacts.filter((a) => evidence.has(a.name)), ...artifacts.filter((a) => !evidence.has(a.name))];
    out.push(...ordered.slice(0, 20).map((a) => `- ${mdLink(a.name, a.url)}${evidence.has(a.name) ? ' (evidence)' : ''}`), '');
  }

  if (feedback?.status === 'failed' && (feedback?.filteredNoise ?? []).length > 0) {
    out.push(
      `<details><summary>Filtered out as runtime/deprecation notices (${feedback.filteredNoise.length})</summary>`,
      '',
      ...feedback.filteredNoise.map((sample) => `- ${mdCode(sample)}`),
      '',
      '</details>',
      ''
    );
  }
  return `${out.join('\n')}\n`;
}

// ---------------------------------------------------------------- GitHub API

/**
 * Minimal GitHub REST client over `fetch`. Injectable so the tests run offline. Log
 * downloads follow the redirect by hand so the token is never sent to the blob host.
 */
export function createApi({ token, baseUrl = 'https://api.github.com', fetchImpl = globalThis.fetch } = {}) {
  const headers = {
    Accept: 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
    'User-Agent': 'saiku-ci-feedback',
    ...(token ? { Authorization: `Bearer ${token}` } : {})
  };
  async function request(method, path, body) {
    const response = await fetchImpl(`${baseUrl}${path}`, {
      method,
      headers: body ? { ...headers, 'Content-Type': 'application/json' } : headers,
      body: body ? JSON.stringify(body) : undefined
    });
    if (!response.ok) throw new Error(`GitHub API ${method} ${path} -> ${response.status}`);
    return response.status === 204 ? null : response.json();
  }
  return {
    json: (path) => request('GET', path),
    send: (method, path, body) => request(method, path, body),
    async paginate(path, key) {
      const items = [];
      for (let page = 1; page <= 10; page += 1) {
        const data = await request('GET', `${path}${path.includes('?') ? '&' : '?'}per_page=100&page=${page}`);
        const batch = key ? (data?.[key] ?? []) : (data ?? []);
        items.push(...batch);
        if (batch.length < 100) break;
      }
      return items;
    },
    /** Tail (last MAX_LOG_BYTES) of a job log as text; null when unavailable. */
    async jobLog(repo, jobId) {
      const first = await fetchImpl(`${baseUrl}/repos/${repo}/actions/jobs/${jobId}/logs`, { headers, redirect: 'manual' });
      let response = first;
      if (first.status >= 300 && first.status < 400) {
        const location = first.headers.get('location');
        if (!location || !location.startsWith('https://')) return null;
        response = await fetchImpl(location); // pre-signed URL: no Authorization header
      }
      if (!response.ok) return null;
      const buffer = Buffer.from(await response.arrayBuffer());
      const tail = buffer.length > MAX_LOG_BYTES ? buffer.subarray(buffer.length - MAX_LOG_BYTES) : buffer;
      return tail.toString('utf8');
    }
  };
}

const REPO_PATTERN = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/;
const SHA_PATTERN = /^[0-9a-f]{40}$/;

/** Newest run per workflow name (a re-run or a later push supersedes an earlier run). */
function latestPerWorkflow(runs) {
  const latest = new Map();
  for (const run of runs) {
    const kept = latest.get(run.name);
    if (!kept || Number(run.run_number) > Number(kept.run_number)) latest.set(run.name, run);
  }
  return [...latest.values()];
}

/** Gather runs, jobs, failing-job logs and artifacts for a commit through `api`. */
export async function collectInput({ api, repo, sha, pullRequest = null, selfPath = '.github/workflows/ci-feedback.yml', previewUrl = null, feedbackUrl = null, branch = null }) {
  if (!REPO_PATTERN.test(String(repo))) throw new Error(`invalid --repo "${repo}"`);
  if (!SHA_PATTERN.test(String(sha))) throw new Error(`invalid --sha "${sha}"`);

  const listed = await api.paginate(`/repos/${repo}/actions/runs?head_sha=${sha}`, 'workflow_runs');
  const picked = latestPerWorkflow(listed.filter((run) => run.path !== selfPath)).slice(0, MAX_RUNS);

  let logBudget = MAX_LOG_JOBS;
  const runs = [];
  for (const run of picked) {
    const id = Number(run.id);
    if (!Number.isInteger(id)) continue;
    const jobs = await api.paginate(`/repos/${repo}/actions/runs/${id}/jobs?filter=latest`, 'jobs');
    const artifacts = await api.paginate(`/repos/${repo}/actions/runs/${id}/artifacts`, 'artifacts');
    const jobEntries = [];
    for (const job of jobs) {
      const entry = { id: job.id, name: job.name, conclusion: job.conclusion, url: job.html_url, logText: null };
      if (isFailing(job) && logBudget > 0 && Number.isInteger(Number(job.id))) {
        logBudget -= 1;
        try {
          entry.logText = await api.jobLog(repo, Number(job.id));
        } catch {
          entry.logText = null; // a log we cannot fetch must not hide the failure itself
        }
      }
      jobEntries.push(entry);
    }
    runs.push({
      id,
      name: run.name,
      status: run.status,
      conclusion: run.conclusion,
      url: run.html_url,
      jobs: jobEntries,
      artifacts: artifacts.map((artifact) => ({
        name: artifact.name,
        expired: artifact.expired,
        sizeInBytes: artifact.size_in_bytes,
        url: `https://github.com/${repo}/actions/runs/${id}/artifacts/${Number(artifact.id)}`
      }))
    });
  }
  return { repository: repo, sha, branch, pullRequest, runs, previewUrl, feedbackUrl };
}

/**
 * Create or refresh the sticky comment. A failing commit always gets one; a commit
 * that is green (or still running) only refreshes an existing comment, so a PR that
 * never failed stays quiet and a fixed one stops showing the old failure.
 */
export async function upsertComment({ api, repo, pr, feedback }) {
  if (!REPO_PATTERN.test(String(repo))) throw new Error(`invalid --repo "${repo}"`);
  if (!/^\d{1,9}$/.test(String(pr))) throw new Error(`invalid --pr "${pr}"`);
  const body = renderMarkdown(feedback);
  const comments = await api.paginate(`/repos/${repo}/issues/${pr}/comments`);
  // Only our own bot's comment counts: a person quoting the marker must not be edited.
  const existing = comments.find(
    (comment) => comment?.user?.login === 'github-actions[bot]' && String(comment.body ?? '').startsWith(COMMENT_MARKER)
  );
  if (existing) {
    if (existing.body === body) return 'unchanged';
    await api.send('PATCH', `/repos/${repo}/issues/comments/${existing.id}`, { body });
    return 'updated';
  }
  if (feedback.status !== 'failed') return 'skipped';
  await api.send('POST', `/repos/${repo}/issues/${pr}/comments`, { body });
  return 'created';
}

// ---------------------------------------------------------------- CLI

export function parseArgs(argv) {
  const opts = { _: [] };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg.startsWith('--')) {
      opts._.push(arg);
      continue;
    }
    const key = arg.slice(2);
    const next = argv[i + 1];
    if (next === undefined || next.startsWith('--')) opts[key] = true;
    else {
      opts[key] = next;
      i += 1;
    }
  }
  return opts;
}

const flag = (opts, name) => (typeof opts[name] === 'string' && opts[name] !== '' ? opts[name] : null);

export async function main(argv, { env = process.env, api = null, write = (path, text) => writeFileSync(path, text), out = process.stdout } = {}) {
  const opts = parseArgs(argv);
  const mode = opts._[0];
  const client = () => api ?? createApi({ token: env.GH_TOKEN || env.GITHUB_TOKEN });

  if (mode === 'collect') {
    const input = await collectInput({
      api: client(),
      repo: flag(opts, 'repo'),
      sha: flag(opts, 'sha'),
      pullRequest: flag(opts, 'pr'),
      selfPath: flag(opts, 'self-path') ?? '.github/workflows/ci-feedback.yml',
      previewUrl: flag(opts, 'preview-url'),
      feedbackUrl: flag(opts, 'feedback-url'),
      branch: flag(opts, 'branch')
    });
    const feedback = buildFeedback(input);
    const json = `${JSON.stringify(feedback, null, 2)}\n`;
    if (flag(opts, 'out')) write(flag(opts, 'out'), json);
    else out.write(json);
    if (flag(opts, 'md')) write(flag(opts, 'md'), renderMarkdown(feedback));
    return 0;
  }
  if (mode === 'render') {
    if (!flag(opts, 'feedback')) throw new Error('render needs --feedback <ci-feedback.json>');
    out.write(renderMarkdown(JSON.parse(readFileSync(flag(opts, 'feedback'), 'utf8'))));
    return 0;
  }
  if (mode === 'comment') {
    if (!flag(opts, 'feedback')) throw new Error('comment needs --feedback <ci-feedback.json>');
    const feedback = JSON.parse(readFileSync(flag(opts, 'feedback'), 'utf8'));
    const result = await upsertComment({ api: client(), repo: flag(opts, 'repo'), pr: flag(opts, 'pr'), feedback });
    out.write(`ci-feedback: comment ${result}\n`);
    return 0;
  }
  process.stderr.write('usage: ci-feedback.mjs collect|render|comment [options]\n');
  return 2;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).then(
    (code) => process.exit(code),
    (err) => {
      process.stderr.write(`ci-feedback: ${redact(err.message)}\n`);
      process.exit(1);
    }
  );
}
