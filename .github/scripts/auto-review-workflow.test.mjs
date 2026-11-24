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

test('the push is a separate step after the model run, on the PR head branch only', () => {
  assert.ok(wf.indexOf('anthropics/claude-code-action@') < wf.indexOf('- name: Push the review commit'), 'push follows the Claude step');
  assert.match(push, /git -c http\.extraheader/, 'the push step authenticates itself; nothing persisted in .git/config');
  assert.match(push, /push origin "HEAD:\$\{HEAD_REF\}"/, 'the push targets only the PR head branch');
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
