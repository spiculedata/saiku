// Preview-environment lifecycle DECISIONS.
// Ported from spiculedata/saiku-cloud .github/scripts/preview-lifecycle.mjs
// (spiculedata/saiku-cloud#1379); see docs/decisions/ci-preview-environments.md.
//
// Pure functions only: no I/O, no clock, no Docker. preview-ctl.mjs does the
// doing; this file decides, so every rule is unit-testable on a laptop.
//
//   plan       one PR event            -> one decision
//   reap       registry + open PRs ... -> teardowns, promotions, orphans
//   diskVerdict / gateOnDisk           -> prune / block creation on a full disk
//   apply / applyReap                  -> the next registry state
//   comment                            -> the sticky PR comment body
//
// Invariants (each has a test):
//   * An environment exists only while its PR is open AND was pushed to within
//     the idle window. Anything else is torn down by the reaper, not a human.
//   * Capacity is a QUEUE, not a failure: a full box says "queued, position N".
//   * Anything on the host the registry does not know about is an orphan. (Only
//     `saiku-oss-pr-<n>` projects are ever candidates: the cloud's previews on
//     the same box are invisible to this lifecycle.)
//   * A full disk is a visible state ("queued: disk low"), never a half-built
//     stack with "No space left on device" in a container log.
//   * Fork PRs never get an environment. Unknown origin fails closed.
//   * Nothing PR-authored (title, branch, body) enters a decision or a comment.
//     Inputs are a number, a login, labels, a 40-hex sha and two repo names.
//   * A `/preview` request (requestedBy) comes only from preview-command.mjs,
//     after a collaborator-permission check. It grants an explicit
//     `entitlement: 'collaborator'` marker that outlives later pushes, but never
//     overrides the fork rule.

import { ENV_DIR, assertBaseDomain, assertPrNumber, assertSha, hostLabelForPr, parseProject, projectForPr } from './preview-guard.mjs';
import { describeImage, prImageTag } from './preview-images.mjs';

const GIB = 1024 ** 3;

export const DEFAULTS = {
  /** Concurrent OSS preview stacks (the box is 16 GB and also runs the cloud's previews). */
  maxEnvs: 3,
  /** An environment with no push for this long is torn down (design: 24h). */
  idleHours: 24,
  baseDomain: 'preview.saiku.bi',
  /**
   * Show the admin login in the sticky PR comment. These are throwaway, tailnet-only test
   * environments, so by default the random per-environment password is posted; set
   * PREVIEW_POST_CREDENTIALS=false to go back to "fetch it from the host" (the repo is public,
   * so anyone who can read the PR can read the comment).
   */
  postCredentials: true,
  /** The tailnet name of the preview host, only used in the "how to fetch the login" hint. */
  sshHostHint: 'saiku-preview',
  previewLabel: 'preview',
  /** Logins treated as Hive-authored (case-insensitive); override with PREVIEW_AUTHORS. */
  authors: ['spicule-hive[bot]'],
  /** Below this fraction free, prune before creating anything. */
  pruneBelowPct: 0.3,
  /** After pruning, below this fraction free (or minFreeBytes) new envs queue. */
  blockBelowPct: 0.15,
  minFreeBytes: 10 * GIB,
  /**
   * How long `up` waits for the PR's image (its docker build may still be
   * running) before failing, and how often it asks the registry. The host lock
   * is held meanwhile, so keep it bounded.
   */
  imageWaitMinutes: 20,
  imagePollSeconds: 20,
};

export const EMPTY_REGISTRY = { version: 1, envs: {}, queue: [] };

const HOUR_MS = 3600 * 1000;
const LOGIN_RE = /^[A-Za-z0-9][A-Za-z0-9[\]-]{0,38}$/;

const asNumber = (value, fallback) => {
  const n = Number(value);
  return value !== null && value !== '' && Number.isFinite(n) ? n : fallback;
};

const byQueueOrder = (a, b) =>
  (Date.parse(a.queuedAt) || 0) - (Date.parse(b.queuedAt) || 0) || a.pr - b.pr;

