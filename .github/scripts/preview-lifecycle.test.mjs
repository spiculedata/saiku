/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */

/**
 * Decision tests for preview-lifecycle.mjs. Pure functions with an injected
 * clock: no I/O, and no calendar date is hard-coded (the clock is derived once
 * and every other instant is an offset from it).
 */

import assert from 'node:assert/strict';
import { test } from 'node:test';

import {
  DEFAULTS,
  EMPTY_REGISTRY,
  announcement,
  activePrs,
  apply,
  applyReap,
  comment,
  diskVerdict,
  eligibility,
  eventFromGithub,
  gateOnDisk,
  normaliseRegistry,
  plan,
  queuedPrs,
  reap,
  touch,
  urlsFor,
} from './preview-lifecycle.mjs';

const GIB = 1024 ** 3;
const HOUR = 3600 * 1000;
// Any whole hour will do: tests only ever compare offsets from it.
const T0 = new Date(Math.floor(Date.now() / HOUR) * HOUR).toISOString();
const HOURS = (n) => new Date(Date.parse(T0) + n * HOUR).toISOString();
const sha = (c) => c.repeat(40);
const REPO = 'spiculedata/saiku';
const config = { ...DEFAULTS, maxEnvs: 2 };

const ev = (over = {}) => ({
  number: 10,
  action: 'opened',
  state: 'open',
  merged: false,
  author: 'spicule-hive[bot]',
  labels: [],
  headSha: sha('a'),
  headRepo: REPO,
  baseRepo: REPO,
  ...over,
});

/** Registry with the given [pr, state] entries, built via apply(). */
function registryOf(rows, now = T0) {
  let r = EMPTY_REGISTRY;
  let t = Date.parse(now);
  for (const [pr, state] of rows) {
    t += 1000;
    r = apply({ action: state === 'queued' ? 'queued' : 'up', reason: 'x', pr, headSha: sha('a') }, r, {
      now: new Date(t).toISOString(),
    });
  }
  return r;
}

/* ------------------------------------------------------------- defaults */

test('defaults match the shared-box capacity decision', () => {
  assert.equal(DEFAULTS.maxEnvs, 3);
  assert.equal(DEFAULTS.idleHours, 24);
  assert.equal(DEFAULTS.imageWaitMinutes, 20);
  assert.equal(DEFAULTS.baseDomain, 'preview.saiku.bi');
  assert.deepEqual(DEFAULTS.authors, ['spicule-hive[bot]']);
  assert.equal(DEFAULTS.previewLabel, 'preview');
});

test('hostnames are one label under the base domain, so the wildcard certificate covers them', () => {
  assert.deepEqual(urlsFor(7), { url: 'https://oss-pr-7.preview.saiku.bi' });
  assert.equal(urlsFor(7, 'preview.example.com').url, 'https://oss-pr-7.preview.example.com');
  assert.throws(() => urlsFor(7, 'bad domain'), /invalid preview base domain/);
  assert.ok(!urlsFor(7).url.slice('https://'.length).split('.')[0].includes('.'));
});

/* ----------------------------------------------------------- eligibility */

test('Hive-authored same-repo PRs are eligible, in any letter case', () => {
  assert.equal(eligibility(ev({ author: 'spicule-hive[bot]' })).eligible, true);
  assert.equal(eligibility(ev({ author: 'Spicule-Hive[Bot]' })).eligible, true);
});

test('the author list is configurable (PREVIEW_AUTHORS)', () => {
  const cfg = { ...DEFAULTS, authors: ['other-bot'] };
  assert.equal(eligibility(ev({ author: 'other-bot' }), cfg).eligible, true);
  assert.equal(eligibility(ev({ author: 'spicule-hive[bot]' }), cfg).eligible, false);
});

test('the preview label opts in any same-repo PR', () => {
  assert.deepEqual(eligibility(ev({ author: 'someone', labels: ['risk/low', 'Preview'] })), {
    eligible: true,
    reason: 'label-preview',
  });
});

test('other authors without the label are not eligible', () => {
  assert.equal(eligibility(ev({ author: 'someone' })).eligible, false);
  assert.equal(eligibility(ev({ author: 'app/spicule-hive' })).eligible, false);
});

test('fork PRs never get an environment, even Hive-authored, labelled or requested', () => {
  for (const over of [
    { headRepo: 'attacker/saiku' },
    { headRepo: 'attacker/saiku', labels: ['preview'] },
    { headRepo: 'attacker/saiku', author: 'spicule-hive[bot]', labels: ['preview'], requestedBy: 'tom' },
  ]) {
    const r = eligibility(ev(over));
    assert.equal(r.eligible, false);
    assert.match(r.reason, /fork/);
  }
});

