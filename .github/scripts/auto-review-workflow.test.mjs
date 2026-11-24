// Guards for auto-review.yml (saiku#2531). The workflow hands a model run a
// `contents: write` token-adjacent job driven by review-comment text, so these
// properties are the whole security argument: only collaborator comments are
// collected, the model run can neither read the checkout token nor write
// outside the workspace, it has no git diff/log/push, the push is a separate
// trusted step, and the job holds no pull-requests: write (it could approve).
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
// Comments explain the rules; assert on code only.
const wf = readFileSync(join(root, '.github', 'workflows', 'auto-review.yml'), 'utf8')
  .split('\n')
  .filter((l) => !/^\s*#/.test(l))
  .join('\n');

const checkout = wf.slice(wf.indexOf('actions/checkout@'), wf.indexOf('- name: Collect review comments'));
const agent = wf.slice(wf.indexOf('anthropics/claude-code-action@'), wf.indexOf('- name: Push the review commit'));
const push = wf.slice(wf.indexOf('- name: Push the review commit'));
const permissions = wf.slice(wf.indexOf('    permissions:'), wf.indexOf('    env:'));

test('both collection queries filter review comments to collaborators', () => {
  const reviews = /gh api "repos\/\$\{GH_REPO\}\/pulls\/\$\{PR_NUMBER\}\/reviews" --paginate[\s\S]*?--jq '([^']*)'/.exec(wf);
  const comments = /gh api "repos\/\$\{GH_REPO\}\/pulls\/\$\{PR_NUMBER\}\/comments" --paginate[\s\S]*?--jq '([^']*)'/.exec(wf);
  assert.ok(reviews && comments, 'both collection queries exist');
  const ASSOCIATIONS = ['OWNER', 'MEMBER', 'COLLABORATOR'];
  for (const [name, jq] of [['reviews', reviews[1]], ['comments', comments[1]]]) {
    for (const assoc of ASSOCIATIONS) {
      assert.ok(jq.includes(`.author_association == "${assoc}"`), `${name} query filters on ${assoc}`);
    }
  }
});

test('paginated collection emits per-item lines and slurps them into ONE array (saiku#2531, review of PR #2561)', () => {
  // gh --paginate emits one JSON document per page (an array per page, a bare
  // object per item); a [ ... ] wrapper inside --jq only wrapped the FIRST
  // page and --argjson then received a scalar. The per-item + jq -s shape
  // survives any number of pages.
  for (const name of ['reviews', 'comments']) {
    const m = new RegExp(
      `gh api "repos/\\$\\{GH_REPO\\}/pulls/\\$\\{PR_NUMBER\\}/${name}" --paginate[\\s\\S]*?--jq '([^']*)'`)
      .exec(wf);
    assert.ok(m, `${name} collection exists`);
    assert.ok(m[1].startsWith('.[] |'), `${name} jq emits one line per item, not a wrapped array`);
  }
  assert.ok(/jq -s '\[\.\[\] \| select\(type == "object"/.test(wf), 'the per-item stream is slurped into one array');
});

test('collection guards against a non-array payload before the model run (saiku#2531, review of PR #2561)', () => {
  assert.ok(
    /if ! jq -e 'type == "array"' >\/dev\/null/.test(wf),
    'a scalar payload aborts the job before the model run');
  assert.ok(/Review collection produced a non-array JSON payload/.test(wf), 'the failure names the cause');
});

test('the checkout does not persist credentials', () => {
  assert.match(checkout, /persist-credentials: false/);
});

test('the Claude step carries no GH_TOKEN and no GITHUB_TOKEN', () => {
  assert.ok(!/\bGH_TOKEN\b/.test(agent), 'the model run must not hold the checkout token');
  assert.ok(!/\bGITHUB_TOKEN\b/.test(agent), 'the model run must not hold the checkout token');
});

test('the tool list is claude.yml-shaped: workspace-scoped edits, no bare Read/Edit/Write, no git diff/log', () => {
  const args = /--allowed-tools\s+"([^"]*)"/.exec(agent);
  assert.ok(args, '--allowed-tools present');
  const tools = args[1].split(',').map((s) => s.trim());
  for (const good of ['Read(**)', 'Edit(**)', 'Write(**)', 'Glob', 'Grep', 'Bash(git status:*)', 'Bash(git add:*)', 'Bash(git commit:*)']) {
    assert.ok(tools.includes(good), `--allowed-tools must contain ${good}`);
  }
  for (const bad of ['Read', 'Edit', 'Write', 'Bash(git diff:*)', 'Bash(git log:*)', 'Bash(git push:*)']) {
    assert.ok(!tools.includes(bad), `--allowed-tools must not contain ${bad}`);
  }
});

test('git push and .github/ edits stay disallowed for the model run, as in claude.yml', () => {
  const args = /--disallowed-tools\s+"([^"]*)"/.exec(agent);
  assert.ok(args, '--disallowed-tools present');
  assert.match(args[1], /Bash\(git push:\*\)/);
  assert.match(args[1], /Edit\(\.github\/\*\*\)/);
  assert.match(args[1], /Write\(\.github\/\*\*\)/);
});

