// Hermetic tests for the CI log analysis. `node --test .github/scripts/`
// Ported from spiculedata/saiku-cloud (#1374); the fixtures are this repository's own.
// Real runner logs live in fixtures/ci-feedback/*.txt (a `.log` suffix is git-ignored):
//   * maven-compile-error, surefire-test-error, prettier-failure: trimmed excerpts of
//     failing runs of the `ci` workflow in spiculedata/saiku (run/job ids in the PR text),
//   * spotless-violation, licence-header, eslint-failure, vitest-failure,
//     svelte-check-failure, playwright-failure: the genuine output of each tool for a
//     deliberately broken input, captured locally and wrapped in the runner's envelope
//     (timestamps, `##[group]Run`, exit-code trailer).
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

import {
  failedStepRange,
  findFirstRealError,
  findPreviewUrl,
  findTestIds,
  isNoiseLine,
  isPreviewUrl,
  noiseSamples,
  redact,
  toLines
} from './ci-feedback-log.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const fixture = (name) => readFileSync(join(here, 'fixtures', 'ci-feedback', name), 'utf8');

const DEPRECATION = /Node\.js 20 is deprecated|forced to run on Node\.js|setup-java v4 is deprecated|DeprecationWarning|trace-deprecation/;

/** The notices that misled the worker, injected around a real log: at the top and inside the failing step. */
const DEPRECATION_NOTICES = [
  '##[warning]Node.js 20 is deprecated. The following actions target Node.js 20 but are being forced to run on Node.js 24: actions/checkout@v4.',
  '##[warning]setup-java v4 is deprecated and will no longer receive updates.'
];
const STEP_NOTICES = [
  '(node:2007) [DEP0040] DeprecationWarning: The `punycode` module is deprecated.',
  '(Use `node --trace-deprecation ...` to show where the warning was created)'
];
function withNotices(log) {
  const lines = log.split('\n');
  const at = lines.findIndex((line) => /##\[endgroup\]/.test(line) && lines.slice(0, lines.indexOf(line)).some((l) => /##\[group\]Run /.test(l)));
  return [...DEPRECATION_NOTICES, ...lines.slice(0, at + 1), ...STEP_NOTICES, ...lines.slice(at + 1)].join('\n');
}

const REAL_LOGS = [
  {
    file: 'maven-compile-error.txt',
    error: /^\[ERROR\] COMPILATION ERROR/,
    excerpt: /SaikuLauncher\.java:\[365,27\] cannot find symbol/,
    ids: []
  },
  {
    file: 'surefire-test-error.txt',
    error: /^\[ERROR\] Tests run: 8, Failures: 0, Errors: 2/,
    excerpt: /SaikuOlapConnectionLocationTest\.xmlaLocationStillGetsThePropertyTerminator -- Time elapsed: .* <<< ERROR!/,
    ids: [
      'org.saiku.datasources.connection.SaikuOlapConnectionLocationTest.xmlaLocationStillGetsThePropertyTerminator',
      'SaikuOlapConnectionLocationTest.mondrianLocationStillGetsThePropertyTerminator'
    ],
    // passing tests print WARN/INFO application logging; it must not win
    notIn: /Standard Commons Logging discovery/
  },
  {
    file: 'spotless-violation.txt',
    error: /^\[ERROR\] Failed to execute goal com\.diffplug\.spotless:spotless-maven-plugin:[\d.]+:check .* The following files had format violations:/,
    excerpt: /BadFormat\.java/,
    ids: []
  },
  {
    file: 'licence-header.txt',
    error: /^ERROR: 1 Java file\(s\) have no licence header:/,
    excerpt: /NoHeader\.java/,
    ids: []
  },
  {
    file: 'prettier-failure.txt',
    error: /^\[warn\] src\/lib\/cube-designer\/mondrian-import\.test\.ts/,
    // the offending file is listed BEFORE the summary line, so the excerpt reaches back for it
    excerpt: /Code style issues found in the above file/,
    ids: [],
    notIn: /Unknown cli config/
  },
  {
    file: 'eslint-failure.txt',
    error: /^\/home\/runner\/work\/saiku\/saiku\/saiku-ui\/src\/lib\/zzLint\.ts/,
    excerpt: /2:2 +error +Unexpected 'debugger' statement/,
    ids: []
  },
  {
    file: 'svelte-check-failure.txt',
    error: /^\d{10,} ERROR "src\/lib\/ZzBad\.svelte" 2:6 "Type 'string' is not assignable to type 'number'\."/,
    ids: [],
    // hundreds of pre-existing WARNING lines precede the single ERROR
    notIn: / WARNING "/
  },
  {
    file: 'vitest-failure.txt',
    error: /^FAIL +src\/lib\/zzFlakeFixture\.test\.ts > formatResetCountdown > renders days for a far-future reset/,
    excerpt: /AssertionError: expected 'in 4 days' to be 'in 5 days'/,
    ids: ['src/lib/zzFlakeFixture.test.ts > formatResetCountdown > renders days for a far-future reset']
  },
  {
    file: 'playwright-failure.txt',
    error: /^1\) \[chromium\] › sample\.spec\.mjs:11:2 › Workbench measures › always fails/,
    excerpt: /expect\(locator\)\.toBeVisible\(\) failed/,
    ids: ['[chromium] › sample.spec.mjs:11:2 › Workbench measures › always fails', '[chromium] › sample.spec.mjs:7:2 › Workbench measures › flaky: fails first attempt only']
  }
];

