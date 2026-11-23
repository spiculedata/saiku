/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */

/**
 * Tests for scripts/check-secrets.sh, the local pre-commit secret-shape hook
 * (saiku#2441). The hook's placeholder allowance must be scoped to the matched
 * credential value: a marker elsewhere on the line — a `process.env` reference
 * in the same statement, a trailing "test fixture" comment — used to exempt
 * the whole line and let a live-shaped value through. Every token-shaped
 * fixture here is generated at run time so the hook under test stays green on
 * the very file that tests it.
 */

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, '..', '..');
const hook = join(repoRoot, 'scripts', 'check-secrets.sh');

/** Marker words a value may carry and still read as a placeholder (the hook's PLACEHOLDER regex). */
const MARKERS = ['your', 'example', 'sample', 'placeholder', 'dummy', 'fake', 'test', 'xxxx'];
const looksPlaceholder = (s) => MARKERS.some((m) => s.toLowerCase().includes(m));

/** A synthetic GitHub-token-shaped value matching gh[pousr]_[A-Za-z0-9]{36,}, carrying no marker. */
function synthToken() {
  for (;;) {
    const body = Array.from(randomBytes(36), (b) => 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'[b % 62]).join('');
    if (!looksPlaceholder(body)) return `ghp_${body}`;
  }
}

/** Run the hook in path mode over one fixture written to a throwaway dir. */
function scan(content) {
  const dir = mkdtempSync(join(tmpdir(), 'check-secrets-test-'));
  const file = join(dir, 'fixture.txt'); // a plain name: no *.example exemption
  writeFileSync(file, content);
  try {
    return spawnSync('bash', [hook, file], { cwd: repoRoot, encoding: 'utf8' });
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

/** Run the hook in --staged mode over a fixture staged in a throwaway git repo. */
function scanStaged(content) {
  const dir = mkdtempSync(join(tmpdir(), 'check-secrets-staged-'));
  writeFileSync(join(dir, 'fixture.txt'), content);
  spawnSync('git', ['init', '-q'], { cwd: dir, encoding: 'utf8' });
  spawnSync('git', ['add', 'fixture.txt'], { cwd: dir, encoding: 'utf8' });
  try {
    return spawnSync('bash', [hook, '--staged'], { cwd: dir, encoding: 'utf8' });
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test('a bare credential-shaped value is rejected', () => {
  const run = scan(`${synthToken()}\n`);
  assert.equal(run.status, 1, run.stderr);
  assert.match(run.stderr, /GitHub token/);
});

test('saiku#2441: a credential-shaped value sharing a line with process.env is rejected, not exempted', () => {
  const run = scan(`const fallback = process.env.FALLBACK; const token = '${synthToken()}';\n`);
  assert.equal(run.status, 1, `expected rejection, got exit ${run.status}: ${run.stderr}`);
  assert.match(run.stderr, /GitHub token/);
});

test('saiku#2441: a trailing placeholder-word comment no longer exempts the value on the line', () => {
  const run = scan(`rotateme = '${synthToken()}'  # test fixture, replace before shipping\n`);
  assert.equal(run.status, 1, `expected rejection, got exit ${run.status}: ${run.stderr}`);
});

test('a value that is itself a placeholder keeps the documented allowance', () => {
  assert.equal(scan(`const token = 'ghp_${'x'.repeat(40)}';\n`).status, 0, 'xxxx padding in the value');
  assert.equal(scan(`aws_access_key_id = AKIAIOSFODNN7EXAMPLE  # docs example\n`).status, 0, 'the AWS documentation example key');
  assert.equal(scan(`SAIKU_EMBED_JWT_SECRET=your_jwt_secret_here_${'x'.repeat(36)}\n`).status, 0, 'a your_… JWT secret');
});

test('two values on one line are judged separately: a placeholder does not shield a live-shaped neighbour', () => {
  const run = scan(`placeholder 'ghp_${'x'.repeat(40)}' then '${synthToken()}'\n`);
  assert.equal(run.status, 1, `expected rejection, got exit ${run.status}: ${run.stderr}`);
});

test('a clean file exits 0', () => {
  assert.equal(scan('const greeting = "hello world";\n').status, 0);
});

test('--staged mode judges added lines the same way', () => {
  const rejected = scanStaged(`const fallback = process.env.FALLBACK; const token = '${synthToken()}';\n`);
  assert.equal(rejected.status, 1, rejected.stderr);

  const allowed = scanStaged(`const token = 'ghp_${'x'.repeat(40)}'; // placeholder\n`);
  assert.equal(allowed.status, 0, allowed.stderr);
});
