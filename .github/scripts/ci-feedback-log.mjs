// Ported from spiculedata/saiku-cloud (.github/scripts/ci-feedback-log.mjs, #1374)
// and adapted to what this repository's CI emits: Maven Surefire/Failsafe, Spotless
// and the licence-header check (scripts/check-licence-headers.sh), prettier, ESLint,
// svelte-check, vitest, Playwright and node:test. Differences from the original are
// listed in docs/ci-feedback.md ("Provenance").
//
// Log analysis for the structured CI feedback: turns a raw GitHub Actions
// job log into "the first REAL error", the failing test ids and the noise that was
// filtered out. Pure functions, no I/O, no environment reads, so the tests are
// hermetic. `ci-feedback.mjs` is the caller; `docs/ci-feedback.md` is the contract.
//
// Everything that comes out of here is derived from log text, and log text is
// UNTRUSTED (a PR controls what its tests print). So every string that leaves this
// module is redacted (`redact`) and size-capped, and nothing here is ever evaluated,
// shell-expanded or used to build a command.
//
// What real runner logs taught us (checked against failing runs in this repo, not
// guessed):
//   * every line carries an `2026-10-03T20:27:12.0148642Z ` timestamp prefix, so an
//     extractor anchored on `^\[ERROR\]` or `^##\[error\]` matches nothing,
//   * the first `##[error]` line is often NOT the failure: `docker run` prints
//     "Unable to find image ... locally" as `##[error]`, and a passing test that
//     exercises an error path prints `##[error]...` lines before the real failure,
//   * deprecation notices are `##[warning]Node.js 20 is deprecated ... forced to run
//     on Node.js 24` and `##[warning]setup-java v4 is deprecated`, plus Node's own
//     `(node:N) [DEP0040] DeprecationWarning`,
//   * the step that failed ends with `##[error]Process completed with exit code N.`;
//     scoping the search to that step's output is what finds the real error.

/** Bumped only when the shape of what these functions return changes. */
export const MAX_LINE_CHARS = 1000;
export const MAX_EXCERPT_LINES = 20;
export const MAX_EXCERPT_CHARS = 2000;
export const MAX_TEST_IDS = 25;
export const MAX_TEST_ID_CHARS = 300;
export const MAX_NOISE_SAMPLES = 10;

const ESC = String.fromCharCode(27);
const ANSI_PATTERN = new RegExp(`${ESC}\\[[0-9;?]*[ -/]*[@-~]`, 'g');
const TIMESTAMP_PREFIX = /^﻿?\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z ?/;

/** Secrets that should never be echoed into a PR comment, even if a log printed one. */
const REDACTIONS = [
  [/-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)/g, '[REDACTED private key]'],
  [/(?<![A-Za-z0-9])gh[pousr]_[A-Za-z0-9]{20,}\b/g, '[REDACTED token]'],
  [/(?<![A-Za-z0-9])github_pat_[A-Za-z0-9_]{20,}\b/g, '[REDACTED token]'],
  [/(?<![A-Za-z0-9])(?:AKIA|ASIA)[0-9A-Z]{16}\b/g, '[REDACTED aws key]'],
  [/(?<![A-Za-z0-9])xox[abprs]-[A-Za-z0-9-]{10,}\b/g, '[REDACTED token]'],
  [/\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b/g, '[REDACTED jwt]'],
  [/\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]{12,}/gi, '$1 [REDACTED]'],
  [/(:\/\/)[^\s/:@]+:[^\s/@]+@/g, '$1[REDACTED]@'],
  [
    /\b((?:[A-Za-z0-9_-]*(?:password|passwd|secret|token|api[_-]?key|private[_-]?key|credential)[A-Za-z0-9_-]*)\s*[=:]\s*)(?!\[REDACTED)("[^"]*"|'[^']*'|[^\s"',;]+)/gi,
    '$1[REDACTED]'
  ]
];

export function redact(text) {
  let out = String(text ?? '');
  for (const [pattern, replacement] of REDACTIONS) out = out.replace(pattern, replacement);
  return out;
}

/**
 * Normalise a raw job log into lines: strip ANSI colour codes, carriage returns, the
 * runner's timestamp prefix, and cap each line (so one pathological line cannot make
 * the signature regexes slow). Line numbers of the input are preserved.
 */
