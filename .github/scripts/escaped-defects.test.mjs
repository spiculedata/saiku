/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */

/**
 * Unit tests for the escaped-defect metric (ported from saiku-cloud#1421).
 *
 * The rule under test: a BUG that claims it escaped AND names a PR that had
 * already merged is counted per week; merely citing a merged PR is not. Every
 * date is a fixed fixture and `now` is injected: nothing here reads the clock.
 * Run with `node --test ".github/scripts/*.test.mjs"`.
 */

import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

import {
	DEFECT_LABEL,
	candidateReferences,
	claimsEscape,
	hasVerificationField,
	isBug,
	parseReferences,
	renderDefects,
	summariseDefects,
	trend,
	weekBoundaries,
	weekOf,
	weeklyMergedCounts
} from './escaped-defects.mjs';

const SCRIPT = fileURLToPath(new URL('./escaped-defects.mjs', import.meta.url));
// Wednesday 2026-10-07; the week it belongs to started Monday 2026-10-05.
const NOW = Date.parse('2026-10-07T12:00:00Z');
const THIS_WEEK = '2026-W41';
const FIELD = (answer) => `### Regressed by PR\n\n${answer}\n\n### Relevant logs\nnone`;

const bug = (number, createdAt, extra = {}) => ({
	number,
	title: `defect ${number}`,
	html_url: `https://github.com/spiculedata/saiku/issues/${number}`,
	created_at: createdAt,
	labels: [{ name: 'Type: Bug' }],
	body: '',
	...extra
});

const mergedPr = (number, mergedAt = '2026-10-02T12:00:00Z') => ({
	[String(number)]: { merged_at: mergedAt, html_url: `https://github.com/spiculedata/saiku/pull/${number}` }
});

const thisWeek = (summary) => summary.weeks.find((w) => w.key === THIS_WEEK);

test('a pull-request reference is recognised in #N, owner/repo#N and /pull/N forms', () => {
	assert.deepEqual(parseReferences('found while verifying #1387 and again in #12').pullRequests, [12, 1387]);
	assert.deepEqual(parseReferences('see spiculedata/saiku#99').pullRequests, [99]);
	assert.deepEqual(parseReferences('https://github.com/spiculedata/saiku/pull/2100').pullRequests, [2100]);
});

test('a colour or anchor that merely starts with # is not a reference', () => {
	assert.deepEqual(parseReferences('colour #3a2b1c and anchor #section-2').pullRequests, []);
});

test('a reference inside a fenced code block is not a claim about a PR', () => {
	const body = ['```bash', 'gh issue close #7', '```', 'but really #8'].join('\n');
	assert.deepEqual(parseReferences(body).pullRequests, [8]);
});

test('an unanswered template field is not attribution', () => {
	assert.equal(hasVerificationField('### Regressed by PR\n_No response_\n\n### Notes\nlogs'), false);
	assert.equal(hasVerificationField('### Regressed by PR\n\n### Relevant logs\n#12'), false);
	assert.equal(hasVerificationField('no field here'), false);
});

test('a filled-in "Regressed by PR" field is attribution', () => {
	assert.equal(hasVerificationField(FIELD('#1387')), true);
	assert.equal(hasVerificationField('### Regressed by PR\nthe export change'), true);
});

test('isBug accepts the template label and the Hive label, case-insensitively', () => {
	assert.equal(isBug({ labels: [{ name: 'Type: Bug' }] }), true);
	assert.equal(isBug({ labels: ['bug'] }), true);
	assert.equal(isBug({ labels: [{ name: 'type: bug' }] }), true);
	assert.equal(isBug({ labels: [{ name: 'Type: Enhancement' }, 'docs'] }), false);
	assert.equal(isBug({}), false);
	assert.equal(isBug(null), false);
});

