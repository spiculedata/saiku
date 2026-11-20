// The acceptance spec for spiculedata/saiku#2442, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2442`.
//
// #2442 asks for unit tests: a dropped probe is released by the cleaner, the registrar seam's
// default scope stays thread-local, and two overlapping Mondrian-scoped probes keep only their
// own statement's SQL. These are JUnit behaviours, so the contract is asserted in the source of
// the tests themselves (run by `mvn verify`); the Throwable nit is asserted in the probe source.
// Exit non-zero on the first broken clause, with the reason on stderr.

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const probePath = join(
  here,
  '..',
  '..',
  'saiku-core',
  'saiku-service',
  'src',
  'main',
  'java',
  'org',
  'saiku',
  'service',
  'olap',
  'ai',
  'explain',
  'SqlCaptureProbe.java',
);
const probeTestPath = join(
  here,
  '..',
  '..',
  'saiku-core',
  'saiku-service',
  'src',
  'test',
  'java',
  'org',
  'saiku',
  'service',
  'olap',
  'ai',
  'explain',
  'SqlCaptureProbeTest.java',
);
const scopeTestPath = join(
  here,
  '..',
  '..',
  'saiku-core',
  'saiku-service',
  'src',
  'test',
  'java',
  'org',
  'saiku',
  'service',
  'olap',
  'ai',
  'explain',
  'MondrianStatementScopeTest.java',
);
const floorsPath = join(here, '..', '..', '.github', 'test-floors.json');

const probe = readFileSync(probePath, 'utf8');
const probeTests = readFileSync(probeTestPath, 'utf8');
const scopeTests = readFileSync(scopeTestPath, 'utf8');
const floors = readFileSync(floorsPath, 'utf8');

const failures = [];
const claim = (id, fn) => {
  try {
    fn();
  } catch (err) {
    failures.push(`${id}: ${err.message}`);
  }
};

// C1 — the dropped-probe release test: opens, drops, GCs in a bounded loop, asserts the count
// returns to baseline and the logger state is restored.
claim('C1', () => {
  assert.match(
    probeTests,
    /aProbeDroppedWithoutCloseIsReleasedAndItsLevelIsRestored/,
    'SqlCaptureProbeTest has aProbeDroppedWithoutCloseIsReleasedAndItsLevelIsRestored',
  );
  const dropped = probeTests.match(/aProbeDroppedWithoutCloseIsReleasedAndItsLevelIsRestored[\s\S]{0,2600}/);
  assert.ok(dropped, 'the dropped-probe test body was found');
  assert.match(dropped[0], /openAndDropProbe\(\)|SqlCaptureProbe\.open\(\)/, 'the test opens a probe the caller drops');
  assert.match(dropped[0], /activeProbeCount\(\)/, 'the test asserts activeProbeCount()');
  assert.match(dropped[0], /System\.gc\(\)/, 'the test forces GC in a bounded loop');
  assert.match(dropped[0], /Level\.WARN|sqlLoggerLevel\(\)/, 'the test asserts the configured level is restored');
  assert.match(
    dropped[0],
    /saiku-sql-capture/,
    'the test asserts no saiku-sql-capture appender remains after the drop',
  );
});

// C2 — the registrar seam's default scope stays thread-local.
claim('C2', () => {
  assert.match(
    probeTests,
    /theRegistrarSeamKeepsTheDefaultThreadScope/,
    'SqlCaptureProbeTest has theRegistrarSeamKeepsTheDefaultThreadScope',
  );
  const seam = probeTests.match(/theRegistrarSeamKeepsTheDefaultThreadScope[\s\S]{0,1200}/);
  assert.ok(seam, 'the registrar-seam test body was found');
  assert.match(
    seam[0],
    /SqlCaptureProbe\.open\(\s*\d+\s*,/,
    'the test opens a probe through the package-private registrar seam',
  );
  assert.match(
    seam[0],
    /new Thread/,
    'the test logs a statement on another thread that must not be captured',
  );
});

// C3 — two overlapping Mondrian-scoped probes each keep only their own statement's SQL.
claim('C3', () => {
  assert.match(
    scopeTests,
    /overlappingProbesEachKeepOnlyTheirOwnStatementsSql/,
    'MondrianStatementScopeTest has overlappingProbesEachKeepOnlyTheirOwnStatementsSql',
  );
  const overlap = scopeTests.match(/overlappingProbesEachKeepOnlyTheirOwnStatementsSql[\s\S]{0,1600}/);
  assert.ok(overlap, 'the overlapping-probes test body was found');
  assert.match(overlap[0], /SqlCaptureProbe\.open/, 'the test opens two probes');
  assert.match(overlap[0], /MondrianStatementScope/, 'the probes use MondrianStatementScope');
  assert.match(overlap[0], /Locus/, 'each statement is logged under its own Locus');
  assert.match(overlap[0], /statements\(\)/, 'the test asserts each probe kept only its own SQL');
});

// C4 — the nit: ownsCurrentEvent catches Throwable, and the test floor covers the new tests.
claim('C4', () => {
  const owns = probe.match(/ownsCurrentEvent\(\)\s*\{[\s\S]{0,400}/);
  assert.ok(owns, 'ownsCurrentEvent() was found in SqlCaptureProbe');
  assert.match(owns[0], /catch\s*\(\s*Throwable/, 'ownsCurrentEvent() catches Throwable, not just RuntimeException');
  assert.doesNotMatch(owns[0], /catch\s*\(\s*RuntimeException/, 'the narrower RuntimeException catch is gone');
  assert.match(
    floors,
    /"saiku-core\/saiku-service"\s*:\s*58\d/,
    'the saiku-service test-count floor is raised to cover the new tests',
  );
});

if (failures.length > 0) {
  process.stderr.write(`${failures.join('\n')}\n`);
  process.exit(1);
}
process.stdout.write('acceptance/2442: dropped-probe release, registrar-seam scope and overlapping Mondrian probes are pinned by tests\n');
