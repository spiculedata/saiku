/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */

/** Image tag grammar: the tag the lifecycle asks for is the one docker.yml publishes. */

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

import { IMAGE_SOURCE_LABEL } from './preview-guard.mjs';

import {
  IMAGE_BUILD_PATHS,
  IMAGE_REGISTRY,
  IMAGE_REPOSITORY,
  SHA_TAG_LENGTH,
  assertImageTag,
  buildsImage,
  describeImage,
  imageRef,
  prImageTag,
} from './preview-images.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

test('the per-SHA tag is the first 7 hex of the head sha and nothing else is accepted', () => {
  assert.equal(prImageTag('0123456789abcdef0123456789abcdef01234567'), '0123456');
  for (const bad of ['abc', 'g'.repeat(40), 'A'.repeat(40), `${'a'.repeat(40)}\n`, 'a'.repeat(39), null, undefined, 5]) {
    assert.throws(() => prImageTag(bad), /invalid commit sha/, String(bad));
  }
});

test('assertImageTag accepts only 7 lower-case hex characters (no develop, latest or pr-<n>)', () => {
  assert.equal(assertImageTag('abcdef0'), 'abcdef0');
  for (const bad of ['develop', 'latest', 'pr-12', 'abcdef', 'abcdef01', 'ABCDEF0', 'abcdef0;id', '$(id)', '', null]) {
    assert.throws(() => assertImageTag(bad), /invalid image tag/, String(bad));
  }
});

test('imageRef is built only from the registry constant, the repository constant and a validated tag', () => {
  assert.equal(imageRef('abcdef0'), 'ghcr.io/spiculedata/saiku:abcdef0');
  assert.equal(IMAGE_REGISTRY, 'ghcr.io/spiculedata');
  assert.equal(IMAGE_REPOSITORY, 'saiku');
  assert.throws(() => imageRef('develop'));
  assert.throws(() => imageRef('a/../b'));
});

test('describeImage drops anything outside the grammar instead of repairing it', () => {
  assert.deepEqual(describeImage({ tag: 'abcdef0', extra: 'x' }), { tag: 'abcdef0' });
  for (const bad of [null, undefined, 'abcdef0', {}, { tag: 'develop' }, { tag: 'abcdef0;id' }]) {
    assert.equal(describeImage(bad), null);
  }
});

test('SHA_TAG_LENGTH is the length docker.yml tags PR head builds with (${IMAGE_SHA::N})', () => {
  const workflow = readFileSync(join(root, '.github', 'workflows', 'docker.yml'), 'utf8');
  const m = /short_sha=\$\{IMAGE_SHA::(\d+)\}/.exec(workflow);
  assert.ok(m, 'docker.yml no longer derives a short_sha from IMAGE_SHA: update preview-images.mjs and this test');
  assert.equal(Number(m[1]), SHA_TAG_LENGTH);
  // The tag is the PR HEAD sha, not the merge commit GITHUB_SHA points at on pull_request events.
  // (A /preview-dispatched build of a PR head resolves it in the `target` job and tags it the same way.)
  assert.match(
    workflow,
    /IMAGE_SHA: \$\{\{ needs\.target\.outputs\.sha \|\| github\.event\.pull_request\.head\.sha \|\| github\.sha \}\}/,
  );
  assert.match(
    workflow,
    /type=raw,value=\$\{\{ steps\.publish\.outputs\.short_sha \}\},enable=\$\{\{ github\.event_name == 'pull_request' \|\| needs\.target\.outputs\.pr != '' \}\}/,
  );
});

test('the image prune label is one docker.yml stamps on every image (docker/metadata-action labels)', () => {
  const workflow = readFileSync(join(root, '.github', 'workflows', 'docker.yml'), 'utf8');
  assert.match(workflow, /labels: \$\{\{ steps\.meta\.outputs\.labels \}\}/);
  // metadata-action sets org.opencontainers.image.source from the repository URL.
  assert.equal(IMAGE_SOURCE_LABEL, 'org.opencontainers.image.source=https://github.com/spiculedata/saiku');
});

test('IMAGE_BUILD_PATHS are exactly the paths docker.yml builds a PR image for', () => {
  const workflow = readFileSync(join(root, '.github', 'workflows', 'docker.yml'), 'utf8');
  const block = /pull_request:\n(?:.*\n)*?\s+paths:\n((?:\s+- .+\n)+)/.exec(workflow)?.[1] ?? '';
  const paths = block.split('\n').map((l) => l.replace(/^\s+- /, '').replace(/^"|"$/g, '')).filter(Boolean);
  assert.deepEqual(paths, [...IMAGE_BUILD_PATHS], 'docker.yml pull_request.paths drifted: update IMAGE_BUILD_PATHS');
});

test('buildsImage matches what the docker workflow would build, and nothing else', () => {
  for (const yes of [
    'pom.xml',
    'saiku-core/saiku-service/pom.xml',
    'saiku-ui/src/app.css',
    'saiku-webapp/src/main/webapp/WEB-INF/x.xml',
    'lib/repo/x.jar',
    'Dockerfile',
    'docker/saiku-entrypoint',
    '.github/workflows/docker.yml',
  ]) assert.equal(buildsImage([yes]), true, yes);
  for (const no of [
    'README.md',
    'docs/ci-images.md',
    '.github/workflows/ci.yml',
    '.github/scripts/preview-ctl.mjs',
    'infra/preview/render-env.sh',
    'scripts/pr-metrics.mjs',
    'saiku/x',
    'saiku-ui',
    'saikuX/y',
    'library/x',
    'Dockerfile.dev',
    'docker',
    'xdocker/x',
    'tests/pom.xml.bak',
  ]) assert.equal(buildsImage([no]), false, no);
  assert.equal(buildsImage(['README.md', 'saiku-core/x']), true);
  assert.equal(buildsImage([]), false);
  assert.equal(buildsImage(null), false);
  assert.equal(buildsImage([5, null, {}]), false);
});
