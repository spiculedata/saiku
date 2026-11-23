/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */

/** Credential hand-off rules: mask first, write only to files, validate everything. */

import assert from 'node:assert/strict';
import { test } from 'node:test';

import { SECRET_KEYS, deliverCredentials, parseCredentials, redactSecrets, toOutputs } from './preview-creds.mjs';
import { CREDENTIAL_KEYS } from './preview-guard.mjs';

const VALID = {
  ORIGIN: 'https://oss-pr-5.preview.saiku.bi',
  PREVIEW_ADMIN_USER: 'admin',
  SAIKU_ADMIN_PASSWORD: `prevpw_${'ab'.repeat(20)}`,
};
const text = (o) => Object.entries(o).map(([k, v]) => `${k}=${v}`).join('\n');

test('parseCredentials accepts exactly the allow-list', () => {
  assert.deepEqual(parseCredentials(text(VALID)), VALID);
  assert.deepEqual(Object.keys(VALID).sort(), [...CREDENTIAL_KEYS].sort());
});

test('parseCredentials refuses unknown keys, missing keys and unsafe values, without echoing the value', () => {
  assert.throws(() => parseCredentials(`${text(VALID)}\nSOMETHING_ELSE=hunter2hunter2`), /unexpected line/);
  const { SAIKU_ADMIN_PASSWORD: _drop, ...partial } = VALID;
  assert.throws(() => parseCredentials(text(partial)), /lacks: SAIKU_ADMIN_PASSWORD/);
  for (const bad of ['a b', 'x;id', '$(id)', '', "a'b", 'x'.repeat(300)]) {
    try {
      parseCredentials(text({ ...VALID, SAIKU_ADMIN_PASSWORD: bad }));
      assert.fail(`accepted ${JSON.stringify(bad)}`);
    } catch (err) {
      assert.match(err.message, /SAIKU_ADMIN_PASSWORD has an unexpected shape|lacks/);
      assert.ok(!err.message.includes('hunter2'));
    }
  }
});

test('toOutputs names the base URL, the admin user and the admin password', () => {
  assert.deepEqual(toOutputs(VALID), {
    PREVIEW_BASE_URL: 'https://oss-pr-5.preview.saiku.bi',
    PREVIEW_ADMIN_USER: 'admin',
    PREVIEW_ADMIN_PASSWORD: VALID.SAIKU_ADMIN_PASSWORD,
  });
});

test('every secret is masked BEFORE any file is written, and only masks are printed', () => {
  const events = [];
  const fs = {
    writeFileSync: (f, _body, opts) => events.push(`write:${f}:${opts.mode.toString(8)}`),
    chmodSync: (_f, mode) => events.push(`chmod:${mode.toString(8)}`),
    appendFileSync: (f) => events.push(`append:${f}`),
  };
  const printed = [];
  deliverCredentials(VALID, {
    outFile: '/o/creds.env',
    githubOutputFile: '/o/gh',
    io: { out: (t) => { events.push('print'); printed.push(t); } },
    fs,
  });
  const masks = SECRET_KEYS.length;
  assert.deepEqual(events.slice(0, masks), Array.from({ length: masks }, () => 'print'));
  assert.deepEqual(events.slice(masks), ['write:/o/creds.env:600', 'chmod:600', 'append:/o/gh']);
  for (const line of printed) assert.match(line, /^::add-mask::\S+\n$/);
  assert.deepEqual(printed.map((l) => l.slice('::add-mask::'.length).trim()), SECRET_KEYS.map((k) => VALID[k]));
});

test('with nowhere to write, nothing is printed at all', () => {
  const printed = [];
  assert.throws(() => deliverCredentials(VALID, { io: { out: (t) => printed.push(t) } }), /never prints/);
  assert.deepEqual(printed, []);
});

test('redactSecrets masks password-shaped strings in anything logged from the host', () => {
  assert.equal(redactSecrets(`login with ${VALID.SAIKU_ADMIN_PASSWORD} failed`), 'login with *** failed');
  assert.equal(redactSecrets('SAIKU_ADMIN_PASSWORD=whatever-it-is next'), 'SAIKU_ADMIN_PASSWORD=*** next');
});