/* --------------------------------------------------------------- registry */

/** `https://oss-pr-<n>.<base domain>`: one label under the base, so the wildcard certificate covers it. */
export function urlsFor(pr, baseDomain = DEFAULTS.baseDomain) {
  assertPrNumber(pr);
  return { url: `https://${hostLabelForPr(pr)}.${assertBaseDomain(baseDomain)}` };
}

/** The per-PR image tag: the short-SHA tag docker.yml publishes. */
export const imageTagFor = (sha) => prImageTag(assertSha(sha));

const collaboratorMarker = (value) =>
  value.entitlement === 'collaborator' && LOGIN_RE.test(value.requestedBy ?? '');

/**
 * Registry JSON is read from a box we do not fully trust to be hand-edit-free.
 * Everything derived from the PR number is recomputed, never believed, and
 * anything malformed is dropped rather than repaired into something the
 * reaper might act on.
 */
export function normaliseRegistry(raw) {
  const source = raw && typeof raw === 'object' ? raw : {};
  const envs = {};
  for (const [key, value] of Object.entries(source.envs ?? {})) {
    if (!value || typeof value !== 'object') continue;
    const pr = Number(value.pr ?? key);
    if (!Number.isInteger(pr) || pr < 1 || String(pr) !== String(key)) continue;
    envs[String(pr)] = {
      pr,
      state: value.state === 'queued' ? 'queued' : 'active',
      project: projectForPr(pr),
      headSha: /^[0-9a-f]{40}$/.test(value.headSha ?? '') ? value.headSha : null,
      imageTag: typeof value.imageTag === 'string' ? value.imageTag.slice(0, 40) : null,
      image: describeImage(value.image),
      author: LOGIN_RE.test(value.author ?? '') ? value.author : null,
      requestedBy: collaboratorMarker(value) ? value.requestedBy : null,
      entitlement: collaboratorMarker(value) ? 'collaborator' : null,
      queueReason: value.queueReason === 'disk-low' ? 'disk-low' : 'capacity-full',
      createdAt: value.createdAt ?? null,
      queuedAt: value.queuedAt ?? null,
      lastActivity: value.lastActivity ?? null,
    };
  }
  const registry = { version: 1, envs, queue: [] };
  registry.queue = queuedPrs(registry);
  return registry;
}

const entries = (registry, state) =>
  Object.values(registry.envs).filter((e) => e.state === state);

export const activePrs = (registry) =>
  entries(registry, 'active')
    .map((e) => e.pr)
    .sort((a, b) => a - b);

/** FIFO by when each PR joined the queue, not by PR number. */
export const queuedPrs = (registry) =>
  entries(registry, 'queued')
    .sort(byQueueOrder)
    .map((e) => e.pr);

/* ------------------------------------------------------------ event input */

/**
 * Reduce a `pull_request` webhook payload to the only fields the lifecycle is
 * allowed to see. Title, body and branch name are never read.
 */
export function eventFromGithub(payload) {
  const pr = payload?.pull_request;
  if (!pr) throw new Error('payload has no pull_request');
  const labels = (pr.labels ?? []).map((l) => String(l?.name ?? '')).filter(Boolean);
  return {
    number: assertPrNumber(pr.number),
    action: String(payload.action ?? ''),
    state: String(pr.state ?? ''),
    merged: pr.merged === true,
    author: String(pr.user?.login ?? ''),
    labels,
    headSha: String(pr.head?.sha ?? ''),
    headRepo: String(pr.head?.repo?.full_name ?? ''),
    baseRepo: String(pr.base?.repo?.full_name ?? ''),
    // GitHub never sends action "requested" for pull_request(_target); only
    // preview-command.mjs writes it, after checking the commenter's permission.
    requestedBy:
      payload.action === 'requested' && LOGIN_RE.test(payload.requested_by ?? '')
        ? payload.requested_by
        : '',
    restart: payload.action === 'requested' && payload.restart === true,
  };
}

