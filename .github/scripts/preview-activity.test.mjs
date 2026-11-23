/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */

/** Collaborator/permission/activity helpers (ported from saiku-cloud#1379). Fake gh only. */

import assert from 'node:assert/strict';
import { test } from 'node:test';

import {
  GhActivity,
  collaboratorHasWrite,
  hasWriteAccess,
  latestCollaboratorActivity,
} from './preview-activity.mjs';

const REPO = 'spiculedata/saiku';

/** Fake `gh`: routes on the api path, records every argv. */
function fakeGh(routes) {
  const calls = [];
  const run = (argv) => {
    calls.push(argv);
    const path = argv.find((a) => a.startsWith('repos/')) ?? '';
    const hit = Object.entries(routes).find(([k]) => path.endsWith(k));
    if (!hit) return { status: 1, stdout: '', stderr: 'not found' };
    return { status: 0, stdout: hit[1], stderr: '' };
  };
  return { run, calls };
}

test('only admin / maintain / write count as write access', () => {
  assert.equal(hasWriteAccess('admin', 'admin'), true);
  assert.equal(hasWriteAccess('write', 'maintain'), true);
  assert.equal(hasWriteAccess('write', 'write'), true);
  assert.equal(hasWriteAccess('read', 'triage'), false);
  assert.equal(hasWriteAccess('read', 'read'), false);
  assert.equal(hasWriteAccess('none', ''), false);
  assert.equal(hasWriteAccess(undefined, undefined), false);
});

test('collaboratorHasWrite fails closed on API errors and bad input', () => {
  const ok = fakeGh({ '/collaborators/tom/permission': 'write\tmaintain\n' });
  assert.equal(collaboratorHasWrite({ repo: REPO, login: 'tom', run: ok.run }), true);
  assert.equal(collaboratorHasWrite({ repo: REPO, login: 'stranger', run: ok.run }), false);
  assert.equal(collaboratorHasWrite({ repo: REPO, login: 'a b; id', run: ok.run }), false);
  assert.equal(collaboratorHasWrite({ repo: 'not a repo', login: 'tom', run: ok.run }), false);
  assert.equal(ok.calls.length, 2, 'bad input never reaches gh');
  const reader = fakeGh({ '/collaborators/ro/permission': 'read\tread\n' });
  assert.equal(collaboratorHasWrite({ repo: REPO, login: 'ro', run: reader.run }), false);
});

test('latestCollaboratorActivity ignores non-collaborators and junk rows', () => {
  const rows = [
    { login: 'tom', at: '2026-10-03T10:00:00Z' },
    { login: 'drive-by', at: '2026-10-03T20:00:00Z' },
    { login: 'tom', at: 'not-a-date' },
    { login: 'bad login', at: '2026-10-03T22:00:00Z' },
    { login: 'amy', at: '2026-10-03T12:00:00Z' },
  ];
  const writers = new Set(['tom', 'amy']);
  assert.equal(latestCollaboratorActivity(rows, (l) => writers.has(l)), '2026-10-03T12:00:00.000Z');
  assert.equal(latestCollaboratorActivity([], () => true), null);
  assert.equal(latestCollaboratorActivity(rows.slice(1, 2), (l) => writers.has(l)), null);
});

test('GhActivity: reviews and review comments by collaborators count, comment text is never requested', () => {
  const gh = fakeGh({
    '/pulls/7/reviews': 'tom\t2026-10-03T10:00:00Z\ndrive-by\t2026-10-03T23:00:00Z\n',
    '/pulls/7/comments': 'amy\t2026-10-03T15:00:00Z\n',
    '/pulls/8/reviews': 'drive-by\t2026-10-03T23:00:00Z\n',
    '/pulls/8/comments': '',
    '/collaborators/tom/permission': 'write\twrite\n',
    '/collaborators/amy/permission': 'admin\tadmin\n',
    '/collaborators/drive-by/permission': 'read\tread\n',
  });
  const found = new GhActivity({ repo: REPO, run: gh.run }).lookup([7, 8]);
  assert.deepEqual(found, { 7: '2026-10-03T15:00:00.000Z' });
  for (const argv of gh.calls.filter((c) => c.some((a) => a.includes('/pulls/')))) {
    assert.ok(argv.join(' ').includes('select(.user.type == "User")'), 'bots are excluded');
    assert.ok(!/\.body\b/.test(argv.join(' ')), 'bodies are never requested');
  }
});

test('GhActivity: a failed listing throws (the reaper treats that as "no extension")', () => {
  const gh = fakeGh({});
  assert.throws(() => new GhActivity({ repo: REPO, run: gh.run }).lookup([7]), /failed/);
  assert.throws(() => new GhActivity({ repo: 'x', run: gh.run }), /invalid repository/);
});
