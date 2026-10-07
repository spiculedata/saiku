/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */

/**
 * End-to-end lifecycle tests against the in-memory FakeHost, plus SshHost
 * behaviour against a fake ssh runner. Every scenario also asserts the safety
 * property: nothing but `saiku-oss-pr-<n>` is ever mutated, and a cloud
 * preview on the same box (`saiku-pr-<n>...`) survives untouched.
 *
 * Hermetic: temp dirs only, no network, no Docker, no calendar date hard-coded
 * (the injected clock is an offset from the current hour), and `main` gets an
 * explicit environment so CI variables cannot leak in.
 */

import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

import {
  GhChangedFiles,
  GhCommenter,
  ImageNotReadyError,
  NoImageBuildError,
  RecordingCommenter,
  SYNC_FILES,
  destroyPr,
  handleEvent,
  loadConfig,
  main,
  parseArgs,
  runReap,
  touchPr,
  waitForImage,
} from './preview-ctl.mjs';
import { FakeHost, HostStepError, SshHost, parseDf, parseProjectList, parseVolumeList } from './preview-host.mjs';
import { ENV_DIR, HOST_MARKER, LOCK_DIR, REGISTRY_PATH, SRC_DIR, STATE_DIR, parseProject } from './preview-guard.mjs';
import { DEFAULTS, normaliseRegistry } from './preview-lifecycle.mjs';

const GIB = 1024 ** 3;
const HOUR = 3600 * 1000;
const T0 = new Date(Math.floor(Date.now() / HOUR) * HOUR).toISOString();
const HOURS = (n) => new Date(Date.parse(T0) + n * HOUR).toISOString();
const sha = (c) => c.repeat(40);
const tag = (c) => c.repeat(7);
const REPO = 'spiculedata/saiku';
const config = { ...DEFAULTS, maxEnvs: 2 };
const BUILT = () => ['saiku-core/saiku-service/src/main/java/X.java', 'docs/x.md'];
const DOCS_ONLY = () => ['docs/x.md', 'README.md', '.github/workflows/ci.yml'];
const CLOUD = ['saiku-pr-5', 'saiku-pr-5-strict', 'saiku-pr-12', 'saiku-cloud'];

