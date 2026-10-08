#!/usr/bin/env node
/**
 * Flake handling for CI: retry once, flake ledger, quarantine.
 *
 * Ported from spiculedata/saiku-cloud (.github/scripts/flake-ledger.mjs, #1375) and
 * adapted to this repository's three test runners (see docs/ci-flakes.md):
 *
 *   Maven Surefire/Failsafe  one in-process retry (-Dsurefire.rerunFailingTestsCount=1);
 *                            the XML itself is the positive evidence (<flakyFailure>)
 *   Playwright               one in-process retry (retries: 1); the JSON reporter's
 *                            per-test `status: "flaky"` is the positive evidence
 *   vitest                   two attempts orchestrated by the workflow (select ->
 *                            re-run the failing files -> record); the id has to appear
 *                            in the second attempt's results to count as a pass
 *
 * Four subcommands, all dependency-free (Node builtins only) so no workflow
 * needs an install step:
 *
 *   select    which of the failing tests are worth re-running right now
 *             (quarantined ones are not) -> prints `Class#method` per line
 *   record    the final verdict for one test job: failure / flake / quarantined.
 *             Writes the run record the nightly ledger workflow aggregates and
 *             a markdown block for the run summary. Exit 1 only on a real
 *             failure, so a quarantined or retried-green test does not redden
 *             the gate.
 *   ledger    fold many run records into `.github/flake-ledger.json`
 *             (per test id, rolling flake rate over a window of runs)
 *   quarantine  inspect/validate `.github/flake-quarantine.json`
 *
 * The rule this implements, in one line: **a flaky test is a bug with a
 * deadline, not a reason to ignore a red check.** Failing tests get one retry,
 * a pass on retry is recorded as a flake and stays visible, and anything that
 * keeps flaking is quarantined (still running, no longer blocking) with an
 * owner and an expiry so it cannot hide forever. See docs/ci-flakes.md.
 */

import {
	existsSync,
	mkdirSync,
	readFileSync,
	readdirSync,
	statSync,
	writeFileSync
} from 'node:fs';
import { dirname, join } from 'node:path';
import { pathToFileURL } from 'node:url';

export const LEDGER_VERSION = 1;

/** Default quarantine file — the checked-in list of non-blocking flaky tests. */
export const QUARANTINE_PATH = '.github/flake-quarantine.json';
/** Default ledger file — the rolling per-test flake rate. */
export const LEDGER_PATH = '.github/flake-ledger.json';

// ---------------------------------------------------------------- parsing --

const ENTITIES = {
	'&quot;': '"',
	'&apos;': "'",
	'&lt;': '<',
	'&gt;': '>',
	'&amp;': '&'
};

function decodeEntities(value) {
	return value.replace(/&(?:quot|apos|lt|gt|amp);/g, (m) => ENTITIES[m] ?? m);
}

function attrs(tag) {
	const out = {};
	const re = /([\w:-]+)="([^"]*)"/g;
	let m;
	while ((m = re.exec(tag)) !== null) {
		out[m[1]] = decodeEntities(m[2]);
	}
	return out;
}

/**
 * A test's stable identity, `classname#name`. Class + method rather than the
 * file path: the ledger has to survive a file being moved, and the quarantine
 * entry has to name something a human recognises in a test report.
 */
export function testId(classname, name) {
	const cls = (classname ?? '').trim();
	const method = (name ?? '').trim();
	if (!cls) return method;
	return `${cls}#${method}`;
}

/**
 * Failed test ids out of one JUnit-style XML document (surefire TEST-*.xml and
 * the vitest junit reporter both emit this shape). Returns
 * `[{ testId, classname, name, kind, message }]` where kind is `failure` or
 * `error`.
 */
export function parseJUnitXml(xml) {
	const failures = [];
	// A passing case is emitted self-closing (`<testcase .../>`). Normalise those
	// to an empty element first, otherwise the block regex below would pair the
	// opening tag of a *passing* case with the closing tag of a later one and
	// report a green test as failed.
	const normalised = xml.replace(/<testcase\b([^>]*?)\/>/g, '<testcase$1></testcase>');
	const blocks = [...normalised.matchAll(/<testcase\b([^>]*?)>([\s\S]*?)<\/testcase>/g)];
	for (const [, tag, body] of blocks) {
		const a = attrs(tag);
		const kind = /<failure\b/.test(body) ? 'failure' : /<error\b/.test(body) ? 'error' : null;
		if (!kind) continue;
		const messageTag = body.match(new RegExp(`<${kind}\\b([^>]*?)/?>`));
		const message = messageTag ? decodeEntities(attrs(messageTag[1]).message ?? '').split('\n')[0] : '';
		failures.push({
			testId: testId(a.classname, a.name),
			classname: a.classname ?? '',
			name: a.name ?? '',
			kind,
			message: message.slice(0, 300)
		});
	}
	return failures;
}