/**
 * Is this PR entitled to a preview environment? Fork PRs never are. `entry` is
 * the PR's registry entry, so a collaborator's earlier `/preview` keeps counting.
 */
export function eligibility(event, config = DEFAULTS, entry = null) {
  const head = String(event.headRepo ?? '').toLowerCase();
  const base = String(event.baseRepo ?? '').toLowerCase();
  if (!head || !base) return { eligible: false, reason: 'unknown-origin (failing closed)' };
  if (head !== base) return { eligible: false, reason: 'fork-pr (no environment for forks)' };

  if (LOGIN_RE.test(event.requestedBy ?? '')) {
    return { eligible: true, reason: 'requested-by-collaborator' };
  }

  const label = String(config.previewLabel).toLowerCase();
  const labels = (event.labels ?? []).map((l) => String(l).toLowerCase());
  if (labels.includes(label)) return { eligible: true, reason: `label-${config.previewLabel}` };

  const author = String(event.author ?? '');
  if (LOGIN_RE.test(author)) {
    const authors = (config.authors ?? []).map((a) => String(a).toLowerCase());
    if (authors.includes(author.toLowerCase())) return { eligible: true, reason: 'hive-authored' };
  }
  if (entry?.entitlement === 'collaborator') {
    return { eligible: true, reason: 'collaborator-requested' };
  }
  return { eligible: false, reason: `not Hive-authored and not labelled ${config.previewLabel}` };
}

/* ------------------------------------------------------------------- plan */

const decision = (action, reason, extra = {}) => ({ action, reason, ...extra });

/**
 * Decide what one pull_request event does to one environment.
 * Actions: up | queued | down | keep (already current) | skip (nothing to do).
 */
export function plan({ event, registry: raw, config = DEFAULTS, now = new Date().toISOString() } = {}) {
  const pr = assertPrNumber(Number(event?.number));
  const registry = normaliseRegistry(raw);
  const entry = registry.envs[String(pr)];
  const base = { pr, project: projectForPr(pr), ...urlsFor(pr, config.baseDomain) };

  const closed =
    event.action === 'closed' || event.merged === true || String(event.state).toLowerCase() === 'closed';
  if (closed) {
    return entry
      ? decision('down', 'pr-closed-or-merged', base)
      : decision('skip', 'closed with no environment', base);
  }

  // Losing entitlement (label removed, fork head replaced) must tear down,
  // or `preview` becomes a label nobody ever removes.
  const { eligible, reason: whyEligible } = eligibility(event, config, entry);
  if (!eligible) {
    return entry
      ? decision('down', `no-longer-eligible: ${whyEligible}`, base)
      : decision('skip', whyEligible, base);
  }

  const headSha = assertSha(event.headSha);
  const common = {
    ...base,
    headSha,
    imageTag: imageTagFor(headSha),
    author: LOGIN_RE.test(event.author ?? '') ? event.author : null,
    ...(LOGIN_RE.test(event.requestedBy ?? '') ? { requestedBy: event.requestedBy } : {}),
  };

  // A label change (e.g. the labeler adding `risk/low`) is not a push: it must
  // neither rebuild nor extend the idle window.
  // A collaborator's `/preview` on a current env keeps it and extends the idle
  // window (see apply); `/preview restart` rebuilds it.
  const current = entry?.state === 'active' && entry.headSha === headSha;
  if (current && !event.restart) {
    return decision('keep', 'already running this commit', common);
  }
  if (entry?.state === 'active') {
    return decision('up', current ? 'restart' : 'refresh', { ...common, createdAt: entry.createdAt });
  }

  const active = activePrs(registry).length;
  if (active >= asNumber(config.maxEnvs, DEFAULTS.maxEnvs)) {
    const queue = queuedPrs(registry);
    const at = queue.indexOf(pr);
    return decision('queued', 'capacity-full', {
      ...common,
      position: at === -1 ? queue.length + 1 : at + 1,
      queuedAt: entry?.queuedAt ?? now,
    });
  }
  return decision('up', entry ? 'promote-from-queue' : 'new', {
    ...common,
    createdAt: entry?.createdAt ?? null,
  });
}

