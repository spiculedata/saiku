// Preview-host abstraction.
// Ported from spiculedata/saiku-cloud .github/scripts/preview-host.mjs
// (spiculedata/saiku-cloud#1379).
//
// Two implementations of one interface, so the whole lifecycle runs offline:
//
//   SshHost   the real preview box, reached over the tailnet with ssh. Every
//             remote command comes from preview-guard.mjs `cmds`.
//   FakeHost  an in-memory box (optionally persisted to a JSON file) that
//             models stacks, volumes, disk and the lock, and records every
//             argv it was asked to run. It builds each command through the
//             SAME `cmds` builders, so a name the guard would refuse on the
//             real host is refused here too.
//
// Interface: lock/unlock, readRegistry/writeRegistry, listProjects,
// listVolumes, sync, up, selfcheck, readCredentials, down, removeVolume,
// diskFree, prune, manifestExists, sleep.
//
// Differences from the cloud: one image instead of three, no database seed step
// (the stack seeds itself on first boot from the launcher), no stack variants.

import { spawnSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';

import { redactSecrets } from './preview-creds.mjs';
import {
  LOCK_DIR,
  REGISTRY_PATH,
  SELFCHECK_TIMEOUT_SECONDS,
  SRC_DIR,
  TIMEOUT_EXIT_STATUS,
  UP_WAIT_TIMEOUT_SECONDS,
  cmds,
  hostLabelForPr,
  parseProject,
  projectForPr,
  remoteLine,
} from './preview-guard.mjs';

const GIB = 1024 ** 3;
/** One OSS preview: ~1.5 GB memory limit, a few hundred MB of disk once the image is pulled. */
const FAKE_STACK_DISK_BYTES = 1 * GIB;
const LOCK_STALE_MINUTES = 45;
const LOCK_RETRY_MS = 5000;
const LOCK_ATTEMPTS = 360; // 30 minutes

/** What `docker manifest inspect` says when the tag is simply not there (vs an auth/network fault). */
const MANIFEST_MISSING_RE = /no such manifest|manifest unknown|name unknown|not found/i;

const defaultSleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);

/** A post-`up` step (the self-check) failed or ran out of time. Never carries secrets. */
export class HostStepError extends Error {
  constructor(step, { timedOut = false, limitSeconds = 0, detail = '' } = {}) {
    super(
      timedOut
        ? `${step} exceeded its ${limitSeconds}s time budget and was killed`
        : `${step} failed${detail ? `: ${detail}` : ''}`,
    );
    this.name = 'HostStepError';
    this.step = step;
    this.timedOut = timedOut;
  }
}

/** First non-empty line of a host failure, with password-shaped strings masked. */
const firstLine = (text) =>
  redactSecrets(String(text ?? '').split('\n').find((l) => l.trim()) ?? '').slice(0, 200);

/** The self-check's machine line plus its inactive rows (evidence has no secrets). */
export function summariseSelfcheck(stdout) {
  const lines = String(stdout).split('\n');
  const summary = lines.find((l) => l.startsWith('PREVIEW_SELFCHECK ')) ?? 'PREVIEW_SELFCHECK (no summary line)';
  const inactive = lines.filter((l) => /^\S+\s+inactive\s/.test(l));
  return { summary: redactSecrets(summary), inactive: inactive.map((l) => redactSecrets(l).slice(0, 300)) };
}

/** Parse `df -Pk <path>` output into byte counts. */
export function parseDf(text) {
  const rows = String(text).trim().split('\n');
  const cols = (rows[rows.length - 1] ?? '').trim().split(/\s+/);
  const totalKb = Number(cols[1]);
  const availKb = Number(cols[3]);
  if (rows.length < 2 || !Number.isFinite(totalKb) || !Number.isFinite(availKb) || totalKb <= 0) {
    throw new Error(`cannot parse df output: ${JSON.stringify(String(text).slice(0, 200))}`);
  }
  const totalBytes = totalKb * 1024;
  const freeBytes = availKb * 1024;
  return { totalBytes, freeBytes, freePct: freeBytes / totalBytes };
}