/**
 * Every test id a JUnit-style XML document reports, passed or failed — the
 * positive-evidence source for "did this actually run". Absence from a
 * failures list is not evidence of a pass; absence from this list is.
 */
export function parseJUnitTestIds(xml) {
	const normalised = xml.replace(/<testcase\b([^>]*?)\/>/g, '<testcase$1></testcase>');
	const ids = [];
	for (const [, tag] of normalised.matchAll(/<testcase\b([^>]*?)>/g)) {
		const a = attrs(tag);
		ids.push(testId(a.classname, a.name));
	}
	return ids;
}

/**
 * Test ids that FAILED and then PASSED on an in-process rerun, out of one Surefire
 * XML document (`-Dsurefire.rerunFailingTestsCount=N`). Surefire records the earlier
 * attempts as <flakyFailure>/<flakyError> children of a testcase whose final result
 * is a pass. A testcase that is still failing carries <failure>/<error> plus
 * <rerunFailure>/<rerunError>, which parseJUnitXml reports as a failure.
 */
export function parseJUnitFlaky(xml) {
	const normalised = xml.replace(/<testcase\b([^>]*?)\/>/g, '<testcase$1></testcase>');
	const flaky = [];
	for (const [, tag, body] of normalised.matchAll(/<testcase\b([^>]*?)>([\s\S]*?)<\/testcase>/g)) {
		if (/<(?:failure|error)\b/.test(body)) continue;
		if (!/<flaky(?:Failure|Error)\b/.test(body)) continue;
		const a = attrs(tag);
		flaky.push(testId(a.classname, a.name));
	}
	return flaky;
}

/**
 * Outcome of one Playwright JSON report (`--reporter=json`). A test's `status` is
 * `expected` | `unexpected` | `flaky` | `skipped`; `flaky` means it failed and then
 * passed on a retry, which is positive evidence recorded by Playwright itself.
 * The id is `<file>#<suite titles> > <test title>`, file relative to the testDir.
 */
export function parsePlaywrightJson(text) {
	let report;
	try {
		report = JSON.parse(text);
	} catch {
		return null;
	}
	if (!report || !Array.isArray(report.suites) || typeof report.stats !== 'object') return null;
	const out = { failed: [], flaky: [], ran: [] };
	const walk = (suite, titles) => {
		const trail = suite.title && suite.title !== suite.file ? [...titles, suite.title] : titles;
		for (const spec of suite.specs ?? []) {
			const id = testId(spec.file ?? suite.file ?? '', [...trail, spec.title].join(' > '));
			for (const test of spec.tests ?? []) {
				if (test.status === 'skipped') continue;
				const entry = test.projectName && (report.config?.projects?.length ?? 0) > 1 ? `${id} [${test.projectName}]` : id;
				out.ran.push(entry);
				if (test.status === 'unexpected') {
					const last = (test.results ?? []).at(-1);
					out.failed.push({
						testId: entry,
						classname: spec.file ?? '',
						name: spec.title ?? '',
						kind: 'failure',
						message: String(last?.error?.message ?? '').split('\n')[0].slice(0, 300)
					});
				} else if (test.status === 'flaky') out.flaky.push(entry);
			}
		}
		for (const child of suite.suites ?? []) walk(child, trail);
	};
	for (const suite of report.suites) walk(suite, []);
	return out;
}

/**
 * Surefire's `-Dtest` does not understand the `(ArgType)[n]` suffix JUnit 5
 * gives a parameterized case's XML name — asking for that exact string finds
 * nothing and silently runs zero tests. Stripping it to the bare method name
 * reruns every case of that parameterized method: coarser than the one
 * failing case, but something Surefire actually understands.
 */
export function mavenTestSelector(id) {
	const hash = id.indexOf('#');
	if (hash === -1) return id;
	const cls = id.slice(0, hash);
	const method = id.slice(hash + 1).replace(/\(.*\)(\[\d+\])?$/, '');
	return `${cls}#${method}`;
}

/** Every `*.xml` / `*.json` under a directory, sorted for determinism. */
function resultFilesUnder(dir) {
	const out = [];
	for (const entry of readdirSync(dir).sort()) {
		const full = join(dir, entry);
		const st = statSync(full);
		if (st.isDirectory()) out.push(...resultFilesUnder(full));
		else if (entry.endsWith('.xml') || entry.endsWith('.json')) out.push(full);
	}
	return out;
}