export function toLines(log) {
  return String(log ?? '')
    .split('\n')
    .map((raw) => {
      const line = raw.replace(/\r/g, '').replace(ANSI_PATTERN, '').replace(TIMESTAMP_PREFIX, '');
      return (line.length > MAX_LINE_CHARS ? line.slice(0, MAX_LINE_CHARS) : line).replace(/\s+$/, '');
    });
}

const LEVEL_PREFIX = /^##\[(error|warning|notice|debug|group|endgroup|command)\](.*)$/;

/** `##[error]foo` -> { level: 'error', text: 'foo' }; a bare line has level null. */
export function unwrap(line) {
  const text = String(line ?? '');
  const match = LEVEL_PREFIX.exec(text);
  if (match) return { level: match[1], text: match[2] };
  if (text.startsWith('[command]')) return { level: 'command', text: text.slice('[command]'.length) };
  return { level: null, text };
}

/**
 * Text that is never the primary error: blank lines, runtime and action deprecation
 * notices, the exit-code trailer GitHub appends to every failed step, docker pull
 * chatter, and the application's own INFO/WARN logging. Kept deliberately narrow: a
 * `warn` line from a tool we do not know may be the failure, so it stays.
 */
export const NOISE_PATTERNS = [
  /^\s*$/,
  // Node's own deprecation machinery.
  /\(node:\d+\)\s*\[?(?:DEP\d+|Deprecation|Warning)/i,
  /\(Use `node --trace-(?:deprecation|warnings)/i,
  /\bDeprecationWarning\b/,
  // GitHub runner / action runtime deprecations.
  /\bNode\.js \d+\b.*\b(?:deprecated|forced to run)\b/i,
  /\bforced to run on Node\.js \d+/i,
  /\bactions? (?:target|use|run on) Node\.js \d+\b/i,
  /\bare deprecated\.\s*Please update/i,
  /^The following actions (?:are deprecated|use a deprecated Node\.js)/i,
  /\bsetup-(?:java|node|python|go|dotnet)\b.*\bdeprecated\b/i,
  /\b(?:actions\/[\w.-]+|pnpm\/action-setup|docker\/[\w.-]+)@v?\d+\S*.*\bdeprecated\b/i,
  /^actions\/[\w.-]+@[\w.-]+\s*$/,
  /^::(?:warning|notice|debug)/,
  /^npm warn deprecated/i,
  /\bWARN(?:ING)?\b.*\bdeprecated\b.*\bpackage\b/i,
  // Step plumbing.
  /^\s*(?:Error: )?Process completed with exit code \d+\.?\s*$/i,
  /^\s*Post job cleanup\./i,
  /::error file=/,
  // docker run/pull chatter that the runner surfaces as ##[error].
  /^Unable to find image '.+' locally$/,
  /^[0-9a-f]{12}: (?:Pulling|Waiting|Download|Pull|Verifying|Already)/,
  /^\S+: Pulling from \S+/,
  /^(?:Digest: sha256:|Status: (?:Downloaded|Image is up to date))/,
  // svelte-check machine output: warnings (hundreds on a clean tree) and its start line.
  /^\d{10,} (?:WARNING|START) /,
  // Maven decoration and the application's own logging (passing tests log WARN/FAILED).
  /^\[INFO\]\s*-{5,}\s*$/,
  /^(?:\d{4}-\d{2}-\d{2}[T ][\d:.,]+Z?\s+)?(?:WARN|INFO|DEBUG|TRACE)\s+\[/
];

function matchesAny(patterns, text) {
  return patterns.some((pattern) => pattern.test(text));
}

export function isNoiseLine(line) {
  const { level, text } = unwrap(line);
  if (level === 'warning' || level === 'notice' || level === 'debug') return true;
  if (level === 'group' || level === 'endgroup' || level === 'command') return true;
  return matchesAny(NOISE_PATTERNS, text);
}

/** Definitive failure verdicts from a test runner, compiler or linter. */
const STRONG_SIGNATURES = [
  /^(?:FAIL|ERROR): \S+ \(/, // python unittest
  /^Traceback \(most recent call last\)/,
  /^FAILED\s+\S+/, // pytest, unittest summary
  /^\s*FAIL\s+\S+/, // vitest / jest
  /^not ok \d+ - \S/, // node:test (TAP) — the failing test's own name line
  /^\s*\d+\)\s+\[[\w -]+\]\s+›/, // playwright failure list
  /\bAssertionError\b/,
  /COMPILATION ERROR|Compilation failure/,
  /\berror TS\d{3,5}\b/,
  /^\[ERROR\]\s+Tests run:.*(?:Failures|Errors):\s*[1-9]/,
  /<<<\s*(?:FAILURE|ERROR)!/,
  /^\[ERROR\]\s+(?:Failures|Errors):\s*$/,
  /^\S+\.ya?ml:\d+:\d+:\s.+\[[\w-]+\]$/, // actionlint
  /^\S+:\d+:\d+:\s+(?:fatal )?error\b/, // gcc/clang/go/shellcheck-style
  /^\s*\d+:\d+\s+error\s+\S/, // eslint stylish
  /\bdoes not meet (?:global )?threshold\b/i, // coverage floors
  /\bUnhandled (?:exception|Promise rejection|error)\b/i,
  // Spotless (Palantir Java Format), the licence-header presence check, prettier.
  /\bThe following files had format violations\b/,
  /spotless-maven-plugin:[\w.-]+:check\b.*\bon project\b/,
  /^ERROR: \d+ Java file\(s\) have no licence header/,
  /^\[warn\]\s+Code style issues found\b/,
  // svelte-check, machine output (what a non-TTY runner gets): `<epoch ms> ERROR "file" line:col "message"`
  // and the closing `<epoch ms> COMPLETED <n> FILES <e> ERRORS ...`. WARNING lines are not failures.
  /^\d{10,} ERROR "[^"]+" \d+:\d+ /,
  /^\d{10,} COMPLETED \d+ FILES [1-9]\d* ERRORS?\b/,
  // The blocking-test block printed by flake-ledger.mjs (`record`): one line per test, any runner.
  /^FAILING TEST: \S/
];

/** Generic error shapes: used only when no strong signature is in scope. */
const GENERIC_SIGNATURES = [
  /^\[ERROR\]\s*\S/,
  /^(?:Error|ERROR|FATAL|fatal)\b:?\s+\S/,
  /^npm ERR!/,
  /^\s*[×✗✘]\s+\S/ // runner progress marker; vitest's qualified `FAIL` line outranks it
];

function signatureTier(line) {
  if (isNoiseLine(line)) return 0;
  const { text } = unwrap(line);
  if (matchesAny(STRONG_SIGNATURES, text)) return 1;
  if (matchesAny(GENERIC_SIGNATURES, text) || unwrap(line).level === 'error') return 2;
  return 0;
}

export function looksLikeError(line) {
  return signatureTier(line) > 0;
}

const EXIT_TRAILER = /^##\[error\]Process completed with exit code \d+/;

/**
 * Index range of the output of the first step that failed, or null. A failed step
 * ends with the exit-code trailer; its output starts after the `##[endgroup]` that
 * closes the preceding `##[group]Run ...` banner. Searching only this range keeps
 * the answer clear of earlier steps' (and passing tests') error-shaped output.
 */
export function failedStepRange(lines) {
  const end = lines.findIndex((line) => EXIT_TRAILER.test(line));
  if (end < 0) return null;
  let banner = -1;
  for (let i = end - 1; i >= 0; i -= 1) {
    if (/^##\[group\]Run\b/.test(lines[i])) {
      banner = i;
      break;
    }
  }
  if (banner < 0) return { start: 0, end };
  let start = banner + 1;
  for (let i = banner + 1; i < end; i += 1) {
    if (/^##\[endgroup\]/.test(lines[i])) {
      start = i + 1;
      break;
    }
  }
  return { start, end };
}

const PATH_ONLY = /^(?:\/|\.\/)?[\w@.-]+(?:\/[\w@.-]+)+\.\w+$/;
const PRETTIER_FILE = /^\[warn\]\s+\S/;
const MAX_LEADING_CONTEXT = 15;
const EXCERPT_STOP = /^={10,}$|^##\[(?:group|endgroup)\]|^##\[error\]Process completed|^\[INFO\] BUILD (?:FAILURE|SUCCESS)/;

function buildExcerpt(lines, hit) {
  const picked = [];
  // eslint prints the file path on the line before its first `3:49 error` finding.
  if (hit > 0 && PATH_ONLY.test(lines[hit - 1]) && /^\s*\d+:\d+\s+error\s/.test(unwrap(lines[hit]).text)) {
    picked.push(lines[hit - 1]);
  }
  // prettier --check lists the offending files as `[warn] path` BEFORE the summary line.
  if (/^\[warn\]\s+Code style issues found\b/.test(unwrap(lines[hit]).text)) {
    let first = hit;
    while (first > 0 && hit - first < MAX_LEADING_CONTEXT && PRETTIER_FILE.test(lines[first - 1])) first -= 1;
    picked.push(...lines.slice(first, hit));
  }
  picked.push(unwrap(lines[hit]).level === 'error' ? unwrap(lines[hit]).text : lines[hit]);
  for (let j = hit + 1; j < lines.length && picked.length < MAX_EXCERPT_LINES; j += 1) {
    const line = lines[j];
    if (EXCERPT_STOP.test(line)) break;
    if (isNoiseLine(line)) continue;
    picked.push(unwrap(line).level === 'error' ? unwrap(line).text : line);
  }
  while (picked.length > 1 && !picked[picked.length - 1].trim()) picked.pop();
  const text = redact(picked.join('\n')).trim();
  return text.length > MAX_EXCERPT_CHARS ? `${text.slice(0, MAX_EXCERPT_CHARS)}…` : text;
}

function firstHit(lines, from, to, tier) {
  for (let i = from; i < to; i += 1) if (signatureTier(lines[i]) === tier) return i;
  return -1;
}

/**
 * The first line that is really a failure, with the lines that continue it (a stack
 * trace, a compile error's location). Search order: strong signatures in the failed
 * step, generic ones in the failed step, strong in the whole log, generic in the
 * whole log. Returns `{line, excerpt, scope}` or null when nothing error-shaped
 * exists (for example a log that only holds deprecation notices).
 */
export function findFirstRealError(input) {
  const lines = Array.isArray(input) ? input : toLines(input);
  const range = failedStepRange(lines);
  const searches = [];
  if (range) {
    searches.push([range.start, range.end, 1, 'failed-step'], [range.start, range.end, 2, 'failed-step']);
  }
  searches.push([0, lines.length, 1, 'log'], [0, lines.length, 2, 'log']);
  for (const [from, to, tier, scope] of searches) {
    const hit = firstHit(lines, from, to, tier);
    if (hit < 0) continue;
    const excerpt = buildExcerpt(lines, hit);
    if (!excerpt) continue;
    return { line: hit + 1, excerpt, scope };
  }
  return null;
}

function cleanId(raw) {
  const id = redact(String(raw ?? '').replace(/\s*[─-]{3,}\s*$/, '').replace(/\s+/g, ' ').trim());
  if (!id) return null;
  return id.length > MAX_TEST_ID_CHARS ? `${id.slice(0, MAX_TEST_ID_CHARS)}…` : id;
}

/** Failing test ids per tool (Surefire, unittest, pytest, vitest, Playwright), de-duplicated and capped. */
export function findTestIds(input) {
  const lines = Array.isArray(input) ? input : toLines(input);
  const qualified = [];
  const summary = [];
  const bare = [];
  let inSummary = false;

  for (const line of lines) {
    const { level, text } = unwrap(line);
    if (level === 'warning' || level === 'notice' || isNoiseLine(line)) continue;

    // Surefire / Failsafe (JUnit 5): `[ERROR] pkg.Class.method -- Time elapsed: 1 s <<< FAILURE!`
    const surefire = /^\[ERROR\]\s+([\w$.]+)\.([\w$]+)(?:\([^)]*\))?\s+--\s+Time elapsed:.*<<<\s*(?:FAILURE|ERROR)!/.exec(text);
    if (surefire) qualified.push(`${surefire[1]}.${surefire[2]}`);

    // Surefire results block: `[ERROR] Failures:` then `[ERROR]   Class.method:116 [message]`.
    if (/^\[ERROR\]\s+(?:Failures|Errors):\s*$/.test(text)) inSummary = true;
    else if (inSummary) {
      const row = /^\[ERROR\]\s{2,}([\w$.]+)\.([\w$]+):\d+/.exec(text);
      if (row) summary.push(`${row[1]}.${row[2]}`);
      else if (/^\[(?:INFO|ERROR)\]\s*Tests run:/.test(text)) inSummary = false;
    }

    // python unittest: `FAIL: test_x (pkg.Class.test_x)`.
    const unittest = /^(?:FAIL|ERROR): (\S+) \(([\w.]+)\)/.exec(text);
    if (unittest) qualified.push(unittest[2]);

    // pytest: `FAILED tests/test_x.py::test_y - AssertionError`.
    const pytest = /^FAILED\s+(\S+::\S+)/.exec(text);
    if (pytest) qualified.push(pytest[1]);

    // vitest / jest: ` FAIL  src/a.test.ts > suite > case`, bare ` × case 6ms`.
    const vitest = /^\s*FAIL\s+(\S+)\s+>\s+(.+)$/.exec(text);
    if (vitest) qualified.push(`${vitest[1]} > ${vitest[2]}`);
    const cross = /^\s*[×✗]\s+(.+?)(?:\s+\d+\s*ms)?\s*$/.exec(text);
    if (cross) bare.push(cross[1]);

    // node:test (TAP) failure: `not ok 7 - the failing test name`. The name is
    // the test id; node does not print the file with it, so it is reported as
    // the runner names it. This is the runner behind `node --test` in
    // ci.yml (the CI-tooling tests, the security pack harness), so without this
    // a failure there produced failingTestIds: [].
    const nodetest = /^not ok \d+ - (.+)$/.exec(text);
    if (nodetest) qualified.push(nodetest[1].trim());

    // flake-ledger.mjs verdict block: `FAILING TEST: <id>` (Surefire `Class#method`, vitest/Playwright `file#title`).
    const blocking = /^FAILING TEST: (\S.*)$/.exec(text);
    if (blocking) qualified.push(blocking[1].trim());

    // svelte-check machine output: `1791162867884 ERROR "src/x.svelte" 2:6 "message"` is a
    // finding, not a test; it is deliberately not a test id.

    // Playwright failure list: `  1) [chromium] › e2e/a.e2e.ts:47:2 › suite › case`.
    const playwright = /^\s*\d+\)\s+(\[[\w -]+\]\s+›.+)$/.exec(text);
    if (playwright) qualified.push(playwright[1]);
  }

  const ids = [];
  const push = (raw) => {
    const id = cleanId(raw);
    if (id && !ids.includes(id)) ids.push(id);
  };
  qualified.forEach(push);
  // The short `Class.method` summary adds nothing when the qualified form is known.
  summary.filter((short) => !ids.some((id) => id.endsWith(`.${short}`) || id.endsWith(short))).forEach(push);
  // A bare vitest `×` line is a fallback only: the qualified `FAIL` line is the same test.
  bare.filter((name) => !ids.some((id) => id.endsWith(name))).forEach(push);
  return ids.slice(0, MAX_TEST_IDS);
}