for (const real of REAL_LOGS) {
  for (const [label, text] of [
    ['as captured', fixture(real.file)],
    ['with runtime deprecation notices injected', withNotices(fixture(real.file))]
  ]) {
    test(`real log ${real.file} (${label}): surfaces the real error, not the deprecation notice`, () => {
      const lines = toLines(text);
      const found = findFirstRealError(lines);
      assert.ok(found, 'expected an error');
      assert.equal(found.scope, 'failed-step');
      assert.match(found.excerpt.split('\n')[0], real.error);
      if (real.excerpt) assert.match(found.excerpt, real.excerpt);
      assert.doesNotMatch(found.excerpt, DEPRECATION);
      if (real.notIn) assert.doesNotMatch(found.excerpt, real.notIn);
      assert.deepEqual(findTestIds(lines).slice(0, real.ids.length), real.ids);
    });
  }
}

test('injected deprecation notices are filtered but stay auditable', () => {
  const samples = noiseSamples(toLines(withNotices(fixture('maven-compile-error.txt'))));
  assert.ok(samples.length > 0);
  assert.ok(samples.every((sample) => DEPRECATION.test(sample)));
});

test('the flake verdict block is read as failing test ids and as the first real error', () => {
  const log = [
    '##[group]Run node .github/scripts/flake-ledger.mjs record',
    '##[endgroup]',
    '### Flake report',
    'FAILING TEST: org.saiku.web.FooTest#bar',
    '  expected 3 but was 4',
    'FAILING TEST: src/lib/a.test.ts#suite > case',
    '##[error]Tests failing:',
    '##[error]Process completed with exit code 1.'
  ].join('\n');
  const found = findFirstRealError(log);
  assert.match(found.excerpt.split('\n')[0], /^FAILING TEST: org\.saiku\.web\.FooTest#bar/);
  assert.deepEqual(findTestIds(log), ['org.saiku.web.FooTest#bar', 'src/lib/a.test.ts#suite > case']);
});

test('toLines strips the runner timestamp, ANSI colours, CR and caps line length', () => {
  const esc = String.fromCharCode(27);
  const lines = toLines(`2026-10-03T20:27:12.0148642Z ${esc}[31;1mFAIL${esc}[0m x\r\n2026-10-03T20:27:12Z plain\n${'a'.repeat(5000)}`);
  assert.equal(lines[0], 'FAIL x');
  assert.equal(lines[1], 'plain');
  assert.equal(lines[2].length, 1000);
});

test('isNoiseLine: deprecation family, trailers, docker chatter and app logging', () => {
  for (const line of [
    '##[warning]Node.js 20 is deprecated. The following actions target Node.js 20 but are being forced to run on Node.js 24: actions/checkout@v4.',
    '##[warning]setup-java v4 is deprecated and will no longer receive updates. Please migrate to actions/setup-java@v5.',
    'Node.js 20 actions are deprecated. Please update the following actions to use Node.js 24: actions/setup-java@v4',
    '(node:2007) [DEP0040] DeprecationWarning: The `punycode` module is deprecated.',
    '(Use `node --trace-deprecation ...` to show where the warning was created)',
    '##[error]Process completed with exit code 1.',
    "##[error]Unable to find image 'rhysd/actionlint:1.7.7' locally",
    '1791162867884 WARNING "src/lib/x.svelte" 80:35 "Unused CSS selector"',
    '1f3e46996e29: Pulling fs layer',
    '2026-10-03T02:38:24.313Z  WARN [req= tenant= api=] 2315 --- [main] x : Descriptor regenerate FAILED (tenant=1)',
    '',
    '   '
  ]) {
    assert.equal(isNoiseLine(line), true, line);
  }
  for (const line of [
    '[ERROR] /w/Foo.java:[42,17] cannot find symbol',
    'AssertionError: expected 3 to equal 4',
    '##[error]V4 is claimed by 2 migrations',
    'FAIL: test_x (mod.Case.test_x)'
  ]) {
    assert.equal(isNoiseLine(line), false, line);
  }
});

test('Hive scenario: the deprecation annotation comes first and the real error later', () => {
  const log = [
    '##[warning]Node.js 20 is deprecated. The following actions target Node.js 20 but are being forced to run on Node.js 24: actions/checkout@v4',
    '##[group]Run mvn test',
    '##[endgroup]',
    'Node.js 20 actions are deprecated. Please update the following actions to use Node.js 24: actions/setup-java@v4',
    '[ERROR] COMPILATION ERROR :',
    '[ERROR] /w/Foo.java:[42,17] cannot find symbol',
    '##[error]Process completed with exit code 1.'
  ].join('\n');
  const found = findFirstRealError(log);
  assert.match(found.excerpt, /cannot find symbol/);
  assert.doesNotMatch(found.excerpt, DEPRECATION);
});

test('negative control: a log that only holds deprecation noise has no error', () => {
  const log = [
    '##[warning]Node.js 20 is deprecated. The following actions target Node.js 20 but are being forced to run on Node.js 24: actions/checkout@v4',
    '##[warning]setup-java v4 is deprecated and will no longer receive updates.',
    '(node:1) [DEP0040] DeprecationWarning: The `punycode` module is deprecated.',
    'Node.js 20 actions are deprecated. Please update the following actions to use Node.js 24:',
    'actions/setup-node@v4'
  ].join('\n');
  assert.equal(findFirstRealError(log), null);
  assert.deepEqual(findTestIds(log), []);
});

test('negative control: empty, undefined and whitespace-only logs', () => {
  for (const log of ['', undefined, null, '\n\n  \n']) {
    assert.equal(findFirstRealError(log), null);
    assert.deepEqual(findTestIds(log), []);
    assert.deepEqual(noiseSamples(log), []);
  }
});

test('negative control: a huge log still finds the error at its end', { timeout: 60000 }, () => {
  const filler = 'x'.repeat(80);
  const body = Array.from({ length: 300000 }, (_, i) => `2026-10-03T20:27:12.0148642Z [INFO] line ${i} ${filler}`);
  body.push('2026-10-03T20:27:12Z [ERROR] COMPILATION ERROR :');
  body.push('2026-10-03T20:27:12Z [ERROR] /w/Foo.java:[1,1] boom');
  const found = findFirstRealError(body.join('\n'));
  assert.match(found.excerpt, /boom/);
});

test('negative control: one pathological 5 MB line cannot stall the regexes', { timeout: 30000 }, () => {
  // A stalled regex would hang until the test timeout above rather than assert on a clock.
  const found = findFirstRealError(`${'ERROR '.repeat(800000)}\n[ERROR] real\n`);
  assert.ok(found);
});

test('excerpts are capped in lines and characters', () => {
  const lines = ['AssertionError: first', ...Array.from({ length: 200 }, (_, i) => `    at frame${i} ${'y'.repeat(100)}`)];
  const found = findFirstRealError(lines.join('\n'));
  assert.ok(found.excerpt.split('\n').length <= 20);
  assert.ok(found.excerpt.length <= 2001);
});

test('failedStepRange scopes to the failing step output', () => {
  const lines = ['##[group]Run a', '##[endgroup]', 'a out', '##[group]Run b', 'cmd', '##[endgroup]', 'b out', '##[error]Process completed with exit code 2.'];
  assert.deepEqual(failedStepRange(lines), { start: 6, end: 7 });
  assert.equal(failedStepRange(['nothing']), null);
});

test('redact removes credentials from anything echoed into a comment', () => {
  const secrets = [
    'ghp_abcdefghijklmnopqrstuvwxyz0123456789',
    'github_pat_11ABCDEFG0123456789_abcdefghijklmnopqrstuvwxyz',
    'AKIAABCDEFGHIJKLMNOP',
    'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnopqrstu',
    'xoxb-1234567890-abcdefghij',
    'hunter2hunter2'
  ];
  const text = [
    `token ${secrets[0]}`,
    `pat ${secrets[1]}`,
    `aws ${secrets[2]}`,
    `jwt ${secrets[3]}`,
    `slack ${secrets[4]}`,
    `Authorization: Bearer abcdefghijklmnop123456`,
    `postgres://admin:${secrets[5]}@db.internal:5432/x`,
    `DB_PASSWORD=${secrets[5]}`,
    `api_key: "${secrets[5]}"`,
    '-----BEGIN RSA PRIVATE KEY-----\nMIIEow\n-----END RSA PRIVATE KEY-----'
  ].join('\n');
  const clean = redact(text);
  for (const secret of [...secrets, 'MIIEow', 'abcdefghijklmnop123456']) assert.ok(!clean.includes(secret), secret);
  assert.match(clean, /REDACTED/);
});

test('redaction is applied to excerpts and test ids', () => {
  const found = findFirstRealError('AssertionError: bad\nDB_PASSWORD=hunter2hunter2\n');
  assert.doesNotMatch(found.excerpt, /hunter2/);
  const ids = findTestIds('FAILED tests/test_x.py::test_token_ghp_abcdefghijklmnopqrstuvwxyz0123456789 - boom');
  assert.doesNotMatch(ids.join(''), /ghp_/);
});

test('findTestIds de-duplicates, caps and ignores noise', () => {
  const many = Array.from({ length: 60 }, (_, i) => `FAILED tests/t.py::test_${i} - boom`).join('\n');
  assert.equal(findTestIds(many).length, 25);
  const dup = 'FAILED tests/t.py::test_a\nFAILED tests/t.py::test_a\n##[warning]FAILED tests/t.py::test_warn';
  assert.deepEqual(findTestIds(dup), ['tests/t.py::test_a']);
});

test('findPreviewUrl accepts only this pull request on the preview domain', () => {
  const log = [
    'see https://evil.example/pr-7.preview.saiku.bi',
    'see https://pr-8.preview.saiku.bi/login',
    'ready at https://pr-7.preview.saiku.bi/login?x=1 now'
  ].join('\n');
  assert.equal(findPreviewUrl(log, 7), 'https://pr-7.preview.saiku.bi/login');
  assert.equal(findPreviewUrl(log, 9), null);
  assert.equal(findPreviewUrl(log, 'abc'), null);
  assert.equal(findPreviewUrl(log, null), null);
  assert.equal(isPreviewUrl('https://pr-7.preview.saiku.bi'), true);
  assert.equal(isPreviewUrl('https://pr-7.preview.saiku.bi.evil.example'), false);
  assert.equal(isPreviewUrl('http://pr-7.preview.saiku.bi'), false);
});
