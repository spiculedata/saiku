/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */

/**
 * Unit tests for `.github/scripts/flake-ledger.mjs` (ported from spiculedata/saiku-cloud#1375).
 *
 * Run them the same way CI does:
 *
 *   node --test .github/scripts/
 *
 * They use `node:test` + `node:assert` only, so no install step is needed on
 * either side. Every case here corresponds to a way the flake policy could
 * quietly do the wrong thing: excuse a real failure, call a stable test flaky,
 * keep an expired quarantine alive, or double-count a test in the ledger.
 */

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

import {
	buildLedger,
	buildRecord,
	collectFailures,
	collectOutcomes,
	collectRan,
	loadQuarantine,
	mavenTestSelector,
	parseJUnitFlaky,
	parseJUnitTestIds,
	parsePlaywrightJson,
	parseJUnitXml,
	readRecords,
	renderLedger,
	renderRunSummary,
	splitByQuarantine,
	splitSources,
	testId
} from './flake-ledger.mjs';

const SUREFIRE = `<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="org.saiku.web.MaskingTest" time="1.2" tests="3" errors="1" failures="1" skipped="0">
  <testcase name="masksTokens" classname="org.saiku.web.MaskingTest" time="0.01"/>
  <testcase name="budgetOn50Kb" classname="org.saiku.web.QueryTimingTest" time="0.9">
    <failure message="expected added &lt; 15ms but was 41ms" type="AssertionError">stack</failure>
  </testcase>
  <testcase name="resolvesSession" classname="org.saiku.web.SessionTest" time="0.2">
    <error message="connection refused" type="RuntimeException">stack</error>
  </testcase>
</testsuite>
`;

function tmpDir(prefix) {
	return mkdtempSync(join(tmpdir(), prefix));
}

function writeSurefire(dir, name = 'TEST-Pii.xml') {
	mkdirSync(dir, { recursive: true });
	const file = join(dir, name);
	writeFileSync(file, SUREFIRE);
	return dir;
}

test('test ids are classname#name and survive a moved file', () => {
	assert.equal(testId('org.saiku.web.MaskingTest', 'masksTokens'), 'org.saiku.web.MaskingTest#masksTokens');
	assert.equal(testId('', 'orphan'), 'orphan');
});

test('parses surefire failures and errors, skipping passing cases', () => {
	const failures = parseJUnitXml(SUREFIRE);
	assert.deepEqual(
		failures.map((f) => f.testId),
		[
			'org.saiku.web.QueryTimingTest#budgetOn50Kb',
			'org.saiku.web.SessionTest#resolvesSession'
		]
	);
	assert.equal(failures[0].kind, 'failure');
	assert.equal(failures[0].message, 'expected added < 15ms but was 41ms');
	assert.equal(failures[1].kind, 'error');
});

test('a vitest junit report parses the same way', () => {
	const junit = `<testsuites>
  <testsuite name="vitest" tests="2" failures="1">
    <testcase classname="src/lib/quota.test.ts" name="formats &gt; 1GB">
      <failure message="expected 1GB to equal 2GB">x</failure>
    </testcase>
    <testcase classname="src/lib/format.test.ts" name="rounds cents" time="0.1"/>
  </testsuite>
</testsuites>`;
	const failures = parseJUnitXml(junit);
	assert.equal(failures.length, 1);
	assert.equal(failures[0].testId, 'src/lib/quota.test.ts#formats > 1GB');
});

test('collectFailures walks a results directory and dedupes', () => {
	const dir = tmpDir('flake-surefire-');
	writeSurefire(dir);
	writeSurefire(join(dir, 'nested'), 'TEST-Pii-copy.xml');
	const failures = collectFailures(dir);
	assert.equal(failures.length, 2);
});

