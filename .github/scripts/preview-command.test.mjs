/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */

/**
 * /preview comment command gate (ported from saiku-cloud#1379): who may ask, on what, and
 * the event it hands to preview-ctl. A fake `gh` stands in for the API.
 */

import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

import { eventFromGithub, eligibility, DEFAULTS } from './preview-lifecycle.mjs';
import { main, parseCommand, prepareCommand } from './preview-command.mjs';

const REPO = 'spiculedata/saiku';
const sha = (c) => c.repeat(40);

const comment = (body, over = {}) => ({
  action: 'created',
  issue: { number: 12, pull_request: { url: 'x' } },
  comment: { id: 99, body },
  sender: { login: 'tom', type: 'User' },
  repository: { full_name: REPO },
  ...over,
});

const pull = (over = {}) => ({
  number: 12,
  state: 'open',
  merged: false,
  title: '$(touch /tmp/pwned)',
  user: { login: 'someone' },
  labels: [{ name: 'risk/low' }],
  head: { sha: sha('a'), ref: '`id`', repo: { full_name: REPO } },
  base: { repo: { full_name: REPO } },
  ...over,
});

function fakeApi({ permission = 'write', role = 'write', pr = pull(), fail = [] } = {}) {
  const calls = [];
  const gh = (path) => {
    calls.push(path);
    if (fail.some((f) => path.includes(f))) throw new Error('404');
    if (path.includes('/collaborators/')) return { permission, role_name: role };
    if (path.includes('/pulls/')) return pr;
    throw new Error(`unexpected ${path}`);
  };
  return { gh, calls };
}

/* ------------------------------------------------------------------ parse */

test('parseCommand accepts exactly /preview and /preview restart on the first line', () => {
  assert.deepEqual(parseCommand('/preview'), { restart: false });
  assert.deepEqual(parseCommand('  /preview  \nthanks'), { restart: false });
  assert.deepEqual(parseCommand('/preview restart'), { restart: true });
  assert.deepEqual(parseCommand('/PREVIEW'), { restart: false });
});

test('parseCommand ignores everything else', () => {
  for (const body of ['', 'hello /preview', '/previews', '/preview now', '/preview restart please', 'text\n/preview', '/preview;id', null, undefined, 5]) {
    assert.equal(parseCommand(body), null, String(body));
  }
});

/* ---------------------------------------------------------------- prepare */

test('a collaborator /preview on an open same-repo PR proceeds with a minimal event', () => {
  const api = fakeApi();
  const r = prepareCommand({ payload: comment('/preview'), repo: REPO, gh: api.gh });
  assert.equal(r.proceed, true);
  assert.equal(r.event.action, 'requested');
  assert.equal(r.event.requested_by, 'tom');
  assert.equal(r.event.restart, false);

  const e = eventFromGithub(r.event);
  assert.equal(e.number, 12);
  assert.equal(e.requestedBy, 'tom');
  assert.equal(e.headSha, sha('a'));
  assert.equal(eligibility(e, DEFAULTS).reason, 'requested-by-collaborator');
  assert.equal(JSON.stringify(r.event).includes('pwned'), false, 'PR title never reaches the event file');
  assert.equal(JSON.stringify(r.event).includes('`id`'), false, 'branch name never reaches the event file');
});

test('/preview restart sets restart', () => {
  const r = prepareCommand({ payload: comment('/preview restart'), repo: REPO, gh: fakeApi().gh });
  assert.equal(r.event.restart, true);
});

test('non-commands and issue (non-PR) comments are ignored without any API call', () => {
  const api = fakeApi();
  for (const payload of [
    comment('looks good'),
    comment('/preview', { issue: { number: 12 } }),
    comment('/preview', { action: 'edited' }),
    comment('/preview', { action: 'deleted' }),
  ]) {
    const r = prepareCommand({ payload, repo: REPO, gh: api.gh });
    assert.equal(r.proceed, false);
    assert.equal(r.reply, undefined);
  }
  assert.deepEqual(api.calls, []);
});