/* ------------------------------------------------------------------- disk */

/**
 * @param {{freeBytes: number, totalBytes: number}} disk
 * @returns 'ok' | 'prune' (below the prune line) | 'blocked' (too full to build)
 *
 * `blocked` is judged AFTER a prune attempt, so callers evaluate it twice:
 * once to decide whether to prune, once to decide whether to proceed.
 */
export function diskVerdict(disk, config = DEFAULTS) {
  const total = Number(disk?.totalBytes);
  const free = Number(disk?.freeBytes);
  if (!Number.isFinite(total) || !Number.isFinite(free) || total <= 0) return 'blocked';
  const pct = free / total;
  if (pct < asNumber(config.blockBelowPct, DEFAULTS.blockBelowPct)) return 'blocked';
  if (free < asNumber(config.minFreeBytes, DEFAULTS.minFreeBytes)) return 'blocked';
  if (pct < asNumber(config.pruneBelowPct, DEFAULTS.pruneBelowPct)) return 'prune';
  return 'ok';
}

/** Turn an `up` into a visible `queued` when the disk cannot take another stack. */
export function gateOnDisk(d, verdict, registryRaw, now = new Date().toISOString()) {
  if (d.action !== 'up' || verdict !== 'blocked') return d;
  const registry = normaliseRegistry(registryRaw);
  // A refresh rebuilds a stack that already holds its disk; demoting it to
  // `queued` would leave a running stack the registry says is not running.
  if (registry.envs[String(d.pr)]?.state === 'active') return d;
  const queue = queuedPrs(registry);
  const at = queue.indexOf(d.pr);
  return {
    ...d,
    action: 'queued',
    reason: 'disk-low',
    position: at === -1 ? queue.length + 1 : at + 1,
    queuedAt: registry.envs[String(d.pr)]?.queuedAt ?? now,
  };
}

/* ------------------------------------------------------------------- reap */

/**
 * Which queued PRs fit into the free slots, oldest first. `open` is the set of
 * PRs known to be open (callers without that knowledge pass every queued PR).
 */
export function pickPromotions(registry, { open, skip = new Set(), maxEnvs, activeCount }) {
  let active = activeCount;
  const picked = [];
  const queue = entries(registry, 'queued')
    .filter((e) => !skip.has(e.pr) && open.has(e.pr) && e.headSha)
    .sort(byQueueOrder);
  for (const e of queue) {
    if (active >= maxEnvs) break;
    picked.push({ pr: e.pr, headSha: e.headSha, reason: 'slot-freed' });
    active += 1;
  }
  return picked;
}

const isIdle = (entry, nowMs, idleHours) => {
  const last = Date.parse(entry.lastActivity ?? '');
  return Number.isNaN(last) || nowMs - last > idleHours * HOUR_MS;
};

/**
 * Hourly reconciliation. Teardowns first so the slots they free are available
 * to the promotions in the same run.
 *
 * @param {{registry?: object, openPrs?: number[], projects?: {pr: number,
 *   project: string}[], diskBlocked?: boolean, now?: string, config?: object}} args
 *   `projects` is what the box actually runs (already guard-filtered).
 */