/**
 * `--source` takes one or more results locations (directory or file), separated by
 * newlines or commas, so a shell can pass `$(find . -name surefire-reports)` as is.
 */
export function splitSources(value) {
	if (!value || value === true) return [];
	return String(value)
		.split(/[\n,]/)
		.map((entry) => entry.trim())
		.filter(Boolean);
}

/**
 * Everything the results sources say, in one pass: the tests that finally FAILED,
 * the tests that FLAKED (failed, then passed on an in-process retry), and every id
 * that actually ran. Understands Surefire/Failsafe and vitest JUnit XML and the
 * Playwright JSON report. Failed ids are deduplicated and sorted so the retry
 * command and the ledger both see one line per test.
 */
export function collectOutcomes(sources) {
	const failed = new Map();
	const flaky = new Set();
	const ran = new Set();
	let files = 0;
	for (const source of splitSources(sources)) {
		// A missing results directory is normal when the build never got as far as
		// running a test (a compile error, a dependency failure, a lost runner). The
		// caller decides whether that is a flake or a real failure; this function
		// just must not throw while finding out.
		if (!existsSync(source)) {
			process.stderr.write(`warning: no test results at ${source}\n`);
			continue;
		}
		const st = statSync(source);
		for (const file of st.isDirectory() ? resultFilesUnder(source) : [source]) {
			const text = readFileSync(file, 'utf8');
			if (file.endsWith('.json')) {
				const playwright = parsePlaywrightJson(text);
				if (!playwright) continue;
				files += 1;
				for (const failure of playwright.failed) if (!failed.has(failure.testId)) failed.set(failure.testId, failure);
				playwright.flaky.forEach((id) => flaky.add(id));
				playwright.ran.forEach((id) => ran.add(id));
				continue;
			}
			files += 1;
			for (const failure of parseJUnitXml(text)) if (!failed.has(failure.testId)) failed.set(failure.testId, failure);
			parseJUnitFlaky(text).forEach((id) => flaky.add(id));
			parseJUnitTestIds(text).forEach((id) => ran.add(id));
		}
	}
	for (const id of failed.keys()) flaky.delete(id);
	return {
		failed: [...failed.values()].sort((a, b) => (a.testId < b.testId ? -1 : 1)),
		flaky: [...flaky].sort(),
		ran,
		files
	};
}

/** Failed test ids from a results source (see {@link collectOutcomes}). */
export function collectFailures(source) {
	return collectOutcomes(source).failed;
}

/**
 * Every test id with a result — pass or fail — in a results source. This is the
 * positive evidence `buildRecord` needs before calling a retry a pass; a test id
 * missing here was never re-executed, so its absence from the failures list proves
 * nothing about it.
 */
export function collectRan(source) {
	return collectOutcomes(source).ran;
}

// ------------------------------------------------------------ quarantine --

/**
 * Load and validate the quarantine list.
 *
 * An entry is only honoured when it has an `owner`, an `expiresAt` and an
 * `expiresAt` in the future. A malformed or lapsed entry is reported instead of
 * silently trusted: an expired quarantine that keeps suppressing a real failure
 * is how a gate dies quietly, which is the failure mode this whole change
 * exists to stop (#1375, "a permanently red check trains everyone to ignore it").
 */
export function loadQuarantine(path, now = new Date()) {
	const empty = { entries: [], active: [], expired: [], invalid: [], missing: true };
	if (!path || !existsSync(path)) return empty;
	let parsed;
	try {
		parsed = JSON.parse(readFileSync(path, 'utf8'));
	} catch (err) {
		return { ...empty, invalid: [{ entry: null, reason: `unparseable JSON: ${err.message}` }] };
	}
	const entries = Array.isArray(parsed.entries) ? parsed.entries : [];
	const active = [];
	const expired = [];
	const invalid = [];
	for (const entry of entries) {
		if (!entry || typeof entry !== 'object' || typeof entry.testId !== 'string' || !entry.testId) {
			invalid.push({ entry, reason: 'entry has no testId' });
			continue;
		}
		if (!entry.owner) {
			invalid.push({ entry, reason: `${entry.testId} has no owner` });
			continue;
		}
		const expiresAt = Date.parse(entry.expiresAt ?? '');
		if (Number.isNaN(expiresAt)) {
			invalid.push({ entry, reason: `${entry.testId} has no parseable expiresAt` });
			continue;
		}
		if (expiresAt <= now.getTime()) {
			expired.push({ ...entry, expiredDays: Math.ceil((now.getTime() - expiresAt) / 86_400_000) });
			continue;
		}
		active.push({ ...entry, daysLeft: Math.ceil((expiresAt - now.getTime()) / 86_400_000) });
	}
	return { entries, active, expired, invalid, missing: false };
}

