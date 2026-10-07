// The `acceptance/<issue>/` convention. Ported from spiculedata/saiku-cloud (#1382) and
// adapted to this repository: REST base path `/rest/saiku/api`, session / basic auth
// (docs/acceptance-specs.md, "Authentication"), and this repo's test layout for the waiver.
//
// Every pure decision about an acceptance spec lives here so it can be unit
// tested offline and reasoned about without a network or a runner:
//
//   * which issues a PR claims to close          -> closingIssues()
//   * whether a spec directory is well formed    -> validateSpec()
//   * whether this PR owes a spec at all         -> decideRequirement()
//   * whether one HTTP assertion holds           -> evaluateExpect()
//   * how a set of spec results reads overall     -> verdictFor()
//
// The CLIs are `acceptance-check.mjs` (the CI convention gate) and
// `acceptance-runner.mjs` (execute the specs, fold the result into
// ci-feedback.json). Both are thin; the rules are here. Convention, format and
// the runner are documented in docs/acceptance-specs.md.

/** The one file a spec directory must contain. */
export const SPEC_FILE = 'spec.json';

/** Labels that waive the requirement for a docs-only PR (#1382). */
export const WAIVER_LABEL = 'acceptance-waived';

const METHODS = new Set(['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD']);
/** How a spec (or one step) authenticates: nothing, a login session cookie, or HTTP basic. */
export const AUTH_MODES = ['none', 'session', 'basic'];
/** Relative step paths resolve under this, unless they start with `/rest/` (then they are used as-is). */
export const DEFAULT_BASE_PATH = '/rest/saiku/api';
/** Login endpoint of the Saiku REST surface; exempt from CSRF, answers 200 + JSESSIONID + XSRF-TOKEN. */
export const SESSION_PATH = '/rest/saiku/session';
const KINDS = new Set(['api', 'ui']);
const MATCH_OPERATORS = new Set(['equals', 'contains', 'matches', 'isNull', 'exists', 'type', 'length']);
const MAX_CLOSING_ISSUES = 20;
/** Ceiling on a repeated step (the limiter example needs ~70 probes, not endless). */
export const MAX_REPEAT = 500;

/**
 * Issue numbers a PR body claims to close. Only the GitHub closing keywords,
 * case-insensitively, each followed by `#<number>` or `owner/repo#<number>`.
 * Anything else ("related to #7", a bare "#7") is deliberately not a claim.
 * @param {unknown} body
 * @returns {number[]} unique, ascending
 */