export function reap({
  registry: raw,
  openPrs = [],
  projects = [],
  diskBlocked = false,
  now = new Date().toISOString(),
  config = DEFAULTS,
} = {}) {
  const registry = normaliseRegistry(raw);
  const nowMs = Date.parse(now);
  if (Number.isNaN(nowMs)) throw new Error(`reap: unparseable now: ${now}`);
  if (!Array.isArray(openPrs) || !openPrs.every((n) => Number.isInteger(n) && n > 0)) {
    throw new Error('reap: openPrs must be an array of positive integers');
  }
  const open = new Set(openPrs);
  const idleHours = asNumber(config.idleHours, DEFAULTS.idleHours);
  const maxEnvs = asNumber(config.maxEnvs, DEFAULTS.maxEnvs);

  const teardowns = [];
  for (const entry of Object.values(registry.envs)) {
    if (!open.has(entry.pr)) {
      teardowns.push({ pr: entry.pr, state: entry.state, reason: 'pr-closed-or-merged' });
    } else if (isIdle(entry, nowMs, idleHours)) {
      teardowns.push({ pr: entry.pr, state: entry.state, reason: 'idle' });
    }
  }
  const tornDown = new Set(teardowns.map((t) => t.pr));

  // Host stacks no registry entry claims: a cancelled teardown, a registry
  // lost in a reboot, a stack someone started by hand under a preview name.
  const orphans = [];
  const seen = new Set();
  for (const p of projects) {
    const parsed = parseProject(p?.project);
    if (!parsed || registry.envs[String(parsed.pr)] || seen.has(parsed.pr)) continue;
    seen.add(parsed.pr);
    orphans.push({
      pr: parsed.pr,
      reason: open.has(parsed.pr) ? 'not-in-registry' : 'pr-closed-or-merged',
    });
  }

  orphans.sort((a, b) => a.pr - b.pr);

  const promotions = diskBlocked
    ? []
    : pickPromotions(registry, {
        open,
        skip: tornDown,
        maxEnvs,
        activeCount: entries(registry, 'active').filter((e) => !tornDown.has(e.pr)).length,
      });

  const notes = [];
  if (teardowns.length) notes.push(`${teardowns.length} torn down`);
  if (orphans.length) notes.push(`${orphans.length} orphans removed`);
  if (promotions.length) notes.push(`${promotions.length} promoted from queue`);
  return {
    now,
    teardowns,
    orphans,
    promotions,
    /** Every PR whose host projects must be destroyed this run. */
    destroyPrs: [...new Set([...teardowns, ...orphans].map((d) => d.pr))].sort((a, b) => a - b),
    summary: notes.length ? notes.join(', ') : 'nothing to do',
  };
}

/* ------------------------------------------------------------------ apply */

const clone = (registry) => JSON.parse(JSON.stringify(registry));

/** Fold a decision into the registry. Returns a NEW registry. */
export function apply(d, registryRaw, { now = new Date().toISOString() } = {}) {
  const registry = clone(normaliseRegistry(registryRaw));
  const pr = assertPrNumber(Number(d?.pr));
  const key = String(pr);
  const existing = registry.envs[key];
  const shared = {
    pr,
    project: projectForPr(pr),
    headSha: d.headSha ?? existing?.headSha ?? null,
    imageTag: d.imageTag ?? existing?.imageTag ?? null,
    image: describeImage(d.image) ?? existing?.image ?? null,
    author: d.author ?? existing?.author ?? null,
    ...(d.requestedBy
      ? { requestedBy: d.requestedBy, entitlement: 'collaborator' }
      : {
          requestedBy: existing?.requestedBy ?? null,
          entitlement: existing?.entitlement ?? null,
        }),
    createdAt: existing?.createdAt ?? d.createdAt ?? now,
  };

  switch (d.action) {
    case 'up':
      registry.envs[key] = {
        ...shared,
        state: 'active',
        queueReason: 'capacity-full',
        queuedAt: null,
        lastActivity: now,
      };
      break;
    case 'queued':
      registry.envs[key] = {
        ...shared,
        state: 'queued',
        queueReason: d.reason === 'disk-low' ? 'disk-low' : 'capacity-full',
        queuedAt: existing?.queuedAt ?? d.queuedAt ?? now,
        lastActivity: now,
      };
      break;
    case 'down':
      delete registry.envs[key];
      break;
    case 'keep':
      // Only a collaborator's explicit request counts as activity here; a keep
      // caused by label noise must not extend the idle window.
      if (existing && d.requestedBy) {
        registry.envs[key] = { ...existing, ...shared, lastActivity: now };
      }
      break;
    case 'skip':
      break;
    default:
      throw new Error(`apply: unknown action ${d.action}`);
  }
  registry.queue = queuedPrs(registry);
  return registry;
}