/**
 * Split failures into the ones that must still block and the ones a live
 * quarantine entry excuses. Quarantine is an exact test id — a prefix or
 * substring match would silently excuse neighbouring tests.
 */
export function splitByQuarantine(failures, quarantine) {
	const active = new Map((quarantine?.active ?? []).map((e) => [e.testId, e]));
	const blocking = [];
	const excused = [];
	for (const failure of failures) {
		const entry = active.get(failure.testId);
		if (entry) excused.push({ ...failure, quarantine: entry });
		else blocking.push(failure);
	}
	return { blocking, excused };
}

// ----------------------------------------------------------- run records --

export function emptyRecord(extra = {}) {
	return {
		version: LEDGER_VERSION,
		signal: extra.signal ?? 'unknown',
		branch: extra.branch ?? null,
		runId: extra.runId ?? null,
		runAttempt: extra.runAttempt ?? null,
		recordedAt: new Date().toISOString(),
		tests: { failed: 0, retried: 0, flaked: 0, quarantined: 0, blocking: 0 },
		failed: [],
		flaked: [],
		quarantined: [],
		blocking: [],
		verdict: 'pass',
		...extra
	};
}

/**
 * Build the run record from what the two attempts saw.
 *
 * - failed in attempt 1, ran and passed in attempt 2  -> `flaked` (stays visible)
 * - failed in attempt 1, failed again in attempt 2    -> `blocking`
 * - failed in attempt 1, never re-run in attempt 2     -> `blocking` (unresolved)
 * - failed in attempt 1, quarantined                   -> `quarantined` (still runs)
 *
 * Passing on retry requires positive evidence — the test id has to actually
 * appear in attempt 2's results (`attempt2Ran`). A retry that produced no
 * results at all, or that never reached the failing test (a bad `-Dtest`
 * selector, an empty retry list), must not be read as a pass: absence of a
 * failure is not evidence of one (saiku-cloud#1401 review).
 */
export function buildRecord({ signal, branch, runId, runAttempt, attempt1 = [], attempt2 = [], attempt2Ran, inlineFlaked = [], quarantine, retriesRun = false, infraFailures = [] }) {
	const record = emptyRecord({ signal, branch, runId, runAttempt, retriesRun });
	const attempt2Ids = new Set(attempt2.map((f) => f.testId));
	const ranIds = attempt2Ran instanceof Set ? attempt2Ran : new Set(attempt2Ran ?? attempt2.map((f) => f.testId));
	const quarantineIds = new Map((quarantine?.active ?? []).map((e) => [e.testId, e]));

	for (const failure of attempt1) {
		if (quarantineIds.has(failure.testId)) {
			record.quarantined.push({
				testId: failure.testId,
				owner: quarantineIds.get(failure.testId).owner,
				expiresAt: quarantineIds.get(failure.testId).expiresAt
			});
		} else if (ranIds.has(failure.testId) && !attempt2Ids.has(failure.testId)) {
			record.flaked.push(failure.testId);
		} else {
			record.blocking.push(failure.testId);
		}
	}
	// Tests that failed and then passed on an IN-PROCESS retry (Surefire rerun,
	// Playwright retries): the runner's own report says so, which is the positive
	// evidence. A quarantined id is attributed to its quarantine entry instead.
	for (const id of inlineFlaked) {
		if (quarantineIds.has(id)) {
			if (!record.quarantined.some((q) => q.testId === id)) {
				record.quarantined.push({ testId: id, owner: quarantineIds.get(id).owner, expiresAt: quarantineIds.get(id).expiresAt });
			}
		} else if (!record.flaked.includes(id)) record.flaked.push(id);
	}
	// A test that only appeared in attempt 2 (a retry that failed something the
	// full run did not reach) still has to block.
	for (const failure of attempt2) {
		if (attempt1.some((f) => f.testId === failure.testId)) continue;
		if (quarantineIds.has(failure.testId)) {
			record.quarantined.push({
				testId: failure.testId,
				owner: quarantineIds.get(failure.testId).owner,
				expiresAt: quarantineIds.get(failure.testId).expiresAt
			});
		} else {
			record.blocking.push(failure.testId);
		}
	}

	record.blocking.sort();
	record.flaked.sort();
	record.quarantined.sort((a, b) => (a.testId < b.testId ? -1 : 1));
	record.tests = {
		failed: attempt1.length + inlineFlaked.length,
		retried: attempt1.filter((f) => !quarantineIds.has(f.testId)).length + inlineFlaked.filter((id) => !quarantineIds.has(id)).length,
		flaked: record.flaked.length,
		quarantined: record.quarantined.length,
		blocking: record.blocking.length
	};
	record.verdict = record.blocking.length === 0 && infraFailures.length === 0 ? 'pass' : 'fail';
	record.infra = infraFailures;
	return record;
}

