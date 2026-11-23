// Tests the body-matching patterns used by .github/workflows/issue-gate.yml. The workflow runs the
// same two patterns through grep; this test reads them out of the workflow so they cannot drift,
// then applies them with grep. Hermetic: no network. Run by `ci / flake-policy tests`.

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const workflow = readFileSync(join(root, '.github', 'workflows', 'issue-gate.yml'), 'utf8');
const pattern = (name) => new RegExp(`^\\s+${name}='(.+)'$`, 'm').exec(workflow)[1];
const heading = pattern('heading');
const checklist = pattern('checklist');

const grep = (flags, expression, body) =>
  spawnSync('grep', [flags, expression], { input: `${body}\n` }).status === 0;
const hasCriteria = (body) => grep('-Eiq', heading, body) || grep('-Eq', checklist, body);

test('accepts an Acceptance criteria heading at any level and case', () => {
  assert.equal(hasCriteria('### Acceptance criteria\nit works'), true);
  assert.equal(hasCriteria('## ACCEPTANCE CRITERIA'), true);
  assert.equal(hasCriteria('intro\r\n#Acceptance Criteria\r\n'), true);
});

test('accepts a markdown task-list item', () => {
  assert.equal(hasCriteria('Do the thing\n- [ ] returns 200'), true);
  assert.equal(hasCriteria('  - [x] done check'), true);
  assert.equal(hasCriteria('* [X] star bullet'), true);
});

test('rejects bodies with neither, including look-alikes', () => {
  assert.equal(hasCriteria(''), false);
  assert.equal(hasCriteria('Please fix the bug soon.'), false);
  assert.equal(hasCriteria('We should state acceptance criteria later'), false);
  assert.equal(hasCriteria('- [] not a task item\n-[ ] missing space'), false);
});

test('the workflow keeps its safety shape', () => {
  const code = workflow.replace(/^\s*#.*$/gm, '');
  assert.match(code, /^permissions: \{\}$/m);
  assert.match(code, /issues: write/);
  assert.match(code, /timeout-minutes: 5/);
  assert.match(code, /github\.event\.label\.name == 'issue-approved'/);
  assert.match(code, /ISSUE_BODY: \$\{\{ github\.event\.issue\.body \}\}/);
  assert.doesNotMatch(code, /actions\/checkout/);
  assert.doesNotMatch(code, /^\s+uses:/m);
  const scripts = code.slice(code.indexOf('run: |'));
  assert.doesNotMatch(scripts, /\$\{\{/, 'no expression interpolation inside the script');
});