test('unknown origin fails closed', () => {
  for (const over of [{ headRepo: '' }, { baseRepo: '' }, { headRepo: undefined }]) {
    const r = eligibility(ev({ labels: ['preview'], ...over }));
    assert.equal(r.eligible, false);
    assert.match(r.reason, /unknown-origin/);
  }
});

test('a login that is not a plausible GitHub login is never matched as an author', () => {
  const cfg = { ...DEFAULTS, authors: ['spicule-hive[bot]'] };
  assert.equal(eligibility(ev({ author: 'spicule-hive[bot]\n' }), cfg).eligible, false);
  assert.equal(eligibility(ev({ author: '' }), cfg).eligible, false);
});

/* ------------------------------------------------------------------ plan */

test('opening an eligible PR on an empty host brings an environment up', () => {
  const d = plan({ event: ev(), config, now: T0 });
  assert.equal(d.action, 'up');
  assert.equal(d.reason, 'new');
  assert.equal(d.project, 'saiku-oss-pr-10');
  assert.equal(d.url, 'https://oss-pr-10.preview.saiku.bi');
  assert.equal(d.imageTag, 'a'.repeat(7));
});

test('a PR that is not eligible is skipped without touching the host', () => {
  assert.equal(plan({ event: ev({ author: 'someone' }), config, now: T0 }).action, 'skip');
});

test('a push to a running environment refreshes it', () => {
  const registry = registryOf([[10, 'active']]);
  const d = plan({ event: ev({ action: 'synchronize', headSha: sha('b') }), registry, config, now: HOURS(1) });
  assert.equal(d.action, 'up');
  assert.equal(d.reason, 'refresh');
  assert.equal(d.imageTag, 'b'.repeat(7));
});

test('a label change on an unchanged commit keeps the environment and does not rebuild it', () => {
  const registry = registryOf([[10, 'active']]);
  const d = plan({ event: ev({ action: 'labeled' }), registry, config, now: HOURS(1) });
  assert.equal(d.action, 'keep');
});

test('closing or merging tears down an existing environment', () => {
  const registry = registryOf([[10, 'active']]);
  assert.equal(plan({ event: ev({ action: 'closed' }), registry, config, now: T0 }).action, 'down');
  assert.equal(plan({ event: ev({ action: 'closed', merged: true }), registry, config, now: T0 }).action, 'down');
  assert.equal(plan({ event: ev({ action: 'closed' }), config, now: T0 }).action, 'skip');
});

test('removing the preview label (or moving the head to a fork) tears the environment down', () => {
  const registry = registryOf([[10, 'active']]);
  const unlabeled = plan({ event: ev({ author: 'someone', action: 'unlabeled' }), registry, config, now: T0 });
  assert.equal(unlabeled.action, 'down');
  assert.match(unlabeled.reason, /no-longer-eligible/);
  assert.equal(plan({ event: ev({ headRepo: 'attacker/saiku' }), registry, config, now: T0 }).action, 'down');
});

test('a full host queues the new PR with a visible position instead of failing', () => {
  const registry = registryOf([[1, 'active'], [2, 'active']]);
  const d = plan({ event: ev({ number: 3 }), registry, config, now: HOURS(1) });
  assert.equal(d.action, 'queued');
  assert.equal(d.position, 1);
  assert.equal(plan({ event: ev({ number: 4 }), registry: apply(d, registry, { now: HOURS(1) }), config, now: HOURS(2) }).position, 2);
});

test('queue positions are first-come-first-served, not by PR number', () => {
  const registry = registryOf([[1, 'active'], [2, 'active'], [9, 'queued'], [4, 'queued']]);
  assert.deepEqual(queuedPrs(registry), [9, 4]);
  assert.equal(plan({ event: ev({ number: 4 }), registry, config, now: HOURS(2) }).position, 2);
});

test('malformed events are rejected rather than guessed at', () => {
  assert.throws(() => plan({ event: ev({ number: 0 }), config, now: T0 }));
  assert.throws(() => plan({ event: ev({ headSha: 'abc' }), config, now: T0 }), /invalid commit sha/);
  assert.throws(() => eventFromGithub({}), /no pull_request/);
});