/** Markdown for the run summary: what flaked, what is quarantined, what failed. */
export function renderRunSummary(record, quarantine) {
	const lines = [];
	lines.push(`### Flake report — ${record.signal}`);
	lines.push('');
	if (record.tests.failed === 0 && !(record.infra?.length > 0)) {
		lines.push('All tests passed on the first attempt. No retries were needed.');
		lines.push('');
		return lines.join('\n');
	}
	lines.push(
		`${record.tests.failed} failed first, ${record.tests.retried} were retried once, ` +
			`${record.tests.flaked} passed on retry (recorded as flakes), ` +
			`${record.tests.quarantined} quarantined, ${record.tests.blocking} still failing.` +
			(record.infra?.length ? ' The run also failed for a non-test reason (below).' : '')
	);
	lines.push('');
	if (record.flaked.length) {
		lines.push('**Flaked — passed on retry. Fix these; they are tracked in `.github/flake-ledger.json`.**');
		lines.push('');
		for (const id of record.flaked) lines.push(`- \`${id}\``);
		lines.push('');
	}
	if (record.quarantined.length) {
		lines.push('**Quarantined — still running, no longer blocking.**');
		lines.push('');
		lines.push('| test | owner | expires |');
		lines.push('|---|---|---|');
		for (const q of record.quarantined) lines.push(`| \`${q.testId}\` | @${q.owner} | ${q.expiresAt} |`);
		lines.push('');
		const soon = (quarantine?.active ?? []).filter((e) => e.daysLeft !== undefined && e.daysLeft <= 7);
		if (soon.length) {
			lines.push(
				`_Quarantine expiring within a week: ${soon
					.map((e) => `\`${e.testId}\` (@${e.owner}, ${e.daysLeft}d)`)
					.join(', ')}._`
			);
			lines.push('');
		}
	}
	if (record.blocking.length) {
		lines.push('**Failing (blocking)**');
		lines.push('');
		for (const id of record.blocking) lines.push(`- \`${id}\``);
		lines.push('');
	}
	if (record.infra?.length) {
		// A failed step with no parseable test failure is not a flake: it is a
		// compile break, a dependency resolution failure, a timeout or a
		// crashed runner. It has to keep blocking, and saying so is the only way
		// a maintainer knows the retry machinery did not eat it.
		lines.push('**Failed for a non-test reason (blocking)**');
		lines.push('');
		for (const reason of record.infra) lines.push(`- ${reason}`);
		lines.push('');
	}
	return lines.join('\n');
}

// ---------------------------------------------------------------- ledger --

/**
 * Fold run records into the rolling ledger: per test id, how many runs in the
 * window saw it flake or fail, over how many runs covered it.
 *
 * `runsInWindow` is the denominator for every entry, so the rate reads as
 * "this test misbehaved in N of the last M runs of that signal" — the number a
 * maintainer needs to decide whether to quarantine it.
 */
export function buildLedger(records, { generatedAt, windowRuns, quarantine } = {}) {
	const runsInWindow = windowRuns ?? records.length;
	const perTest = new Map();
	for (const record of records) {
		for (const id of [...record.flaked ?? [], ...record.blocking ?? [], ...(record.quarantined ?? []).map((q) => q.testId)]) {
			const entry = perTest.get(id) ?? {
				testId: id,
				flaked: 0,
				failed: 0,
				quarantinedRuns: 0,
				signals: new Set(),
				firstSeen: null,
				lastSeen: null
			};
			if ((record.flaked ?? []).includes(id)) entry.flaked += 1;
			else if ((record.quarantined ?? []).some((q) => q.testId === id)) entry.quarantinedRuns += 1;
			else entry.failed += 1;
			entry.signals.add(record.signal ?? 'unknown');
			if (record.recordedAt) {
				if (!entry.firstSeen || record.recordedAt < entry.firstSeen) entry.firstSeen = record.recordedAt;
				if (!entry.lastSeen || record.recordedAt > entry.lastSeen) entry.lastSeen = record.recordedAt;
			}
			perTest.set(id, entry);
		}
	}

	const quarantinedIds = new Map((quarantine?.active ?? []).map((e) => [e.testId, e]));
	const entries = [...perTest.values()]
		.map((entry) => {
			const hits = entry.flaked + entry.failed + entry.quarantinedRuns;
			const q = quarantinedIds.get(entry.testId);
			return {
				testId: entry.testId,
				signals: [...entry.signals].sort(),
				hits,
				runsInWindow,
				rate: runsInWindow > 0 ? Number((hits / runsInWindow).toFixed(3)) : 0,
				flaked: entry.flaked,
				failed: entry.failed,
				quarantinedRuns: entry.quarantinedRuns,
				firstSeen: entry.firstSeen,
				lastSeen: entry.lastSeen,
				quarantined: Boolean(q),
				owner: q?.owner ?? null,
				expiresAt: q?.expiresAt ?? null
			};
		})
		.sort((a, b) => b.rate - a.rate || (a.testId < b.testId ? -1 : 1));

	return {
		version: LEDGER_VERSION,
		generatedAt: generatedAt ?? new Date().toISOString(),
		runsInWindow,
		entries
	};
}