export function closingIssues(body) {
  if (typeof body !== 'string' || body.length > 100000) return [];
  const found = new Set();
  const re = /\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\b[^\S\n]*:?[^\S\n]*(?:[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+)?#(\d{1,7})/gi;
  let m;
  while ((m = re.exec(body)) !== null) {
    found.add(Number(m[1]));
    if (found.size >= MAX_CLOSING_ISSUES) break;
  }
  return [...found].sort((a, b) => a - b);
}

/**
 * Validate a parsed spec. Returns a list of human-readable problems; empty means
 * usable. Deliberately strict: a spec that cannot run is worse than no spec,
 * because CI would report a pass nobody earned.
 * @param {unknown} spec
 * @returns {string[]}
 */
export function validateSpec(spec) {
  const errors = [];
  if (spec === null || typeof spec !== 'object' || Array.isArray(spec)) {
    return ['spec.json must contain a JSON object'];
  }
  if (!Number.isInteger(spec.issue) || spec.issue <= 0) errors.push('"issue" must be a positive integer');
  if (typeof spec.title !== 'string' || spec.title.trim() === '') errors.push('"title" must be a non-empty string');
  if (!KINDS.has(spec.kind)) errors.push(`"kind" must be one of ${[...KINDS].join(', ')}`);

  if (!Array.isArray(spec.criteria) || spec.criteria.length === 0) {
    errors.push('"criteria" must be a non-empty array: one entry per acceptance criterion in the issue');
  } else {
    const ids = new Set();
    spec.criteria.forEach((c, i) => {
      if (c === null || typeof c !== 'object' || Array.isArray(c)) {
        errors.push(`criteria[${i}] must be an object`);
        return;
      }
      if (typeof c.id !== 'string' || c.id.trim() === '') errors.push(`criteria[${i}].id must be a non-empty string`);
      else if (ids.has(c.id)) errors.push(`criteria[${i}].id "${c.id}" is duplicated`);
      else ids.add(c.id);
      if (typeof c.then !== 'string' || c.then.trim() === '') errors.push(`criteria[${i}].then must be a non-empty string`);
    });
  }

  if (spec.auth !== undefined && !AUTH_MODES.includes(spec.auth)) {
    errors.push(`"auth" must be one of ${AUTH_MODES.join(', ')}; credentials come from SAIKU_ACCEPTANCE_USER / SAIKU_ACCEPTANCE_PASSWORD, never from the spec`);
  }
  if (spec.basePath !== undefined && !(typeof spec.basePath === 'string' && /^\/[\w./-]*$/.test(spec.basePath) && !spec.basePath.includes('..'))) {
    errors.push('"basePath" must be a path such as "/rest/saiku/api"');
  }
  if (spec.kind === 'api') {
    if (!Array.isArray(spec.steps) || spec.steps.length === 0) {
      errors.push('"steps" must be a non-empty array for an api spec');
    } else {
      spec.steps.forEach((s, i) => {
        errors.push(...stepErrors(s, i));
      });
    }
  }

  if (spec.kind === 'ui') {
    if (!Array.isArray(spec.command) || spec.command.length === 0) {
      errors.push('"command" must be a non-empty argv array for a ui spec');
    } else if (spec.command.some((a) => typeof a !== 'string')) {
      errors.push('"command" entries must all be strings');
    } else if (spec.command.some((a) => /[;&|`$><\n]/.test(a))) {
      // argv only: nothing is ever handed to a shell, and a shell metacharacter
      // in a spec is a sign the author expected one.
      errors.push('"command" must not contain shell metacharacters; it is executed as argv');
    }
    if (spec.timeoutSeconds !== undefined && !(Number.isInteger(spec.timeoutSeconds) && spec.timeoutSeconds > 0)) {
      errors.push('"timeoutSeconds" must be a positive integer when present');
    }
  }

  return errors;
}

function stepErrors(step, i) {
  const at = `steps[${i}]`;
  const errors = [];
  if (step === null || typeof step !== 'object' || Array.isArray(step)) return [`${at} must be an object`];
  if (typeof step.name !== 'string' || step.name.trim() === '') errors.push(`${at}.name must be a non-empty string`);
  if (typeof step.criterion !== 'string' || step.criterion.trim() === '') {
    errors.push(`${at}.criterion must name the criteria id this step proves`);
  }
  if (step.auth !== undefined && !AUTH_MODES.includes(step.auth)) errors.push(`${at}.auth must be one of ${AUTH_MODES.join(', ')}`);
  const req = step.request;
  if (req === null || typeof req !== 'object' || Array.isArray(req)) {
    errors.push(`${at}.request must be an object`);
  } else {
    const method = typeof req.method === 'string' ? req.method.toUpperCase() : '';
    if (!METHODS.has(method)) errors.push(`${at}.request.method must be one of ${[...METHODS].join(', ')}`);
    const path = req.path;
    if (typeof path !== 'string' || path.trim() === '') errors.push(`${at}.request.path must be a string`);
    else if (!path.startsWith('/') && !/^https?:\/\//i.test(path)) {
      errors.push(`${at}.request.path must start with "/" (resolved against the base URL) or be an absolute http(s) URL`);
    }
    if (req.headers !== undefined && !isPlainObject(req.headers)) errors.push(`${at}.request.headers must be an object`);
    if (req.body !== undefined) errors.push(`${at}.request.body must be given as "json"`);
    if (req.json !== undefined && !isPlainObject(req.json)) errors.push(`${at}.request.json must be an object`);
  }
  if (step.repeat !== undefined) {
    const repeat = step.repeat;
    if (!isPlainObject(repeat)) errors.push(`${at}.repeat must be an object { "count": N, "untilStatus": N }`);
    else {
      if (!Number.isInteger(repeat.count) || repeat.count < 1 || repeat.count > MAX_REPEAT) {
        errors.push(`${at}.repeat.count must be an integer between 1 and ${MAX_REPEAT}`);
      }
      if (!Number.isInteger(repeat.untilStatus)) errors.push(`${at}.repeat.untilStatus must be an integer HTTP status`);
    }
  }
  if (step.expect !== undefined) errors.push(...expectErrors(step.expect, `${at}.expect`));
  return errors;
}

function expectErrors(expect, at) {
  const errors = [];
  if (!isPlainObject(expect)) return [`${at} must be an object`];
  if (expect.status !== undefined && !isStatusSet(expect.status)) errors.push(`${at}.status must be a number or array of numbers`);
  if (expect.bodyContains !== undefined && typeof expect.bodyContains !== 'string') {
    errors.push(`${at}.bodyContains must be a string`);
  }
  if (expect.header !== undefined) {
    if (!isPlainObject(expect.header) || typeof expect.header.name !== 'string') {
      errors.push(`${at}.header must be { "name": ..., ...assertion }`);
    } else errors.push(...assertionErrors(expect.header, `${at}.header`, ['name']));
  }
  if (expect.json !== undefined) {
    if (!isPlainObject(expect.json)) {
      errors.push(`${at}.json must be an object keyed by json path`);
    } else {
      for (const [path, assertion] of Object.entries(expect.json)) {
        if (path.trim() === '') errors.push(`${at}.json has an empty path`);
        errors.push(...assertionErrors(assertion, `${at}.json["${path}"]`));
      }
    }
  }
  return errors;
}

function assertionErrors(assertion, at, extraKeys = []) {
  if (!isPlainObject(assertion)) return [`${at} must be an object with one assertion operator`];
  const operators = Object.keys(assertion).filter((k) => k !== 'because' && !extraKeys.includes(k));
  if (operators.length !== 1) return [`${at} must carry exactly one of ${[...MATCH_OPERATORS].join(', ')}`];
  const [op] = operators;
  if (!MATCH_OPERATORS.has(op)) return [`${at}.${op} is not an assertion operator`];
  if (op === 'matches') {
    try {
      new RegExp(assertion.matches);
    } catch {
      return [`${at}.matches is not a valid regular expression`];
    }
  }
  if (op === 'length' && !Number.isInteger(assertion.length)) return [`${at}.length must be an integer`];
  if (op === 'type' && !['string', 'number', 'boolean', 'object', 'array', 'null'].includes(assertion.type)) {
    return [`${at}.type must be string, number, boolean, object, array or null`];
  }
  return [];
}

function isStatusSet(status) {
  if (Number.isInteger(status)) return true;
  return Array.isArray(status) && status.length > 0 && status.every((s) => Number.isInteger(s));
}

function isPlainObject(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

/**
 * Should this PR owe `acceptance/<issue>/` specs?
 *
 * @param {{body?: unknown, labels?: string[], presentIssues?: number[],
 *          waivers?: string[]}} args
 * @returns {{required: {issue: number, dir: string, present: boolean}[],
 *            waived: boolean, reason: string, ok: boolean}}
 */
export function decideRequirement({
  body = '',
  labels = [],
  presentIssues = [],
  waivers = [WAIVER_LABEL],
  enforceFrom = 0,
  changedFiles
} = {}) {
  const closing = closingIssues(body);
  const wanted = (waivers ?? []).map((l) => String(l).toLowerCase());
  const applied = labels.map((l) => String(l).toLowerCase());
  const waivedBy = wanted.find((l) => applied.includes(l));

  if (closing.length === 0) {
    return { required: [], grandfathered: [], waived: false, reason: 'no-closing-issue', ok: true };
  }
  // Rollout cutoff: issues opened before the convention existed owe no spec.
  const grandfathered = closing.filter((issue) => issue < enforceFrom);
  const enforced = closing.filter((issue) => issue >= enforceFrom);
  if (enforced.length === 0) {
    const reason = Number.isFinite(enforceFrom)
      ? `grandfathered: issues before #${enforceFrom} are not enforced`
      : 'grandfathered: enforcement is not configured (ACCEPTANCE_ENFORCE_FROM_ISSUE)';
    return { required: [], grandfathered, waived: false, reason, ok: true };
  }
  const required = enforced.map((issue) => ({
    issue,
    dir: `acceptance/${issue}`,
    present: presentIssues.includes(issue)
  }));
  if (waivedBy) {
    // The waiver is for docs-only and tests-only PRs. When the change list is
    // known, hold the PR to that; an empty list does not qualify.
    if (Array.isArray(changedFiles)) {
      const offending = nonWaivableFiles(changedFiles);
      if (changedFiles.length === 0 || offending.length > 0) {
        return {
          required,
          grandfathered,
          waived: false,
          waiverRejected: { files: offending },
          reason: 'closing issue',
          ok: required.every((r) => r.present)
        };
      }
    }
    return { required, grandfathered, waived: true, reason: `waived by label "${waivedBy}"`, ok: true };
  }
  return {
    required,
    grandfathered,
    waived: false,
    reason: 'closing issue',
    ok: required.every((r) => r.present)
  };
}

/**
 * Paths a waived PR may touch: documentation, and tests. A tests-only change
 * adds no product behaviour to specify, so it owes no acceptance spec either.
 * Anything else (including a test file alongside a production change, in which
 * case the production file still disqualifies the waiver) is held.
 */
const WAIVABLE_PATHS = [
  /^docs\//,
  /\.md$/i,
  /^\.github\/ISSUE_TEMPLATE\//,
  /(^|\/)src\/test\//, // Maven layout: src/test/java, src/test/resources
  /(^|\/)__tests__\//,
  /^saiku-ui\/e2e\//, // Playwright specs
  /^tests\//,
  /\.(test|spec)\.[cm]?[jt]sx?$/,
  /\.e2e\.[cm]?[jt]s$/
];

/** The changed files that are neither documentation nor tests. */
export const nonWaivableFiles = (files) => files.filter((f) => !WAIVABLE_PATHS.some((re) => re.test(String(f))));

/** @deprecated name kept for existing imports; the waiver now also covers tests. */
export const nonDocsFiles = nonWaivableFiles;

/** The closing-keyword wording a PR body should use, for the check's message. */
export const CONVENTION_HINT =
  'Add acceptance/<issue>/spec.json (see acceptance/README.md and docs/acceptance-specs.md), or label the PR ' +
  `${WAIVER_LABEL} if it is docs-only or tests-only.`;

/**
 * `data[0].rows[1].name` -> ['data', '0', 'rows', '1', 'name'].
 * Dots separate, brackets carry array indices; a missing token is simply not
 * found (the caller reports "path does not exist", never a crash).
 */
export function parseJsonPath(path) {
  const tokens = [];
  let current = '';
  let inBracket = false;
  for (const ch of String(path)) {
    if (ch === '[') {
      inBracket = true;
      if (current !== '') tokens.push(current);
      current = '';
      continue;
    }
    if (ch === ']') {
      inBracket = false;
      if (current !== '') tokens.push(current);
      current = '';
      continue;
    }
    if (ch === '.' && !inBracket) {
      if (current !== '') tokens.push(current);
      current = '';
      continue;
    }
    current += ch;
  }
  if (current !== '') tokens.push(current);
  return tokens.filter((t) => t !== '');
}

/** Resolve a json path. `$` is the document itself (an array body, for instance). Returns `{ found, value }`. */
export function lookupJson(root, path) {
  if (String(path).trim() === '$') return { found: true, value: root };
  let node = root;
  for (const token of parseJsonPath(path)) {
    if (node === null || typeof node !== 'object') return { found: false, value: undefined };
    if (!Object.prototype.hasOwnProperty.call(node, token)) return { found: false, value: undefined };
    node = node[token];
  }
  return { found: true, value: node };
}

/** Replace `{{env.NAME}}` placeholders with environment values. */
export function interpolate(value, env = {}) {
  if (typeof value === 'string') {
    return value.replace(/\{\{env\.([A-Z0-9_]+)\}\}/g, (whole, name) =>
      Object.prototype.hasOwnProperty.call(env, name) ? String(env[name]) : whole
    );
  }
  if (Array.isArray(value)) return value.map((v) => interpolate(v, env));
  if (isPlainObject(value)) {
    const out = {};
    for (const [k, v] of Object.entries(value)) out[k] = interpolate(v, env);
    return out;
  }
  return value;
}

function describe(value) {
  const text = typeof value === 'string' ? value : JSON.stringify(value);
  return text === undefined ? 'undefined' : String(text).slice(0, 300);
}

/** Compare one value against one assertion operator. */
export function assertValue(actual, assertion, { exists } = {}) {
  if (!isPlainObject(assertion)) return { ok: false, detail: 'assertion must be an object' };
  const [op] = Object.keys(assertion).filter((k) => k !== 'because' && k !== 'name');
  const expected = assertion[op];
  const because = assertion.because ? ` (${assertion.because})` : '';
  const fail = (detail) => ({ ok: false, detail: `${detail}${because}` });
  if (exists === false && op !== 'exists') return { ok: false, detail: `assertion on a path that does not exist${because}` };

  switch (op) {
    case 'equals':
      return actual === expected ? { ok: true } : fail(`expected ${describe(expected)}, got ${describe(actual)}`);
    case 'length':
      return Array.isArray(actual) && actual.length === expected
        ? { ok: true }
        : fail(`expected an array of length ${expected}, got ${describe(actual)}`);
    case 'contains':
      if (typeof actual === 'string') {
        return actual.includes(expected)
          ? { ok: true }
          : fail(`expected text containing ${describe(expected)}, got ${describe(actual)}`);
      }
      if (Array.isArray(actual)) {
        return actual.some((v) => describe(v).includes(String(expected)))
          ? { ok: true }
          : fail(`expected an array containing ${describe(expected)}, got ${describe(actual)}`);
      }
      return fail(`"contains" needs a string or array, got ${describe(actual)}`);
    case 'matches': {
      const re = new RegExp(expected);
      return typeof actual === 'string' && re.test(actual)
        ? { ok: true }
        : fail(`expected text matching /${expected}/, got ${describe(actual)}`);
    }
    case 'isNull':
      return (actual === null) === Boolean(expected)
        ? { ok: true }
        : fail(`expected ${expected ? 'null' : 'a non-null value'}, got ${describe(actual)}`);
    case 'exists':
      return exists === Boolean(expected) ? { ok: true } : fail(`expected exists=${expected}, got ${exists}`);
    case 'type': {
      const actualType = actual === null ? 'null' : Array.isArray(actual) ? 'array' : typeof actual;
      return actualType === expected ? { ok: true } : fail(`expected type ${expected}, got ${actualType}`);
    }
    default:
      return fail(`unknown assertion operator "${op}"`);
  }
}

/**
 * Evaluate one step's `expect` block against a response.
 * @returns {{assertion: string, ok: boolean, detail?: string}[]}
 */
export function evaluateExpect({ status, headers = {}, bodyText = '', json, expect = {} }) {
  const results = [];
  if (expect.status !== undefined) {
    const allowed = Array.isArray(expect.status) ? expect.status : [expect.status];
    results.push({
      assertion: 'status',
      ok: allowed.includes(status),
      ...(allowed.includes(status) ? {} : { detail: `expected ${allowed.join(' or ')}, got ${status}` })
    });
  }
  if (expect.bodyContains !== undefined) {
    const ok = typeof bodyText === 'string' && bodyText.includes(expect.bodyContains);
    results.push({
      assertion: 'bodyContains',
      ok,
      ...(ok ? {} : { detail: `expected body containing ${describe(expect.bodyContains)}` })
    });
  }
  if (expect.header !== undefined) {
    const actual = headerValue(headers, expect.header.name);
    const outcome = assertValue(actual, expect.header);
    results.push({
      assertion: `header ${expect.header.name}`,
      ok: outcome.ok,
      ...(outcome.ok ? {} : { detail: outcome.detail })
    });
  }
  for (const [path, assertion] of Object.entries(expect.json ?? {})) {
    const { found, value } = json === undefined ? { found: false, value: undefined } : lookupJson(json, path);
    const outcome = assertValue(value, assertion, { exists: found });
    results.push({
      assertion: `json ${path}`,
      ok: outcome.ok,
      ...(outcome.ok ? {} : { detail: `path ${found ? 'resolves' : 'does not exist'}: ${outcome.detail}` })
    });
  }
  return results;
}

function headerValue(headers, name) {
  const wanted = String(name).toLowerCase();
  for (const [k, v] of Object.entries(headers ?? {})) {
    if (k.toLowerCase() === wanted) return Array.isArray(v) ? v.join(', ') : v;
  }
  return undefined;
}

/**
 * Overall verdict of a run: `fail` if any spec failed, `not-run` if nothing
 * could run at all, else `pass`. A spec that could not run is never a pass.
 * @param {{verdict: string}[]} specs
 */
export function verdictFor(specs) {
  if (!Array.isArray(specs) || specs.length === 0) return 'not-run';
  if (specs.some((s) => s.verdict === 'fail')) return 'fail';
  if (specs.every((s) => s.verdict === 'not-run')) return 'not-run';
  return 'pass';
}