test('an expired quarantine entry does not excuse anything', () => {
	const dir = tmpDir('flake-q-');
	const file = join(dir, 'q.json');
	writeFileSync(
		file,
		JSON.stringify({
			version: 1,
			entries: [{ testId: 'a.B#c', owner: 'tom', expiresAt: '2020-01-01T00:00:00Z', reason: 'old' }]
		})
	);
	const quarantine = loadQuarantine(file, new Date('2026-10-03T00:00:00Z'));
	assert.equal(quarantine.active.length, 0);
	assert.equal(quarantine.expired.length, 1);
	const { blocking, excused } = splitByQuarantine([{ testId: 'a.B#c' }], quarantine);
	assert.equal(blocking.length, 1);
	assert.equal(excused.length, 0);
});

test('a quarantine entry without an owner is invalid and blocks', () => {
	const dir = tmpDir('flake-q2-');
	const file = join(dir, 'q.json');
	writeFileSync(
		file,
		JSON.stringify({
			version: 1,
			entries: [
				{ testId: 'a.B#c', expiresAt: '2099-01-01T00:00:00Z' },
				{ testId: 'a.B#d', owner: 'tom', expiresAt: '2099-01-01T00:00:00Z' }
			]
		})
	);
	const quarantine = loadQuarantine(file, new Date('2026-10-03T00:00:00Z'));
	assert.equal(quarantine.invalid.length, 1);
	assert.match(quarantine.invalid[0].reason, /no owner/);
	assert.equal(quarantine.active.length, 1);
	assert.ok(quarantine.active[0].daysLeft > 1000, 'a 2099 expiry is nowhere near due');
});

test('a missing quarantine file excuses nothing and is not an error', () => {
	const quarantine = loadQuarantine('/nonexistent/flake-quarantine.json');
	assert.equal(quarantine.missing, true);
	assert.equal(quarantine.active.length, 0);
});

test('quarantine matching is exact — a neighbour is not excused', () => {
	const quarantine = {
		active: [{ testId: 'a.B#c', owner: 'tom', expiresAt: '2099-01-01T00:00:00Z', daysLeft: 10 }]
	};
	const { blocking, excused } = splitByQuarantine(
		[{ testId: 'a.B#c' }, { testId: 'a.B#cAndMore' }, { testId: 'a.BC#c' }],
		quarantine
	);
	assert.deepEqual(
		excused.map((f) => f.testId),
		['a.B#c']
	);
	assert.deepEqual(
		blocking.map((f) => f.testId),
		['a.B#cAndMore', 'a.BC#c']
	);
});

const fail = (testId) => ({ testId, classname: '', name: testId, kind: 'failure', message: '' });
const ctx = { signal: 'maven / saiku-web', branch: 'development', runId: '1', runAttempt: 1 };

test('a test that passes on retry is a flake, not a failure', () => {
	const record = buildRecord({
		...ctx,
		attempt1: [fail('a.B#flaky')],
		attempt2: [],
		// Positive evidence it actually ran this time and did not fail again.
		attempt2Ran: ['a.B#flaky'],
		quarantine: { active: [] }
	});
	assert.equal(record.verdict, 'pass');
	assert.deepEqual(record.flaked, ['a.B#flaky']);
	assert.deepEqual(record.blocking, []);
	assert.equal(record.tests.retried, 1);
	assert.equal(record.tests.flaked, 1);
});

test('a retry that produces no results leaves the original failure blocking, not flaked', () => {
	// e.g. the retry step crashed before producing any surefire XML at all.
	const record = buildRecord({
		...ctx,
		attempt1: [fail('a.B#flaky')],
		attempt2: [],
		attempt2Ran: [],
		quarantine: { active: [] }
	});
	assert.equal(record.verdict, 'fail');
	assert.deepEqual(record.flaked, []);
	assert.deepEqual(record.blocking, ['a.B#flaky']);
});

test('a retry that ran but never reached the failing id still blocks it', () => {
	// e.g. a bad `-Dtest` selector matched the wrong tests entirely.
	const record = buildRecord({
		...ctx,
		attempt1: [fail('a.B#flaky')],
		attempt2: [],
		attempt2Ran: ['a.B#unrelated'],
		quarantine: { active: [] }
	});
	assert.equal(record.verdict, 'fail');
	assert.deepEqual(record.flaked, []);
	assert.deepEqual(record.blocking, ['a.B#flaky']);
});

