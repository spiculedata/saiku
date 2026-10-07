#!/usr/bin/env node
// Preview lifecycle driver: turns the pure decisions in preview-lifecycle.mjs
// into actions on a Host (preview-host.mjs) and PR comments, under the host lock.
// Ported from spiculedata/saiku-cloud .github/scripts/preview-ctl.mjs
// (spiculedata/saiku-cloud#1379); see docs/decisions/ci-preview-environments.md.
//
//   preview-ctl.mjs event --github-event payload.json --host ssh|fake [...]
//                        [--ensure-build github]  (start the docker build if the PR has no image)
//   preview-ctl.mjs reap  --open-prs open.json         --host ssh|fake [...]
//                        [--activity github]   (collaborator reviews extend idle)
//   preview-ctl.mjs touch --pr N [--at ISO]            --host ssh|fake [...]
//   preview-ctl.mjs status                             --host ssh|fake
//   preview-ctl.mjs creds --pr N (--out FILE | --github-output) --host ssh|fake
//
// `--host fake [--state file.json]` runs the whole lifecycle against an
// in-memory box; `--dry-run` with `--host ssh` reads the real box but only
// logs what it would change. There is no default host: the workflow must say.

import { spawnSync } from 'node:child_process';
import { appendFileSync, readFileSync, realpathSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

import { GhActivity } from './preview-activity.mjs';
import { deliverCredentials, maskCommands, parseCredentials } from './preview-creds.mjs';
import { SELFCHECK_TIMEOUT_SECONDS, assertPrNumber, assertSha, parseProject, projectForPr } from './preview-guard.mjs';
import { buildsImage } from './preview-images.mjs';
import { FakeHost, SshHost } from './preview-host.mjs';
import {
  COMMENT_MARKER,
  DEFAULTS,
  announcement,
  apply,
  comment,
  diskVerdict,
  eventFromGithub,
  gateOnDisk,
  imageTagFor,
  normaliseRegistry,
  pickPromotions,
  plan,
  reap,
  touch,
} from './preview-lifecycle.mjs';

/**
 * Trusted inputs copied from the BASE-branch checkout (never the PR head): the
 * compose file, the env renderer and the self-check script, and nothing else.
 */
export const SYNC_FILES = [
  'infra/preview/docker-compose.preview.yml',
  'infra/preview/render-env.sh',
  'infra/preview/selfcheck.sh',
];

/* ---------------------------------------------------------------- helpers */

const silent = () => {};

/** Destroy every host project belonging to one PR, then sweep its leftovers. */
export function destroyPr(host, pr, log = silent) {
  assertPrNumber(pr);
  const targets = new Set([projectForPr(pr)]);
  for (const p of host.listProjects().projects) if (p.pr === pr) targets.add(p.project);
  for (const project of [...targets].sort()) {
    log(`destroying ${project}`);
    host.down(project);
  }
  for (const v of host.listVolumes()) {
    if (parseProject(v.project)?.pr === pr) host.removeVolume(v.name, v.project);
  }
}

/** Volumes of preview projects no registry entry claims (left by dead stacks). */
export function sweepOrphanVolumes(host, registry, log = silent) {
  const removed = [];
  for (const v of host.listVolumes()) {
    const owner = parseProject(v.project)?.pr;
    if (!registry.envs[String(owner)]) {
      log(`removing orphan volume ${v.name}`);
      host.removeVolume(v.name, v.project);
      removed.push(v.name);
    }
  }
  return removed;
}

/** Prune until the disk is healthy or there is nothing left to prune. */
export function ensureDisk(host, config, log = silent) {
  let disk = host.diskFree();
  let verdict = diskVerdict(disk, config);
  if (verdict === 'ok') return { verdict, disk, pruned: false };
  log(`disk ${(100 * disk.freeBytes) / disk.totalBytes}% free: pruning images`);
  host.prune({ aggressive: false });
  disk = host.diskFree();
  verdict = diskVerdict(disk, config);
  if (verdict === 'blocked') {
    log('still low: aggressive prune of all unused saiku images');
    host.prune({ aggressive: true });
    disk = host.diskFree();
    verdict = diskVerdict(disk, config);
  }
  return { verdict: verdict === 'prune' ? 'ok' : verdict, disk, pruned: true };
}

/* ----------------------------------------------------------------- images */

/** The PR's image is not in the registry yet; its docker build may still be running. */
export class ImageNotReadyError extends Error {
  constructor(tag, waitedMinutes) {
    super(
      `the image ghcr.io/spiculedata/saiku:${tag} was not published within ${waitedMinutes} minutes ` +
        '(image not ready, comment /preview after the docker build for this commit has finished)',
    );
    this.name = 'ImageNotReadyError';
    this.tag = tag;
  }
}

/** The PR changes nothing docker.yml builds, so no image will ever exist for it. */
export class NoImageBuildError extends Error {
  constructor() {
    super('this PR changes nothing the docker workflow builds, so no image exists for it');
    this.name = 'NoImageBuildError';
  }
}

/** No provider wired: refuse to guess which paths a PR changed. */
const noChangedFiles = () => {
  throw new Error('no changed-files provider configured');
};

const positive = (value, fallback) => (Number.isFinite(Number(value)) && Number(value) > 0 ? Number(value) : fallback);

/**
 * Wait (bounded) for the PR's per-SHA image, on the host side where the GHCR
 * login is. There is no fallback image: a preview of anything but this commit
 * would validate the wrong code. `changedFiles()` is only called when the image is
 * missing: a PR that changes nothing docker.yml builds will never get one, and waiting
 * for it would hold the host lock for the whole window.
 *
 * `ensureBuild()` (optional) makes sure a docker build for this commit is running or
 * started: a PR last pushed before per-PR images existed, or whose build was cancelled,
 * would otherwise just wait out the window. A failure to start one is logged, never
 * fatal: the wait still runs, because a build may be on its way for another reason.
 * `onWait(info)` is called once, before the first poll, so the PR can say what is going on.
 */
export function waitForImage({ host, sha, changedFiles = noChangedFiles, ensureBuild, onWait, config = DEFAULTS, log = silent }) {
  const tag = imageTagFor(sha);
  if (host.manifestExists(tag)) return { tag };
  if (!buildsImage(changedFiles())) throw new NoImageBuildError();

  let build = { started: false, reason: 'waiting-for-build' };
  if (ensureBuild) {
    try {
      build = ensureBuild();
      log(`image ${tag} missing: ${build.reason}`);
    } catch (err) {
      log(`could not start an image build (still waiting for one): ${String(err.message).split('\n')[0]}`);
    }
  }
  if (onWait) onWait({ tag, build });

  const waitMinutes = positive(config.imageWaitMinutes, DEFAULTS.imageWaitMinutes);
  const pollSeconds = positive(config.imagePollSeconds, DEFAULTS.imagePollSeconds);
  const attempts = Math.ceil((waitMinutes * 60) / pollSeconds);
  for (let i = 0; i < attempts; i += 1) {
    log(`waiting for the PR image ${tag} (poll ${i + 1}/${attempts})`);
    host.sleep(pollSeconds * 1000);
    if (host.manifestExists(tag)) return { tag };
  }
  throw new ImageNotReadyError(tag, waitMinutes);
}

const firstLine = (err) => String(err.message).split('\n')[0];

const failureFor = (pr, stage, err) => {
  if (err instanceof NoImageBuildError) {
    return { reason: 'no-image-build', benign: true, message: `PR #${pr}: ${err.message}.` };
  }
  if (err instanceof ImageNotReadyError) {
    return { reason: 'image-not-ready', message: `PR #${pr}: ${err.message}.` };
  }
  if (stage === 'selfcheck') {
    return {
      reason: err.timedOut ? 'selfcheck-timeout' : 'selfcheck-failed',
      message:
        `PR #${pr}: the post-start self-check ` +
        `${err.timedOut ? `exceeded its ${SELFCHECK_TIMEOUT_SECONDS}s time budget` : `failed: ${firstLine(err)}`}. The stack was removed.`,
    };
  }
  if (stage === 'resolve') {
    return {
      reason: 'image-resolution-failed',
      message: `PR #${pr}: could not check the image in the registry: ${firstLine(err)}`,
    };
  }
  return { reason: 'compose-up-failed', message: `PR #${pr}: the stack did not come up: ${firstLine(err)}` };
};

function post(commenter, pr, body, log, failures) {
  try {
    commenter.upsert(pr, body);
  } catch (err) {
    failures.push(pr);
    log(`could not update the PR comment for #${pr}: ${err.message}`);
  }
}

/**
 * The login for a running preview, or undefined (posting disabled, or the host would not say).
 * The password is masked in the workflow log BEFORE anything else touches it. A failure here only
 * costs the login line; the comment falls back to "fetch it from the host".
 */
function loginFor(ctx, pr) {
  if (ctx.config.postCredentials === false) return undefined;
  try {
    const found = parseCredentials(ctx.host.readCredentials({ pr }));
    for (const line of maskCommands(found)) ctx.log(line);
    return { user: found.PREVIEW_ADMIN_USER, password: found.SAIKU_ADMIN_PASSWORD };
  } catch (err) {
    ctx.log(`could not read the login for #${pr}: ${firstLine(err)}`);
    return undefined;
  }
}

/** The sticky "UP" comment, with the login when it is enabled. */
function upComment(ctx, d, registry) {
  return comment({ ...d, credentials: loginFor(ctx, d.pr) }, { config: ctx.config, registry });
}

/** Ping the PR with a NEW comment when a preview comes up (a push refresh only edits the sticky one). */
function announceUp(ctx, d) {
  if (d.reason === 'refresh') return;
  try {
    ctx.commenter.notify?.(d.pr, announcement(d, { config: ctx.config }));
  } catch (err) {
    ctx.failures.push(d.pr);
    ctx.log(`could not announce the preview on #${d.pr}: ${err.message}`);
  }
}

/**
 * Bring PR `pr` up on the host. The registry claim is written FIRST so a crash
 * or cancellation leaves a tracked stack the reaper will remove, not an orphan.
 * On failure the half-built stack is destroyed and the claim dropped.
 */
function bringUp(ctx, registry, d) {
  const { host, now, log } = ctx;
  let next = apply(d, registry, { now });
  host.writeRegistry(next);
  let stage = 'sync';
  try {
    host.sync(SYNC_FILES, { cwd: ctx.syncRoot });
    stage = 'resolve';
    const image = waitForImage({
      host,
      sha: d.headSha,
      changedFiles: () => ctx.changedFiles(d.pr),
      ensureBuild: ctx.ensureBuild ? () => ctx.ensureBuild(d.pr, d.headSha) : undefined,
      onWait: ({ build }) =>
        post(
          ctx.commenter,
          d.pr,
          comment({ action: 'building', pr: d.pr, reason: build.reason }, { config: ctx.config, registry: next }),
          log,
          ctx.failures,
        ),
      config: ctx.config,
      log,
    });
    log(`#${d.pr}: image ${image.tag}`);
    next = apply({ ...d, image }, next, { now });
    host.writeRegistry(next);
    stage = 'up';
    host.up({ pr: d.pr, sha: d.headSha, tag: image.tag, baseDomain: ctx.config.baseDomain });
    // The stack seeds itself on first boot (admin password + sample data), so
    // there is no separate seed step: the self-check is the proof.
    stage = 'selfcheck';
    const report = host.selfcheck({ pr: d.pr });
    log(`#${d.pr}: ${report.summary}`);
    for (const row of report.inactive) log(`#${d.pr}: ${row}`);
    return { ok: true, registry: next, image };
  } catch (err) {
    log(`up failed for #${d.pr}: ${firstLine(err)}`);
    destroyPr(host, d.pr, log);
    next = apply({ action: 'down', pr: d.pr }, next, { now });
    host.writeRegistry(next);
    return { ok: false, registry: next, failure: failureFor(d.pr, stage, err) };
  }
}

/** Promote queued PRs into free slots (after a teardown or in the reaper). */
function promote(ctx, registry, open) {
  const { host, commenter, config, now, log, failures } = ctx;
  const maxEnvs = config.maxEnvs ?? DEFAULTS.maxEnvs;
  const activeCount = Object.values(registry.envs).filter((e) => e.state === 'active').length;
  const picks = pickPromotions(registry, { open, maxEnvs, activeCount });
  const promoted = [];
  const failed = [];
  const messages = [];
  let current = registry;
  if (!picks.length) return { registry, promoted, failed, messages };

  const { verdict } = ensureDisk(host, config, log);
  if (verdict === 'blocked') return { registry, promoted, failed, messages, diskBlocked: true };

  for (const pick of picks) {
    const d = {
      action: 'up',
      pr: pick.pr,
      headSha: pick.headSha,
      reason: 'promote-from-queue',
      imageTag: imageTagFor(pick.headSha),
    };
    const result = bringUp(ctx, current, d);
    current = result.registry;
    if (result.ok) {
      promoted.push(pick.pr);
      post(commenter, pick.pr, upComment(ctx, { ...d, image: result.image }, current), log, failures);
      announceUp(ctx, { ...d, image: result.image });
    } else if (result.failure.benign) {
      // Nothing to run for this PR (e.g. a docs-only change): say so, do not count a failure.
      post(commenter, pick.pr, comment({ action: 'unbuilt', pr: pick.pr, reason: result.failure.reason }, { config, registry: current }), log, failures);
    } else {
      failed.push(pick.pr);
      const { reason, message } = result.failure;
      if (message) messages.push(message);
      const f = { action: 'failed', pr: pick.pr, reason: reason === 'compose-up-failed' ? 'promotion-failed' : reason };
      post(commenter, pick.pr, comment(f, { config, registry: current }), log, failures);
    }
  }
  return { registry: current, promoted, failed, messages };
}

/* ------------------------------------------------------------------ event */

/**
 * Handle one pull_request event.
 * @returns {{decision: object, exitCode: number, ...}}
 */
export function handleEvent({ host, commenter, event, config = DEFAULTS, now, log = silent, syncRoot, changedFiles = noChangedFiles, ensureBuild }) {
  const failures = [];
  const ctx = { host, commenter, config, now, log, failures, syncRoot, changedFiles, ensureBuild };
  host.lock();
  try {
    let registry = normaliseRegistry(host.readRegistry());
    let d = plan({ event, registry, config, now });
    let exitCode = 0;
    let promoted = [];
    let diskVerdictFinal = null;
    const annotations = [];

    if (d.action === 'up') {
      const disk = ensureDisk(host, config, log);
      diskVerdictFinal = disk.verdict;
      d = gateOnDisk(d, disk.verdict, registry, now);
    }

    switch (d.action) {
      case 'skip':
        log(`#${d.pr}: ${d.action} (${d.reason})`);
        break;
      case 'keep':
        log(`#${d.pr}: ${d.action} (${d.reason})`);
        if (d.requestedBy) {
          // A collaborator asked for it: extend the idle window, no rebuild.
          registry = apply(d, registry, { now });
          host.writeRegistry(registry);
          const refreshed = {
            ...d,
            action: 'up',
            reason: 'refreshed by /preview',
            image: registry.envs[String(d.pr)]?.image,
          };
          post(commenter, d.pr, upComment(ctx, refreshed, registry), log, failures);
          announceUp(ctx, refreshed);
        }
        break;
      case 'queued':
        registry = apply(d, registry, { now });
        host.writeRegistry(registry);
        post(commenter, d.pr, comment(d, { config, registry }), log, failures);
        break;
      case 'down': {
        registry = apply(d, registry, { now });
        host.writeRegistry(registry);
        destroyPr(host, d.pr, log);
        post(commenter, d.pr, comment(d, { config, registry }), log, failures);
        const open = new Set(Object.values(registry.envs).map((e) => e.pr));
        const p = promote(ctx, registry, open);
        registry = p.registry;
        promoted = p.promoted;
        if (p.failed.length) exitCode = 1;
        annotations.push(...p.messages);
        break;
      }
      case 'up': {
        const result = bringUp(ctx, registry, d);
        registry = result.registry;
        if (result.ok) {
          d = { ...d, image: result.image };
          post(commenter, d.pr, upComment(ctx, d, registry), log, failures);
          announceUp(ctx, d);
        } else if (result.failure.benign) {
          log(`#${d.pr}: ${result.failure.message}`);
          post(commenter, d.pr, comment({ action: 'unbuilt', pr: d.pr, reason: result.failure.reason }, { config, registry }), log, failures);
        } else {
          exitCode = 1;
          const { reason, message } = result.failure;
          if (message) annotations.push(message);
          const f = { action: 'failed', pr: d.pr, reason };
          post(commenter, d.pr, comment(f, { config, registry }), log, failures);
        }
        break;
      }
      default:
        throw new Error(`unhandled decision ${d.action}`);
    }
    return { decision: d, exitCode, promoted, diskVerdict: diskVerdictFinal, annotations, commentFailures: failures };
  } finally {
    host.unlock();
  }
}

/* ------------------------------------------------------------------ touch */

/**
 * Record activity for an EXISTING env: moves lastActivity forward and nothing
 * else. No env means no-op; it never creates, rebuilds or comments.
 */
export function touchPr({ host, pr, at, now = new Date().toISOString(), log = silent }) {
  assertPrNumber(pr);
  host.lock();
  try {
    const registry = normaliseRegistry(host.readRegistry());
    const next = touch(registry, pr, at ?? now, now);
    const key = String(pr);
    const touched = next.envs[key]?.lastActivity !== registry.envs[key]?.lastActivity;
    if (touched) host.writeRegistry(next);
    log(`#${pr}: touch ${touched ? 'refreshed' : 'no-op'}`);
    return { pr, touched, exitCode: 0 };
  } finally {
    host.unlock();
  }
}

/* ------------------------------------------------------------------- reap */

/** Fold collaborator activity (e.g. reviews) into lastActivity. Never fatal. */
function applyActivity(registry, activity, now, log) {
  if (!activity) return registry;
  const prs = Object.values(registry.envs).map((e) => e.pr).sort((a, b) => a - b);
  let found;
  try {
    found = activity(prs) ?? {};
  } catch (err) {
    log(`activity lookup failed (ignored): ${firstLine(err)}`);
    return registry;
  }
  return prs.reduce((r, pr) => (found[pr] ? touch(r, pr, found[pr], now) : r), registry);
}

/**
 * Hourly reconciliation: idle/closed teardowns, orphan stacks and volumes,
 * image pruning, queue promotion. Exits non-zero (loudly) when the disk is
 * still too full after pruning, so a stuck host shows up as a red run.
 */
export function runReap({ host, commenter, openPrs, config = DEFAULTS, now, log = silent, syncRoot, activity, changedFiles = noChangedFiles, ensureBuild }) {
  const failures = [];
  const ctx = { host, commenter, config, now, log, failures, syncRoot, changedFiles, ensureBuild };
  host.lock();
  try {
    let registry = normaliseRegistry(host.readRegistry());
    registry = applyActivity(registry, activity, now, log);
    const { projects, ignored } = host.listProjects();
    const disk = ensureDisk(host, config, log);
    const result = reap({
      registry,
      openPrs,
      projects,
      diskBlocked: disk.verdict === 'blocked',
      now,
      config,
    });

    // Registry first: a teardown interrupted below is then retried as an orphan.
    for (const t of result.teardowns) {
      registry = apply({ action: 'down', pr: t.pr }, registry, { now });
    }
    host.writeRegistry(registry);

    for (const pr of result.destroyPrs) destroyPr(host, pr, log);
    const orphanVolumes = sweepOrphanVolumes(host, registry, log);

    for (const t of result.teardowns) {
      const d = { action: 'down', pr: t.pr, reason: t.reason };
      post(commenter, t.pr, comment(d, { config, registry }), log, failures);
    }

    const open = new Set(openPrs);
    const p = promote(ctx, registry, open);
    registry = p.registry;

    const annotations = [];
    if (disk.verdict === 'blocked') {
      annotations.push(
        'Preview host disk is still too full after pruning; new environments are queued. ' +
          'Free space on the host (docker system df) or raise its disk.',
      );
    }
    if (p.failed.length) annotations.push(`Promotion failed for PR(s): ${p.failed.join(', ')}`);
    annotations.push(...p.messages);
    if (ignored.length) {
      log(`ignored ${ignored.length} non-OSS-preview project(s) on the host (never touched)`);
    }
    return {
      ...result,
      promoted: p.promoted,
      promotionFailed: p.failed,
      orphanVolumes,
      ignoredProjects: ignored,
      diskVerdict: disk.verdict,
      disk: disk.disk,
      annotations,
      commentFailures: failures,
      exitCode: annotations.length ? 1 : 0,
    };
  } finally {
    host.unlock();
  }
}

/* -------------------------------------------------------------- commenters */

const REPO_RE = /^[A-Za-z0-9_.-]{1,100}\/[A-Za-z0-9_.-]{1,100}$/;

/** Sticky comment via the gh CLI. Only ever edits a comment authored by a bot. */
export class GhCommenter {
  constructor({ repo, run = ghRun }) {
    if (!REPO_RE.test(repo ?? '')) throw new Error('GhCommenter: invalid repository');
    this.repo = repo;
    this.run = run;
  }

  upsert(pr, body) {
    assertPrNumber(pr);
    const listed = this.run([
      'gh',
      'api',
      '--paginate',
      `repos/${this.repo}/issues/${pr}/comments`,
      '--jq',
      `.[] | select(.user.type == "Bot") | select(.body | contains("${COMMENT_MARKER}")) | .id`,
    ]);
    if (listed.status !== 0) throw new Error(`listing comments failed: ${listed.stderr}`);
    const id = listed.stdout.split('\n').find((l) => /^\d+$/.test(l.trim()))?.trim();
    const payload = JSON.stringify({ body });
    const args = id
      ? ['gh', 'api', '-X', 'PATCH', `repos/${this.repo}/issues/comments/${id}`, '--input', '-']
      : ['gh', 'api', '-X', 'POST', `repos/${this.repo}/issues/${pr}/comments`, '--input', '-'];
    const res = this.run(args, { input: payload });
    if (res.status !== 0) throw new Error(`posting comment failed: ${res.stderr}`);
  }

  /** A NEW comment (so GitHub notifies subscribers), unlike the sticky one, which is edited in place. */
  notify(pr, body) {
    assertPrNumber(pr);
    const res = this.run(['gh', 'api', '-X', 'POST', `repos/${this.repo}/issues/${pr}/comments`, '--input', '-'], {
      input: JSON.stringify({ body }),
    });
    if (res.status !== 0) throw new Error(`posting comment failed: ${res.stderr}`);
  }
}

const FILE_LIST_CAP = 4000;

/** The PR's changed file names (current and, for renames, previous), via the gh CLI. */
export class GhChangedFiles {
  constructor({ repo, run = ghRun }) {
    if (!REPO_RE.test(repo ?? '')) throw new Error('GhChangedFiles: invalid repository');
    this.repo = repo;
    this.run = run;
  }

  list(pr) {
    assertPrNumber(pr);
    const res = this.run([
      'gh',
      'api',
      '--paginate',
      `repos/${this.repo}/pulls/${pr}/files?per_page=100`,
      '--jq',
      '.[] | .filename, (.previous_filename // empty)',
    ]);
    if (res.status !== 0) throw new Error(`listing the files changed by #${pr} failed: ${res.stderr}`);
    return res.stdout.split('\n').filter(Boolean).slice(0, FILE_LIST_CAP);
  }
}

/**
 * Make sure a docker build exists for a PR's head commit. A build is already there when a
 * `docker` run for that exact commit (a pull_request run) or a dispatch for that PR is queued
 * or running; otherwise dispatch `docker.yml` from the DEFAULT branch with `pr=<n>`. The
 * workflow re-validates the PR itself (open, same repository, targets development, not
 * Dependabot) and publishes only the per-PR tags, so this class holds no trust: it only
 * decides whether a second build would be redundant.
 */
export class GhImageBuilder {
  constructor({ repo, workflow = 'docker.yml', run = ghRun }) {
    if (!REPO_RE.test(repo ?? '')) throw new Error('GhImageBuilder: invalid repository');
    if (!/^[a-z0-9._-]+\.ya?ml$/i.test(workflow)) throw new Error('GhImageBuilder: invalid workflow file');
    this.repo = repo;
    this.workflow = workflow;
    this.run = run;
  }

  ensure(pr, sha) {
    assertPrNumber(pr);
    assertSha(sha);
    // pr is an integer and sha is 40 hex (asserted above), so both are safe inside the jq program.
    const active = this.run([
      'gh',
      'api',
      `repos/${this.repo}/actions/workflows/${this.workflow}/runs?per_page=50`,
      '--jq',
      `.workflow_runs[] | select(.status != "completed") | select(.head_sha == "${sha}" or (.event == "workflow_dispatch" and (.display_title | test("PR #${pr}( |$)")))) | .id`,
    ]);
    if (active.status !== 0) throw new Error(`listing ${this.workflow} runs failed: ${active.stderr}`);
    if (active.stdout.split('\n').some((l) => /^\d+$/.test(l.trim()))) {
      return { started: false, reason: 'build-in-progress' };
    }

    const branch = this.run(['gh', 'api', `repos/${this.repo}`, '--jq', '.default_branch']);
    const ref = branch.stdout.trim();
    if (branch.status !== 0 || !/^[A-Za-z0-9._/-]{1,100}$/.test(ref)) {
      throw new Error(`could not read the default branch: ${branch.stderr || ref}`);
    }
    const sent = this.run([
      'gh',
      'api',
      '-X',
      'POST',
      `repos/${this.repo}/actions/workflows/${this.workflow}/dispatches`,
      '-f',
      `ref=${ref}`,
      '-f',
      `inputs[pr]=${pr}`,
    ]);
    if (sent.status !== 0) throw new Error(`dispatching ${this.workflow} for #${pr} failed: ${sent.stderr}`);
    return { started: true, reason: 'build-started' };
  }
}

/** Fixed file list for the fake host (`--changed-files file.json`) and tests. */
export const staticChangedFiles = (files = []) => () => files;

export class RecordingCommenter {
  constructor(log = silent) {
    this.comments = [];
    this.log = log;
  }

  upsert(pr, body) {
    this.comments.push({ pr, body });
    this.log(`[comment #${pr}] ${body.split('\n').slice(3, 5).join(' ')}`);
  }

  notify(pr, body) {
    this.announcements ??= [];
    this.announcements.push({ pr, body });
    this.log(`[announce #${pr}] ${body.split('\n')[0]}`);
  }
}

function ghRun(argv, { input } = {}) {
  const r = spawnSync(argv[0], argv.slice(1), { input, encoding: 'utf8' });
  return { status: r.status ?? 255, stdout: r.stdout ?? '', stderr: r.stderr ?? '' };
}

/* -------------------------------------------------------------------- cli */

const readJson = (path) => JSON.parse(readFileSync(path, 'utf8'));

export function loadConfig(env, path) {
  const raw = path ? readJson(path) : {};
  const num = (v, f) => (v === undefined || v === '' ? f : Number(v));
  return {
    ...DEFAULTS,
    ...raw,
    maxEnvs: num(env.SAIKU_PREVIEW_MAX_ENVS, raw.maxEnvs ?? DEFAULTS.maxEnvs),
    idleHours: num(env.SAIKU_PREVIEW_IDLE_HOURS, raw.idleHours ?? DEFAULTS.idleHours),
    baseDomain: env.SAIKU_PREVIEW_BASE_DOMAIN || raw.baseDomain || DEFAULTS.baseDomain,
    imageWaitMinutes: num(env.SAIKU_PREVIEW_IMAGE_WAIT_MINUTES, raw.imageWaitMinutes ?? DEFAULTS.imageWaitMinutes),
    previewLabel: env.SAIKU_PREVIEW_LABEL || raw.previewLabel || DEFAULTS.previewLabel,
    // Only an explicit "false" turns the login off; unset or empty keeps the default (shown).
    postCredentials: String(env.SAIKU_PREVIEW_POST_CREDENTIALS ?? '').trim().toLowerCase() === 'false'
      ? false
      : (raw.postCredentials ?? DEFAULTS.postCredentials),
    authors: env.SAIKU_PREVIEW_AUTHORS
      ? env.SAIKU_PREVIEW_AUTHORS.split(',').map((a) => a.trim()).filter(Boolean)
      : (raw.authors ?? DEFAULTS.authors),
  };
}

export function parseArgs(argv) {
  const [command, ...rest] = argv;
  const flags = {};
  for (let i = 0; i < rest.length; i += 1) {
    const a = rest[i];
    if (!a.startsWith('--')) throw new Error(`unexpected argument ${a}`);
    const name = a.slice(2);
    if (name === 'dry-run' || name === 'github-output') flags[name] = true;
    else {
      flags[name] = rest[i + 1];
      i += 1;
    }
  }
  return { command, flags };
}

function buildHost(flags, env, log) {
  if (flags.host === 'fake') {
    return flags.state ? FakeHost.fromFile(flags.state, { log }) : new FakeHost({}, { log });
  }
  if (flags.host === 'ssh') {
    return new SshHost({
      target: env.PREVIEW_SSH_TARGET,
      identityFile: env.PREVIEW_SSH_KEY,
      knownHostsFile: env.PREVIEW_SSH_KNOWN_HOSTS,
      dryRun: flags['dry-run'] === true,
      log,
    });
  }
  throw new Error('--host must be "ssh" or "fake" (there is no default)');
}

function buildCommenter(flags, env, log) {
  if (flags['dry-run'] || flags.host === 'fake') return new RecordingCommenter(log);
  return new GhCommenter({ repo: env.GITHUB_REPOSITORY });
}

/** Fake host: a JSON array from --changed-files (default: a built path). Real host: the GitHub API. */
function buildChangedFiles(flags, env) {
  if (flags.host === 'fake') {
    return staticChangedFiles(flags['changed-files'] ? readJson(flags['changed-files']) : ['saiku-core/x.java']);
  }
  const lister = new GhChangedFiles({ repo: env.GITHUB_REPOSITORY });
  return (pr) => lister.list(pr);
}

/** `--ensure-build github` (the /preview command only): start a missing image build via the GitHub API. */
function buildEnsureBuild(flags, env) {
  if (flags['ensure-build'] !== 'github' || flags.host === 'fake') return undefined;
  const builder = new GhImageBuilder({ repo: env.GITHUB_REPOSITORY });
  return (pr, sha) => builder.ensure(pr, sha);
}

function summaryMarkdown(command, result) {
  const lines = [`### preview ${command}`, ''];
  if (command === 'touch') {
    lines.push(`PR #${result.pr}: ${result.touched ? 'idle window refreshed' : 'no environment, nothing to do'}`);
  } else if (command === 'event') {
    lines.push(`PR #${result.decision.pr}: **${result.decision.action}** (${result.decision.reason})`);
    if (result.decision.image?.tag) lines.push('', `Image: ${result.decision.image.tag}`);
  } else {
    lines.push(result.summary, '');
    lines.push(`disk: ${result.diskVerdict}`);
    if (result.annotations.length) lines.push('', ...result.annotations.map((a) => `- ${a}`));
  }
  return `${lines.join('\n')}\n`;
}

/**
 * `creds --pr N --host ssh|fake (--out FILE | --github-output)`: hand the PR's
 * credentials to a later step of the SAME job. Never prints them; see
 * preview-creds.mjs for the rules. No lock: read only.
 */
function credsCommand(flags, env, io) {
  const pr = assertPrNumber(Number(flags.pr));
  const host = buildHost(flags, env, (m) => io.out(`${m}\n`));
  const found = parseCredentials(host.readCredentials({ pr }));
  const githubOutputFile = flags['github-output'] ? env.GITHUB_OUTPUT : undefined;
  if (flags['github-output'] && !githubOutputFile) throw new Error('--github-output needs $GITHUB_OUTPUT');
  const { names } = deliverCredentials(found, { outFile: flags.out, githubOutputFile, io });
  io.out(`credentials for PR #${pr} written (${names.length} values; secrets masked, none printed)\n`);
  return 0;
}

export function main(argv, env = process.env, io = { out: (s) => process.stdout.write(s) }) {
  const { command, flags } = parseArgs(argv);
  const log = (m) => io.out(`${m}\n`);
  const config = loadConfig(env, flags.config);
  const now = flags.now ?? new Date().toISOString();
  if (!['event', 'reap', 'status', 'touch', 'creds'].includes(command)) {
    io.out('usage: preview-ctl.mjs <event|reap|status|touch|creds> --host ssh|fake [--dry-run] [...]\n');
    return 2;
  }
  if (command === 'creds') return credsCommand(flags, env, io);
  const host = buildHost(flags, env, log);
  const commenter = buildCommenter(flags, env, log);
  const syncRoot = flags['sync-root'] ?? process.cwd();
  const changedFiles = ['event', 'reap'].includes(command) ? buildChangedFiles(flags, env) : undefined;
  const ensureBuild = ['event', 'reap'].includes(command) ? buildEnsureBuild(flags, env) : undefined;

  if (io.installSignalHandlers) {
    // A cancelled run (concurrency, timeout) must not leave the host lock held.
    const onSignal = (code) => () => {
      host.unlock();
      process.exit(code);
    };
    process.once('SIGINT', onSignal(130));
    process.once('SIGTERM', onSignal(143));
  }

  let result;
  if (command === 'status') {
    result = {
      registry: normaliseRegistry(host.readRegistry()),
      disk: host.diskFree(),
      ...host.listProjects(),
      exitCode: 0,
    };
  } else if (command === 'touch') {
    result = touchPr({ host, pr: Number(flags.pr), at: flags.at, now, log });
  } else if (command === 'event') {
    const event = eventFromGithub(readJson(flags['github-event']));
    result = handleEvent({ host, commenter, event, config, now, log, syncRoot, changedFiles, ensureBuild });
  } else {
    const activity = flags.activity === 'github' ? new GhActivity({ repo: env.GITHUB_REPOSITORY }).lookup : undefined;
    result = runReap({ host, commenter, openPrs: readJson(flags['open-prs']), config, now, log, syncRoot, activity, changedFiles, ensureBuild });
  }

  if (flags.out) writeFileSync(flags.out, `${JSON.stringify(result, null, 2)}\n`);
  else if (command === 'status') io.out(`${JSON.stringify(result, null, 2)}\n`);
  if (flags.summary && command !== 'status') appendFileSync(flags.summary, summaryMarkdown(command, result));
  for (const a of result.annotations ?? []) io.out(`::error::${a}\n`);
  return result.exitCode;
}

const isEntrypoint = (() => {
  try {
    return import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href;
  } catch {
    return false;
  }
})();

if (isEntrypoint) {
  try {
    process.exit(
      main(process.argv.slice(2), process.env, {
        out: (s) => process.stdout.write(s),
        installSignalHandlers: true,
      }),
    );
  } catch (err) {
    process.stderr.write(`::error::${String(err.message).split('\n')[0]}\n`);
    process.exit(1);
  }
}
