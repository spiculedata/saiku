#!/usr/bin/env node
// The `acceptance/<issue>/` convention gate. Ported from spiculedata/saiku-cloud (#1382).
//
// Fails a PR that claims to close issue N (via `Closes #N` and friends) but
// ships no `acceptance/N/spec.json`. A docs-only PR opts out with the
// `acceptance-waived` label.
//
// Runs on `pull_request` in acceptance.yml with no secrets, so the PR body is
// untrusted text: it is read from the environment, parsed into integers by
// closingIssues(), and never interpolated into a command line.
//
//   acceptance-check.mjs [--repo-root DIR] [--body-file FILE]
//                        [--labels a,b] [--changed-files FILE] [--json OUT]
//
// All inputs default to the environment variables the workflow sets:
// PR_BODY, PR_LABELS (comma separated), PR_CHANGED_FILES (newline separated),
// GITHUB_REPOSITORY. Exit 0 when the convention is satisfied, 1 when it is not,
// 2 on bad input.

import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

import {
  CONVENTION_HINT,
  SPEC_FILE,
  WAIVER_LABEL,
  decideRequirement,
  validateSpec
} from './acceptance-spec.mjs';

const ROOT = resolve(process.cwd());

/** Issue numbers with a spec directory in the checkout (valid spec or not). */
export function presentIssues(repoRoot, log = () => {}) {
  const base = join(repoRoot, 'acceptance');
  let entries;
  try {
    entries = readdirSync(base, { withFileTypes: true });
  } catch (err) {
    if (err.code === 'ENOENT') return [];
    throw err;
  }
  const issues = [];
  for (const entry of entries) {
    if (!entry.isDirectory() || !/^\d+$/.test(entry.name)) continue;
    const issue = Number(entry.name);
    try {
      readFileSync(join(base, entry.name, SPEC_FILE), 'utf8');
    } catch {
      log(`acceptance/${entry.name} exists but has no ${SPEC_FILE}; it does not count`);
      continue;
    }
    issues.push(issue);
  }
  return issues.sort((a, b) => a - b);
}

/**
 * Full decision for one PR, including the problems inside each spec directory.
 * @param {{repoRoot?: string, body?: string, labels?: string[], log?: (s: string) => void}} args
 */
export function check({
  repoRoot = ROOT,
  body = '',
  labels = [],
  enforceFrom = 0,
  changedFiles,
  log = () => {}
}) {
  const present = presentIssues(repoRoot, log);
  const decision = decideRequirement({ body, labels, presentIssues: present, enforceFrom, changedFiles });

  const problems = [];
  if (!decision.ok) {
    for (const entry of decision.required.filter((r) => !r.present)) {
      problems.push(`this PR closes #${entry.issue} but adds no ${entry.dir}/${SPEC_FILE}`);
    }
    if (decision.waiverRejected) {
      const files = decision.waiverRejected.files.length > 0 ? decision.waiverRejected.files.join(', ') : '(no changed files)';
      problems.push(`the ${WAIVER_LABEL} label does not apply: it is for docs-only or tests-only PRs and this one touches ${files}`);
    }
  }

  // A directory that exists but is broken fails too: a spec nobody can run must
  // not sit there waiting to be discovered later.
  for (const issue of present) {
    const file = join(repoRoot, 'acceptance', String(issue), SPEC_FILE);
    let parsed;
    try {
      parsed = JSON.parse(readFileSync(file, 'utf8'));
    } catch (err) {
      problems.push(`acceptance/${issue}/${SPEC_FILE} is not valid JSON: ${err.message}`);
      continue;
    }
    const errors = validateSpec(parsed);
    if (parsed.issue !== issue) errors.push(`"issue" is ${parsed.issue} but the directory is acceptance/${issue}`);
    for (const error of errors) problems.push(`acceptance/${issue}/${SPEC_FILE}: ${error}`);
  }

  return { ...decision, problems, present };
}

/** Report text, so the workflow log and the sticky answer read the same. */
export function renderReport(result) {
  const lines = [];
  if (result.reason === 'no-closing-issue') {
    lines.push(`acceptance convention: not applicable (${result.reason}).`);
    return lines.join('\n');
  }
  if (result.reason?.startsWith('grandfathered')) {
    lines.push(`acceptance convention: not enforced (${result.reason}).`);
    for (const issue of result.grandfathered ?? []) lines.push(`  #${issue}: not required`);
    return lines.join('\n');
  }
  if (result.waived) {
    lines.push(`acceptance convention: waived — ${result.reason}.`);
    for (const entry of result.required) lines.push(`  ${entry.dir}: not required`);
    return lines.join('\n');
  }
  for (const entry of result.required) {
    lines.push(`acceptance convention: ${entry.dir}/${SPEC_FILE} ${entry.present ? 'present' : 'MISSING'}`);
  }
  for (const problem of result.problems) lines.push(`::error::${problem}`);
  if (result.problems.length > 0) lines.push(CONVENTION_HINT);
  return lines.join('\n');
}