test('automation accounts and per-login permission checks guard the collection (saiku#2531 rework)', () => {
  const collect = wf.slice(wf.indexOf('- name: Collect review comments'), wf.indexOf('- name: Record the pre-review head'));
  // author_association alone is not write access; each distinct login must be
  // resolved through the collaborators API and only admin/maintain/write kept.
  assert.match(collect, /collaborators\/\$\{login\}\/permission/, 'each candidate login is resolved through the collaborators API');
  assert.match(collect, /admin\|maintain\|write/, 'only admin/maintain/write logins are collected');
  for (const bot of ['spicule-hive-bot', 'github-actions', 'claude', 'dependabot', 'renovate']) {
    assert.ok(collect.includes(bot), `automation account ${bot} is denylisted`);
  }
});

test('the model run cannot read or write .git (config, hooks, fsmonitor) (saiku#2531 rework)', () => {
  const args = /--disallowed-tools\s+"([^"]*)"/.exec(agent);
  assert.ok(args, '--disallowed-tools present');
  for (const t of ['Read(.git/**)', 'Edit(.git/**)', 'Write(.git/**)']) {
    assert.ok(args[1].includes(t), `--disallowed-tools must contain ${t}`);
  }
});

test('the model inputs live in the workspace, not RUNNER_TEMP (saiku#2531 rework)', () => {
  const collect = wf.slice(wf.indexOf('- name: Collect review comments'), wf.indexOf('- name: Record the pre-review head'));
  assert.match(collect, /\.auto-review\/review-comments\.md/);
  assert.match(collect, /\.auto-review\/pr-diff\.patch/);
  assert.match(collect, /\.git\/info\/exclude/, '.auto-review/ is git-ignored so the review commit never includes it');
  assert.ok(!collect.includes('RUNNER_TEMP'), 'inputs are not written to RUNNER_TEMP (workspace-scoped Read(**) cannot read it)');
  assert.match(agent, /\.auto-review\/review-comments\.md/, 'the prompt points at the workspace copy');
  assert.match(agent, /\.auto-review\/pr-diff\.patch/, 'the prompt points at the workspace copy');
});

const pushStep = wf.slice(wf.indexOf('- name: Push the review commit'), wf.indexOf('- name: Remove the trigger label'));

test('the push does not trust the workspace git state (saiku#2531 rework)', () => {
  assert.match(pushStep, /https:\/\/github\.com\/\$\{GH_REPO\}\.git/, 'the push goes to an explicit URL, never the .git/config remote');
  assert.match(pushStep, /core\.hooksPath=\/dev\/null/, 'hooks are disabled for the push');
  assert.match(pushStep, /core\.fsmonitor=/, 'fsmonitor is disabled for the push');
  assert.match(pushStep, /push --no-verify/, '--no-verify set for the push');
});

test('the push carries at most one commit and never touches .github/ (saiku#2531 rework)', () => {
  assert.match(pushStep, /rev-list --count "\$BEFORE\.\.HEAD"/, 'the push validates the commit count');
  assert.match(pushStep, /git diff --name-only "\$BEFORE\.\.HEAD" \| grep -q '\^\\.github\/'/, 'the push rejects commits touching .github/');
});

test('the push is a separate step after the model run, on the PR head branch only', () => {
  assert.ok(wf.indexOf('anthropics/claude-code-action@') < wf.indexOf('- name: Push the review commit'), 'push follows the Claude step');
  assert.match(pushStep, /-c http\.extraheader/, 'the push step authenticates itself; nothing persisted in .git/config');
  assert.match(pushStep, /"HEAD:\$\{HEAD_REF\}"/, 'the push targets only the PR head branch');
  const allowed = /--allowed-tools\s+"([^"]*)"/.exec(agent);
  assert.ok(allowed && !allowed[1].includes('Bash(git push'), 'the model run has no push tool in --allowed-tools');
});

test('the job cannot approve: pull-requests is read, never write', () => {
  assert.match(permissions, /pull-requests: read/);
  assert.ok(!/pull-requests: write/.test(permissions), 'pull-requests: write allows approving the PR');
  assert.match(permissions, /issues: write/, 'removing the trigger label needs issues: write');
  assert.match(permissions, /id-token: write/, 'claude-code-action exchanges OIDC for its app token');
});

test('the trusted gate still guards the trigger: labeller access and branch charset checks survive', () => {
  assert.match(wf, /github\.event\.label\.name == 'apply-review'/);
  assert.match(wf, /head\.repo\.full_name == github\.repository/, 'fork PRs are skipped');
  assert.match(wf, /collaborators\/\$\{LABELLER\}\/permission/, 'the labeller write-access check survives');
  assert.match(wf, /\^\[A-Za-z0-9\._\/-\]\+\$/, 'the head branch charset check survives');
});