const ev = (over = {}) => ({
  number: 1,
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

function world(state = {}, changedFiles = BUILT) {
  const host = new FakeHost({ images: [tag('a'), tag('b'), tag('c'), tag('d')], foreign: [...CLOUD], ...state });
  const commenter = new RecordingCommenter();
  return {
    host,
    commenter,
    event: (e, now = T0, cfg = config) => handleEvent({ host, commenter, event: ev(e), config: cfg, now, changedFiles }),
    reap: (openPrs, now) => runReap({ host, commenter, openPrs, config, now, changedFiles }),
    registry: () => normaliseRegistry(host.readRegistry()),
    stacks: () => Object.keys(host.state.stacks).sort(),
    lastComment: (pr) => commenter.comments.filter((c) => c.pr === pr).at(-1)?.body ?? '',
  };
}

/** The safety property, checked on every command a scenario made the host run. */
function assertOnlyOssMutations(host) {
  for (const { argv, mutating } of host.state.ops) {
    if (!mutating) continue;
    const line = argv.join(' ');
    assert.doesNotMatch(line, /saiku-pr-\d|saiku-cloud|saiku-preview\/|\/var\/lib\/saiku-preview\b(?!-oss)/, line);
    if (argv[0] === 'timeout') {
      assert.equal(argv[1], '--kill-after=10', line);
      assert.match(argv[2], /^\d+$/, line);
      assert.ok(parseProject(argv[argv.indexOf('-p') + 1]), line);
    } else if (argv[0] === 'docker' && argv[1] === 'compose') {
      assert.ok(parseProject(argv[argv.indexOf('-p') + 1]), line);
    } else if (argv[0] === 'docker' && argv[1] === 'volume') {
      assert.match(argv[3], /^saiku-oss-pr-[1-9]\d*_/, line);
    } else if (argv[0] === 'docker' && argv[1] === 'image') {
      assert.ok(line.includes('label=org.opencontainers.image.source=https://github.com/spiculedata/saiku'), line);
    } else if (argv[0] === 'rm') {
      assert.match(argv[2], new RegExp(`^${ENV_DIR}/saiku-oss-pr-[1-9]\\d*\\.env$`), line);
    } else {
      assert.match(argv[0], new RegExp(`^${SRC_DIR}/infra/preview/render-env\\.sh$`), line);
    }
  }
  for (const project of CLOUD) assert.ok(host.state.foreign.includes(project), `${project} must survive`);
}

/* ------------------------------------------------------------- lifecycle */

test('open -> update -> label noise -> close, end to end', () => {
  const w = world();
  const up = w.event({});
  assert.equal(up.decision.action, 'up');
  assert.equal(up.exitCode, 0);
  assert.deepEqual(w.stacks(), ['saiku-oss-pr-1']);
  assert.equal(w.registry().envs['1'].state, 'active');
  assert.deepEqual(w.registry().envs['1'].image, { tag: tag('a') });
  assert.match(w.lastComment(1), /\*\*UP\*\*/);
  assert.match(w.lastComment(1), /https:\/\/oss-pr-1\.preview\.saiku\.bi/);

  const ops = w.host.state.ops.map((o) => o.argv.join(' '));
  const order = ['manifest inspect', 'render-env.sh', 'up -d --wait', 'preview-selfcheck'];
  let at = -1;
  for (const needle of order) {
    const i = ops.findIndex((o, idx) => idx > at && o.includes(needle));
    assert.ok(i > at, `${needle} runs after the previous step`);
    at = i;
  }

  const push = w.event({ action: 'synchronize', headSha: sha('b') }, HOURS(1));
  assert.equal(push.decision.reason, 'refresh');
  assert.equal(w.registry().envs['1'].headSha, sha('b'));
  assert.equal(w.registry().envs['1'].image.tag, tag('b'));

  const before = w.host.state.ops.length;
  const noise = w.event({ action: 'labeled', headSha: sha('b') }, HOURS(2));
  assert.equal(noise.decision.action, 'keep');
  assert.equal(w.host.state.ops.length, before, 'label noise runs no host command');
  assert.equal(w.registry().envs['1'].lastActivity, HOURS(1), 'and does not extend the idle window');

  const close = w.event({ action: 'closed' }, HOURS(3));
  assert.equal(close.decision.action, 'down');
  assert.deepEqual(w.stacks(), []);
  assert.deepEqual(w.host.state.volumes, []);
  assert.equal(w.host.state.lockOwner, null);
  assertOnlyOssMutations(w.host);
});

test('a full host queues, and closing a PR promotes the head of the queue', () => {
  const w = world();
  w.event({ number: 1 });
  w.event({ number: 2 }, HOURS(0.1));
  const queued = w.event({ number: 3, headSha: sha('c') }, HOURS(0.2));
  assert.equal(queued.decision.action, 'queued');
  assert.match(w.lastComment(3), /queued at position 1/);
  assert.deepEqual(w.stacks(), ['saiku-oss-pr-1', 'saiku-oss-pr-2']);

  const close = w.event({ number: 1, action: 'closed' }, HOURS(1));
  assert.deepEqual(close.promoted, [3]);
  assert.deepEqual(w.stacks(), ['saiku-oss-pr-2', 'saiku-oss-pr-3']);
  assert.match(w.lastComment(3), /\*\*UP\*\*/);
  assertOnlyOssMutations(w.host);
});

test('the hourly reaper promotes the queue when a slot frees without a close event', () => {
  const w = world();
  w.event({ number: 1 });
  w.event({ number: 2 }, HOURS(0.1));
  w.event({ number: 3, headSha: sha('c') }, HOURS(0.2));
  const r = w.reap([2, 3], HOURS(1));
  assert.deepEqual(r.promoted, [3]);
  assert.deepEqual(w.stacks(), ['saiku-oss-pr-2', 'saiku-oss-pr-3']);
  assert.equal(r.exitCode, 0);
  assertOnlyOssMutations(w.host);
});

test('the reaper tears down after 24h idle, then the PR is told how to get one back', () => {
  const w = world();
  w.event({});
  assert.deepEqual(w.reap([1], HOURS(23)).teardowns, []);
  const r = w.reap([1], HOURS(25));
  assert.deepEqual(r.teardowns.map((t) => t.reason), ['idle']);
  assert.deepEqual(w.stacks(), []);
  assert.match(w.lastComment(1), /TORN DOWN/);
  assert.match(w.lastComment(1), /\/preview/);
  assertOnlyOssMutations(w.host);
});

test('the reaper removes OSS orphan stacks and volumes but never a cloud project or its volumes', () => {
  const w = world({
    stacks: { 'saiku-oss-pr-9': { sha: sha('a') } },
    volumes: [
      { name: 'saiku-oss-pr-9_saiku-home', project: 'saiku-oss-pr-9' },
      { name: 'saiku-oss-pr-8_saiku-home', project: 'saiku-oss-pr-8' },
      { name: 'saiku-pr-5_control_plane_data', project: 'saiku-pr-5' },
      { name: 'saiku-pr-12_engine_data', project: 'saiku-pr-12' },
    ],
  });
  w.event({ number: 1 });
  const r = w.reap([1, 9], HOURS(0.5));
  assert.deepEqual(r.destroyPrs, [9]);
  assert.deepEqual(r.orphanVolumes, ['saiku-oss-pr-8_saiku-home']);
  assert.deepEqual(r.ignoredProjects.sort(), [...CLOUD].sort());
  assert.deepEqual(w.host.state.volumes.map((v) => v.name).sort(), [
    'saiku-oss-pr-1_saiku-home',
    'saiku-pr-12_engine_data',
    'saiku-pr-5_control_plane_data',
  ]);
  assertOnlyOssMutations(w.host);
});

test('closing PR 1 never touches PR 12 (prefix collision) or the cloud twin of PR 1', () => {
  const w = world({ foreign: [...CLOUD, 'saiku-pr-1', 'saiku-pr-1-strict'] });
  w.event({ number: 1 });
  w.event({ number: 12, headSha: sha('b') }, HOURS(0.1));
  w.event({ number: 1, action: 'closed' }, HOURS(1));
  assert.deepEqual(w.stacks(), ['saiku-oss-pr-12']);
  assert.ok(w.host.state.foreign.includes('saiku-pr-1'));
  assertOnlyOssMutations(w.host);
});

test('fork PRs get no environment, no comment and no host command', () => {
  const w = world();
  const r = w.event({ headRepo: 'attacker/saiku', labels: ['preview'] });
  assert.equal(r.decision.action, 'skip');
  assert.deepEqual(w.host.state.ops, []);
  assert.deepEqual(w.commenter.comments, []);
});

test('removing the preview label tears an environment down', () => {
  const w = world();
  w.event({ author: 'someone', labels: ['preview'] });
  assert.deepEqual(w.stacks(), ['saiku-oss-pr-1']);
  const r = w.event({ author: 'someone', labels: [], action: 'unlabeled' }, HOURS(1));
  assert.equal(r.decision.action, 'down');
  assert.deepEqual(w.stacks(), []);
});

/* ------------------------------------------------------------------ disk */

test('a low disk is pruned (OSS images only) before the environment is created', () => {
  const w = world({ disk: { totalBytes: 100 * GIB, freeBytes: 25 * GIB }, reclaimable: 40 * GIB });
  w.event({});
  const prune = w.host.state.ops.filter((o) => o.argv.includes('prune'));
  assert.equal(prune.length, 1);
  assert.ok(prune[0].argv.includes('until=168h'));
  assert.deepEqual(w.stacks(), ['saiku-oss-pr-1']);
  assertOnlyOssMutations(w.host);
});

test('a full disk queues the PR with a visible reason instead of building half a stack', () => {
  const w = world({ disk: { totalBytes: 100 * GIB, freeBytes: 5 * GIB }, reclaimable: 0 });
  const r = w.event({});
  assert.equal(r.decision.action, 'queued');
  assert.equal(r.decision.reason, 'disk-low');
  assert.deepEqual(w.stacks(), []);
  assert.match(w.lastComment(1), /low on disk space/);
});

test('an aggressive prune is used only when the gentle one was not enough', () => {
  const w = world({ disk: { totalBytes: 100 * GIB, freeBytes: 5 * GIB }, reclaimable: 12 * GIB });
  w.event({});
  const prunes = w.host.state.ops.filter((o) => o.argv.includes('prune')).map((o) => o.argv.includes('--filter') && o.argv.some((a) => a.startsWith('until=')));
  assert.deepEqual(prunes, [true, false]);
  assertOnlyOssMutations(w.host);
});

test('a refresh of a running environment is not demoted by a tight disk', () => {
  const w = world();
  w.event({});
  w.host.state.disk.freeBytes = 5 * GIB;
  const r = w.event({ action: 'synchronize', headSha: sha('b') }, HOURS(1));
  assert.equal(r.decision.action, 'up');
  assert.equal(w.registry().envs['1'].state, 'active');
});

test('a disk that stays full after pruning fails the reaper loudly and holds the queue', () => {
  const w = world();
  w.event({ number: 1 });
  w.event({ number: 2 }, HOURS(0.1));
  w.event({ number: 3, headSha: sha('c') }, HOURS(0.2));
  w.event({ number: 4, headSha: sha('d') }, HOURS(0.3));
  w.host.state.disk.freeBytes = 3 * GIB;
  const r = w.reap([2, 3, 4], HOURS(1));
  assert.equal(r.exitCode, 1);
  assert.match(r.annotations.join('\n'), /still too full/);
  assert.deepEqual(r.promoted, []);
  assert.deepEqual(w.stacks(), ['saiku-oss-pr-2']);
});

/* -------------------------------------------------------------- failures */

test('a failed compose up leaves nothing behind and says so on the PR', () => {
  const w = world({ failUp: [1] });
  const r = w.event({});
  assert.equal(r.exitCode, 1);
  assert.deepEqual(w.stacks(), []);
  assert.deepEqual(w.host.state.volumes, []);
  assert.deepEqual(w.registry().envs, {});
  assert.match(w.lastComment(1), /FAILED TO START/);
  assert.match(r.annotations.join('\n'), /did not come up/);
  assertOnlyOssMutations(w.host);
});

test('a failed self-check removes the stack and reports why, without secrets', () => {
  const w = world({ failSelfcheck: [1] });
  const r = w.event({});
  assert.equal(r.exitCode, 1);
  assert.deepEqual(w.stacks(), []);
  assert.match(r.annotations.join('\n'), /self-check failed: .*foodmart_cube inactive/);
  assert.doesNotMatch(JSON.stringify(w.commenter.comments), /prevpw_/);
});

test('a failed promotion is reported and does not wedge the queue', () => {
  const w = world({ failUp: [3] });
  w.event({ number: 1 });
  w.event({ number: 2 }, HOURS(0.1));
  w.event({ number: 3, headSha: sha('c') }, HOURS(0.2));
  w.event({ number: 4, headSha: sha('d') }, HOURS(0.3));
  const r = w.reap([3, 4], HOURS(1));
  assert.deepEqual(r.promotionFailed, [3]);
  assert.deepEqual(r.promoted, [4]);
  assert.equal(r.exitCode, 1);
  assert.match(w.lastComment(3), /FAILED TO START/);
});

test('the host lock is released even when handling throws', () => {
  const w = world();
  w.host.readRegistry = () => {
    throw new Error('boom');
  };
  assert.throws(() => w.event({}), /boom/);
  assert.equal(w.host.state.lockOwner, null);
});

test('the registry claim is written before the stack is started (a crash leaves a tracked env)', () => {
  const w = world();
  const seen = [];
  const realUp = w.host.up.bind(w.host);
  w.host.up = (args) => {
    seen.push(normaliseRegistry(w.host.readRegistry()).envs['1']?.state);
    return realUp(args);
  };
  w.event({});
  assert.deepEqual(seen, ['active']);
});

test('destroyPr refuses an invalid PR number', () => {
  assert.throws(() => destroyPr(new FakeHost(), 0), /invalid PR number/);
  assert.throws(() => destroyPr(new FakeHost(), 'x'), /invalid PR number/);
});

test('FakeHost refuses destructive operations on non-OSS projects exactly like the real host', () => {
  const host = new FakeHost({ foreign: [...CLOUD] });
  for (const name of CLOUD) {
    assert.throws(() => host.down(name), /refusing/);
    assert.throws(() => host.removeVolume(`${name}_x`, name), /refusing/);
  }
  assert.deepEqual(host.state.ops, []);
});

/* ----------------------------------------------------------------- images */

test('an image that is still being built is waited for (20 minutes by default), then used', () => {
  const w = world({ images: [], pendingImages: { [tag('a')]: 3 } });
  const r = w.event({});
  assert.equal(r.decision.action, 'up');
  assert.deepEqual(w.host.state.sleeps, [20000, 20000, 20000]);
  assert.equal(w.registry().envs['1'].image.tag, tag('a'));
});

test('an image that never appears fails clearly, leaves nothing running and tells the PR what to do', () => {
  const w = world({ images: [] });
  const r = w.event({});
  assert.equal(r.exitCode, 1);
  assert.equal(w.host.state.sleeps.length, 60, '20 minutes at 20s polls');
  assert.equal(w.host.state.sleeps.reduce((a, b) => a + b, 0), 20 * 60 * 1000);
  assert.deepEqual(w.stacks(), []);
  assert.match(w.lastComment(1), /Image not ready/);
  assert.match(w.lastComment(1), /comment `\/preview`/);
  assert.match(r.annotations.join('\n'), /image not ready, comment \/preview after the docker build/);
  assert.equal(w.host.state.ops.some((o) => o.argv.includes('up')), false, 'never composes up without the image');
});

test('waitForImage never substitutes another tag and honours a custom wait', () => {
  const host = new FakeHost({ images: ['develop', 'latest'] });
  assert.throws(
    () => waitForImage({ host, sha: sha('e'), changedFiles: BUILT, config: { ...DEFAULTS, imageWaitMinutes: 1, imagePollSeconds: 30 } }),
    (err) => err instanceof ImageNotReadyError && err.tag === tag('e'),
  );
  assert.equal(host.state.sleeps.length, 2);
  const asked = host.state.ops.map((o) => o.argv.at(-1));
  assert.ok(asked.every((ref) => ref === `ghcr.io/spiculedata/saiku:${tag('e')}`));
});

test('a PR that changes nothing docker.yml builds is not previewed, and does not hold the lock waiting', () => {
  const w = world({ images: [] }, DOCS_ONLY);
  const r = w.event({});
  assert.equal(r.exitCode, 0, 'not a failure: there is simply nothing to run');
  assert.deepEqual(w.host.state.sleeps, [], 'no waiting');
  assert.deepEqual(w.stacks(), []);
  assert.deepEqual(w.registry().envs, {});
  assert.match(w.lastComment(1), /NO PREVIEW/);
  assert.match(w.lastComment(1), /changes nothing the `docker` workflow builds/);
  assert.equal(w.host.state.lockOwner, null);
  assert.equal(w.host.state.ops.some((o) => o.argv.includes('up')), false);
});

test('an existing image wins: the changed files are not even asked for', () => {
  const w = world({}, () => {
    throw new Error('should not be called');
  });
  assert.equal(w.event({}).exitCode, 0);
  assert.deepEqual(w.stacks(), ['saiku-oss-pr-1']);
});

test('a changed-files lookup failure fails the bring-up instead of guessing', () => {
  const w = world({ images: [] }, () => {
    throw new Error('gh: 502');
  });
  const r = w.event({});
  assert.equal(r.exitCode, 1);
  assert.deepEqual(w.stacks(), []);
  assert.match(r.annotations.join('\n'), /could not check the image|gh: 502|did not come up/);
});

test('a missing changed-files provider is an error, not an empty list', () => {
  const host = new FakeHost({ images: [] });
  assert.throws(() => waitForImage({ host, sha: sha('a') }), /no changed-files provider configured/);
  assert.throws(() => waitForImage({ host, sha: sha('a'), changedFiles: DOCS_ONLY }), (e) => e instanceof NoImageBuildError);
});

test('GhChangedFiles asks for current and previous names, and fails closed', () => {
  const calls = [];
  const lister = new GhChangedFiles({ repo: REPO, run: (argv) => { calls.push(argv); return { status: 0, stdout: 'a\nb\n', stderr: '' }; } });
  assert.deepEqual(lister.list(7), ['a', 'b']);
  assert.ok(calls[0].includes(`repos/${REPO}/pulls/7/files?per_page=100`));
  assert.match(calls[0].at(-1), /previous_filename/);
  assert.throws(() => new GhChangedFiles({ repo: REPO, run: () => ({ status: 1, stdout: '', stderr: 'x' }) }).list(7), /listing the files changed by #7 failed/);
  assert.throws(() => lister.list(0), /invalid PR number/);
  assert.throws(() => new GhChangedFiles({ repo: 'bad; repo' }), /invalid repository/);
});

test('a queued docs-only PR is dropped from promotion quietly, and the next one is promoted', () => {
  const w = world({ images: [tag('a'), tag('c'), tag('d')] });
  w.event({ number: 1 });
  w.event({ number: 2 }, HOURS(0.1));
  w.event({ number: 3, headSha: sha('b') }, HOURS(0.2));
  w.event({ number: 4, headSha: sha('c') }, HOURS(0.3));
  // PR 3 has no image and changes only docs; PR 4's image exists.
  const files = { 3: DOCS_ONLY, 4: BUILT };
  const r = runReap({ host: w.host, commenter: w.commenter, openPrs: [3, 4], config: { ...config, maxEnvs: 2 }, now: HOURS(1), changedFiles: (pr) => files[pr]() });
  assert.deepEqual(r.promotionFailed, []);
  assert.equal(r.exitCode, 0);
  assert.deepEqual(r.promoted, [4]);
  assert.match(w.lastComment(3), /NO PREVIEW/);
});

test('a registry outage while checking the image is an error, not "not published yet"', () => {
  const w = world();
  w.host.manifestExists = () => {
    throw new Error('cannot check image: unauthorized');
  };
  const r = w.event({});
  assert.equal(r.exitCode, 1);
  assert.match(r.annotations.join('\n'), /could not check the image in the registry/);
  assert.deepEqual(w.stacks(), []);
});

test('SYNC_FILES is exactly the trusted compose file, renderer and self-check, and the renderer is executable', () => {
  assert.deepEqual(SYNC_FILES, [
    'infra/preview/docker-compose.preview.yml',
    'infra/preview/render-env.sh',
    'infra/preview/selfcheck.sh',
  ]);
  const root = join(import.meta.dirname, '..', '..');
  for (const f of SYNC_FILES) assert.ok(existsSync(join(root, f)), f);
  assert.ok(statSync(join(root, 'infra/preview/render-env.sh')).mode & 0o100, 'render-env.sh must be executable');
});

/* ------------------------------------------------------- /preview command */

test('/preview creates an env for an unlabelled non-Hive PR and the next reaper run keeps it', () => {
  const w = world();
  const r = w.event({ author: 'someone', action: 'requested', requestedBy: 'tom' });
  assert.equal(r.decision.action, 'up');
  assert.deepEqual(w.reap([1], HOURS(1)).teardowns, []);
});

test('/preview on a fork PR does nothing on the host', () => {
  const w = world();
  w.event({ author: 'someone', action: 'requested', requestedBy: 'tom', headRepo: 'attacker/saiku' });
  assert.deepEqual(w.host.state.ops, []);
});

test('/preview after an idle teardown brings the env back', () => {
  const w = world();
  w.event({ author: 'someone', action: 'requested', requestedBy: 'tom' });
  w.reap([1], HOURS(30));
  assert.deepEqual(w.stacks(), []);
  w.event({ author: 'someone', action: 'requested', requestedBy: 'tom' }, HOURS(31));
  assert.deepEqual(w.stacks(), ['saiku-oss-pr-1']);
  assert.match(w.lastComment(1), /\*\*UP\*\*/);
});

test('/preview on a running env extends the idle window without rebuilding; restart rebuilds', () => {
  const w = world();
  w.event({});
  const before = w.host.state.ops.length;
  const r = w.event({ action: 'requested', requestedBy: 'tom' }, HOURS(10));
  assert.equal(r.decision.action, 'keep');
  assert.equal(w.host.state.ops.length, before);
  assert.equal(w.registry().envs['1'].lastActivity, HOURS(10));
  assert.match(w.lastComment(1), /refreshed by \/preview/);
  const restart = w.event({ action: 'requested', requestedBy: 'tom', restart: true }, HOURS(11));
  assert.equal(restart.decision.reason, 'restart');
  assert.ok(w.host.state.ops.length > before);
});

test('touchPr refreshes lastActivity only when an env exists, and releases the lock', () => {
  const w = world();
  w.event({});
  assert.equal(touchPr({ host: w.host, pr: 1, at: HOURS(5), now: HOURS(6) }).touched, true);
  assert.equal(w.registry().envs['1'].lastActivity, HOURS(5));
  assert.equal(touchPr({ host: w.host, pr: 2, at: HOURS(5), now: HOURS(6) }).touched, false);
  assert.equal(w.host.state.lockOwner, null);
});

test('collaborator review activity found by the reaper keeps an env that would otherwise be idle', () => {
  const w = world();
  w.event({});
  const r = runReap({ host: w.host, commenter: w.commenter, openPrs: [1], config, now: HOURS(30), changedFiles: BUILT, activity: () => ({ 1: HOURS(29) }) });
  assert.deepEqual(r.teardowns, []);
  assert.deepEqual(w.stacks(), ['saiku-oss-pr-1']);
});

test('a failing activity lookup never blocks or breaks the reaper', () => {
  const w = world();
  w.event({});
  const r = runReap({ host: w.host, commenter: w.commenter, openPrs: [1], config, now: HOURS(30), changedFiles: BUILT, activity: () => { throw new Error('rate limited'); } });
  assert.deepEqual(r.teardowns.map((t) => t.reason), ['idle']);
});

test('a comment failure is recorded but does not undo the lifecycle', () => {
  const w = world();
  w.commenter.upsert = () => {
    throw new Error('403');
  };
  const r = w.event({});
  assert.deepEqual(r.commentFailures, [1]);
  assert.deepEqual(w.stacks(), ['saiku-oss-pr-1']);
});

/* ------------------------------------------------------------------ creds */

function credsRun(extra = [], env = {}, state = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'preview-creds-'));
  const out = join(dir, 'creds.env');
  const gho = join(dir, 'output');
  writeFileSync(gho, '');
  const stateFile = join(dir, 'state.json');
  writeFileSync(stateFile, JSON.stringify({ stacks: { 'saiku-oss-pr-4': { sha: sha('a'), baseDomain: 'preview.saiku.bi' } }, ...state }));
  const printed = [];
  const code = main(['creds', '--pr', '4', '--host', 'fake', '--state', stateFile, ...extra.map((e) => (e === '$OUT' ? out : e))], { GITHUB_OUTPUT: gho, ...env }, { out: (s) => printed.push(s) });
  return { dir, out, gho, printed: printed.join(''), code };
}

test('creds writes a 0600 file after masking the secret, and prints no secret outside add-mask', () => {
  const r = credsRun(['--out', '$OUT']);
  try {
    assert.equal(r.code, 0);
    assert.equal(statSync(r.out).mode & 0o777, 0o600);
    const body = readFileSync(r.out, 'utf8');
    assert.match(body, /^PREVIEW_BASE_URL=https:\/\/oss-pr-4\.preview\.saiku\.bi$/m);
    assert.match(body, /^PREVIEW_ADMIN_USER=admin$/m);
    assert.match(body, /^PREVIEW_ADMIN_PASSWORD=prevpw_[0-9a-f]+$/m);
    const password = body.match(/^PREVIEW_ADMIN_PASSWORD=(.+)$/m)[1];
    const lines = r.printed.split('\n').filter((l) => l.includes(password));
    assert.deepEqual(lines, [`::add-mask::${password}`]);
    assert.ok(r.printed.indexOf('::add-mask::') < r.printed.indexOf('credentials for PR #4 written'));
  } finally {
    rmSync(r.dir, { recursive: true, force: true });
  }
});

test('creds can append step outputs to $GITHUB_OUTPUT; with neither sink or no stack it fails without printing', () => {
  const r = credsRun(['--github-output']);
  try {
    assert.match(readFileSync(r.gho, 'utf8'), /^PREVIEW_ADMIN_PASSWORD=prevpw_/m);
  } finally {
    rmSync(r.dir, { recursive: true, force: true });
  }
  assert.throws(() => credsRun([]), /never prints/);
  assert.throws(() => credsRun(['--out', '$OUT'], {}, { stacks: {} }), /no credentials for PR #4/);
  assert.throws(() => main(['creds', '--pr', '0', '--host', 'fake', '--out', '/dev/null'], {}, { out() {} }), /invalid PR number/);
  assert.throws(() => credsRun(['--out', '$OUT'], {}, { credentials: 'SAIKU_ADMIN_PASSWORD=bad value\nORIGIN=x\nPREVIEW_ADMIN_USER=a' }), /unexpected shape/);
});

/* ----------------------------------------------------------------- ssh */

function sshWorld(handler = () => ({ status: 0, stdout: '', stderr: '' })) {
  const calls = [];
  const host = new SshHost({
    target: 'saiku@100.78.167.101',
    identityFile: '/k/id',
    knownHostsFile: '/k/kh',
    run: (argv, opts) => {
      calls.push({ argv, input: opts?.input });
      return handler(argv, opts);
    },
    sleep: () => {},
  });
  return { host, calls };
}

test('SshHost: strict host-key checking, batch mode, the dedicated key, one quoted remote command per call', () => {
  const { host, calls } = sshWorld();
  host.down('saiku-oss-pr-3');
  assert.deepEqual(calls[0].argv.slice(0, 7), ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15', '-o', 'StrictHostKeyChecking=yes']);
  assert.ok(calls[0].argv.includes('UserKnownHostsFile=/k/kh'));
  assert.ok(calls[0].argv.includes('IdentitiesOnly=yes'));
  assert.equal(calls[0].argv.at(-2), 'saiku@100.78.167.101');
  assert.equal(calls[0].argv.at(-1), `test -f ${HOST_MARKER} && docker compose -p saiku-oss-pr-3 down -v --remove-orphans --timeout 30`);
  assert.equal(calls[1].argv.at(-1), `test -f ${HOST_MARKER} && rm -f ${ENV_DIR}/saiku-oss-pr-3.env`);
});

test('SshHost: a cloud or hostile project never reaches ssh', () => {
  const { host, calls } = sshWorld();
  for (const name of [...CLOUD, 'saiku-oss-pr-1; rm -rf /', '$(id)']) {
    assert.throws(() => host.down(name), /refusing/);
    assert.throws(() => host.removeVolume(`${name}_v`, name), /refusing/);
  }
  assert.throws(() => host.manifestExists('develop'), /invalid image tag/);
  assert.deepEqual(calls, []);
});

test('SshHost: the ssh target must be a plain user@host', () => {
  for (const bad of ['', 'host', '-oProxyCommand=x@y', 'a b@host', 'saiku@', undefined]) {
    assert.throws(() => new SshHost({ target: bad }), /user@host/);
  }
});

test('SshHost: state lives in the OSS directory and the lock is a mkdir inside it', () => {
  const { host, calls } = sshWorld();
  host.lock();
  const script = calls[0].argv.at(-1);
  assert.ok(script.startsWith(`if mkdir ${LOCK_DIR} `));
  assert.equal(LOCK_DIR, `${STATE_DIR}/lock`);
  assert.equal(STATE_DIR, '/var/lib/saiku-preview-oss');
  host.writeRegistry({ version: 1, envs: {} });
  assert.equal(calls[1].argv.at(-1), `cat > ${REGISTRY_PATH}.tmp && mv ${REGISTRY_PATH}.tmp ${REGISTRY_PATH}`);
  assert.equal(JSON.parse(calls[1].input).version, 1);
  host.sync([], { cwd: '/nonexistent' });
  assert.match(calls.at(-1).argv.at(-1), new RegExp(`^rm -rf ${SRC_DIR} && mkdir -p ${SRC_DIR} && tar -xz -C ${SRC_DIR}$`));
});

test('SshHost: lock retries while held, gives up cleanly, and unlock only removes our own lock', () => {
  let n = 0;
  const { host, calls } = sshWorld(() => ({ status: n++ < 2 ? 75 : 0, stdout: '', stderr: '' }));
  host.lock();
  assert.equal(calls.length, 3);
  const held = sshWorld(() => ({ status: 75, stdout: '', stderr: '' }));
  assert.throws(() => held.host.lock(), /timed out waiting for the preview host lock/);
  const broken = sshWorld(() => ({ status: 255, stdout: '', stderr: 'no route' }));
  assert.throws(() => broken.host.lock(), /cannot take host lock/);
  host.unlock();
  assert.match(calls.at(-1).argv.at(-1), /\[ "\$\(cat .*\/owner 2>\/dev\/null\)" = [0-9a-f]+ \] && rm -rf /);
});

test('SshHost: dry-run reads the host but runs nothing that mutates', () => {
  const calls = [];
  const logs = [];
  const host = new SshHost({ target: 'saiku@h.example', dryRun: true, run: (argv) => { calls.push(argv); return { status: 0, stdout: '', stderr: '' }; }, log: (m) => logs.push(m) });
  host.lock();
  host.unlock();
  host.down('saiku-oss-pr-3');
  host.writeRegistry({});
  host.prune();
  host.listProjects();
  assert.equal(calls.length, 1, 'only the read-only docker ps went out');
  assert.ok(logs.length >= 4 && logs.every((l) => l.startsWith('[dry-run]')));
});

test('SshHost.manifestExists: 0 is present, "no such manifest" is absent, any other failure is an error', () => {
  const answer = (status, stderr) => sshWorld(() => ({ status, stdout: '', stderr })).host;
  assert.equal(answer(0, '').manifestExists('abcdef0'), true);
  assert.equal(answer(1, 'no such manifest: ghcr.io/spiculedata/saiku:abcdef0').manifestExists('abcdef0'), false);
  assert.equal(answer(1, 'manifest unknown').manifestExists('abcdef0'), false);
  assert.throws(() => answer(1, 'unauthorized: authentication required').manifestExists('abcdef0'), /cannot check image/);
  assert.throws(() => answer(255, 'Connection timed out').manifestExists('abcdef0'), /cannot check image/);
});

test('SshHost.up renders with the resolved tag and the configured domain, then waits for compose up', () => {
  const { host, calls } = sshWorld();
  host.up({ pr: 4, sha: sha('a'), tag: tag('a'), baseDomain: 'preview.saiku.bi' });
  assert.match(calls[0].argv.at(-1), /render-env\.sh 4 --sha a{40} --image-tag a{7} --base-domain preview\.saiku\.bi --out .*saiku-oss-pr-4\.env$/);
  assert.match(calls[1].argv.at(-1), /timeout --kill-after=10 \d+ docker compose -p saiku-oss-pr-4 .* up -d --wait --wait-timeout 600 --remove-orphans$/);
  const failing = sshWorld((argv) => (argv.at(-1).includes(' up ') ? { status: 124, stdout: '', stderr: '' } : { status: 0, stdout: '', stderr: '' }));
  assert.throws(() => failing.host.up({ pr: 4, sha: sha('a'), tag: tag('a'), baseDomain: 'preview.saiku.bi' }), (e) => e instanceof HostStepError && e.timedOut);
});

test('SshHost.selfcheck: success returns the summary; failure carries the inactive rows and never a password', () => {
  const stdout = `foodmart_cube          inactive  no cube prevpw_${'0'.repeat(40)}\nPREVIEW_SELFCHECK stack=x active=5 inactive=1 skipped=0\n`;
  const ok = sshWorld(() => ({ status: 0, stdout: 'PREVIEW_SELFCHECK stack=x active=6 inactive=0 skipped=0\n', stderr: '' })).host;
  assert.match(ok.selfcheck({ pr: 4 }).summary, /active=6/);
  const bad = sshWorld(() => ({ status: 1, stdout, stderr: '' })).host;
  assert.throws(() => bad.selfcheck({ pr: 4 }), (e) => e instanceof HostStepError && /foodmart_cube\s+inactive/.test(e.message) && !e.message.includes('prevpw_0'));
});

test('SshHost.readCredentials fails without echoing the host output', () => {
  const host = sshWorld(() => ({ status: 2, stdout: 'SAIKU_ADMIN_PASSWORD=secret', stderr: 'grep: nope' })).host;
  assert.throws(() => host.readCredentials({ pr: 4 }), (e) => /no credentials for PR #4/.test(e.message) && !e.message.includes('secret'));
});

test('parsers: df, project list (cloud projects ignored) and volume list', () => {
  const df = parseDf('Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/sda1 1000 400 600 40% /var/lib/docker\n');
  assert.equal(df.totalBytes, 1000 * 1024);
  assert.equal(df.freePct, 0.6);
  assert.throws(() => parseDf('garbage'), /cannot parse df output/);
  const list = parseProjectList('saiku-oss-pr-3\nsaiku-pr-3\n\nsaiku-oss-pr-3\nsaiku-cloud\n');
  assert.deepEqual(list.projects.map((p) => p.project), ['saiku-oss-pr-3']);
  assert.deepEqual(list.ignored, ['saiku-pr-3', 'saiku-cloud']);
  assert.deepEqual(parseVolumeList('saiku-oss-pr-3_saiku-home\tsaiku-oss-pr-3\nsaiku-pr-3_x\tsaiku-pr-3\nloose\t\n'), [
    { name: 'saiku-oss-pr-3_saiku-home', project: 'saiku-oss-pr-3' },
  ]);
});

test('GhCommenter updates the existing bot comment in place, body on stdin, and validates its inputs', () => {
  const calls = [];
  const run = (argv, opts) => {
    calls.push({ argv, input: opts?.input });
    return { status: 0, stdout: argv.includes('--jq') ? '4242\n' : '', stderr: '' };
  };
  new GhCommenter({ repo: REPO, run }).upsert(7, 'body');
  assert.deepEqual(calls[1].argv.slice(0, 5), ['gh', 'api', '-X', 'PATCH', `repos/${REPO}/issues/comments/4242`]);
  assert.deepEqual(JSON.parse(calls[1].input), { body: 'body' });
  const fresh = [];
  new GhCommenter({ repo: REPO, run: (argv) => { fresh.push(argv); return { status: 0, stdout: '', stderr: '' }; } }).upsert(7, 'b');
  assert.deepEqual(fresh[1].slice(2, 5), ['-X', 'POST', `repos/${REPO}/issues/7/comments`]);
  assert.throws(() => new GhCommenter({ repo: 'bad repo; id' }), /invalid repository/);
  assert.throws(() => new GhCommenter({ repo: REPO, run }).upsert(0, 'x'), /invalid PR number/);
  assert.throws(() => new GhCommenter({ repo: REPO, run: () => ({ status: 1, stdout: '', stderr: 'nope' }) }).upsert(7, 'x'), /listing comments failed/);
});

/* -------------------------------------------------------------------- cli */

test('the CLI drives a persisted fake host through open, reap and close', () => {
  const dir = mkdtempSync(join(tmpdir(), 'preview-cli-'));
  try {
    const state = join(dir, 'state.json');
    const payload = join(dir, 'event.json');
    const open = join(dir, 'open.json');
    writeFileSync(state, JSON.stringify({ images: [tag('a')], foreign: ['saiku-pr-5'] }));
    const pr = (action) => ({
      action,
      pull_request: { number: 8, state: action === 'closed' ? 'closed' : 'open', user: { login: 'spicule-hive[bot]' }, labels: [], head: { sha: sha('a'), repo: { full_name: REPO } }, base: { repo: { full_name: REPO } } },
    });
    const out = [];
    const io = { out: (s) => out.push(s) };
    const base = ['--host', 'fake', '--state', state, '--now', T0];

    writeFileSync(payload, JSON.stringify(pr('opened')));
    assert.equal(main(['event', ...base, '--github-event', payload, '--out', join(dir, 'r.json'), '--summary', join(dir, 's.md')], {}, io), 0);
    assert.deepEqual(Object.keys(JSON.parse(readFileSync(state, 'utf8')).stacks), ['saiku-oss-pr-8']);
    assert.match(readFileSync(join(dir, 's.md'), 'utf8'), /PR #8: \*\*up\*\*/);

    writeFileSync(open, '[8]');
    assert.equal(main(['reap', ...base, '--open-prs', open], {}, io), 0);
    writeFileSync(open, '[]');
    main(['reap', ...base, '--open-prs', open], {}, io);
    assert.deepEqual(JSON.parse(readFileSync(state, 'utf8')).stacks, {});
    assert.deepEqual(JSON.parse(readFileSync(state, 'utf8')).foreign, ['saiku-pr-5']);

    assert.equal(main(['status', ...base], {}, io), 0);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('the CLI has no default host and rejects bad usage', () => {
  const io = { out() {} };
  assert.throws(() => main(['status'], {}, io), /--host must be "ssh" or "fake"/);
  assert.throws(() => main(['status', '--host', 'ssh'], {}, io), /user@host/);
  assert.equal(main(['frobnicate'], {}, io), 2);
  assert.throws(() => parseArgs(['event', 'stray']), /unexpected argument/);
});

test('CI environment variables cannot change behaviour (only explicit SAIKU_PREVIEW_* can)', () => {
  const hostile = { CI: 'true', GITHUB_ACTIONS: 'true', MAX_ENVS: '99', IDLE_HOURS: '1', PREVIEW_MAX_ENVS: '99', PREVIEW_AUTHORS: 'evil', PREVIEW_BASE_DOMAIN: 'evil.example.com' };
  const c = loadConfig(hostile);
  assert.equal(c.maxEnvs, DEFAULTS.maxEnvs);
  assert.equal(c.idleHours, DEFAULTS.idleHours);
  assert.deepEqual(c.authors, DEFAULTS.authors);
  assert.equal(c.baseDomain, DEFAULTS.baseDomain);
  const explicit = loadConfig({ SAIKU_PREVIEW_MAX_ENVS: '5', SAIKU_PREVIEW_IDLE_HOURS: '12', SAIKU_PREVIEW_AUTHORS: 'a[bot], b', SAIKU_PREVIEW_BASE_DOMAIN: 'p.example.com', SAIKU_PREVIEW_LABEL: 'pv' });
  assert.deepEqual([explicit.maxEnvs, explicit.idleHours, explicit.authors, explicit.baseDomain, explicit.previewLabel], [5, 12, ['a[bot]', 'b'], 'p.example.com', 'pv']);
  assert.equal(loadConfig({ SAIKU_PREVIEW_MAX_ENVS: '', SAIKU_PREVIEW_AUTHORS: '' }).maxEnvs, 3, 'an unset repository variable (empty string) keeps the default');
});

test('a base domain that is not a DNS name stops the bring-up before any host command mutates', () => {
  const w = world();
  assert.throws(() => w.event({}, T0, { ...config, baseDomain: 'x; rm -rf /' }), /invalid preview base domain/);
  assert.equal(w.host.state.ops.filter((o) => o.mutating).length, 0);
});