/** One entry per line: file names are data, and a comma is a legal character in one. */
function parseLines(text) {
  if (typeof text !== 'string') return [];
  return text
    .split('\n')
    .map((line) => line.replace(/\r$/, ''))
    .filter((line) => line !== '');
}

function parseList(text) {
  if (typeof text !== 'string' || text.trim() === '') return [];
  return text
    .split(/[\n,]/)
    .map((s) => s.trim())
    .filter(Boolean);
}

/**
 * The rollout cutoff: only issues numbered at or above it owe a spec. Unset or
 * invalid means nothing is enforced yet (the gate is safe to merge before the
 * variable is set), so a typo never turns the gate on for everyone.
 */
export function parseEnforceFrom(raw) {
  const text = String(raw ?? '').trim();
  return /^\d{1,7}$/.test(text) ? Number(text) : Number.POSITIVE_INFINITY;
}

function usage(msg) {
  process.stderr.write(`acceptance-check: ${msg}\n`);
  process.stderr.write(
    'usage: acceptance-check.mjs [--repo-root DIR] [--body-file FILE] [--labels a,b] [--changed-files FILE] [--enforce-from N] [--json OUT]\n'
  );
  process.exit(2);
}

function main(argv) {
  const opts = { repoRoot: ROOT, bodyFile: null, labels: [], json: null, changedFiles: null, enforceFrom: null };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--repo-root') opts.repoRoot = resolve(argv[++i] ?? usage('--repo-root needs a value'));
    else if (arg === '--body-file') opts.bodyFile = argv[++i] ?? usage('--body-file needs a value');
    else if (arg === '--labels') opts.labels = parseList(argv[++i] ?? usage('--labels needs a value'));
    else if (arg === '--json') opts.json = argv[++i] ?? usage('--json needs a value');
    else if (arg === '--changed-files') opts.changedFiles = argv[++i] ?? usage('--changed-files needs a value');
    else if (arg === '--enforce-from') opts.enforceFrom = argv[++i] ?? usage('--enforce-from needs a value');
    else if (arg === '--help' || arg === '-h') usage('nothing to do');
    else usage(`unknown argument "${arg}"`);
  }

  let body = process.env.PR_BODY ?? '';
  if (opts.bodyFile) {
    try {
      body = readFileSync(opts.bodyFile, 'utf8');
    } catch (err) {
      usage(`cannot read ${opts.bodyFile}: ${err.message}`);
    }
  }
  const labels = opts.labels.length > 0 ? opts.labels : parseList(process.env.PR_LABELS ?? '');

  // PR_CHANGED_FILES being *set* (even empty) means the workflow computed the
  // list, so the waiver is verified against it; unset means not verified.
  let changedFiles;
  if (opts.changedFiles) {
    try {
      changedFiles = parseLines(readFileSync(opts.changedFiles, 'utf8'));
    } catch (err) {
      usage(`cannot read ${opts.changedFiles}: ${err.message}`);
    }
  } else if (process.env.PR_CHANGED_FILES !== undefined) {
    changedFiles = parseLines(process.env.PR_CHANGED_FILES);
  }
  const enforceFrom = parseEnforceFrom(opts.enforceFrom ?? process.env.ACCEPTANCE_ENFORCE_FROM_ISSUE);

  let result;
  try {
    result = check({
      repoRoot: opts.repoRoot,
      body,
      labels,
      enforceFrom,
      changedFiles,
      log: (s) => process.stdout.write(`${s}\n`)
    });
  } catch (err) {
    process.stderr.write(`acceptance-check: ${err.message}\n`);
    return 2;
  }

  const report = renderReport(result);
  process.stdout.write(`${report}\n`);
  if (opts.json) writeFileSync(opts.json, `${JSON.stringify({ ...result, report }, null, 2)}\n`);
  if (result.problems.length > 0) return 1;
  return 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) process.exit(main(process.argv.slice(2)));

export { WAIVER_LABEL };