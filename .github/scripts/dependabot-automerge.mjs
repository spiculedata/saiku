// Eligibility rules for .github/workflows/dependabot-automerge.yml. Pure functions plus a thin
// CLI so the workflow runs the same code the tests cover. The workflow fetches this file from
// the base branch through the API; it never checks out or executes pull-request code.
//
// A PR is eligible only when ALL of these hold:
//   - the title is a single-package Dependabot bump ("Bump X from A to B"); a group PR
//     ("Bump the <group> group ... with N updates") is never eligible;
//   - the update is a semver PATCH (maven, npm) or a non-major / digest update (github-actions);
//     majors, prereleases and every other ecosystem (docker, ...) are never eligible;
//   - every changed file is a dependency manifest/lockfile, or a workflow file whose changed
//     lines are all `uses:` lines.

import { appendFileSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const TITLE_RE = /^(?:[a-z-]+(?:\([^)]*\))?!?:\s*)?bump\s+(\S+)\s+from\s+(\S+)\s+to\s+(\S+?)(?:\s+in\s+\S+)?$/i;
const GROUP_RE = /\bgroup\b|\bwith\s+\d+\s+updates?\b/i;
const SEMVER_RE = /^v?(\d+)\.(\d+)\.(\d+)$/;
const MAJOR_MINOR_RE = /^v?(\d+)(?:\.(\d+))?$/;
const DIGEST_RE = /^[0-9a-f]{7,40}$/;
const MANIFEST_RE = /(^|\/)(pom\.xml|package\.json|package-lock\.json)$/;
const WORKFLOW_RE = /^\.github\/workflows\/[^/]+\.ya?ml$/;
const USES_LINE_RE = /^[+-]\s*(?:-\s*)?uses:\s/;
const MAX_FILES = 100;

export const ECOSYSTEM_LABELS = ['maven', 'npm', 'github-actions'];

export function parseTitle(title) {
  const text = String(title ?? '').trim();
  if (GROUP_RE.test(text)) return null;
  const match = TITLE_RE.exec(text);
  return match ? { name: match[1], from: match[2], to: match[3] } : null;
}

export function isPatchUpdate(from, to) {
  const a = SEMVER_RE.exec(from);
  const b = SEMVER_RE.exec(to);
  if (!a || !b) return false;
  return a[1] === b[1] && a[2] === b[2] && Number(b[3]) > Number(a[3]);
}

// github-actions: a digest refresh, or any version change inside the same major.
export function isActionsNonMajor(from, to) {
  if (DIGEST_RE.test(from) && DIGEST_RE.test(to)) return from !== to;
  const a = MAJOR_MINOR_RE.exec(from) ?? SEMVER_RE.exec(from);
  const b = MAJOR_MINOR_RE.exec(to) ?? SEMVER_RE.exec(to);
  return Boolean(a && b && a[1] === b[1] && from !== to);
}

export function filesAreSafe(files) {
  if (!Array.isArray(files) || files.length === 0 || files.length >= MAX_FILES) return false;
  return files.every((file) => {
    const name = String(file?.filename ?? '');
    if (MANIFEST_RE.test(name)) return true;
    if (!WORKFLOW_RE.test(name) || typeof file.patch !== 'string') return false;
    const changed = file.patch.split('\n').filter((line) => /^[+-]/.test(line));
    return changed.length > 0 && changed.every((line) => USES_LINE_RE.test(line));
  });
}

export function classify({ title, labels = [], files }) {
  const parsed = parseTitle(title);
  if (!parsed) return { eligible: false, reason: 'title is not a single-package Dependabot bump (groups are never auto-merged)' };
  const ecosystems = ECOSYSTEM_LABELS.filter((label) => labels.includes(label));
  if (ecosystems.length !== 1) return { eligible: false, reason: 'PR does not carry exactly one of the maven, npm or github-actions labels' };
  const [kind] = ecosystems;
  const versionOk = kind === 'github-actions' ? isActionsNonMajor(parsed.from, parsed.to) : isPatchUpdate(parsed.from, parsed.to);
  if (!versionOk) return { eligible: false, reason: `${parsed.from} to ${parsed.to} is not an auto-mergeable ${kind === 'github-actions' ? 'non-major or digest' : 'patch'} update` };
  if (!filesAreSafe(files)) return { eligible: false, reason: 'PR changes files other than dependency manifests/lockfiles or workflow uses: lines' };
  return { eligible: true, reason: `${kind} ${parsed.name} ${parsed.from} to ${parsed.to}` };
}

function main() {
  const { PR_TITLE, PR_LABELS, PR_FILES_FILE, GITHUB_OUTPUT } = process.env;
  const result = classify({
    title: PR_TITLE,
    labels: String(PR_LABELS ?? '').split(',').filter(Boolean),
    files: JSON.parse(readFileSync(PR_FILES_FILE, 'utf8')),
  });
  console.log(`eligible=${result.eligible} (${result.reason})`);
  if (GITHUB_OUTPUT) appendFileSync(GITHUB_OUTPUT, `eligible=${result.eligible}\n`);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main();