/**
 * Record out-of-band activity (a collaborator review) for an existing env.
 * No env, an older timestamp or junk is a no-op; the future is clamped to `now`.
 * Returns a NEW registry.
 */
export function touch(registryRaw, pr, at, now = new Date().toISOString()) {
  const registry = normaliseRegistry(registryRaw);
  const entry = registry.envs[String(pr)];
  const atMs = Date.parse(at);
  const nowMs = Date.parse(now);
  if (!entry || Number.isNaN(atMs) || Number.isNaN(nowMs)) return registry;
  const last = Date.parse(entry.lastActivity ?? '');
  if (!Number.isNaN(last) && atMs <= last) return registry;
  const lastActivity = new Date(Math.min(atMs, nowMs)).toISOString();
  return { ...registry, envs: { ...registry.envs, [String(pr)]: { ...entry, lastActivity } } };
}

/** Fold a reap plan in: remove everything destroyed, then claim the promoted. */
export function applyReap(result, registryRaw, opts = {}) {
  let registry = normaliseRegistry(registryRaw);
  for (const t of result.teardowns) {
    registry = apply({ action: 'down', pr: t.pr }, registry, opts);
  }
  for (const p of result.promotions) {
    registry = apply({ action: 'up', pr: p.pr, headSha: p.headSha }, registry, opts);
  }
  return registry;
}

/* ---------------------------------------------------------------- comment */

export const COMMENT_MARKER = '<!-- saiku-oss-preview-status -->';

const STATUS = {
  up: 'UP',
  queued: 'QUEUED',
  down: 'TORN DOWN',
  failed: 'FAILED TO START',
  unbuilt: 'NO PREVIEW',
  building: 'BUILDING IMAGE',
};

/** Where validators learn how to fetch credentials. Never the credentials. */
export const CREDENTIALS_DOC_URL =
  'https://github.com/spiculedata/saiku/blob/development/infra/preview/README.md#credentials-for-validators';

/** A login is rendered only if it has the exact shape the host writes (never arbitrary text). */
const LOGIN_USER_RE = /^[A-Za-z0-9][A-Za-z0-9_.@-]{0,63}$/;
const LOGIN_PASSWORD_RE = /^[A-Za-z0-9_.-]{1,128}$/;
function validLogin(credentials) {
  const user = credentials?.user;
  const password = credentials?.password;
  return LOGIN_USER_RE.test(user ?? '') && LOGIN_PASSWORD_RE.test(password ?? '') ? { user, password } : null;
}

/**
 * The comment that PINGS people when a preview comes up. The sticky status comment is edited in
 * place and GitHub sends no notification for an edit, so a new comment is the only way to tell
 * the author and subscribers. It repeats no credential: the login (if shown at all) is only in
 * the sticky comment, in one place.
 */
export function announcement(d, { config = DEFAULTS } = {}) {
  const pr = assertPrNumber(Number(d?.pr));
  const { url } = urlsFor(pr, config.baseDomain);
  return [
    `**Preview is running** for this PR: ${url}/ui/ (private: reachable from the tailnet only).`,
    '',
    'The login and status are in the **Preview environment** comment on this PR, which is updated in place on every push.',
  ].join('\n');
}