test('a parameterized test id that passes on retry is a flake, by exact id', () => {
	const id = 'org.saiku.web.schemas.ConverterTest#everyCorpusFileConverts(Path)[1]';
	const record = buildRecord({
		...ctx,
		attempt1: [fail(id)],
		attempt2: [],
		// Other cases of the same parameterized method also ran (the Maven
		// retry selector reruns the whole method, see mavenTestSelector), but
		// only this exact id is what attempt 1 failed.
		attempt2Ran: [id, id.replace('[1]', '[2]')],
		quarantine: { active: [] }
	});
	assert.deepEqual(record.flaked, [id]);
	assert.deepEqual(record.blocking, []);
});

test('mavenTestSelector strips a parameterized id to something -Dtest understands', () => {
	assert.equal(
		mavenTestSelector('org.saiku.web.schemas.ConverterTest#everyCorpusFileConverts(Path)[1]'),
		'org.saiku.web.schemas.ConverterTest#everyCorpusFileConverts'
	);
	assert.equal(mavenTestSelector('a.B#plain'), 'a.B#plain');
	assert.equal(mavenTestSelector('orphan'), 'orphan');
});

test('parseJUnitTestIds and collectRan see passing cases too, including parameterized ones', () => {
	const junit = `<testsuite name="t">
  <testcase name="masksTokens" classname="a.B" time="0.01"/>
  <testcase name="everyCorpusFileConverts(Path)[1]" classname="a.B" time="0.01"/>
  <testcase name="broken" classname="a.B" time="0.01"><failure message="x">x</failure></testcase>
</testsuite>`;
	assert.deepEqual(parseJUnitTestIds(junit), [
		'a.B#masksTokens',
		'a.B#everyCorpusFileConverts(Path)[1]',
		'a.B#broken'
	]);
	const dir = tmpDir('flake-ran-');
	mkdirSync(dir, { recursive: true });
	writeFileSync(join(dir, 'TEST-a.xml'), junit);
	const ran = collectRan(dir);
	assert.ok(ran.has('a.B#everyCorpusFileConverts(Path)[1]'));
	assert.ok(ran.has('a.B#broken'));
	assert.equal(collectRan('/nonexistent').size, 0);
});

test('a test that fails twice still blocks', () => {
	const record = buildRecord({
		...ctx,
		attempt1: [fail('a.B#broken')],
		attempt2: [fail('a.B#broken')],
		quarantine: { active: [] }
	});
	assert.equal(record.verdict, 'fail');
	assert.deepEqual(record.blocking, ['a.B#broken']);
	assert.deepEqual(record.flaked, []);
});

test('a quarantined test runs but does not block, and is attributed', () => {
	const record = buildRecord({
		...ctx,
		attempt1: [fail('a.B#quarantined'), fail('a.B#broken')],
		attempt2: [fail('a.B#broken')],
		quarantine: {
			active: [{ testId: 'a.B#quarantined', owner: 'tom', expiresAt: '2026-11-01T00:00:00Z', daysLeft: 29 }]
		}
	});
	assert.equal(record.verdict, 'fail');
	assert.deepEqual(record.blocking, ['a.B#broken']);
	assert.deepEqual(record.flaked, []);
	assert.deepEqual(record.quarantined, [
		{ testId: 'a.B#quarantined', owner: 'tom', expiresAt: '2026-11-01T00:00:00Z' }
	]);
	// A quarantined test is not re-run: it is not in the retried count.
	assert.equal(record.tests.retried, 1);
});

test('a failure that only shows up on the retry still blocks', () => {
	const record = buildRecord({
		...ctx,
		attempt1: [fail('a.B#first')],
		attempt2: [fail('a.B#first'), fail('a.B#second')],
		quarantine: { active: [] }
	});
	assert.deepEqual(record.blocking, ['a.B#first', 'a.B#second']);
});

