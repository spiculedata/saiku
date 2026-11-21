// Regression guard for the interim CVE-2026-114938 mitigation: every JVM that Saiku's own
// launch paths start (the dist scripts) must carry -XX:-UseBASE64Intrinsics until the JRE underneath is fixed.
// The flag lives in the dist zip's launch scripts, dist/run.sh and dist/run.bat, and each is a
// one-token edit away from silently disappearing. The Docker image no longer carries it: its
// Chainguard JRE base is OpenJDK 21.0.13.1, which includes the fix (saiku#2462).
//
// EXPIRY. The dist script tests may stay as long as the scripts keep the flag (the JRE there
// is the user's own).
//
// Hermetic: no network, no clock. run.sh is executed with a fake `java`
// first on PATH that prints its arguments, so the assertions are on the argv a real start
// would get, not on the script text.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, dirname, join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const FLAG = '-XX:-UseBASE64Intrinsics';
const WHY = `${FLAG} is the interim mitigation for CVE-2026-114938; remove it only together with this test, once the JRE is fixed`;
const needsBash = process.platform === 'win32' ? 'needs bash (CI runs on ubuntu-latest)' : false;

/** The flag must be present, and (when there is a -jar) must come before it to be a JVM option. */
function assertFlagBeforeJar(args, where) {
  const flag = args.indexOf(FLAG);
  assert.ok(flag >= 0, `${where}: ${FLAG} missing from JVM arguments [${args.join(' ')}]. ${WHY}`);
  const jar = args.indexOf('-jar');
  if (jar >= 0) assert.ok(flag < jar, `${where}: ${FLAG} must precede -jar to reach the JVM. ${WHY}`);
}

/** A temp dir holding a fake `java` that prints one argument per line and exits 0. */
function withFakeJava(fn) {
  const dir = mkdtempSync(join(tmpdir(), 'jvm-flag-guard-'));
  try {
    const bin = join(dir, 'bin');
    mkdirSync(bin);
    writeFileSync(join(bin, 'java'), '#!/usr/bin/env bash\nprintf \'%s\\n\' "$@"\n', { mode: 0o755 });
    return fn(dir, bin);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

function runWithFakeJava(script, args, bin, extraEnv) {
  const env = { ...process.env, PATH: `${bin}${delimiter}${process.env.PATH}`, ...extraEnv };
  delete env.JAVA_OPTS;
  if (extraEnv.JAVA_OPTS !== undefined) env.JAVA_OPTS = extraEnv.JAVA_OPTS;
  const res = spawnSync('bash', [script, ...args], { env, encoding: 'utf8' });
  assert.equal(res.status, 0, `${script} exited ${res.status}: ${res.stderr}`);
  return res.stdout.split('\n').filter((l) => l !== '');
}

test('dist/run.sh: the JVM gets the flag, even when the caller sets JAVA_OPTS', { skip: needsBash }, () => {
  withFakeJava((dir, bin) => {
    const script = join(dir, 'run.sh');
    copyFileSync(join(root, 'dist', 'run.sh'), script);
    writeFileSync(join(dir, 'saiku-0.0.0.jar'), '');
    const args = runWithFakeJava(script, ['--port', '8080'], bin, { JAVA_OPTS: '-Xmx1g' });
    assert.ok(args.includes(join(dir, 'saiku-0.0.0.jar')), `run.sh started the jar: [${args.join(' ')}]`);
    assertFlagBeforeJar(args, 'dist/run.sh');
  });
});

test('dist/run.bat: the java command line carries the flag', () => {
  // cmd.exe cannot run on the CI runner, so this one reads the script: the non-comment line
  // that starts the JVM.
  const starts = readFileSync(join(root, 'dist', 'run.bat'), 'utf8')
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => !/^(rem\b|::|@)/i.test(l))
    .filter((l) => /^java\s/i.test(l));
  assert.equal(starts.length, 1, `exactly one java start line in dist/run.bat, found ${starts.length}`);
  assertFlagBeforeJar(starts[0].split(/\s+/), 'dist/run.bat');
});