/** The sticky PR comment. Built only from numbers, enums and config. */
export function comment(d, { config = DEFAULTS, registry } = {}) {
  const pr = assertPrNumber(Number(d?.pr));
  const { url } = urlsFor(pr, config.baseDomain);
  const idle = config.idleHours ?? DEFAULTS.idleHours;
  const lines = [COMMENT_MARKER, '### Preview environment', ''];
  lines.push(`**${STATUS[d.action] ?? 'UNKNOWN'}** (${d.reason})`);

  if (d.action === 'up') {
    // The app lives under /ui/; the bare root answers 500 (the engine has no landing page).
    lines.push('', `Saiku: ${url}/ui/ (private: reachable from the tailnet only)`);
    const image = describeImage(d.image);
    if (image) lines.push('', `Image: \`ghcr.io/spiculedata/saiku:${image.tag}\` (this PR's build)`);
    const login = config.postCredentials === false ? null : validLogin(d.credentials);
    if (login) {
      lines.push(
        '',
        `Login: \`${login.user}\` / \`${login.password}\``,
        '',
        'This is a throwaway password for this test environment, shown here on purpose: anyone who can read ' +
          'this PR can read it, and it only works on the private preview. It stays the same while the ' +
          'environment is up and changes if it is torn down and rebuilt. The bundled FoodMart sample data is loaded.',
      );
    } else {
      lines.push(
        '',
        'Login as `admin` with a random per-environment password. Fetch it with ' +
          `\`ssh ${config.sshHostHint ?? DEFAULTS.sshHostHint} "grep SAIKU_ADMIN_PASSWORD ${ENV_DIR}/${projectForPr(pr)}.env"\` ` +
          `(validators: [the preview README](${CREDENTIALS_DOC_URL})). The bundled FoodMart sample data is loaded.`,
      );
    }
    lines.push(
      '',
      `Torn down when this PR closes or merges, or after ${idle}h without activity ` +
        '(a push, a collaborator `/preview`, or a collaborator review). ' +
        'Updated in place on every push. Comment `/preview` to refresh the idle window.',
    );
  } else if (d.action === 'queued') {
    const why =
      d.reason === 'disk-low'
        ? 'The preview host is low on disk space and is being pruned.'
        : `The preview host is at capacity (${config.maxEnvs ?? DEFAULTS.maxEnvs} OSS environments).`;
    lines.push(
      '',
      `${why} This PR is **queued at position ${d.position ?? '?'}** and is brought up ` +
        `automatically when room frees (checked hourly). It will be dropped after ${idle}h without activity. ` +
        'Comment `/preview` to (re)start this preview.',
      '',
      `It will be served at ${url}`,
    );
  } else if (d.action === 'failed') {
    const imageWait = d.reason === 'image-not-ready';
    lines.push(
      '',
      'The stack did not come up and its containers and volumes were removed. ' +
        'Comment `/preview` to (re)start this preview. The workflow run log has the cause.',
      ...(imageWait
        ? [
            '',
            'Image not ready: the PR build image was not published in time. ' +
              'Once the `docker` build for this commit has finished, comment `/preview`.',
          ]
        : []),
    );
  } else if (d.action === 'unbuilt') {
    lines.push(
      '',
      'This PR changes nothing the `docker` workflow builds (poms, `saiku-*/`, `lib/`, `Dockerfile`, ' +
        '`docker/`), so no image exists for it and there is nothing to run. ' +
        'Push a change to one of those paths and the preview is created automatically.',
    );
  } else if (d.action === 'building') {
    const started =
      d.reason === 'build-started'
        ? 'No image exists for this PR\'s head commit yet, so a `docker` build was started for it.'
        : 'The `docker` build for this PR\'s head commit is still running.';
    lines.push(
      '',
      `${started} It usually takes 10 to 15 minutes. The preview comes up automatically as soon as the image ` +
        'is published, and this comment is updated in place; there is no need to comment again.',
      '',
      `It will be served at ${url} (private: reachable from the tailnet only).`,
      '',
      'If no image appears within 20 minutes this comment turns into **FAILED TO START**; comment `/preview` to retry.',
    );
  } else if (d.action === 'down') {
    lines.push(
      '',
      'The environment, its containers and its volumes are removed. ' +
        'Comment `/preview` to (re)start this preview.',
    );
  }

  if (registry) {
    const r = normaliseRegistry(registry);
    lines.push('', `Host: ${activePrs(r).length} active, ${queuedPrs(r).length} queued (OSS previews).`);
  }
  lines.push('', 'Managed by `.github/workflows/preview-env.yml`.');
  return lines.join('\n');
}