test('the run summary names flakes, quarantine owners and blocking failures', () => {
	const record = buildRecord({
		...ctx,
		attempt1: [fail('a.B#flaky'), fail('a.B#q'), fail('a.B#broken')],
		attempt2: [fail('a.B#broken')],
		attempt2Ran: ['a.B#flaky', 'a.B#broken'],
		quarantine: {
			active: [{ testId: 'a.B#q', owner: 'tom', expiresAt: '2026-11-01T00:00:00Z', daysLeft: 29 }]
		}
	});
	const summary = renderRunSummary(record, {
		active: [{ testId: 'a.B#q', owner: 'tom', expiresAt: '2026-11-01T00:00:00Z', daysLeft: 29 }]
	});
	assert.match(summary, /Flake report — maven \/ saiku-web/);
	assert.match(summary, /a\.B#flaky/);
	assert.match(summary, /@tom/);
	assert.match(summary, /a\.B#broken/);
	// The green "nothing failed" case must not shout.
	assert.match(
		renderRunSummary(buildRecord({ ...ctx, quarantine: { active: [] } }), { active: [] }),
		/No retries were needed/
	);
});

test('records are read from the per-artifact subdirectories gh run download creates', () => {
	const dir = tmpDir('flake-records-');
	mkdirSync(join(dir, 'flake-records-saiku-web'), { recursive: true });
	mkdirSync(join(dir, 'flake-records-ui-vitest'), { recursive: true });
	writeFileSync(
		join(dir, 'flake-records-saiku-web', 'saiku-web.json'),
		JSON.stringify(buildRecord({ ...ctx, attempt1: [fail('a.B#flaky')], quarantine: { active: [] } }))
	);
	writeFileSync(
		join(dir, 'flake-records-ui-vitest', 'ui-vitest.json'),
		JSON.stringify(buildRecord({ ...ctx, signal: 'ui / vitest', attempt1: [], quarantine: { active: [] } }))
	);
	// A truncated artifact must not lose the good ones.
	writeFileSync(join(dir, 'flake-records-saiku-web', 'half-written.json'), '{"version":1,"fla');

	assert.equal(readRecords(dir).length, 2);
	assert.equal(readRecords(join(dir, 'does-not-exist')).length, 0);
});

test('the ledger rate is hits over runs in the window, not over appearances', () => {
	const records = [
		buildRecord({ ...ctx, attempt1: [fail('a.B#flaky')], attempt2Ran: ['a.B#flaky'], quarantine: { active: [] } }),
		buildRecord({ ...ctx, attempt1: [], quarantine: { active: [] } }),
		buildRecord({ ...ctx, attempt1: [fail('a.B#flaky')], attempt2Ran: ['a.B#flaky'], quarantine: { active: [] } }),
		buildRecord({ ...ctx, attempt1: [fail('a.B#broken')], attempt2: [fail('a.B#broken')], quarantine: { active: [] } })
	];
	const ledger = buildLedger(records, { windowRuns: 10, quarantine: { active: [] } });
	const flaky = ledger.entries.find((e) => e.testId === 'a.B#flaky');
	const broken = ledger.entries.find((e) => e.testId === 'a.B#broken');
	assert.equal(flaky.hits, 2);
	assert.equal(flaky.runsInWindow, 10);
	assert.equal(flaky.rate, 0.2);
	assert.equal(broken.flaked, 0);
	assert.equal(broken.failed, 1);
	assert.equal(ledger.entries[0].testId, 'a.B#flaky'); // highest rate first
});

test('the ledger carries quarantine owner and expiry for active entries', () => {
	const records = [
		buildRecord({
			...ctx,
			attempt1: [fail('a.B#q')],
			quarantine: { active: [{ testId: 'a.B#q', owner: 'tom', expiresAt: '2026-11-01T00:00:00Z' }] }
		})
	];
	const ledger = buildLedger(records, {
		quarantine: { active: [{ testId: 'a.B#q', owner: 'tom', expiresAt: '2026-11-01T00:00:00Z', daysLeft: 29 }] }
	});
	const entry = ledger.entries[0];
	assert.equal(entry.quarantined, true);
	assert.equal(entry.owner, 'tom');
	assert.equal(entry.expiresAt, '2026-11-01T00:00:00Z');
	assert.match(renderLedger(ledger, { active: [], expired: [], invalid: [] }), /@tom/);
});

test('an empty ledger still renders the quarantine state honestly', () => {
	const rendered = renderLedger(
		{ version: 1, runsInWindow: 5, entries: [] },
		{ active: [], expired: [{ testId: 'a.B#old', expiredDays: 3 }], invalid: [], missing: false }
	);
	assert.match(rendered, /No flakes recorded/);
	assert.match(rendered, /No test is quarantined/);
	assert.match(rendered, /a\.B#old/);
});

test('a failed step with no parseable failure is a blocking infra failure, not a pass', () => {
	const record = buildRecord({
		...ctx,
		attempt1: [],
		attempt2: [],
		quarantine: { active: [] },
		infraFailures: ['maven: the test step failed but no failing test could be read']
	});
	assert.equal(record.verdict, 'fail');
	assert.deepEqual(record.flaked, []);
	assert.match(renderRunSummary(record, { active: [] }), /non-test reason/);
});

test('retry bookkeeping is visible in the record the ledger aggregates', () => {
	const record = buildRecord({
		...ctx,
		attempt1: [fail('a.B#flaky')],
		attempt2: [],
		quarantine: { active: [] },
		retriesRun: true
	});
	assert.equal(record.retriesRun, true);
	assert.equal(record.tests.retried, 1);
});
// ----------------------------------------------------------------------------
// Maven / vitest / Playwright specifics (this repository). The fixtures are the
// genuine output of each tool: Surefire 3.5.2 with -Dsurefire.rerunFailingTestsCount=1,
// Playwright 1.63's JSON reporter with retries: 1, and vitest 5's JUnit reporter.
// ----------------------------------------------------------------------------

const here = dirname(fileURLToPath(import.meta.url));
const script = join(here, 'flake-ledger.mjs');
const fx = (name) => join(here, 'fixtures', 'flake', name);
const read = (name) => readFileSync(fx(name), 'utf8');
const DAY_MS = 86_400_000;

function runCli(args, { cwd } = {}) {
	return spawnSync(process.execPath, [script, ...args], { encoding: 'utf8', cwd, env: { PATH: process.env.PATH } });
}

/** A quarantine file whose expiry is relative to the real clock, so the test never ages out. */
function quarantineFile(dir, testIds, { expiresInDays = 14 } = {}) {
	const file = join(dir, 'q.json');
	const expiresAt = new Date(Date.now() + expiresInDays * DAY_MS).toISOString();
	writeFileSync(
		file,
		JSON.stringify({ version: 1, entries: testIds.map((id) => ({ testId: id, owner: 'tom', reason: 'fixture', expiresAt })) })
	);
	return file;
}

test('surefire rerun: a failure that passed on rerun is a flake, a rerun that failed again still blocks', () => {
	const xml = read('surefire-rerun-flaky.xml');
	assert.deepEqual(parseJUnitFlaky(xml), ['demo.FlakyTest#flakesOnce']);
	assert.deepEqual(parseJUnitXml(xml).map((f) => f.testId), ['demo.FlakyTest#alwaysFails']);
	// the JUnit 4 (vintage engine) report has the same shape
	assert.deepEqual(parseJUnitFlaky(read('surefire-rerun-junit4.xml')), ['demo.LegacyTest#legacyFlake']);
	assert.deepEqual(parseJUnitXml(read('surefire-rerun-junit4.xml')), []);
});

test('a testcase with a rerun failure AND a final failure is never reported as flaky', () => {
	assert.ok(!parseJUnitFlaky(read('surefire-rerun-flaky.xml')).includes('demo.FlakyTest#alwaysFails'));
});

test('playwright json: status flaky is the positive evidence, unexpected is a failure', () => {
	const outcome = parsePlaywrightJson(read('playwright-report.json'));
	assert.deepEqual(outcome.failed.map((f) => f.testId), ['sample.spec.mjs#Workbench measures > always fails']);
	assert.deepEqual(outcome.flaky, ['sample.spec.mjs#Workbench measures > flaky: fails first attempt only']);
	assert.equal(outcome.ran.length, 3);
	assert.equal(parsePlaywrightJson('not json'), null);
	assert.equal(parsePlaywrightJson('{"unrelated":true}'), null);
});

test('splitSources takes newline or comma separated locations, as `find` prints them', () => {
	assert.deepEqual(splitSources('a/target/surefire-reports\nb/target/failsafe-reports,c'), [
		'a/target/surefire-reports',
		'b/target/failsafe-reports',
		'c'
	]);
	assert.deepEqual(splitSources(undefined), []);
	assert.deepEqual(splitSources(true), []);
});

test('collectOutcomes folds several sources and ignores a missing one', () => {
	const outcome = collectOutcomes(`${fx('surefire-rerun-flaky.xml')}\n${fx('playwright-report.json')}\n${join(tmpdir(), 'flake-does-not-exist')}`);
	assert.deepEqual(outcome.failed.map((f) => f.testId), ['demo.FlakyTest#alwaysFails', 'sample.spec.mjs#Workbench measures > always fails']);
	assert.equal(outcome.flaky.length, 2);
	assert.equal(outcome.files, 2);
});

test('a flaky id that also failed finally is only a failure', () => {
	const dir = tmpDir('flake-both-');
	writeFileSync(join(dir, 'a.xml'), read('surefire-rerun-flaky.xml'));
	writeFileSync(
		join(dir, 'b.xml'),
		`<testsuite><testcase classname="demo.FlakyTest" name="flakesOnce"><failure message="again">x</failure></testcase></testsuite>`
	);
	const outcome = collectOutcomes(dir);
	assert.ok(!outcome.flaky.includes('demo.FlakyTest#flakesOnce'));
	assert.ok(outcome.failed.some((f) => f.testId === 'demo.FlakyTest#flakesOnce'));
});

test('buildRecord: in-process flakes are recorded, quarantined ones are attributed to the entry', () => {
	const quarantine = { active: [{ testId: 'demo.FlakyTest#alwaysFails', owner: 'tom', expiresAt: '2099-01-01T00:00:00Z' }] };
	const record = buildRecord({
		...ctx,
		attempt1: [{ testId: 'demo.FlakyTest#alwaysFails' }],
		inlineFlaked: ['demo.FlakyTest#flakesOnce'],
		quarantine
	});
	assert.deepEqual(record.flaked, ['demo.FlakyTest#flakesOnce']);
	assert.deepEqual(record.quarantined.map((q) => q.testId), ['demo.FlakyTest#alwaysFails']);
	assert.deepEqual(record.blocking, []);
	assert.equal(record.verdict, 'pass');
	assert.equal(record.tests.failed, 2);
	// a flaky id that is quarantined must not be counted twice
	const twice = buildRecord({ ...ctx, inlineFlaked: ['demo.FlakyTest#alwaysFails'], quarantine });
	assert.equal(twice.quarantined.length, 1);
	assert.deepEqual(twice.flaked, []);
});

test('record --source: a still-failing test blocks, names itself on stderr, and the flake is kept', () => {
	const dir = tmpDir('flake-cli-');
	const out = join(dir, 'record.json');
	const result = runCli(['record', '--source', fx('surefire-rerun-flaky.xml'), '--signal', 'maven / test', '--require-results', '--out', out, '--quarantine', join(dir, 'none.json')]);
	assert.equal(result.status, 1, result.stderr);
	assert.match(result.stderr, /^FAILING TEST: demo\.FlakyTest#alwaysFails$/m);
	const record = JSON.parse(readFileSync(out, 'utf8'));
	assert.deepEqual(record.flaked, ['demo.FlakyTest#flakesOnce']);
	assert.deepEqual(record.blocking, ['demo.FlakyTest#alwaysFails']);
	assert.equal(record.tests.ran, 3);
});

test('record --source: a quarantined failure no longer blocks but is attributed and still visible', () => {
	const dir = tmpDir('flake-cli-q-');
	const out = join(dir, 'record.json');
	const quarantine = quarantineFile(dir, ['demo.FlakyTest#alwaysFails']);
	const result = runCli(['record', '--source', fx('surefire-rerun-flaky.xml'), '--signal', 'maven / test', '--out', out, '--quarantine', quarantine]);
	assert.equal(result.status, 0, result.stderr);
	const record = JSON.parse(readFileSync(out, 'utf8'));
	assert.deepEqual(record.quarantined.map((q) => q.owner), ['tom']);
	assert.deepEqual(record.flaked, ['demo.FlakyTest#flakesOnce']);
	assert.equal(record.verdict, 'pass');
});

test('record --source: an expired quarantine entry blocks again', () => {
	const dir = tmpDir('flake-cli-exp-');
	const quarantine = quarantineFile(dir, ['demo.FlakyTest#alwaysFails'], { expiresInDays: -1 });
	const result = runCli(['record', '--source', fx('surefire-rerun-flaky.xml'), '--signal', 's', '--out', join(dir, 'r.json'), '--quarantine', quarantine]);
	assert.equal(result.status, 1);
});

test('record --build-failed: a non-test failure blocks even when no test failed', () => {
	const dir = tmpDir('flake-cli-build-');
	const results = join(dir, 'reports');
	mkdirSync(results);
	writeFileSync(join(results, 'TEST-ok.xml'), '<testsuite><testcase classname="a.B" name="ok"/></testsuite>');
	const green = runCli(['record', '--source', results, '--signal', 's', '--require-results', '--out', join(dir, 'g.json'), '--quarantine', join(dir, 'none.json')]);
	assert.equal(green.status, 0, green.stderr);
	const red = runCli(['record', '--source', results, '--signal', 's', '--build-failed', '--out', join(dir, 'r.json'), '--quarantine', join(dir, 'none.json')]);
	assert.equal(red.status, 1);
	assert.match(JSON.parse(readFileSync(join(dir, 'r.json'), 'utf8')).infra[0], /non-zero/);
});

test('record --require-results: no results at all is a failure, not a pass', () => {
	const dir = tmpDir('flake-cli-empty-');
	const result = runCli(['record', '--source', join(dir, 'missing'), '--signal', 's', '--require-results', '--out', join(dir, 'r.json'), '--quarantine', join(dir, 'none.json')]);
	assert.equal(result.status, 1);
	assert.match(JSON.parse(readFileSync(join(dir, 'r.json'), 'utf8')).infra[0], /no test results/);
});

test('vitest: select prints the failing FILE, and a second attempt that ran and passed makes it a flake', () => {
	const dir = tmpDir('flake-vitest-');
	const attempt1 = fx('vitest-junit.xml');
	const selected = runCli(['select', '--source', attempt1, '--as-file', '--quarantine', join(dir, 'none.json')]);
	assert.equal(selected.status, 0);
	assert.equal(selected.stdout.trim(), 'src/lib/zzFlakeFixture.test.ts');

	const attempt2 = join(dir, 'attempt2.xml');
	writeFileSync(
		attempt2,
		`<testsuites><testsuite><testcase classname="src/lib/zzFlakeFixture.test.ts" name="formatResetCountdown &gt; renders days for a far-future reset"/></testsuite></testsuites>`
	);
	const flaked = runCli(['record', '--source1', attempt1, '--source2', attempt2, '--attempt1-failed', '--signal', 'ui / vitest', '--out', join(dir, 'f.json'), '--quarantine', join(dir, 'none.json')]);
	assert.equal(flaked.status, 0, flaked.stderr);
	assert.deepEqual(JSON.parse(readFileSync(join(dir, 'f.json'), 'utf8')).flaked, ['src/lib/zzFlakeFixture.test.ts#formatResetCountdown > renders days for a far-future reset']);

	// positive evidence: a second attempt that never ran the test is NOT a pass
	const empty = join(dir, 'empty.xml');
	writeFileSync(empty, '<testsuites></testsuites>');
	const unresolved = runCli(['record', '--source1', attempt1, '--source2', empty, '--attempt1-failed', '--signal', 'ui / vitest', '--out', join(dir, 'u.json'), '--quarantine', join(dir, 'none.json')]);
	assert.equal(unresolved.status, 1);
});