test('eventFromGithub keeps only the fields the lifecycle may see', () => {
  const e = eventFromGithub({
    action: 'opened',
    pull_request: {
      number: 5,
      state: 'open',
      title: '$(touch /tmp/pwned)',
      body: 'x',
      user: { login: 'someone' },
      labels: [{ name: 'preview' }],
      head: { sha: sha('c'), ref: '`id`', repo: { full_name: REPO } },
      base: { repo: { full_name: REPO } },
    },
  });
  assert.deepEqual(Object.keys(e).sort(), [
    'action', 'author', 'baseRepo', 'headRepo', 'headSha', 'labels', 'merged', 'number', 'requestedBy', 'restart', 'state',
  ]);
  assert.ok(!JSON.stringify(e).includes('pwned'));
  assert.ok(!JSON.stringify(e).includes('`id`'));
});

test('eventFromGithub treats a deleted fork (null head repo) as unknown origin', () => {
  const e = eventFromGithub({ action: 'opened', pull_request: { number: 5, state: 'open', head: { sha: sha('c'), repo: null }, base: { repo: { full_name: REPO } } } });
  assert.equal(eligibility(e).eligible, false);
});

test('eventFromGithub honours requested_by only on a "requested" payload', () => {
  const pull = { number: 5, state: 'open', head: { sha: sha('c'), repo: { full_name: REPO } }, base: { repo: { full_name: REPO } } };
  assert.equal(eventFromGithub({ action: 'opened', requested_by: 'tom', pull_request: pull }).requestedBy, '');
  assert.equal(eventFromGithub({ action: 'requested', requested_by: 'tom', pull_request: pull }).requestedBy, 'tom');
  assert.equal(eventFromGithub({ action: 'requested', requested_by: 'tom; id', pull_request: pull }).requestedBy, '');
});

/* ------------------------------------------------------- collaborator /preview */

test('a collaborator request makes an unlabelled, non-Hive PR eligible, and the marker outlives later pushes', () => {
  const requested = ev({ author: 'someone', action: 'requested', requestedBy: 'tom' });
  assert.equal(eligibility(requested).reason, 'requested-by-collaborator');
  const up = plan({ event: requested, config, now: T0 });
  assert.equal(up.action, 'up');
  const registry = apply(up, EMPTY_REGISTRY, { now: T0 });
  assert.equal(registry.envs['10'].entitlement, 'collaborator');
  const push = plan({ event: ev({ author: 'someone', action: 'synchronize', headSha: sha('b') }), registry, config, now: HOURS(1) });
  assert.equal(push.action, 'up');
});

test('without the marker the same unlabelled non-Hive PR loses its env, and the marker never survives a fork head', () => {
  const registry = registryOf([[10, 'active']]);
  assert.equal(plan({ event: ev({ author: 'someone' }), registry, config, now: T0 }).action, 'down');
  const marked = apply({ action: 'up', pr: 10, headSha: sha('a'), requestedBy: 'tom' }, EMPTY_REGISTRY, { now: T0 });
  assert.equal(plan({ event: ev({ author: 'someone', headRepo: 'attacker/saiku' }), registry: marked, config, now: T0 }).action, 'down');
});

test('a request on a running env at the same commit keeps it and refreshes the idle window only; restart rebuilds', () => {
  const registry = registryOf([[10, 'active']]);
  const keep = plan({ event: ev({ action: 'requested', requestedBy: 'tom' }), registry, config, now: HOURS(5) });
  assert.equal(keep.action, 'keep');
  const refreshed = apply(keep, registry, { now: HOURS(5) });
  assert.equal(refreshed.envs['10'].lastActivity, HOURS(5));
  const restart = plan({ event: ev({ action: 'requested', requestedBy: 'tom', restart: true }), registry, config, now: HOURS(5) });
  assert.equal(restart.action, 'up');
  assert.equal(restart.reason, 'restart');
});

test('a label change never extends the idle window', () => {
  const registry = registryOf([[10, 'active']]);
  const keep = plan({ event: ev({ action: 'labeled' }), registry, config, now: HOURS(5) });
  assert.equal(apply(keep, registry, { now: HOURS(5) }).envs['10'].lastActivity, registry.envs['10'].lastActivity);
});

test('normaliseRegistry keeps only a valid collaborator marker and a valid image', () => {
  const r = normaliseRegistry({
    envs: {
      5: { pr: 5, state: 'active', entitlement: 'collaborator', requestedBy: 'tom', image: { tag: 'abcdef0' } },
      6: { pr: 6, state: 'active', entitlement: 'collaborator', requestedBy: 'tom; id', image: { tag: 'develop' } },
    },
  });
  assert.equal(r.envs['5'].entitlement, 'collaborator');
  assert.deepEqual(r.envs['5'].image, { tag: 'abcdef0' });
  assert.equal(r.envs['6'].entitlement, null);
  assert.equal(r.envs['6'].image, null);
});