test('only write/maintain/admin may use it; everyone else is refused and told so', () => {
  for (const [permission, role] of [['write', 'maintain'], ['admin', 'admin'], ['write', 'write']]) {
    assert.equal(prepareCommand({ payload: comment('/preview'), repo: REPO, gh: fakeApi({ permission, role }).gh }).proceed, true);
  }
  for (const [permission, role] of [['read', 'read'], ['read', 'triage'], ['none', 'none']]) {
    const r = prepareCommand({ payload: comment('/preview'), repo: REPO, gh: fakeApi({ permission, role }).gh });
    assert.equal(r.proceed, false);
    assert.match(r.reply, /collaborators with write access/);
  }
});

test('a failing permission lookup fails closed and never fetches the PR', () => {
  const api = fakeApi({ fail: ['/collaborators/'] });
  const r = prepareCommand({ payload: comment('/preview'), repo: REPO, gh: api.gh });
  assert.equal(r.proceed, false);
  assert.equal(api.calls.some((c) => c.includes('/pulls/')), false);
});

test('a sender that is not a plausible login is refused before any API call', () => {
  const api = fakeApi();
  const r = prepareCommand({ payload: comment('/preview', { sender: { login: 'tom; id', type: 'User' } }), repo: REPO, gh: api.gh });
  assert.equal(r.proceed, false);
  assert.deepEqual(api.calls, []);
});

test('fork PRs are refused', () => {
  for (const head of [{ full_name: 'attacker/saiku' }, null]) {
    const r = prepareCommand({ payload: comment('/preview'), repo: REPO, gh: fakeApi({ pr: pull({ head: { sha: sha('a'), repo: head } }) }).gh });
    assert.equal(r.proceed, false);
    assert.match(r.reply, /forks/);
  }
});

test('closed or merged PRs get a "nothing done" reply', () => {
  for (const over of [{ state: 'closed' }, { state: 'closed', merged: true }]) {
    const r = prepareCommand({ payload: comment('/preview'), repo: REPO, gh: fakeApi({ pr: pull(over) }).gh });
    assert.equal(r.proceed, false);
    assert.match(r.reply, /closed.*Nothing was done/);
  }
});

test('a PR number that disagrees with the API response is refused', () => {
  const r = prepareCommand({ payload: comment('/preview'), repo: REPO, gh: fakeApi({ pr: pull({ number: 13 }) }).gh });
  assert.equal(r.proceed, false);
});

/* -------------------------------------------------------------------- cli */

test('the CLI writes the event file and GITHUB_OUTPUT, and reacts instead of replying on success', () => {
  const dir = mkdtempSync(join(tmpdir(), 'preview-cmd-'));
  try {
    const eventPath = join(dir, 'payload.json');
    const out = join(dir, 'event.json');
    const gho = join(dir, 'output');
    writeFileSync(eventPath, JSON.stringify(comment('/preview')));
    writeFileSync(gho, '');
    const api = fakeApi();
    const posts = [];
    const code = main(['--github-event', eventPath, '--out', out], { GITHUB_REPOSITORY: REPO, GITHUB_OUTPUT: gho }, { gh: api.gh, post: (...a) => posts.push(a) });
    assert.equal(code, 0);
    assert.match(readFileSync(gho, 'utf8'), /^proceed=true$/m);
    assert.equal(JSON.parse(readFileSync(out, 'utf8')).requested_by, 'tom');
    assert.deepEqual(posts, [['react', 99]]);

    writeFileSync(eventPath, JSON.stringify(comment('/preview')));
    const closed = fakeApi({ pr: pull({ state: 'closed' }) });
    const posts2 = [];
    writeFileSync(gho, '');
    main(['--github-event', eventPath, '--out', out], { GITHUB_REPOSITORY: REPO, GITHUB_OUTPUT: gho }, { gh: closed.gh, post: (...a) => posts2.push(a) });
    assert.match(readFileSync(gho, 'utf8'), /^proceed=false$/m);
    assert.equal(posts2[0][0], 'reply');
    assert.equal(posts2[0][1], 12);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
