// Pins the security posture of coverage-badge.yml, the one workflow that holds a
// write token for the `badges` branch. Run: node --test .github/scripts/coverage-badge.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const workflow = readFileSync(new URL('../workflows/coverage-badge.yml', import.meta.url), 'utf8');
const ci = readFileSync(new URL('../workflows/ci.yml', import.meta.url), 'utf8');

test('only acts on a successful push run of ci on development', () => {
  assert.match(workflow, /workflows: \[ci\]/);
  assert.match(workflow, /workflow_run\.conclusion == 'success'/);
  assert.match(workflow, /workflow_run\.event == 'push'/);
  assert.match(workflow, /workflow_run\.head_branch == 'development'/);
});

test('holds the minimum permissions and never checks out or runs PR code', () => {
  assert.match(workflow, /permissions:\n {2}actions: read.*\n {2}contents: write.*\n/);
  assert.doesNotMatch(workflow, /uses: actions\/checkout/);
  assert.doesNotMatch(workflow, /pull_request_target/);
});

test('event values reach the shell through env, not interpolation into a script', () => {
  const runBlocks = [...workflow.matchAll(/run: [|>][-+]?\n((?: {10}.*\n|\n)+)/g)].map((m) => m[1]);
  assert.ok(runBlocks.length >= 2, 'found the multi-line run blocks');
  for (const block of runBlocks) assert.doesNotMatch(block, /\$\{\{/);
  assert.doesNotMatch(workflow, /run: .*\$\{\{/);
});

test('validates the artifact before publishing it', () => {
  assert.ok(workflow.indexOf('Validate the badge') < workflow.indexOf('Publish to the badges branch'));
  assert.match(workflow, /\^\[0-9\]\{1,3\}/);
});

test('ci.yml builds the badge read-only, only on a push to development', () => {
  const step = ci.slice(ci.indexOf('- name: Build coverage badge'));
  assert.match(step, /if: github\.event_name == 'push' && github\.ref == 'refs\/heads\/development'/);
  assert.match(step, /name: coverage-badge/);
  assert.doesNotMatch(ci, /contents: write/);
});