/** Distinct, guard-valid preview projects from raw `docker ps` output. */
export function parseProjectList(text) {
  const seen = new Set();
  const projects = [];
  const ignored = [];
  for (const raw of String(text).split('\n')) {
    const name = raw.trim();
    if (!name || seen.has(name)) continue;
    seen.add(name);
    const parsed = parseProject(name);
    if (parsed) projects.push(parsed);
    else ignored.push(name);
  }
  return { projects, ignored };
}

export function parseVolumeList(text) {
  const volumes = [];
  for (const raw of String(text).split('\n')) {
    const [name, project] = raw.split('\t');
    if (!name || !project) continue;
    if (parseProject(project)) volumes.push({ name, project });
  }
  return volumes;
}

/* ------------------------------------------------------------------- ssh */

const TARGET_RE = /^[a-z_][a-z0-9_-]{0,31}@[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/;

export class SshHost {
  /**
   * @param {{target: string, identityFile?: string, knownHostsFile?: string,
   *          dryRun?: boolean, run?: Function, sleep?: Function, log?: Function}} opts
   */
  constructor({ target, identityFile, knownHostsFile, dryRun = false, run, sleep, log } = {}) {
    if (!TARGET_RE.test(target ?? '')) {
      throw new Error('SshHost: target must look like user@host');
    }
    this.target = target;
    this.identityFile = identityFile;
    this.knownHostsFile = knownHostsFile;
    this.dryRun = dryRun;
    this.runner = run ?? defaultRunner;
    this.sleeper = sleep ?? defaultSleep;
    this.log = log ?? (() => {});
    this.token = randomBytes(8).toString('hex');
  }

  sshArgv(remote) {
    const argv = ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15'];
    argv.push('-o', 'StrictHostKeyChecking=yes');
    if (this.knownHostsFile) argv.push('-o', `UserKnownHostsFile=${this.knownHostsFile}`);
    if (this.identityFile) argv.push('-i', this.identityFile, '-o', 'IdentitiesOnly=yes');
    argv.push(this.target, remote);
    return argv;
  }

  /** Run one remote line. Dry-run skips anything that mutates. */
  raw(remote, { mutating = false, input, allowFail = false } = {}) {
    if (mutating && this.dryRun) {
      this.log(`[dry-run] ssh ${this.target} ${remote}`);
      return { status: 0, stdout: '', stderr: '' };
    }
    const result = this.runner(this.sshArgv(remote), { input });
    if (result.status !== 0 && !allowFail) {
      throw new Error(`remote command failed (${result.status}): ${remote}\n${result.stderr ?? ''}`);
    }
    return result;
  }

  exec(command, opts = {}) {
    return this.raw(remoteLine(command), { mutating: command.mutating, ...opts });
  }

  /**
   * Take the host lock with `mkdir` INSIDE the state directory. The state
   * directory itself must therefore be writable by the ssh user (not just its
   * src/ and env/ children): see the preview_host Ansible role.
   */
  lock() {
    if (this.dryRun) return;
    const dir = LOCK_DIR;
    const script =
      `if mkdir ${dir} 2>/dev/null; then echo ${this.token} > ${dir}/owner; ` +
      `elif [ -n "$(find ${dir} -maxdepth 0 -mmin +${LOCK_STALE_MINUTES} 2>/dev/null)" ]; ` +
      `then rm -rf ${dir} && mkdir ${dir} && echo ${this.token} > ${dir}/owner; ` +
      `else exit 75; fi`;
    for (let attempt = 0; attempt < LOCK_ATTEMPTS; attempt += 1) {
      const result = this.raw(script, { allowFail: true });
      if (result.status === 0) return;
      if (result.status !== 75) throw new Error(`cannot take host lock: ${result.stderr}`);
      this.sleeper(LOCK_RETRY_MS);
    }
    throw new Error('timed out waiting for the preview host lock');
  }

  unlock() {
    if (this.dryRun) return;
    this.raw(
      `[ "$(cat ${LOCK_DIR}/owner 2>/dev/null)" = ${this.token} ] && rm -rf ${LOCK_DIR}; true`,
      { allowFail: true },
    );
  }

  readRegistry() {
    const { stdout } = this.raw(`cat ${REGISTRY_PATH} 2>/dev/null; true`, {});
    if (!stdout.trim()) return null;
    return JSON.parse(stdout);
  }

  writeRegistry(registry) {
    const body = `${JSON.stringify(registry, null, 2)}\n`;
    this.raw(`cat > ${REGISTRY_PATH}.tmp && mv ${REGISTRY_PATH}.tmp ${REGISTRY_PATH}`, {
      mutating: true,
      input: body,
    });
  }

  listProjects() {
    return parseProjectList(this.exec(cmds.listProjects()).stdout);
  }

  listVolumes() {
    return parseVolumeList(this.exec(cmds.listVolumes()).stdout);
  }

  /** Copy the trusted compose files (from the base-branch checkout) to the box. */
  sync(files, { cwd = process.cwd() } = {}) {
    if (this.dryRun) return this.log(`[dry-run] sync ${files.join(' ')}`);
    const tar = this.runner(['tar', '-C', cwd, '-cz', ...files], { binary: true });
    if (tar.status !== 0) throw new Error(`tar failed: ${tar.stderr}`);
    this.raw(`rm -rf ${SRC_DIR} && mkdir -p ${SRC_DIR} && tar -xz -C ${SRC_DIR}`, {
      mutating: true,
      input: tar.stdout,
    });
  }

  /** Render the env file and bring the stack up (waits for the container to be healthy). */
  up({ pr, sha, tag, baseDomain }) {
    this.exec(cmds.render({ pr, sha, tag, baseDomain }));
    const result = this.exec(cmds.composeUp({ pr }), { allowFail: true });
    if (result.status === 0) return;
    throw new HostStepError('up', {
      timedOut: result.status === TIMEOUT_EXIT_STATUS,
      limitSeconds: UP_WAIT_TIMEOUT_SECONDS,
      detail: firstLine(result.stderr),
    });
  }

  /** Run the one-shot self-check after `up`. Non-zero (`--require-all`) is a failure. */
  selfcheck({ pr }) {
    const result = this.exec(cmds.selfcheck({ pr }), { allowFail: true });
    const report = summariseSelfcheck(result.stdout);
    if (result.status === 0) return report;
    throw new HostStepError('selfcheck', {
      timedOut: result.status === TIMEOUT_EXIT_STATUS,
      limitSeconds: SELFCHECK_TIMEOUT_SECONDS,
      detail: [report.summary, ...report.inactive].join(' | '),
    });
  }

  /** SECRET output: KEY=VALUE lines for CREDENTIAL_KEYS. Hand to preview-creds.mjs only. */
  readCredentials({ pr }) {
    const result = this.exec(cmds.readCreds({ pr }), { allowFail: true });
    if (result.status !== 0) {
      throw new Error(`no credentials for PR #${pr}: is its preview up? (exit ${result.status})`);
    }
    return result.stdout;
  }

  /**
   * Is the image in GHCR? Uses the host's own registry login. A definite "no
   * such manifest" is `false`; anything else (denied, network, daemon down) is
   * an error, so an outage can never read as "not published yet".
   */
  manifestExists(tag) {
    const result = this.exec(cmds.manifestInspect(tag), { allowFail: true });
    if (result.status === 0) return true;
    if (MANIFEST_MISSING_RE.test(`${result.stderr ?? ''}${result.stdout ?? ''}`)) return false;
    throw new Error(`cannot check image ${tag} (${result.status}): ${String(result.stderr ?? '').split('\n')[0]}`);
  }

  sleep(ms) {
    this.sleeper(ms);
  }

  down(project) {
    this.exec(cmds.composeDown(project));
    this.exec(cmds.rmEnvFile(project));
  }

  removeVolume(volume, project) {
    this.exec(cmds.volumeRm(volume, project));
  }

  diskFree() {
    return parseDf(this.exec(cmds.diskFree()).stdout);
  }

  prune({ aggressive = false, olderThanHours = 168 } = {}) {
    this.exec(cmds.imagePrune(aggressive ? {} : { olderThanHours }));
  }
}

function defaultRunner(argv, { input, binary = false } = {}) {
  const r = spawnSync(argv[0], argv.slice(1), {
    input,
    encoding: binary ? 'buffer' : 'utf8',
    maxBuffer: 64 * 1024 * 1024,
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  if (r.error) return { status: 255, stdout: '', stderr: String(r.error.message) };
  return {
    status: r.status ?? 255,
    stdout: r.stdout ?? '',
    stderr: r.stderr ? String(r.stderr) : '',
  };
}

/* ------------------------------------------------------------------ fake */

/**
 * In-memory preview box. `state` shape:
 *   { registry, stacks: {project: {sha, tag}}, volumes: [{name, project}],
 *     foreign: [names], disk: {totalBytes, freeBytes}, reclaimable: bytes,
 *     lockOwner, ops: [argv...], failUp: [pr...], failSelfcheck: [pr...],
 *     images: ['abcdef0', ...]   tags GHCR has,
 *     pendingImages: {'abcdef0': n}  appear after n existence checks,
 *     sleeps: [ms...] }
 * `foreign` are compose projects that are not OSS previews (e.g. the cloud's
 * `saiku-pr-12`, `saiku-cloud`); they are listed like everything else and must
 * survive any lifecycle run.
 */
export class FakeHost {
  constructor(state = {}, { file, log } = {}) {
    this.file = file;
    this.log = log ?? (() => {});
    this.state = {
      registry: null,
      stacks: {},
      volumes: [],
      foreign: [],
      disk: { totalBytes: 100 * GIB, freeBytes: 60 * GIB },
      reclaimable: 0,
      lockOwner: null,
      ops: [],
      failUp: [],
      failSelfcheck: [],
      images: [],
      pendingImages: {},
      sleeps: [],
      ...state,
    };
    this.token = randomBytes(4).toString('hex');
  }

  static fromFile(file, opts = {}) {
    const state = existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : {};
    return new FakeHost(state, { ...opts, file });
  }

  persist() {
    if (this.file) writeFileSync(this.file, `${JSON.stringify(this.state, null, 2)}\n`);
  }

  record(command) {
    this.state.ops.push({ argv: command.argv, mutating: command.mutating, marker: command.marker });
    this.persist();
  }

  lock() {
    if (this.state.lockOwner && this.state.lockOwner !== this.token) {
      throw new Error('fake host lock is held by another run');
    }
    this.state.lockOwner = this.token;
  }

  unlock() {
    if (this.state.lockOwner === this.token) this.state.lockOwner = null;
    this.persist();
  }

  readRegistry() {
    return this.state.registry ? JSON.parse(JSON.stringify(this.state.registry)) : null;
  }

  writeRegistry(registry) {
    this.state.registry = JSON.parse(JSON.stringify(registry));
    this.persist();
  }

  listProjects() {
    this.record(cmds.listProjects());
    const raw = [...Object.keys(this.state.stacks), ...this.state.foreign].join('\n');
    return parseProjectList(raw);
  }

  listVolumes() {
    this.record(cmds.listVolumes());
    // Same parser as the real host, so non-preview volumes are filtered identically.
    return parseVolumeList(this.state.volumes.map((v) => `${v.name}\t${v.project}`).join('\n'));
  }

  sync() {}

  /** Mirrors SshHost.manifestExists; a `pendingImages` entry models a build still running. */
  manifestExists(tag) {
    this.record(cmds.manifestInspect(tag));
    const waiting = this.state.pendingImages[tag];
    if (waiting !== undefined) {
      if (waiting > 0) {
        this.state.pendingImages = { ...this.state.pendingImages, [tag]: waiting - 1 };
        this.persist();
        return false;
      }
      return true;
    }
    return this.state.images.includes(tag);
  }

  sleep(ms) {
    this.state.sleeps.push(ms);
  }

  up({ pr, sha, tag, baseDomain }) {
    this.record(cmds.render({ pr, sha, tag, baseDomain }));
    this.record(cmds.composeUp({ pr }));
    const project = projectForPr(pr);
    if (this.state.failUp.includes(pr)) {
      // A real failed `up` leaves a half-started stack behind; model that so
      // the cleanup path is exercised.
      this.state.stacks[project] = { sha, tag, partial: true };
      this.persist();
      throw new HostStepError('up', { detail: `compose up failed for ${project}` });
    }
    this.state.stacks[project] = { sha, tag, baseDomain };
    const name = `${project}_saiku-home`;
    if (!this.state.volumes.some((v) => v.name === name)) this.state.volumes.push({ name, project });
    this.state.disk.freeBytes = Math.max(0, this.state.disk.freeBytes - FAKE_STACK_DISK_BYTES);
    this.persist();
  }

  selfcheck({ pr }) {
    this.record(cmds.selfcheck({ pr }));
    const project = projectForPr(pr);
    if (!this.state.stacks[project]) throw new HostStepError('selfcheck', { detail: 'stack is not up' });
    if (this.state.failSelfcheck.includes(pr)) {
      throw new HostStepError('selfcheck', {
        detail: 'PREVIEW_SELFCHECK inactive=1 | foodmart_cube inactive no FoodMart cube listed',
      });
    }
    return { summary: `PREVIEW_SELFCHECK stack=${project} active=6 inactive=0 skipped=0`, inactive: [] };
  }

  /** Synthetic, clearly fake credentials. */
  readCredentials({ pr }) {
    this.record(cmds.readCreds({ pr }));
    const stack = this.state.stacks[projectForPr(pr)];
    if (!stack) throw new Error(`no credentials for PR #${pr}: is its preview up?`);
    return (
      this.state.credentials ??
      [
        `ORIGIN=https://${hostLabelForPr(pr)}.${stack.baseDomain ?? 'preview.saiku.bi'}`,
        'PREVIEW_ADMIN_USER=admin',
        'SAIKU_ADMIN_PASSWORD=prevpw_0123456789abcdef0123456789abcdef01234567',
      ].join('\n')
    );
  }

  down(project) {
    this.record(cmds.composeDown(project));
    this.record(cmds.rmEnvFile(project));
    if (this.state.stacks[project]) {
      delete this.state.stacks[project];
      this.state.disk.freeBytes += FAKE_STACK_DISK_BYTES;
    }
    this.state.volumes = this.state.volumes.filter((v) => v.project !== project);
    this.persist();
  }

  removeVolume(volume, project) {
    this.record(cmds.volumeRm(volume, project));
    this.state.volumes = this.state.volumes.filter((v) => v.name !== volume);
    this.persist();
  }

  diskFree() {
    const { totalBytes, freeBytes } = this.state.disk;
    return { totalBytes, freeBytes, freePct: freeBytes / totalBytes };
  }

  prune({ aggressive = false, olderThanHours = 168 } = {}) {
    this.record(cmds.imagePrune(aggressive ? {} : { olderThanHours }));
    const freed = aggressive ? this.state.reclaimable : Math.floor(this.state.reclaimable / 2);
    this.state.disk.freeBytes += freed;
    this.state.reclaimable -= freed;
    this.persist();
  }
}
