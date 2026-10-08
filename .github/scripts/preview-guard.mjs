// The only place preview-host commands are built.
// Ported from spiculedata/saiku-cloud .github/scripts/preview-guard.mjs
// (spiculedata/saiku-cloud#1379); see docs/decisions/ci-preview-environments.md
// for the provenance table and the deliberate differences.
//
// The preview host is SHARED with the saiku-cloud previews (compose projects
// `saiku-pr-<n>[-strict|-minimal|-base]`, state in /var/lib/saiku-preview) and
// may also run an operator's own containers. The failure that matters is this
// automation destroying something that is NOT an OSS preview stack. Two rules
// make that impossible by construction:
//
//   1. Every project name that reaches a destructive command passes through
//      `assertPreviewProject`, which accepts `saiku-oss-pr-<n>` and NOTHING
//      else. In particular the cloud's `saiku-pr-<n>` is refused, so a bug here
//      can never tear down a cloud preview (and the cloud guard refuses ours).
//      Names come from the PR number (an integer), never from PR-authored text.
//   2. Commands are built here as argv arrays, never as interpolated shell
//      strings. The host layer quotes each element, so even a name that slipped
//      past the regex could not become a second command. The regex is the
//      policy; the quoting is the backstop.
//
// There is deliberately no `docker system prune`, no `docker volume prune` and
// no `docker rm` of a bare name here. Image pruning is the one command that is
// not project-scoped (images are shared), so it is (a) only emitted behind the
// host marker file and (b) restricted by an OCI source label to images built from
// THIS repository, so it can never delete an image a cloud preview holds.

import { assertImageTag, imageRef } from './preview-images.mjs';