/** A sample of what was filtered out, so the filtering stays auditable. */
export function noiseSamples(input, limit = MAX_NOISE_SAMPLES) {
  const lines = Array.isArray(input) ? input : toLines(input);
  const samples = [];
  for (const line of lines) {
    const text = unwrap(line).text.trim();
    if (!text || !isNoiseLine(line)) continue;
    // Only the deprecation family is worth auditing; blank lines and step plumbing are not.
    if (!/deprecat|forced to run|Node\.js \d+/i.test(text)) continue;
    const clean = redact(text).slice(0, 200);
    if (!samples.includes(clean)) samples.push(clean);
    if (samples.length >= limit) break;
  }
  return samples;
}

/**
 * Preview environment URL for pull request `pr`, if a log mentions it. Deliberately
 * strict: only `pr-<n>[-api].preview.saiku.bi` for THIS pull request, because log text
 * is attacker-influenced and a link in a bot comment must never be arbitrary.
 */
export function findPreviewUrl(input, pr) {
  if (!/^\d{1,9}$/.test(String(pr ?? ''))) return null;
  const lines = Array.isArray(input) ? input : toLines(input);
  const pattern = new RegExp(`https://pr-${Number(pr)}(?:-api)?\\.preview\\.saiku\\.bi(?:/[A-Za-z0-9._~/-]{0,200})?`);
  for (const line of lines) {
    const match = pattern.exec(line);
    if (match) return match[0];
  }
  return null;
}

/** Same host rule for a URL handed in by configuration (a repository variable). */
export function isPreviewUrl(value) {
  return /^https:\/\/pr-\d{1,9}(?:-api)?\.preview\.saiku\.bi(?:\/[A-Za-z0-9._~/-]{0,200})?$/.test(String(value ?? ''));
}
