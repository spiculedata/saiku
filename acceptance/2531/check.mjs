// Acceptance spec for spiculedata/saiku#2531. Text-based (no YAML dependency); exits non-zero with
// the broken clause on stderr.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const strip = (t) => t.split('\n').filter((l) => !/^\s*#/.test(l)).join('\n');
const wf = strip(readFileSync(join(root, '.github', 'workflows', 'auto-review.yml'), 'utf8'));

// C1 — both collection queries filter on author_association.
const queries = wf.match(/gh api "repos\/\$\{GH_REPO\}\/pulls\/\$\{PR_NUMBER\}\/(reviews|comments)"/g) ?? [];
assert.equal(queries.length, 2, 'C1: both collection queries (reviews, comments) present');
const selects = wf.match(/select\(\.author_association == "OWNER"/g) ?? [];
assert.ok(
  selects.length >= 2,
  'C1: both gh api collection queries must select author_association in (OWNER, MEMBER, COLLABORATOR)',
);

// C2 — the checkout token never reaches the model run, and the tool list mirrors claude.yml.
const checkout = wf.slice(wf.indexOf('actions/checkout@'), wf.indexOf('- name: Collect review comments'));
assert.ok(checkout.includes('persist-credentials: false'), 'C2: checkout must set persist-credentials: false');
const agent = wf.slice(wf.indexOf('anthropics/claude-code-action@'), wf.indexOf('- name: Push the review commit'));
assert.ok(!/\bGH_TOKEN\b/.test(agent) && !/\bGITHUB_TOKEN\b/.test(agent), 'C2: the Claude step must carry no GH_TOKEN/GITHUB_TOKEN');
const allowed = /--allowed-tools\s+"([^"]*)"/.exec(agent);
assert.ok(allowed, 'C2: --allowed-tools present');
const tools = allowed[1].split(',').map((s) => s.trim());
for (const bad of ['Read', 'Edit', 'Write', 'Bash(git diff:*)', 'Bash(git log:*)', 'Bash(git push:*)']) {
  assert.ok(!tools.includes(bad), `C2: --allowed-tools must not contain ${bad}`);
}
for (const good of ['Read(**)', 'Edit(**)', 'Write(**)', 'Glob', 'Grep', 'Bash(git status:*)', 'Bash(git add:*)', 'Bash(git commit:*)']) {
  assert.ok(tools.includes(good), `C2: --allowed-tools must contain ${good}`);
}
const disallowed = /--disallowed-tools\s+"([^"]*)"/.exec(agent);
assert.ok(disallowed, 'C2: --disallowed-tools present');
assert.match(disallowed[1], /Bash\(git push:\*\)/, 'C2: git push must be disallowed for the model run');
assert.match(disallowed[1], /Edit\(\.github\/\*\*\)/, 'C2: .github/ edits must be disallowed');
assert.match(disallowed[1], /Write\(\.github\/\*\*\)/, 'C2: .github/ writes must be disallowed');

// C3 — the job cannot approve: pull-requests is read, and the push is a separate trusted step.
const perms = wf.slice(wf.indexOf('    permissions:'), wf.indexOf('    env:'));
assert.match(perms, /pull-requests: read/, 'C3: the job must request pull-requests: read');
assert.ok(!/pull-requests: write/.test(perms), 'C3: the job must not request pull-requests: write (it could approve)');
const push = wf.slice(wf.indexOf('- name: Push the review commit'));
assert.match(push, /git push/, 'C3: the push is a separate step after the model run');
assert.ok(wf.indexOf('anthropics/claude-code-action@') < wf.indexOf('- name: Push the review commit'), 'C3: push step follows the Claude step');

// C4 — a workflow-shape test pins the filter and the tool list.
const pin = readFileSync(join(root, '.github', 'scripts', 'auto-review-workflow.test.mjs'), 'utf8');
assert.match(pin, /author_association/, 'C4: the workflow-shape test pins the collaborator filter');
assert.match(pin, /--allowed-tools/, 'C4: the workflow-shape test pins the tool list');
assert.match(pin, /persist-credentials/, 'C4: the workflow-shape test pins the credential handling');
assert.match(pin, /pull-requests: read/, 'C4: the workflow-shape test pins the approval barrier');
console.log('auto-review.yml applies only collaborator comments through a claude.yml-shaped tool list and cannot approve');