/** `saiku-oss-pr-<n>`; n is 1-9 digits, no leading zero. */
const PROJECT_RE = /^saiku-oss-pr-([1-9][0-9]{0,8})$/;
const VOLUME_TAIL_RE = /^[a-z0-9][a-z0-9_.-]{0,127}$/;
const SHA_RE = /^[0-9a-f]{40}$/;
/** A DNS name with at least two labels, lower case. */
const DOMAIN_RE = /^(?=.{4,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$/;

/** Exists only on a host provisioned as a preview box (shared with the cloud). */
export const HOST_MARKER = '/etc/saiku-preview-host';
/** The OSS lifecycle's OWN state (the cloud uses /var/lib/saiku-preview). */
export const STATE_DIR = '/var/lib/saiku-preview-oss';
export const SRC_DIR = `${STATE_DIR}/src`;
export const ENV_DIR = `${STATE_DIR}/env`;
export const REGISTRY_PATH = `${STATE_DIR}/registry.json`;
export const LOCK_DIR = `${STATE_DIR}/lock`;

/** OCI label every image built by this repo's docker.yml carries (docker/metadata-action). */
export const IMAGE_SOURCE_LABEL = 'org.opencontainers.image.source=https://github.com/spiculedata/saiku';

export function parseProject(name) {
  if (typeof name !== 'string') return null;
  const m = PROJECT_RE.exec(name);
  if (!m) return null;
  return { pr: Number(m[1]), project: name };
}

export const isPreviewProject = (name) => parseProject(name) !== null;

export function assertPreviewProject(name) {
  const parsed = parseProject(name);
  if (!parsed) {
    throw new Error(
      `refusing to touch ${JSON.stringify(name)}: not an OSS preview project (saiku-oss-pr-<n>)`,
    );
  }
  return parsed;
}

export function assertPrNumber(pr) {
  if (!Number.isInteger(pr) || pr < 1 || pr > 999_999_999) {
    throw new Error(`invalid PR number: ${JSON.stringify(pr)}`);
  }
  return pr;
}

export function assertSha(sha) {
  if (typeof sha !== 'string' || !SHA_RE.test(sha)) {
    throw new Error(`invalid commit sha: ${JSON.stringify(sha)}`);
  }
  return sha;
}

export function assertBaseDomain(domain) {
  if (typeof domain !== 'string' || !DOMAIN_RE.test(domain)) {
    throw new Error(`invalid preview base domain: ${JSON.stringify(domain)}`);
  }
  return domain;
}

/** Upper bounds (seconds) the host enforces with `timeout`. */
export const SELFCHECK_TIMEOUT_SECONDS = 300;
/** `compose up --wait` gives up after this long (first boot of the image is ~1-2 min). */
export const UP_WAIT_TIMEOUT_SECONDS = 600;
/** `timeout` exit status when the command was killed for running too long. */
export const TIMEOUT_EXIT_STATUS = 124;

/** The only keys `readCreds` may ever bring back from a per-PR env file. */
export const CREDENTIAL_KEYS = ['ORIGIN', 'PREVIEW_ADMIN_USER', 'SAIKU_ADMIN_PASSWORD'];

export const projectForPr = (pr) => `saiku-oss-pr-${assertPrNumber(pr)}`;
/** The identifier render-env.sh derives the hostname from: `oss-pr-<n>`. */
export const hostLabelForPr = (pr) => `oss-pr-${assertPrNumber(pr)}`;
export const envFileFor = (project) => `${ENV_DIR}/${assertPreviewProject(project).project}.env`;

/** A volume may be removed only if it belongs to the given preview project. */
export function assertPreviewVolume(volume, project) {
  assertPreviewProject(project);
  if (typeof volume !== 'string' || !volume.startsWith(`${project}_`)) {
    throw new Error(`refusing to remove volume ${JSON.stringify(volume)}: not owned by ${project}`);
  }
  if (!VOLUME_TAIL_RE.test(volume.slice(project.length + 1))) {
    throw new Error(`refusing to remove volume ${JSON.stringify(volume)}: unexpected name`);
  }
  return volume;
}

/* ---- command builders: each returns { argv, mutating, marker } ----------- */

const cmd = (argv, { mutating = false, marker = false } = {}) => ({ argv, mutating, marker });

export const cmds = {
  /** All compose project names on the box, one per line (read only). */
  listProjects: () =>
    cmd(['docker', 'ps', '-a', '--format', '{{.Label "com.docker.compose.project"}}']),

  /** `name<TAB>project` for every compose-labelled volume (read only). */
  listVolumes: () =>
    cmd([
      'docker',
      'volume',
      'ls',
      '--filter',
      'label=com.docker.compose.project',
      '--format',
      '{{.Name}}\t{{.Label "com.docker.compose.project"}}',
    ]),

  /** Stop the stack and delete its containers, network AND volumes. */
  composeDown: (project) =>
    cmd(
      [
        'docker',
        'compose',
        '-p',
        assertPreviewProject(project).project,
        'down',
        '-v',
        '--remove-orphans',
        '--timeout',
        '30',
      ],
      { mutating: true, marker: true },
    ),

  volumeRm: (volume, project) =>
    cmd(['docker', 'volume', 'rm', assertPreviewVolume(volume, project)], {
      mutating: true,
      marker: true,
    }),

  rmEnvFile: (project) => cmd(['rm', '-f', envFileFor(project)], { mutating: true, marker: true }),

  /**
   * Unused images built from THIS repository only (the shared box also holds the
   * cloud's images; the cloud reaper prunes those). Never one a running stack holds.
   */
  imagePrune: ({ olderThanHours } = {}) => {
    const argv = ['docker', 'image', 'prune', '-af', '--filter', `label=${IMAGE_SOURCE_LABEL}`];
    if (olderThanHours !== undefined) {
      if (!Number.isInteger(olderThanHours) || olderThanHours < 1) {
        throw new Error(`invalid prune age: ${olderThanHours}`);
      }
      argv.push('--filter', `until=${olderThanHours}h`);
    }
    return cmd(argv, { mutating: true, marker: true });
  },

  /** Reads the Docker data filesystem: `df -Pk` is POSIX on every Linux. */
  diskFree: () => cmd(['df', '-Pk', '/var/lib/docker']),

  /**
   * Does `<registry>/saiku:<tag>` exist in GHCR? Read only; it runs with the
   * host's own registry login. The reference is built here from a tag that
   * matched the strict grammar.
   */
  manifestInspect: (tag) => cmd(['docker', 'manifest', 'inspect', imageRef(tag)]),

  /**
   * Render the per-PR env file. The image tag, sha and base domain are each
   * validated and passed as their own flag, so render-env.sh never guesses.
   */
  render: ({ pr, sha, tag, baseDomain }) =>
    cmd(
      [
        `${SRC_DIR}/infra/preview/render-env.sh`,
        String(assertPrNumber(pr)),
        '--sha',
        assertSha(sha),
        '--image-tag',
        assertImageTag(tag),
        '--base-domain',
        assertBaseDomain(baseDomain),
        '--out',
        envFileFor(projectForPr(pr)),
      ],
      { mutating: true, marker: true },
    ),

  composeUp: ({ pr }) =>
    cmd(
      [
        'timeout',
        '--kill-after=10',
        String(UP_WAIT_TIMEOUT_SECONDS + 60),
        ...composeBase(pr),
        'up',
        '-d',
        '--wait',
        '--wait-timeout',
        String(UP_WAIT_TIMEOUT_SECONDS),
        '--remove-orphans',
      ],
      { mutating: true, marker: true },
    ),

  /**
   * The self-check, as a one-shot `compose run` of the profile-gated
   * `preview-selfcheck` service. Must come after `up --wait`.
   */
  selfcheck: ({ pr }) =>
    cmd(
      [
        'timeout',
        '--kill-after=10',
        String(SELFCHECK_TIMEOUT_SECONDS),
        ...composeBase(pr),
        '--profile',
        'selfcheck',
        'run',
        '--rm',
        '-T',
        'preview-selfcheck',
      ],
      { mutating: true, marker: true },
    ),

  /**
   * Read ONLY the credential keys out of the per-PR env file (grep with a fixed
   * pattern, so no other value ever leaves the host). Read only; the output is
   * secret and must be handled by preview-creds.mjs, never logged.
   */
  readCreds: ({ pr }) =>
    cmd(['grep', '-E', `^(${CREDENTIAL_KEYS.join('|')})=`, envFileFor(projectForPr(pr))], {
      marker: true,
    }),
};

function composeBase(pr) {
  const project = projectForPr(pr);
  return [
    'docker',
    'compose',
    '-p',
    project,
    '--project-directory',
    SRC_DIR,
    '--env-file',
    envFileFor(project),
    '-f',
    `${SRC_DIR}/infra/preview/docker-compose.preview.yml`,
  ];
}

const SAFE_WORD = /^[A-Za-z0-9_@%+=:,./-]+$/;

/** POSIX single-quote every word that is not plainly safe. */
export function shellQuote(word) {
  const s = String(word);
  if (s !== '' && SAFE_WORD.test(s)) return s;
  return `'${s.replace(/'/g, `'\\''`)}'`;
}

/** The remote command line for one command, with the preview-host marker gate. */
export function remoteLine({ argv, marker }) {
  const line = argv.map(shellQuote).join(' ');
  return marker ? `test -f ${HOST_MARKER} && ${line}` : line;
}