/** Markdown table of the ledger + quarantine state, for the quality dashboard. */
export function renderLedger(ledger, quarantine) {
	const lines = [];
	lines.push('### Flake ledger');
	lines.push('');
	if (!ledger || !Array.isArray(ledger.entries) || ledger.entries.length === 0) {
		lines.push('_No flakes recorded in the ledger window._');
		lines.push('');
	} else {
		lines.push(
			`_Rolling rate per test id over the last ${ledger.runsInWindow ?? '?'} runs that covered it ` +
				`(ledger generated ${ledger.generatedAt ?? 'unknown'})._`
		);
		lines.push('');
		lines.push('| test | rate | flakes | failures | quarantined | owner | expires |');
		lines.push('|---|---|---|---|---|---|---|');
		for (const e of ledger.entries.slice(0, 20)) {
			lines.push(
				`| \`${e.testId}\` | ${Math.round((e.rate ?? 0) * 100)}% | ${e.flaked ?? 0} | ` +
					`${e.failed ?? 0} | ${e.quarantined ? 'yes' : 'no'} | ${e.owner ? `@${e.owner}` : '—'} | ` +
					`${e.expiresAt ?? '—'} |`
			);
		}
		if (ledger.entries.length > 20) {
			lines.push('');
			lines.push(`_… and ${ledger.entries.length - 20} more; full list in \`.github/flake-ledger.json\`._`);
		}
		lines.push('');
	}

	const active = quarantine?.active ?? [];
	lines.push('### Quarantine');
	lines.push('');
	if (!quarantine || quarantine.missing) {
		lines.push(`_No quarantine file at \`${QUARANTINE_PATH}\`._`);
	} else if (active.length === 0) {
		lines.push('_No test is quarantined. Every failure blocks._');
	} else {
		lines.push('| test | owner | expires | reason |');
		lines.push('|---|---|---|---|');
		for (const e of active) {
			lines.push(
				`| \`${e.testId}\` | @${e.owner} | ${e.expiresAt} (${e.daysLeft}d) | ${e.reason ?? '—'} |`
			);
		}
	}
	if ((quarantine?.expired ?? []).length) {
		lines.push('');
		lines.push(
			`_Expired but still listed (now blocking again): ${quarantine.expired
				.map((e) => `\`${e.testId}\``)
				.join(', ')}._`
		);
	}
	if ((quarantine?.invalid ?? []).length) {
		lines.push('');
		lines.push('_**Invalid quarantine entries (ignored, and the test blocks again):**_');
		for (const bad of quarantine.invalid) lines.push(`- ${bad.reason}`);
	}
	lines.push('');
	return lines.join('\n');
}

// ------------------------------------------------------------- artifacts --

/**
 * Run records from a directory of downloaded artifacts.
 *
 * Recursive on purpose: `gh run download` / `download-artifact` unpack each
 * artifact into its own subdirectory, so the records are never at the top
 * level. Bad or truncated files are ignored — a nightly report that dies on one
 * unreadable artifact is worse than one that is a day short.
 */
export function readRecords(dir, records = []) {
	if (!dir || !existsSync(dir)) return records;
	for (const entry of readdirSync(dir).sort()) {
		const full = join(dir, entry);
		if (statSync(full).isDirectory()) {
			readRecords(full, records);
			continue;
		}
		if (!entry.endsWith('.json')) continue;
		try {
			const parsed = JSON.parse(readFileSync(full, 'utf8'));
			for (const record of Array.isArray(parsed) ? parsed : [parsed]) {
				if (record && typeof record === 'object' && Array.isArray(record.flaked)) records.push(record);
			}
		} catch {
			// A truncated artifact is not a reason to fail the nightly job.
		}
	}
	return records;
}

function writeJson(path, value) {
	if (!path || path === true) return;
	mkdirSync(dirname(path), { recursive: true });
	writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`);
}