test('touch moves lastActivity forward only, never into the future, and ignores junk', () => {
  const registry = registryOf([[10, 'active']]);
  assert.equal(touch(registry, 10, HOURS(3), HOURS(4)).envs['10'].lastActivity, HOURS(3));
  assert.equal(touch(registry, 10, HOURS(9), HOURS(4)).envs['10'].lastActivity, HOURS(4));
  assert.deepEqual(touch(registry, 10, HOURS(-9), HOURS(4)), registry);
  assert.deepEqual(touch(registry, 99, HOURS(3), HOURS(4)), registry);
  assert.deepEqual(touch(registry, 10, 'junk', HOURS(4)), registry);
});

/* ------------------------------------------------------------------ disk */

test('diskVerdict: healthy, prune and blocked bands, failing closed on garbage', () => {
  const total = 100 * GIB;
  assert.equal(diskVerdict({ totalBytes: total, freeBytes: 60 * GIB }), 'ok');
  assert.equal(diskVerdict({ totalBytes: total, freeBytes: 25 * GIB }), 'prune');
  assert.equal(diskVerdict({ totalBytes: total, freeBytes: 12 * GIB }), 'blocked');
  assert.equal(diskVerdict({ totalBytes: 40 * GIB, freeBytes: 9 * GIB }), 'blocked');
  for (const junk of [null, {}, { totalBytes: 0, freeBytes: 1 }, { totalBytes: 'x', freeBytes: 'y' }]) {
    assert.equal(diskVerdict(junk), 'blocked');
  }
});

test('gateOnDisk queues a new up on a blocked disk but never demotes a running environment', () => {
  const d = plan({ event: ev(), config, now: T0 });
  const gated = gateOnDisk(d, 'blocked', EMPTY_REGISTRY, T0);
  assert.equal(gated.action, 'queued');
  assert.equal(gated.reason, 'disk-low');
  const registry = registryOf([[10, 'active']]);
  const refresh = plan({ event: ev({ headSha: sha('b') }), registry, config, now: T0 });
  assert.equal(gateOnDisk(refresh, 'blocked', registry, T0).action, 'up');
  assert.equal(gateOnDisk(d, 'ok', EMPTY_REGISTRY, T0).action, 'up');
});

/* ------------------------------------------------------------------ reap */

test('reap tears down closed PRs and environments idle for more than 24h, and only those', () => {
  const registry = registryOf([[1, 'active'], [2, 'active'], [3, 'active']]);
  const fresh = touch(touch(registry, 1, HOURS(20), HOURS(20)), 2, HOURS(30), HOURS(30));
  const r = reap({ registry: fresh, openPrs: [1, 2], projects: [], now: HOURS(26), config });
  assert.deepEqual(r.teardowns.map((t) => [t.pr, t.reason]), [[3, 'pr-closed-or-merged']]);
  const later = reap({ registry: fresh, openPrs: [1, 2, 3], projects: [], now: HOURS(60), config });
  assert.deepEqual(later.teardowns.map((t) => t.pr).sort(), [1, 2, 3]);
  assert.ok(later.teardowns.every((t) => t.reason === 'idle'));
});

test('an unreadable or missing activity timestamp counts as idle', () => {
  const registry = normaliseRegistry({ envs: { 4: { pr: 4, state: 'active', lastActivity: 'junk' } } });
  assert.equal(reap({ registry, openPrs: [4], now: T0, config }).teardowns[0].reason, 'idle');
});

test('reap removes OSS orphan stacks the registry does not know, and never a cloud project', () => {
  const registry = registryOf([[1, 'active']]);
  const r = reap({
    registry,
    openPrs: [1, 7],
    projects: [
      { pr: 1, project: 'saiku-oss-pr-1' },
      { pr: 7, project: 'saiku-oss-pr-7' },
      { pr: 8, project: 'saiku-oss-pr-8' },
      { pr: 5, project: 'saiku-pr-5' }, // a cloud preview on the same box
      { pr: 5, project: 'saiku-pr-5-strict' },
      { pr: 0, project: 'saiku-cloud' },
    ],
    now: T0,
    config,
  });
  assert.deepEqual(r.orphans, [
    { pr: 7, reason: 'not-in-registry' },
    { pr: 8, reason: 'pr-closed-or-merged' },
  ]);
  assert.deepEqual(r.destroyPrs, [7, 8]);
});

