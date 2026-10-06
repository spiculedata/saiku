// Collaborator permission + review activity for preview environments.
// Ported verbatim from spiculedata/saiku-cloud .github/scripts/preview-activity.mjs
// (spiculedata/saiku-cloud#1379).
//
// Why the reaper polls instead of a pull_request_review workflow: review events
// run the workflow file from the PR's merge ref, so a PR could edit that file
// and receive the SSH/tailnet secrets whenever a collaborator reviewed it. The
// hourly reaper runs from the default branch, so it asks GitHub instead.
//
// Only a number, a validated login and an ISO timestamp are ever used. Review
// text and comment bodies are never requested (the --jq projections below pick
// fields), and nothing here reaches a shell: argv arrays only.

import { spawnSync } from 'node:child_process';

import { assertPrNumber } from './preview-guard.mjs';

const REPO_RE = /^[A-Za-z0-9_.-]{1,100}\/[A-Za-z0-9_.-]{1,100}$/;
const LOGIN_RE = /^[A-Za-z0-9][A-Za-z0-9[\]-]{0,38}$/;
const WRITE_PERMISSIONS = new Set(['admin', 'write']);
const WRITE_ROLES = new Set(['admin', 'maintain', 'write']);

export const isLogin = (value) => LOGIN_RE.test(value ?? '');

/**
 * `permission` is the legacy field (maintain reads as write); `role_name` is the
 * precise one. Anything else (read, triage, none, missing) is not enough.
 */
export const hasWriteAccess = (permission, roleName) =>
  WRITE_PERMISSIONS.has(String(permission ?? '').toLowerCase()) ||
  WRITE_ROLES.has(String(roleName ?? '').toLowerCase());

function ghRun(argv) {
  const r = spawnSync(argv[0], argv.slice(1), { encoding: 'utf8' });
  return { status: r.status ?? 255, stdout: r.stdout ?? '', stderr: r.stderr ?? '' };
}

/** Is `login` a collaborator with write+ access? Any failure means no. */
export function collaboratorHasWrite({ repo, login, run = ghRun }) {
  if (!REPO_RE.test(repo ?? '') || !isLogin(login)) return false;
  const res = run([
    'gh',
    'api',
    `repos/${repo}/collaborators/${login}/permission`,
    '--jq',
    '[.permission, .role_name] | @tsv',
  ]);
  if (res.status !== 0) return false;
  const [permission, roleName] = res.stdout.trim().split('\t');
  return hasWriteAccess(permission, roleName);
}

/**
 * Newest timestamp among rows of {login, at} authored by a write+ collaborator.
 * @returns {string|null} ISO timestamp
 */
export function latestCollaboratorActivity(rows, isWriter) {
  let best = null;
  for (const { login, at } of rows) {
    const ms = Date.parse(at);
    if (!isLogin(login) || Number.isNaN(ms) || (best !== null && ms <= best)) continue;
    if (isWriter(login)) best = ms;
  }
  return best === null ? null : new Date(best).toISOString();
}

export class GhActivity {
  constructor({ repo, run = ghRun }) {
    if (!REPO_RE.test(repo ?? '')) throw new Error('GhActivity: invalid repository');
    this.repo = repo;
    this.run = run;
    this.writers = new Map();
    this.lookup = (prs) => this.lookupAll(prs);
  }

  isWriter(login) {
    if (!this.writers.has(login)) {
      this.writers.set(login, collaboratorHasWrite({ repo: this.repo, login, run: this.run }));
    }
    return this.writers.get(login);
  }

  rows(pr, endpoint, atField) {
    const res = this.run([
      'gh',
      'api',
      '--paginate',
      `repos/${this.repo}/pulls/${pr}/${endpoint}`,
      '--jq',
      `.[] | select(.user.type == "User") | [.user.login, .${atField}] | @tsv`,
    ]);
    if (res.status !== 0) throw new Error(`listing ${endpoint} for #${pr} failed: ${res.stderr}`);
    return res.stdout
      .split('\n')
      .filter(Boolean)
      .map((line) => {
        const [login, at] = line.split('\t');
        return { login, at };
      });
  }

  /** @returns {Record<number, string>} PR number -> newest collaborator activity */
  lookupAll(prs) {
    const found = {};
    for (const pr of prs) {
      assertPrNumber(pr);
      const rows = [...this.rows(pr, 'reviews', 'submitted_at'), ...this.rows(pr, 'comments', 'created_at')];
      const latest = latestCollaboratorActivity(rows, (login) => this.isWriter(login));
      if (latest) found[pr] = latest;
    }
    return found;
  }
}