test('a bug that claims it escaped and names a merged PR is counted with both links', () => {
	const summary = summariseDefects({
		now: NOW,
		issues: [bug(2201, '2026-10-06T10:00:00Z', { body: FIELD('#2100') })],
		pullRequests: mergedPr(2100)
	});
	const week = thisWeek(summary);
	assert.equal(week.count, 1);
	assert.equal(week.defects[0].url, 'https://github.com/spiculedata/saiku/issues/2201');
	assert.equal(week.defects[0].mergedRefs[0].number, 2100);
	assert.equal(week.defects[0].via, 'merged reference');
});

test('the escaped-defect label plus a merged reference is counted and says so', () => {
	const summary = summariseDefects({
		now: NOW,
		issues: [bug(2202, '2026-10-06T10:00:00Z', { labels: [{ name: 'Type: Bug' }, { name: DEFECT_LABEL }], body: 'see #2100' })],
		pullRequests: mergedPr(2100)
	});
	assert.equal(thisWeek(summary).defects[0].via, 'label + merged reference');
});

test('the label alone, with no merged PR behind it, is not counted', () => {
	const summary = summariseDefects({
		now: NOW,
		issues: [bug(2203, '2026-10-06T10:00:00Z', { labels: [{ name: 'Type: Bug' }, { name: DEFECT_LABEL }] })]
	});
	assert.equal(summary.total, 0);
});

test('an open PR reference is not an escaped defect', () => {
	const summary = summariseDefects({
		now: NOW,
		issues: [bug(2204, '2026-10-06T10:00:00Z', { body: FIELD('#2101') })],
		pullRequests: { 2101: { merged_at: null, html_url: null } }
	});
	assert.equal(summary.total, 0);
});

test('a PR merged after the issue was filed is the fix, not the cause', () => {
	const summary = summariseDefects({
		now: NOW,
		issues: [bug(2205, '2026-10-06T10:00:00Z', { body: FIELD('#2102') })],
		pullRequests: mergedPr(2102, '2026-10-06T11:00:00Z')
	});
	assert.equal(summary.total, 0);
});

test('a feature request or question citing a merged PR is not a defect, however it is worded', () => {
	const summary = summariseDefects({
		now: NOW,
		issues: [
			bug(2206, '2026-10-06T10:00:00Z', { labels: [{ name: 'Type: Enhancement' }], body: 'This escaped #2100' }),
			bug(2207, '2026-10-06T10:00:00Z', { labels: [], body: FIELD('#2100') })
		],
		pullRequests: mergedPr(2100)
	});
	assert.equal(summary.total, 0);
});

test('merely citing a merged PR is not a defect: most issues cite a merged design PR', () => {
	// The rejected rule reported 43 false escapes in one week against ~2 real.
	const summary = summariseDefects({
		now: NOW,
		issues: [
			bug(2208, '2026-10-06T10:00:00Z', { body: 'Part of #2100. Design: docs/plans/foo.md' }),
			bug(2209, '2026-10-06T10:00:00Z', { body: 'Related to #2100, see also #2103' })
		],
		pullRequests: { ...mergedPr(2100), ...mergedPr(2103) }
	});
	assert.equal(summary.total, 0);
});

test('a body that says the defect escaped is a claim even without the label or field', () => {
	const summary = summariseDefects({
		now: NOW,
		issues: [bug(2210, '2026-10-06T10:00:00Z', { body: 'This escaped #2100: the export drops a column' })],
		pullRequests: mergedPr(2100)
	});
	assert.equal(summary.total, 1);
});

test('claimsEscape reads the label, the template field and the wording', () => {
	assert.equal(claimsEscape({ labels: [{ name: DEFECT_LABEL }], body: '' }), true);
	assert.equal(claimsEscape({ labels: ['bug'], body: FIELD('#1') }), true);
	assert.equal(claimsEscape({ labels: [], body: 'escaped the gate in #1' }), true);
	assert.equal(claimsEscape({ labels: [], body: '### Regressed by PR\n_No response_\n' }), false);
	assert.equal(claimsEscape({ labels: [], body: 'part of #1' }), false);
	assert.equal(claimsEscape(null), false);
});