test('reap promotes queued PRs into freed slots, oldest first; idle queued entries free no slot', () => {
  const registry = registryOf([[1, 'active'], [2, 'active'], [9, 'queued'], [4, 'queued']]);
  const closed = reap({ registry, openPrs: [2, 9, 4], now: T0, config });
  assert.deepEqual(closed.promotions.map((p) => p.pr), [9]);
  const closedQueued = reap({ registry, openPrs: [1, 2, 4], now: T0, config });
  assert.deepEqual(closedQueued.promotions, [], 'a closed PR in the queue is never promoted');
});

test('a full disk blocks promotion but not teardown', () => {
  const registry = registryOf([[1, 'active'], [9, 'queued']]);
  const r = reap({ registry, openPrs: [9], diskBlocked: true, now: T0, config: { ...config, maxEnvs: 2 } });
  assert.equal(r.teardowns.length, 1);
  assert.deepEqual(r.promotions, []);
});

test('reap rejects a malformed open-PR list instead of treating it as empty', () => {
  const registry = registryOf([[1, 'active']]);
  for (const openPrs of [null, 'x', [1, '2'], [0], [1.5]]) assert.throws(() => reap({ registry, openPrs, now: T0, config }), /openPrs/);
  assert.throws(() => reap({ registry, openPrs: [1], now: 'junk', config }), /unparseable now/);
});

test('applyReap removes teardowns and claims promotions', () => {
  const registry = registryOf([[1, 'active'], [9, 'queued']]);
  const result = reap({ registry, openPrs: [9], now: T0, config });
  const next = applyReap(result, registry, { now: T0 });
  assert.deepEqual(activePrs(next), [9]);
  assert.deepEqual(queuedPrs(next), []);
});

test('apply returns a new registry and never mutates its input', () => {
  const before = registryOf([[1, 'active']]);
  const snapshot = JSON.stringify(before);
  apply({ action: 'down', pr: 1 }, before, { now: T0 });
  assert.equal(JSON.stringify(before), snapshot);
  assert.throws(() => apply({ action: 'nope', pr: 1 }, before), /unknown action/);
});

test('normaliseRegistry recomputes derived fields, drops malformed entries and survives junk', () => {
  const r = normaliseRegistry({
    envs: {
      5: { pr: 5, state: 'active', project: 'rm -rf /', headSha: 'nothex', author: 'x; id' },
      6: { pr: 7, state: 'active' },
      bad: { pr: 'x' },
      9: 'junk',
    },
  });
  assert.deepEqual(Object.keys(r.envs), ['5']);
  assert.equal(r.envs['5'].project, 'saiku-oss-pr-5');
  assert.equal(r.envs['5'].headSha, null);
  assert.equal(r.envs['5'].author, null);
  for (const junk of [null, undefined, [], 'x', 5]) assert.deepEqual(normaliseRegistry(junk).envs, {});
});

/* --------------------------------------------------------------- comment */

