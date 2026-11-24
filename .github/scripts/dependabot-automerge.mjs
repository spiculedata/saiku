// Eligibility rules for .github/workflows/dependabot-automerge.yml. Pure functions plus a thin
// CLI so the workflow runs the same code the tests cover. The workflow fetches this file from
// the base branch through the API; it never checks out or executes pull-request code.
//
// A PR is eligible only when ALL of these hold:
//   - the title is a single-package Dependabot bump ("Bump X from A to B"); a group PR
//     ("Bump the <group> group ... with N updates") is never eligible;
//   - the PR carries exactly one of the maven or npm labels; GitHub Actions updates are never
//     auto-merged (actions execute in jobs holding packages:/contents: write and tokens, and
//     only `ci` stands between them and development — #2548), and neither are docker or majors;
//   - every changed file is a dependency manifest/lockfile, and no file under
//     .github/workflows/ changes at all;
//   - the diff itself yields a single from→to version pair, identical across every changed
//     file: versions come from the diff, never from the title (a triager can edit a title —
//     #2548). A maven hunk that looks like a build-plugin bump (a `<plugin>` context line or a
//     `*.plugin.version` property) is rejected: build plugins execute at build time.
//   - the diff pair matches the title pair and is a semver PATCH (maven, npm).

import { appendFileSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const TITLE_RE = /^(?:[a-z-]+(?:\([^)]*\))?!?:\s*)?bump\s+(\S+)\s+from\s+(\S+)\s+to\s+(\S+?)(?:\s+in\s+\S+)?$/i;
const GROUP_RE = /\bgroup\b|\bwith\s+\d+\s+updates?\b/i;
const SEMVER_RE = /^v?(\d+)\.(\d+)\.(\d+)$/;
const MANIFEST_RE = /(^|\/)(pom\.xml|package\.json|package-lock\.json)$/;
const WORKFLOW_RE = /^\.github\/workflows\//;
const PLUGIN_CONTEXT_RE = /<plugin(?:s|Management)?[\s>]|\bplugin\.version\b/i;
const MAX_FILES = 100;

export const ECOSYSTEM_LABELS = ['maven', 'npm'];

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

// Strip range prefixes npm writes into package.json ("^1.2.3", "~1.2.3", ">=1.2.3", "v1.2.3")
// so diff values compare equal to the plain versions a Dependabot title carries.
function normalizeVersion(value) {
  return String(value ?? '').trim().replace(/^[v^~>=<*]+/i, '');
}

function versionValue(filename, line) {
  if (/pom\.xml$/.test(filename)) {
    const match = /<([A-Za-z0-9._-]*version)>([^<>]{1,80})<\/\1>/.exec(line);
    return match ? normalizeVersion(match[2]) : null;
  }
  if (/package-lock\.json$/.test(filename)) {
    const match = /^\s*"version":\s*"([^"]{1,80})",?\s*$/.exec(line);
    return match ? normalizeVersion(match[1]) : null;
  }
  if (/package\.json$/.test(filename)) {
    const match = /^\s*"([^"]{1,128})":\s*"([^"]{0,80})",?\s*$/.exec(line);
    if (!match) return null;
    const value = normalizeVersion(match[2]);
    // A dependency value is a version or a simple version range; anything else ("latest",
    // "*", "file:../design-system", a git URL, a list of ranges) carries no comparable version.
    return /^(v)?\d/.test(value) && !/\s/.test(value) ? value : null;
  }
  return null;
}

// Walk the diff and pull one from→to pair per changed manifest file. A file whose changed
// lines carry no version token, or more than one distinct from or to, is unprovable — the
// caller treats that as not eligible. Returns { ok, from, to } or { ok: false, reason }.
export function diffVersions(files) {
  if (!Array.isArray(files) || files.length === 0 || files.length >= MAX_FILES) {
    return { ok: false, reason: 'PR changes no files (or more than 99)' };
  }
  let pair = null;
  for (const file of files) {
    const filename = String(file?.filename ?? '');
    if (typeof file?.patch !== 'string' || !MANIFEST_RE.test(filename)) continue;
    const changed = file.patch.split('\n').filter((line) => /^[+-]/.test(line));
    // The guard reads context lines too: a dependency version three lines below a <plugin>
    // sits in the same hunk, and the plugin would otherwise hide behind its own +/- lines.
    const hunk = file.patch.split('\n').filter((line) => !/^@@/.test(line));
    if (hunk.some((line) => PLUGIN_CONTEXT_RE.test(line.replace(/^[+-]/, '')))) {
      return { ok: false, reason: `${filename} looks like a Maven build-plugin change (plugins execute at build time)` };
    }
    const from = new Set();
    const to = new Set();
    for (const line of changed) {
      const value = versionValue(filename, line.slice(1));
      if (value === null) continue;
      (line[0] === '-' ? from : to).add(value);
    }
    if (from.size === 0 && to.size === 0) {
      return { ok: false, reason: `${filename} changed without a comparable version pair` };
    }
    if (from.size !== 1 || to.size !== 1 || from.values().next().value === to.values().next().value) {
      return { ok: false, reason: `${filename} changed more than one version (or none)` };
    }
    const filePair = [from.values().next().value, to.values().next().value];
    if (pair && (pair[0] !== filePair[0] || pair[1] !== filePair[1])) {
      return { ok: false, reason: 'the diff changes more than one version pair' };
    }
    pair = filePair;
  }
  if (!pair) return { ok: false, reason: 'the diff names no version pair' };
  return { ok: true, from: pair[0], to: pair[1] };
}

export function filesAreSafe(files) {
  if (!Array.isArray(files) || files.length === 0 || files.length >= MAX_FILES) return false;
  return files.every((file) => {
    const name = String(file?.filename ?? '');
    if (WORKFLOW_RE.test(name)) return false;
    return MANIFEST_RE.test(name);
  });
}

export function classify({ title, labels = [], files }) {
  const parsed = parseTitle(title);
  if (!parsed) return { eligible: false, reason: 'title is not a single-package Dependabot bump (groups are never auto-merged)' };
  const ecosystems = ECOSYSTEM_LABELS.filter((label) => labels.includes(label));
  if (ecosystems.length !== 1) {
    return { eligible: false, reason: 'PR does not carry exactly one of the maven or npm labels (github-actions, docker, majors and groups are never auto-merged)' };
  }
  const [kind] = ecosystems;
  if (!filesAreSafe(files)) return { eligible: false, reason: 'PR changes files other than dependency manifests/lockfiles, or touches .github/workflows/' };
  const diff = diffVersions(files);
  if (!diff.ok) return { eligible: false, reason: diff.reason };
  if (normalizeVersion(parsed.from) !== diff.from || normalizeVersion(parsed.to) !== diff.to) {
    return { eligible: false, reason: 'the versions in the title do not match the diff' };
  }
  if (!isPatchUpdate(diff.from, diff.to)) {
    return { eligible: false, reason: `${diff.from} to ${diff.to} is not an auto-mergeable ${kind} patch update` };
  }
  return { eligible: true, reason: `${kind} ${parsed.name} ${diff.from} to ${diff.to} (read from the diff)` };
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