function appendSummary(markdown) {
	process.stdout.write(markdown.endsWith('\n') ? markdown : `${markdown}\n`);
	const summaryPath = process.env.GITHUB_STEP_SUMMARY;
	if (summaryPath && existsSync(dirname(summaryPath) || '.')) {
		try {
			writeFileSync(summaryPath, `${markdown}\n`, { flag: 'a' });
		} catch {
			// A read-only or missing summary path must not fail the job.
		}
	}
}

export function parseArgs(argv) {
	const args = { _: [] };
	for (let i = 0; i < argv.length; i += 1) {
		const arg = argv[i];
		if (arg.startsWith('--')) {
			const eq = arg.indexOf('=');
			if (eq !== -1) {
				args[arg.slice(2, eq)] = arg.slice(eq + 1);
				continue;
			}
			const key = arg.slice(2);
			const next = argv[i + 1];
			if (next === undefined || next.startsWith('--')) {
				args[key] = true;
			} else {
				args[key] = next;
				i += 1;
			}
		} else {
			args._.push(arg);
		}
	}
	return args;
}

const str = (v) => (typeof v === 'string' ? v : undefined);

function reportContext(args) {
	return {
		signal: str(args.signal) ?? 'unknown',
		branch: str(args.branch) ?? null,
		runId: str(args['run-id']) ?? str(args.runid) ?? null,
		runAttempt: Number(args['run-attempt'] ?? args.runattempt ?? 0) || null
	};
}

// ------------------------------------------------------------------- CLI --

function cmdSelect(args) {
	const failures = collectFailures(args.source);
	const quarantine = loadQuarantine(str(args.quarantine) ?? QUARANTINE_PATH);
	const { blocking, excused } = splitByQuarantine(failures, quarantine);
	// `classname` prints just the test file, which is what a vitest run takes as
	// a positional filter; the default mode is what maven's -Dtest wants, so a
	// parameterized JUnit 5 id (`Class#method(Path)[1]`) is stripped to the bare
	// method -Dtest actually understands (and dedupe: several failing cases of
	// the same method collapse to one selector).
	const asFile = args['as-file'];
	const ids = asFile
		? [...new Set(blocking.map((f) => f.classname || f.testId))]
		: [...new Set(blocking.map((f) => mavenTestSelector(f.testId)))];
	if (args.out) {
		writeFileSync(args.out, ids.join('\n') + (ids.length ? '\n' : ''));
	}
	for (const failure of excused) {
		console.log(`::notice title=Quarantined::${failure.testId} is quarantined (@${failure.quarantine.owner}, until ${failure.quarantine.expiresAt}); not retried, not blocking.`);
	}
	for (const id of ids) console.log(id);
	return 0;
}

function cmdRecord(args) {
	const quarantine = loadQuarantine(str(args.quarantine) ?? QUARANTINE_PATH);
	const signal = reportContext(args).signal;
	// Two shapes. `--source` is ONE pass whose runner already retried in-process
	// (Surefire rerun, Playwright retries) and says so in its own report.
	// `--source1/--source2` is two attempts the workflow orchestrated (vitest).
	const single = args.source !== undefined && args.source1 === undefined;
	const first = collectOutcomes(single ? args.source : args.source1);
	const second = args.source2 ? collectOutcomes(args.source2) : null;
	const attempt1 = first.failed;
	const attempt2 = second?.failed ?? [];
	// Positive evidence a retried test id actually ran (pass or fail) — see
	// buildRecord. Not the same as `attempt2`, which is only the failures.
	const attempt2Ran = second?.ran ?? new Set();
	// A failed step with no parseable test failure is not a flake. Compile
	// breaks, dependency resolution failures, timeouts and crashed runners all
	// land here, and treating them as "no failures" would let a broken build
	// through the gate as green.
	const infraFailures = [];
	if (args['build-failed']) {
		infraFailures.push(
			`${signal}: the build/test command exited non-zero although test failures do not fail it here (compile error, formatter, dependency resolution, timeout, runner loss or another gate); read the step output above`
		);
	}
	if (args['attempt1-failed'] && attempt1.length === 0 && !args['build-failed']) {
		infraFailures.push(
			`${signal}: the test step failed but no failing test could be read from its results (compile error, dependency resolution, timeout, runner loss, or a non-test assertion such as the coverage floor)`
		);
	}
	if (args['attempt2-failed'] && attempt2.length === 0) {
		infraFailures.push(`${signal}: the retry step failed without a parseable result, so the retried tests are unresolved`);
	}
	if (args['require-results'] && first.ran.size === 0) {
		infraFailures.push(`${signal}: no test results were found, so nothing proves the suite ran`);
	}
	const record = buildRecord({
		...reportContext(args),
		attempt1,
		attempt2,
		attempt2Ran,
		inlineFlaked: first.flaky,
		quarantine,
		retriesRun: Boolean(args.source2) || first.flaky.length > 0,
		infraFailures
	});
	record.tests.ran = first.ran.size;
	writeJson(args.out ?? 'flake-records/run.json', record);
	appendSummary(renderRunSummary(record, quarantine));
	if (record.verdict === 'fail') {
		const messages = new Map([...attempt1, ...attempt2].map((f) => [f.testId, f.message]));
		// One stable line per blocking test: ci-feedback.mjs reads `FAILING TEST:` as a
		// failing test id (and as the first real error) without knowing which runner it was.
		for (const id of record.blocking) {
			console.error(`FAILING TEST: ${id}`);
			if (messages.get(id)) console.error(`  ${messages.get(id)}`);
		}
		const detail = [
			...record.blocking.map((id) => `  failing test: ${id}`),
			...record.infra.map((reason) => `  ${reason}`)
		];
		console.error(`::error title=Tests failing::${detail.join('\n')}`);
		return 1;
	}
	return 0;
}