test('the up comment carries the URL, the image and the sticky marker, and never a credential', () => {
  const body = comment({ action: 'up', reason: 'new', pr: 5, image: { tag: 'abcdef0' } }, { config });
  assert.match(body, /^<!-- saiku-oss-preview-status -->/);
  assert.match(body, /https:\/\/oss-pr-5\.preview\.saiku\.bi/);
  assert.match(body, /ghcr\.io\/spiculedata\/saiku:abcdef0/);
  assert.match(body, /README\.md#credentials-for-validators/);
  assert.doesNotMatch(body, /prevpw_|password=|PASSWORD=/i);
});

const LOGIN = { user: 'admin', password: 'prevpw_0123456789abcdef0123456789abcdef01234567' };

test('the up comment links the app under /ui/ and shows the throwaway login when one is given', () => {
  const body = comment({ action: 'up', reason: 'new', pr: 5, image: { tag: 'abcdef0' }, credentials: LOGIN }, { config });
  assert.match(body, /Saiku: https:\/\/oss-pr-5\.preview\.saiku\.bi\/ui\/ /, 'the bare root is a 500, so link /ui/');
  assert.ok(body.includes(`Login: \`admin\` / \`${LOGIN.password}\``));
  assert.match(body, /throwaway/);
  assert.match(body, /anyone who can read this PR can read it/);
  assert.match(body, /\*\*UP\*\* \(new\)/);
  assert.doesNotMatch(body, /Fetch it with/, 'no fetch hint when the login is shown');
});

test('without a login (disabled, or the host would not say) the comment says how to fetch it and never invents one', () => {
  const none = comment({ action: 'up', reason: 'new', pr: 7, image: { tag: 'abcdef0' } }, { config });
  assert.match(none, /Fetch it with `ssh saiku-preview "grep SAIKU_ADMIN_PASSWORD \/var\/lib\/saiku-preview-oss\/env\/saiku-oss-pr-7\.env"`/);
  assert.match(none, /README\.md#credentials-for-validators/);
  assert.doesNotMatch(none, /prevpw_|Login: /);

  // PREVIEW_POST_CREDENTIALS=false wins even when a login was passed in.
  const off = comment({ action: 'up', reason: 'new', pr: 7, credentials: LOGIN }, { config: { ...config, postCredentials: false } });
  assert.doesNotMatch(off, /prevpw_|Login: /);
  assert.match(off, /Fetch it with/);
});

test('only a login with the exact shape the host writes is rendered', () => {
  for (const bad of [
    { user: 'admin', password: 'x y' },
    { user: 'admin', password: 'pw`; rm -rf /' },
    { user: 'ad min', password: 'prevpw_ab' },
    { user: 'admin', password: '' },
    { user: undefined, password: 'prevpw_ab' },
    { password: 'prevpw_ab' },
    'prevpw_ab',
  ]) {
    const body = comment({ action: 'up', reason: 'new', pr: 5, credentials: bad }, { config });
    assert.doesNotMatch(body, /Login: /, JSON.stringify(bad));
    assert.match(body, /Fetch it with/);
  }
});

test('the announcement pings with the URL, points at the sticky comment, and carries no credential or sticky marker', () => {
  const body = announcement({ pr: 12 }, { config });
  assert.match(body, /^\*\*Preview is running\*\* for this PR: https:\/\/oss-pr-12\.preview\.saiku\.bi\/ui\//);
  assert.match(body, /\*\*Preview environment\*\* comment/);
  assert.doesNotMatch(body, /prevpw_|password/i);
  assert.ok(!body.includes('saiku-oss-preview-status'), 'must not be mistaken for the sticky comment');
  assert.throws(() => announcement({ pr: 'x; rm' }, { config }));
});

test('the building comment says a build was started or is running, where it will be served, and when it gives up', () => {
  const started = comment({ action: 'building', reason: 'build-started', pr: 7 }, { config });
  assert.match(started, /^<!-- saiku-oss-preview-status -->/);
  assert.match(started, /\*\*BUILDING IMAGE\*\* \(build-started\)/);
  assert.match(started, /a `docker` build was started/);
  assert.match(started, /https:\/\/oss-pr-7\.preview\.saiku\.bi/);
  assert.match(started, /no need to comment again/);
  assert.match(started, /20 minutes/);
  assert.doesNotMatch(started, /prevpw_|password=|PASSWORD=/i);

  const running = comment({ action: 'building', reason: 'build-in-progress', pr: 7 }, { config });
  assert.match(running, /\(build-in-progress\)/);
  assert.match(running, /still running/);
  assert.doesNotMatch(running, /was started/);
});

test('the queued, failed and down comments say what happened and how to retry', () => {
  const queued = comment({ action: 'queued', reason: 'capacity-full', pr: 5, position: 2 }, { config });
  assert.match(queued, /queued at position 2/);
  assert.match(queued, /at capacity \(2 OSS environments\)/);
  assert.match(comment({ action: 'queued', reason: 'disk-low', pr: 5, position: 1 }, { config }), /low on disk space/);
  const failed = comment({ action: 'failed', reason: 'image-not-ready', pr: 5 }, { config });
  assert.match(failed, /Image not ready/);
  assert.match(failed, /comment `\/preview`/);
  assert.match(comment({ action: 'down', reason: 'idle', pr: 5 }, { config }), /Comment `\/preview` to \(re\)start/);
});

test('comments are built from enums and numbers only, never PR-authored text', () => {
  const body = comment({ action: 'up', reason: 'new', pr: 5, title: '$(pwned)', branch: '`id`', image: { tag: 'abcdef0' } }, { config });
  assert.ok(!body.includes('pwned') && !body.includes('`id`'));
  const hostile = comment({ action: 'up', reason: 'new', pr: 5, image: { tag: '[x](http://evil)' } }, { config });
  assert.ok(!hostile.includes('evil'), 'an image outside the tag grammar is omitted, not echoed');
});
