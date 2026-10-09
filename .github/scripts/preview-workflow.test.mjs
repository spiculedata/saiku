/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */

/**
 * Guards on the SHAPE of the preview workflows. actionlint checks syntax; these pin
 * the security properties a later edit could quietly remove: trusted checkout, no
 * PR-authored text in expressions, no fork environments (this repository is public),
 * per-PR concurrency, minimal token permissions, a dedicated (non-deploy) SSH key and
 * real 40-hex action pins.
 */

import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
// Comments explain the rules ("never `ref:` the PR head"); assert on code only.
const read = (rel) =>
  readFileSync(join(root, rel), 'utf8')
    .split('\n')
    .filter((l) => !/^\s*#/.test(l))
    .join('\n');

const ENV_WF = read('workflows/preview-env.yml');
const REAPER_WF = read('workflows/preview-reaper.yml');
const COMMAND_WF = read('workflows/preview-command.yml');
const ACTION = read('actions/preview-host-access/action.yml');
const WORKFLOWS = [ENV_WF, REAPER_WF, COMMAND_WF];

/** Every `${{ ... }}` expression in a file. */
const expressions = (text) => [...text.matchAll(/\$\{\{\s*([^}]*?)\s*\}\}/g)].map((m) => m[1]);

test('preview-env runs from the base branch via pull_request_target and never checks out the PR head', () => {
  assert.match(ENV_WF, /^on:\n {2}pull_request_target:/m);
  assert.doesNotMatch(ENV_WF, /^on:[\s\S]*?\n {2}pull_request:/m);
  for (const wf of WORKFLOWS) {
    assert.doesNotMatch(wf, /\bref:/, 'checkout must use the default (base) ref');
    assert.doesNotMatch(wf, /head\.sha|head\.ref|github\.head_ref|merge_commit_sha/);
    assert.match(wf, /persist-credentials: false/);
  }
});

test('preview-env only ever targets PRs into development', () => {
  assert.match(ENV_WF, /pull_request_target:\n {4}branches: \[development\]/);
});

test('no expression reads attacker-controlled event data', () => {
  // The only values from the triggering event an expression may read: a number, a
  // repo name, the action name and the label name (compared for equality in an `if`).
  const allowed = new Set([
    'github.event.pull_request.number',
    'github.event.pull_request.head.repo.full_name',
    'github.event_name',
    'github.event.action',
    'github.event.label.name',
    'github.event.issue.number',
    'github.event.issue.pull_request != null',
  ]);
  for (const wf of [...WORKFLOWS, ACTION]) {
    for (const e of expressions(wf)) {
      if (!/github\.event\.|github\.head_ref|github\.ref_name/.test(e) || /^github\.event\.comment\.body/.test(e)) continue;
      const refs = [...e.matchAll(/github\.event\.[a-zA-Z_.]+(?: != null)?/g)].map((m) => m[0].replace(/\.$/, ''));
      for (const ref of refs) assert.ok(allowed.has(ref), `expression reads event data it should not: ${e}`);
    }
  }
  // The label gate in the job `if` is an equality test against a literal.
  assert.match(ENV_WF, /github\.event\.label\.name == 'preview'/);
});

