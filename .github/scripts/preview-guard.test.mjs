/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */

/**
 * The guard is the only place host commands are built. These tests pin the
 * destructive-command safety properties, above all that the OSS lifecycle can
 * never address a saiku-cloud preview (`saiku-pr-<n>...`) that shares the box.
 */

import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { test } from 'node:test';

import {
  CREDENTIAL_KEYS,
  ENV_DIR,
  HOST_MARKER,
  IMAGE_SOURCE_LABEL,
  LOCK_DIR,
  REGISTRY_PATH,
  SRC_DIR,
  STATE_DIR,
  assertBaseDomain,
  assertPreviewProject,
  assertPreviewVolume,
  cmds,
  envFileFor,
  hostLabelForPr,
  isPreviewProject,
  parseProject,
  projectForPr,
  remoteLine,
  shellQuote,
} from './preview-guard.mjs';

const sha = (c) => c.repeat(40);
const CLOUD_NAMES = [
  'saiku-pr-5',
  'saiku-pr-5-strict',
  'saiku-pr-5-minimal',
  'saiku-pr-5-base',
  'saiku-cloud',
  'saiku',
  'preview-traefik',
];
const BAD_NAMES = [
  ...CLOUD_NAMES,
  'saiku-oss-pr-0',
  'saiku-oss-pr-05',
  'saiku-oss-pr-',
  'saiku-oss-pr-5-strict',
  'saiku-oss-pr-5 ',
  'saiku-oss-pr-5\n',
  'saiku-oss-pr-1234567890',
  'xsaiku-oss-pr-5',
  'saiku-oss-pr-5;id',
  'SAIKU-OSS-PR-5',
  '',
  null,
  undefined,
  5,
];

test('the OSS state lives in its own directory, never the cloud one', () => {
  assert.equal(STATE_DIR, '/var/lib/saiku-preview-oss');
  for (const p of [SRC_DIR, ENV_DIR, REGISTRY_PATH, LOCK_DIR]) {
    assert.ok(p.startsWith(`${STATE_DIR}/`), p);
    assert.ok(!p.startsWith('/var/lib/saiku-preview/'), p);
  }
  assert.equal(HOST_MARKER, '/etc/saiku-preview-host');
});

test('parseProject accepts saiku-oss-pr-<n> and nothing else', () => {
  assert.deepEqual(parseProject('saiku-oss-pr-7'), { pr: 7, project: 'saiku-oss-pr-7' });
  assert.equal(parseProject('saiku-oss-pr-999999999').pr, 999_999_999);
  for (const name of BAD_NAMES) assert.equal(parseProject(name), null, String(name));
});

test('cloud preview projects are refused by every destructive builder', () => {
  for (const name of CLOUD_NAMES) {
    assert.equal(isPreviewProject(name), false, name);
    assert.throws(() => assertPreviewProject(name), /not an OSS preview project/, name);
    assert.throws(() => cmds.composeDown(name), /refusing/, name);
    assert.throws(() => cmds.rmEnvFile(name), /refusing/, name);
    assert.throws(() => cmds.volumeRm(`${name}_saiku-home`, name), /refusing/, name);
    assert.throws(() => envFileFor(name), /refusing/, name);
  }
});

test('composeDown targets exactly one OSS project and its volumes, with no project-agnostic form', () => {
  const { argv, mutating, marker } = cmds.composeDown('saiku-oss-pr-12');
  assert.deepEqual(argv, ['docker', 'compose', '-p', 'saiku-oss-pr-12', 'down', '-v', '--remove-orphans', '--timeout', '30']);
  assert.equal(mutating, true);
  assert.equal(marker, true);
});

test('a volume is removable only when it belongs to the named OSS project', () => {
  assert.equal(assertPreviewVolume('saiku-oss-pr-5_saiku-home', 'saiku-oss-pr-5'), 'saiku-oss-pr-5_saiku-home');
  for (const [vol, project] of [
    ['saiku-oss-pr-50_saiku-home', 'saiku-oss-pr-5'], // prefix collision
    ['saiku-pr-5_control_plane_data', 'saiku-oss-pr-5'], // a cloud volume
    ['saiku-oss-pr-5_', 'saiku-oss-pr-5'],
    ['saiku-oss-pr-5_../etc', 'saiku-oss-pr-5'],
    ['saiku-oss-pr-5_Home', 'saiku-oss-pr-5'],
    ['saiku-oss-pr-5_a b', 'saiku-oss-pr-5'],
    ['named', 'saiku-oss-pr-5'],
    [undefined, 'saiku-oss-pr-5'],
  ]) {
    assert.throws(() => assertPreviewVolume(vol, project), /refusing/, String(vol));
  }
});