function cmdLedger(args) {
	const records = readRecords(args.records ?? args.source);
	const quarantine = loadQuarantine(str(args.quarantine) ?? QUARANTINE_PATH);
	const windowRuns = Number(args['window-runs'] ?? args.windowRuns ?? 0) || records.length;
	const ledger = buildLedger(records, { windowRuns, quarantine });
	writeJson(args.out ?? LEDGER_PATH, ledger);
	if (args.print) {
		console.log(JSON.stringify(ledger, null, 2));
	}
	return 0;
}

function cmdQuarantine(args) {
	const quarantine = loadQuarantine(str(args.file) ?? str(args.quarantine) ?? QUARANTINE_PATH);
	if (args.json) {
		console.log(JSON.stringify(quarantine, null, 2));
	} else {
		for (const e of quarantine.active) {
			console.log(`quarantined  ${e.testId}  @${e.owner}  expires ${e.expiresAt} (${e.daysLeft}d)`);
		}
		for (const e of quarantine.expired) {
			console.log(`EXPIRED      ${e.testId}  @${e.owner}  expired ${e.expiresAt} (${e.expiredDays}d ago) — blocking again`);
		}
		for (const bad of quarantine.invalid) {
			console.log(`INVALID      ${bad.reason}`);
		}
		if (!quarantine.active.length && !quarantine.expired.length && !quarantine.invalid.length) {
			console.log('No quarantined tests.');
		}
	}
	return args.check && (quarantine.invalid.length || quarantine.expired.length) ? 1 : 0;
}

function cmdRender(args) {
	const ledgerPath = str(args.ledger) ?? LEDGER_PATH;
	const ledger = existsSync(ledgerPath) ? JSON.parse(readFileSync(ledgerPath, 'utf8')) : null;
	const quarantine = loadQuarantine(str(args.quarantine) ?? QUARANTINE_PATH);
	appendSummary(renderLedger(ledger, quarantine));
	return 0;
}

// Only act as a CLI when invoked as a program. `node --test` imports this file
// for its functions, and importing must not run a subcommand or exit the
// process.
function main(argv) {
	const args = parseArgs(argv);
	const mode = args._[0];
	let code;
	switch (mode) {
	case 'select':
		code = cmdSelect(args);
		break;
	case 'record':
		code = cmdRecord(args);
		break;
	case 'ledger':
		code = cmdLedger(args);
		break;
	case 'quarantine':
		code = cmdQuarantine(args);
		break;
	case 'render':
		code = cmdRender(args);
		break;
	default:
		process.stderr.write(
			'usage: flake-ledger.mjs select|record|ledger|quarantine|render [options]\n' +
				'  select    --source <results dir|xml|json>[,...] [--quarantine f] [--out list.txt] [--as-file]\n' +
				'  record    --source <results>                    one pass whose runner retried in-process\n' +
				'            --source1 <results> [--source2 <retry results>]  two orchestrated attempts\n' +
				'            [--attempt1-failed] [--attempt2-failed] [--build-failed] [--require-results]\n' +
				'            --signal <name> --out <record.json>\n' +
				'  ledger    --records <dir> [--window-runs N] --out .github/flake-ledger.json\n' +
				'  quarantine [--file f] [--json] [--check]\n' +
				'  render    [--ledger f] [--quarantine f]\n'
		);
		code = 2;
	}
	return code;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
	process.exit(main(process.argv.slice(2)));
}