test('no event or input expression is interpolated into a run script', () => {
  for (const wf of [...WORKFLOWS, ACTION]) {
    const blocks = [...wf.matchAll(/^(\s*)run: \|\n((?:\1 {2}.*\n|\n)+)/gm)].map((m) => m[2]);
    assert.ok(blocks.length > 0);
    for (const block of blocks) {
      assert.doesNotMatch(block, /\$\{\{/, `expression inside a run script:\n${block}`);
    }
  }
});

test('fork PRs are excluded at the job level', () => {
  assert.match(ENV_WF, /github\.event\.pull_request\.head\.repo\.full_name == github\.repository/);
});

test('two runs for the same PR cannot race: one concurrency group per PR, never cancelled mid-flight', () => {
  assert.match(ENV_WF, /group: preview-pr-\$\{\{ github\.event\.pull_request\.number \}\}/);
  assert.match(ENV_WF, /cancel-in-progress: false/);
  assert.match(REAPER_WF, /group: preview-reaper/);
  assert.match(REAPER_WF, /cancel-in-progress: false/);
  assert.match(COMMAND_WF, /group: preview-pr-\$\{\{ github\.event\.issue\.number \}\}/);
  assert.match(COMMAND_WF, /cancel-in-progress: false/);
});

test('token permissions are empty at the top and minimal per job', () => {
  const base = ['contents: read', 'pull-requests: write'];
  for (const [name, wf] of [['preview-env', ENV_WF], ['preview-reaper', REAPER_WF], ['preview-command', COMMAND_WF]]) {
    assert.match(wf, /^permissions: \{\}$/m, name);
    const jobPerms = wf.match(/^ {4}permissions:\n((?: {6}.+\n)+)/m)?.[1] ?? '';
    // Only the explicit /preview command may start a docker build (actions: write, to dispatch
    // docker.yml); the automatic flows get the base set, because a push already triggers the build.
    const expected = name === 'preview-command' ? [...base, 'actions: write'] : base;
    assert.deepEqual(jobPerms.trim().split('\n').map((l) => l.trim()).sort(), [...expected].sort(), name);
  }
});

test('only the /preview command asks for a missing image to be built', () => {
  assert.match(COMMAND_WF, /--ensure-build github/);
  assert.doesNotMatch(ENV_WF, /--ensure-build/);
  assert.doesNotMatch(REAPER_WF, /--ensure-build/);
  // The only workflow it may start is the PR image build, and the input stays out of any shell.
  assert.doesNotMatch(COMMAND_WF, /gh workflow run|workflows\/[^/\s]+\/dispatches/, 'dispatching happens in preview-ctl.mjs, not in the workflow file');
});

test('previews use their own SSH identity, never a deploy key', () => {
  for (const wf of [...WORKFLOWS, ACTION]) {
    assert.doesNotMatch(wf, /DEPLOY_SSH|GH_PACKAGES_TOKEN|ANSIBLE_VAULT|HETZNER|hetzner/);
  }
  for (const wf of WORKFLOWS) assert.match(wf, /secrets\.PREVIEW_SSH_PRIVATE_KEY/);
});

test('every third-party action is pinned to a full 40-hex commit with a version comment', () => {
  const files = [
    'workflows/preview-env.yml',
    'workflows/preview-reaper.yml',
    'workflows/preview-command.yml',
    'workflows/preview-infra.yml',
    'actions/preview-host-access/action.yml',
  ];
  for (const f of files) {
    const uses = readFileSync(join(root, f), 'utf8')
      .split('\n')
      .filter((l) => /^\s*-?\s*uses:/.test(l))
      .map((l) => l.replace(/^\s*-?\s*uses:\s*/, ''));
    assert.ok(uses.length > 0, f);
    for (const u of uses) {
      if (u.startsWith('./')) continue;
      assert.match(u, /^[\w.-]+\/[\w.-]+@[0-9a-f]{40} # v\d+(\.\d+){0,2}$/, `${f}: ${u}`);
    }
  }
});

test('the workflows skip cleanly when no preview host is configured', () => {
  for (const wf of WORKFLOWS) {
    assert.match(wf, /vars\.PREVIEW_SSH_TARGET/);
    assert.match(wf, /steps\.cfg\.outputs\.enabled == 'true'/);
    // Every step after the config check is gated on it (secrets are not touched otherwise).
    const after = wf.slice(wf.indexOf('- uses: actions/checkout'));
    for (const step of after.split(/\n {6}- /).slice(1)) {
      assert.match(step, /if: .*steps\.cfg\.outputs\.enabled == 'true'/, step.split('\n')[0]);
    }
  }
});

test('manual reaper runs default to dry-run; scheduled runs are real', () => {
  assert.match(REAPER_WF, /dry-run:[\s\S]*?default: true/);
  assert.match(REAPER_WF, /DRY_RUN: .*github\.event_name == 'workflow_dispatch' && inputs\.dry-run/);
  assert.match(REAPER_WF, /schedule:\n\s+- cron:/);
});

test('every ctl invocation names its host explicitly', () => {
  for (const wf of WORKFLOWS) {
    for (const m of wf.matchAll(/preview-ctl\.mjs (event|reap)[\s\S]*?(?=\n\n|$)/g)) {
      assert.match(m[0], /--host ssh/);
    }
  }
});

test('the reaper refuses a failed or malformed open-PR list instead of treating it as empty', () => {
  assert.match(REAPER_WF, /set -euo pipefail/);
  assert.match(REAPER_WF, /jq -e 'type == "array" and all\(\.\[\]; type == "number"\)'/);
});

test('there is no pull_request_review workflow: reviews are polled by the reaper from the default branch', () => {
  assert.match(REAPER_WF, /--activity github/);
  const dir = join(root, 'workflows');
  for (const f of readdirSync(dir).filter((n) => n.startsWith('preview-'))) {
    assert.doesNotMatch(read(`workflows/${f}`), /pull_request_review/, f);
  }
});

test('jq calls that must yield a raw string use -er, not -e', () => {
  assert.match(ACTION, /jq -er '\.BackendState'/);
  for (const wf of [...WORKFLOWS, ACTION]) assert.doesNotMatch(wf, /jq -e '\.[A-Za-z]+'/);
});

test('the composite action is referenced from the repository root checkout', () => {
  // These jobs check out into the workspace root, so the local action path has no
  // subdirectory. A job that checks out elsewhere must prefix that directory.
  for (const wf of WORKFLOWS) {
    for (const step of wf.split(/\n {6}- /).filter((st) => st.includes('actions/checkout'))) {
      assert.doesNotMatch(step, /\bpath:/, 'checkout into a subdirectory changes how the local action must be referenced');
    }
    assert.match(wf, /uses: \.\/\.github\/actions\/preview-host-access/);
  }
});

/* ------------------------------------------------ /preview command workflow */

test('preview-command runs from the default branch on issue_comment (created) and never on pull_request(_target)', () => {
  assert.match(COMMAND_WF, /^on:\n {2}issue_comment:\n {4}types: \[created\]$/m);
  assert.doesNotMatch(COMMAND_WF, /pull_request(_target|_review|_review_comment)?:/);
  assert.doesNotMatch(COMMAND_WF, /github\.event\.(issue|comment)\.[a-z_.]*(title|head|ref)\b/);
});

test('preview-command reads the comment body only in the job `if`, as a startsWith pre-filter', () => {
  const lines = COMMAND_WF.split('\n').filter((l) => /comment\.body/.test(l));
  assert.equal(lines.length, 1);
  assert.match(lines[0], /^\s+if: .*startsWith\(github\.event\.comment\.body, '\/preview'\)/);
});

test('preview-command gates host access and the lifecycle on the script verdict (collaborator, same-repo, open)', () => {
  assert.match(COMMAND_WF, /preview-command\.mjs/);
  assert.match(COMMAND_WF, /id: cmd/);
  const gated = COMMAND_WF.match(/if: .*steps\.cmd\.outputs\.proceed == 'true'/g) ?? [];
  assert.ok(gated.length >= 3, 'host access, ctl event and upload are all gated on proceed');
  // ctl reads the event the script built from API data, not the raw comment payload.
  assert.match(COMMAND_WF, /preview-ctl\.mjs event[\s\S]*--github-event "\$RUNNER_TEMP\/preview-request\.json"/);
  assert.doesNotMatch(COMMAND_WF, /preview-ctl\.mjs event[^\n]*\n[^\n]*--github-event "\$GITHUB_EVENT_PATH"/);
});

test('every secret is read only by steps behind the proceed/enabled gates', () => {
  for (const step of COMMAND_WF.split(/\n {6}- /).slice(1)) {
    if (/secrets\./.test(step)) assert.match(step, /steps\.cmd\.outputs\.proceed == 'true'/, step.split('\n')[0]);
  }
});