test('no builder emits a project-agnostic destructive command', () => {
  const all = [
    cmds.listProjects(),
    cmds.listVolumes(),
    cmds.diskFree(),
    cmds.imagePrune(),
    cmds.imagePrune({ olderThanHours: 24 }),
    cmds.manifestInspect('abcdef0'),
    cmds.composeDown('saiku-oss-pr-1'),
    cmds.volumeRm('saiku-oss-pr-1_saiku-home', 'saiku-oss-pr-1'),
    cmds.rmEnvFile('saiku-oss-pr-1'),
    cmds.render({ pr: 1, sha: sha('a'), tag: 'aaaaaaa', baseDomain: 'preview.saiku.bi' }),
    cmds.composeUp({ pr: 1 }),
    cmds.selfcheck({ pr: 1 }),
    cmds.readCreds({ pr: 1 }),
  ];
  for (const { argv } of all) {
    const line = argv.join(' ');
    assert.doesNotMatch(line, /system prune|volume prune|network prune|builder prune|container prune/, line);
    assert.doesNotMatch(line, /\bdocker (rm|kill|stop)\b/, line);
    assert.doesNotMatch(line, /\brm -rf?\b/, line);
  }
});

test('image prune is scoped to images built from this repository, so a cloud image is never pruned', () => {
  for (const opts of [undefined, { olderThanHours: 168 }]) {
    const { argv, mutating, marker } = cmds.imagePrune(opts);
    assert.deepEqual(argv.slice(0, 4), ['docker', 'image', 'prune', '-af']);
    const label = argv[argv.indexOf('--filter') + 1];
    assert.equal(label, `label=${IMAGE_SOURCE_LABEL}`);
    assert.match(IMAGE_SOURCE_LABEL, /^org\.opencontainers\.image\.source=https:\/\/github\.com\/spiculedata\/saiku$/);
    assert.equal(mutating && marker, true);
  }
  assert.ok(cmds.imagePrune({ olderThanHours: 24 }).argv.includes('until=24h'));
  for (const bad of [0, -1, 1.5, '24', NaN]) assert.throws(() => cmds.imagePrune({ olderThanHours: bad }), /invalid prune age/);
});

test('every mutating command is gated on the preview-host marker file', () => {
  const mutating = [
    cmds.composeDown('saiku-oss-pr-1'),
    cmds.volumeRm('saiku-oss-pr-1_saiku-home', 'saiku-oss-pr-1'),
    cmds.rmEnvFile('saiku-oss-pr-1'),
    cmds.imagePrune(),
    cmds.render({ pr: 1, sha: sha('a'), tag: 'aaaaaaa', baseDomain: 'preview.saiku.bi' }),
    cmds.composeUp({ pr: 1 }),
    cmds.selfcheck({ pr: 1 }),
  ];
  for (const c of mutating) {
    assert.equal(c.mutating, true, c.argv.join(' '));
    assert.match(remoteLine(c), new RegExp(`^test -f ${HOST_MARKER} && `));
  }
  for (const c of [cmds.listProjects(), cmds.listVolumes(), cmds.diskFree(), cmds.manifestInspect('abcdef0')]) {
    assert.equal(c.mutating, false);
  }
});

test('the project, hostname and env file are derived from the PR number alone', () => {
  assert.equal(projectForPr(42), 'saiku-oss-pr-42');
  assert.equal(hostLabelForPr(42), 'oss-pr-42');
  assert.equal(envFileFor('saiku-oss-pr-42'), `${ENV_DIR}/saiku-oss-pr-42.env`);
  for (const bad of [0, -1, 1.5, '1', null, 1e10]) {
    assert.throws(() => projectForPr(bad), /invalid PR number/);
    assert.throws(() => hostLabelForPr(bad), /invalid PR number/);
  }
});