test('a PR in the issues payload is not an issue', () => {
	const summary = summariseDefects({
		now: NOW,
		issues: [{ ...bug(2100, '2026-10-01T10:00:00Z', { body: FIELD('#1') }), pull_request: { url: 'x' } }],
		pullRequests: mergedPr(1, '2026-09-01T00:00:00Z')
	});
	assert.equal(summary.total, 0);
});

test('defects land in the Monday-anchored week they were filed in', () => {
	const pullRequests = mergedPr(2100, '2026-09-01T00:00:00Z');
	const summary = summariseDefects({
		now: NOW,
		pullRequests,
		issues: [
			bug(1, '2026-10-04T23:00:00Z', { body: FIELD('#2100') }), // Sunday
			bug(2, '2026-10-05T00:30:00Z', { body: FIELD('#2100') }) // Monday
		]
	});
	const counts = Object.fromEntries(summary.weeks.filter((w) => w.count > 0).map((w) => [w.key, w.count]));
	assert.deepEqual(counts, { '2026-W40': 1, '2026-W41': 1 });
	assert.equal(weekOf(Date.parse('2026-10-04T23:00:00Z')).key, '2026-W40');
});

test('issues older than the window are not silently counted as this week', () => {
	const summary = summariseDefects({
		now: NOW,
		window: 2,
		issues: [bug(9, '2026-06-01T10:00:00Z', { body: FIELD('#2100') })],
		pullRequests: mergedPr(2100, '2026-05-01T00:00:00Z')
	});
	assert.equal(summary.total, 0);
});

test('the rate is defects per merged PR, and null when the denominator is unknown', () => {
	const issues = [bug(1, '2026-10-06T10:00:00Z', { body: FIELD('#2100') })];
	const withCount = summariseDefects({ now: NOW, issues, pullRequests: mergedPr(2100), mergedCounts: { [THIS_WEEK]: 10 } });
	assert.equal(thisWeek(withCount).rate, 0.1);
	const without = summariseDefects({ now: NOW, issues, pullRequests: mergedPr(2100) });
	assert.equal(thisWeek(without).rate, null);
	assert.equal(thisWeek(without).mergedPrs, null);
});

test('the trend compares the latest week with the earlier weeks', () => {
	const weeks = [{ count: 4 }, { count: 2 }, { count: 0 }];
	assert.deepEqual(trend(weeks), { latest: 0, previousAverage: 3, direction: 'improving' });
	assert.deepEqual(trend([{ count: 1 }, { count: 1 }]), { latest: 1, previousAverage: 1, direction: 'flat' });
	assert.deepEqual(trend([{ count: 1 }, { count: 4 }]), { latest: 4, previousAverage: 1, direction: 'worsening' });
	assert.equal(trend([]).direction, 'unknown');
	assert.equal(trend([{ count: 2 }]).previousAverage, null);
});