test('render passes validated flags, targets the OSS state dir and refuses malformed input', () => {
  const { argv } = cmds.render({ pr: 9, sha: sha('b'), tag: 'bbbbbbb', baseDomain: 'preview.saiku.bi' });
  assert.deepEqual(argv, [
    `${SRC_DIR}/infra/preview/render-env.sh`,
    '9',
    '--sha',
    sha('b'),
    '--image-tag',
    'bbbbbbb',
    '--base-domain',
    'preview.saiku.bi',
    '--out',
    `${ENV_DIR}/saiku-oss-pr-9.env`,
  ]);
  const ok = { pr: 9, sha: sha('b'), tag: 'bbbbbbb', baseDomain: 'preview.saiku.bi' };
  for (const over of [
    { pr: 0 },
    { pr: '9' },
    { sha: 'abc' },
    { sha: `${sha('b')};id` },
    { tag: 'develop' },
    { tag: 'latest' },
    { tag: 'BBBBBBB' },
    { tag: 'bbbbbbbb' },
    { baseDomain: 'x; id' },
    { baseDomain: 'localhost' },
    { baseDomain: '$(id).example.com' },
    { baseDomain: undefined },
  ]) {
    assert.throws(() => cmds.render({ ...ok, ...over }), undefined, JSON.stringify(over));
  }
});

test('assertBaseDomain takes plain DNS names only', () => {
  for (const good of ['preview.saiku.bi', 'a-b.example.co.uk', 'x1.y2']) assert.equal(assertBaseDomain(good), good);
  for (const bad of ['', 'nodots', 'UPPER.example.com', '-a.example.com', 'a..b', 'a b.example.com', 'a.example.com/', 'a.b\n', 5, null]) {
    assert.throws(() => assertBaseDomain(bad), /invalid preview base domain/, String(bad));
  }
});

test('composeUp and selfcheck are bounded by timeout and address exactly one OSS project', () => {
  for (const c of [cmds.composeUp({ pr: 3 }), cmds.selfcheck({ pr: 3 })]) {
    assert.deepEqual(c.argv.slice(0, 2), ['timeout', '--kill-after=10']);
    assert.match(c.argv[2], /^\d+$/);
    assert.equal(c.argv[c.argv.indexOf('-p') + 1], 'saiku-oss-pr-3');
    assert.equal(c.argv[c.argv.indexOf('--env-file') + 1], `${ENV_DIR}/saiku-oss-pr-3.env`);
    assert.deepEqual(c.argv.filter((a) => a === '-f').length, 1, 'only the OSS compose file is layered');
    assert.ok(c.argv.includes(`${SRC_DIR}/infra/preview/docker-compose.preview.yml`));
  }
  const up = cmds.composeUp({ pr: 3 }).argv;
  assert.ok(up.includes('--wait') && up.includes('--wait-timeout'));
  assert.equal(cmds.selfcheck({ pr: 3 }).argv.at(-1), 'preview-selfcheck');
  assert.throws(() => cmds.composeUp({ pr: 0 }));
  assert.throws(() => cmds.selfcheck({ pr: 'x' }));
});

test('manifestInspect is a read-only inspect of a validated saiku reference', () => {
  const { argv, mutating } = cmds.manifestInspect('abcdef0');
  assert.deepEqual(argv, ['docker', 'manifest', 'inspect', 'ghcr.io/spiculedata/saiku:abcdef0']);
  assert.equal(mutating, false);
  for (const bad of ['develop', 'latest', 'pr-5', 'abcdef', 'abcdef01', '$(id)', '', undefined]) {
    assert.throws(() => cmds.manifestInspect(bad), /invalid image tag/);
  }
});

test('readCreds greps ONLY the allow-listed keys out of the one env file, read only', () => {
  assert.deepEqual(CREDENTIAL_KEYS, ['ORIGIN', 'PREVIEW_ADMIN_USER', 'SAIKU_ADMIN_PASSWORD']);
  const { argv, mutating, marker } = cmds.readCreds({ pr: 8 });
  assert.deepEqual(argv, ['grep', '-E', '^(ORIGIN|PREVIEW_ADMIN_USER|SAIKU_ADMIN_PASSWORD)=', `${ENV_DIR}/saiku-oss-pr-8.env`]);
  assert.equal(mutating, false);
  assert.equal(marker, true);
});

test('shellQuote round-trips hostile strings through a real shell as one literal word', () => {
  const hostile = ["a b", "it's", '$(touch /tmp/pwned-x)', '`id`', 'a;b', 'a\nb', '*', '', '"q"', '\\'];
  for (const word of hostile) {
    const out = execFileSync('sh', ['-c', `printf %s ${shellQuote(word)}`], { encoding: 'utf8' });
    assert.equal(out, word);
  }
  assert.equal(shellQuote('plain-word_1.2/3'), 'plain-word_1.2/3');
});