test('the rendered section links the issue and the PR it escaped from, and states the target', () => {
	const summary = summariseDefects({
		now: NOW,
		issues: [bug(2201, '2026-10-06T10:00:00Z', { body: FIELD('#2100') })],
		pullRequests: mergedPr(2100)
	});
	const md = renderDefects(summary);
	assert.match(md, /### Escaped defects/);
	assert.match(md, /\| 2026-W41 \| 1 \|/);
	assert.match(md, /\[#2201\]\(https:\/\/github.com\/spiculedata\/saiku\/issues\/2201\)/);
	assert.match(md, /\[PR #2100\]\(https:\/\/github.com\/spiculedata\/saiku\/pull\/2100\)/);
	assert.match(md, /target is \*\*0 per week\*\*/);
	assert.equal(renderDefects(null), '');
});

test('weekBoundaries lists the Mondays oldest first, keyed like the report weeks', () => {
	assert.deepEqual(weekBoundaries(NOW, 3), [
		{ key: '2026-W39', date: '2026-09-21' },
		{ key: '2026-W40', date: '2026-09-28' },
		{ key: '2026-W41', date: '2026-10-05' }
	]);
});

test('weekly merged counts are differences of cumulative totals, and the newest week is its own total', () => {
	// merged:>=Monday is cumulative: W39 total 30 includes everything merged since.
	const counts = weeklyMergedCounts([
		{ key: '2026-W39', total: 30 },
		{ key: '2026-W40', total: 22 },
		{ key: '2026-W41', total: 9 }
	]);
	assert.deepEqual(counts, { '2026-W39': 8, '2026-W40': 13, '2026-W41': 9 });
});

test('a failed search leaves both weeks it touches unknown rather than inventing a difference', () => {
	const counts = weeklyMergedCounts([
		{ key: '2026-W39', total: 30 },
		{ key: '2026-W40', total: null },
		{ key: '2026-W41', total: 9 }
	]);
	assert.deepEqual(counts, { '2026-W41': 9 });
	assert.deepEqual(weeklyMergedCounts([{ key: 'a', total: 1 }, { key: 'b', total: 5 }]), { b: 5 }, 'a non-monotonic pair is dropped');
	assert.deepEqual(weeklyMergedCounts(undefined), {});
});

test('candidateReferences only asks about bugs that claim to have escaped, newest first, capped', () => {
	const issues = [
		bug(1, '2026-10-01T00:00:00Z', { body: FIELD('#10') }),
		bug(2, '2026-10-02T00:00:00Z', { body: 'escaped #20 and #21' }),
		bug(3, '2026-10-03T00:00:00Z', { body: 'part of #30' }), // no claim
		bug(4, '2026-10-04T00:00:00Z', { labels: [], body: 'escaped #40' }) // not a bug
	];
	assert.deepEqual(candidateReferences(issues), [10, 20, 21]);
	assert.deepEqual(candidateReferences(issues, 2), [20, 21], 'the newest claims win the cap');
});

const run = (...args) => execFileSync(process.execPath, [SCRIPT, ...args], { encoding: 'utf8' });

test('the label mode applies the label only when the field was filled in', () => {
	const dir = mkdtempSync(join(tmpdir(), 'escaped-defects-'));
	const label = (name, body) => {
		const file = join(dir, name);
		writeFileSync(file, body);
		return run('label', '--body', file).trim();
	};
	assert.equal(label('filled.md', FIELD('#2100')), DEFECT_LABEL);
	assert.equal(label('empty.md', '### Regressed by PR\n_No response_\n'), 'none');
	assert.equal(label('none.md', 'nothing here'), 'none');
});

test('the render mode prints the section, and prints nothing for a missing payload', () => {
	const dir = mkdtempSync(join(tmpdir(), 'escaped-defects-'));
	const issues = join(dir, 'issues.json');
	writeFileSync(issues, JSON.stringify({ issues: [] }));
	assert.match(run('render', '--issues', issues), /### Escaped defects/);

	const missing = join(dir, 'absent.json');
	assert.throws(() => execFileSync(process.execPath, [SCRIPT, 'render', '--issues', missing], { stdio: 'pipe' }), (error) => {
		assert.equal(error.status, 3);
		assert.equal(error.stdout.toString(), '', 'an omitted section prints nothing, not a clean week');
		return true;
	});
});

test('the boundaries and merged-counts modes agree on the week keys', () => {
	const lines = run('boundaries', '--weeks', '2', '--now', '2026-10-07T12:00:00Z').trim().split('\n');
	assert.deepEqual(lines, ['2026-W40 2026-09-28', '2026-W41 2026-10-05']);
	const dir = mkdtempSync(join(tmpdir(), 'escaped-defects-'));
	const totals = join(dir, 'totals.txt');
	writeFileSync(totals, '2026-W40 20\n2026-W41 7\n');
	assert.deepEqual(JSON.parse(run('merged-counts', '--totals', totals)), { '2026-W40': 13, '2026-W41': 7 });
	writeFileSync(totals, '2026-W40 skip\n2026-W41 7\n');
	assert.deepEqual(JSON.parse(run('merged-counts', '--totals', totals)), { '2026-W41': 7 });
});
