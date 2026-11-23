#!/usr/bin/env node
/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */

/**
 * Escaped-defect metric (ported from saiku-cloud#1421, itself saiku-cloud#1387).
 *
 * "Escaped defect" = a bug filed against code that had already merged. It is the
 * one number that says whether the CI pipeline is doing its job: a gate that is
 * always green and still lets defects through is not a gate. It is a REPORT
 * rendered into the quality-report job summary, never a gate (docs/quality.md).
 *
 * An issue counts as an escaped defect only when ALL of these hold:
 *   1. it is a bug (`Type: Bug`, the bug template's label, or Hive's `bug`);
 *   2. it *claims* to have escaped: the `escaped-defect` label (applied by
 *      `escaped-defect-label.yml` from the bug template's "Regressed by PR"
 *      field), a filled-in field, or the word "escaped" in the body;
 *   3. it names a pull request that is in fact merged, and merged no later than
 *      the issue was filed (a PR merged after the report is the fix, not the
 *      cause).
 *
 * Counting every issue that cites a merged PR was tried in saiku-cloud and
 * rejected: most issues cite a merged design or feature PR ("part of #1368"),
 * which reported 43 escapes in a week where ~2 were real. A bare `#N` is not
 * evidence. Unlike the saiku-cloud original, a label alone is not enough either:
 * the merged reference is always required, because a label can be applied for a
 * PR that is still open.
 *
 * Pure functions only, no API access and no clock reads outside `now`: the
 * workflow pipes JSON in, this file decides what it means. Tests live in
 * `escaped-defects.test.mjs` (`node --test ".github/scripts/*.test.mjs"`).
 */

import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

/** The label that makes an issue an escaped defect without a human tagging it. */
export const DEFECT_LABEL = 'escaped-defect';

const DAY_MS = 86_400_000;
const WEEK_MS = 7 * DAY_MS;

/**
 * Issue forms render an unanswered field as `### <label>` followed by
 * `_No response_`. Matching the heading alone would label every issue in the
 * repository, so the answer has to be present.
 */
const FIELD_HEADING = 'Regressed by PR';

/** Labels that mark an issue as a bug (the template's, Hive's). Compared lower-case. */
const BUG_LABELS = ['type: bug', 'bug'];

/** Wording that claims the issue is a defect that got past the gate. */
const ESCAPED_WORDING = /\bescaped\b/i;

/** Longest defect list rendered in one week row before it is summarised. */
const MAX_LINKS_PER_WEEK = 20;

/** Most PR numbers the workflow resolves in one run (one API call each). */
const DEFAULT_REFERENCE_LIMIT = 100;

/**
 * Pull-request references in an issue body.
 *
 * `#123` is a reference unless it is followed by extra path characters
 * (`#123abc`, a URL fragment), which is how GitHub itself disambiguates.
 * `owner/repo#123` is treated the same as `#123` and `/pull/123` URLs are read
 * too: this repository only ever references itself.
 *
 * @param {string|null|undefined} body
 * @returns {{pullRequests: number[]}}
 */
export function parseReferences(body) {
	const text = typeof body === 'string' ? body : '';
	const pullRequests = new Set();
	// Skip anything inside a fenced code block: a shell example containing `#12`
	// is not a claim about a PR.
	for (const line of stripCodeBlocks(text).split('\n')) {
		const re = /(?:[\w.-]+\/[\w.-]+)?#(\d+)(?![\w-])|\/pull\/(\d+)(?![\w-])/g;
		let match;
		while ((match = re.exec(line)) !== null) {
			const number = Number(match[1] ?? match[2]);
			if (Number.isSafeInteger(number) && number > 0) pullRequests.add(number);
		}
	}
	return { pullRequests: [...pullRequests].sort((a, b) => a - b) };
}

/** Text with fenced code blocks removed, so examples do not create references. */
function stripCodeBlocks(text) {
	return text.replace(/```[\s\S]*?(?:```|$)/g, '\n');
}

/**
 * Whether an issue form body carries a filled-in "Regressed by PR" field. This
 * is what makes the attribution automatic: the reporter names the PR, and CI
 * applies the label.
 *
 * @param {string|null|undefined} body
 * @returns {boolean}
 */
export function hasVerificationField(body) {
	if (typeof body !== 'string' || !body.includes(FIELD_HEADING)) return false;
	const lines = body.split('\n');
	const start = lines.findIndex((line) => line.trim().replace(/^#+\s*/, '') === FIELD_HEADING);
	if (start === -1) return false;
	for (let i = start + 1; i < lines.length; i += 1) {
		const line = lines[i].trim();
		// A new section header ends the field.
		if (/^#{1,6}\s+/.test(line)) break;
		if (line === '' || line === '_No response_') continue;
		return true;
	}
	return false;
}

/** Label names on a raw issue payload entry. */
function labelNames(raw) {
	if (!Array.isArray(raw?.labels)) return [];
	return raw.labels
		.map((label) => (typeof label === 'string' ? label : label?.name))
		.filter((name) => typeof name === 'string');
}

/**
 * Is this issue a bug? An escaped defect is by definition a bug; a feature
 * request or question that cites a merged PR is not one.
 *
 * @param {object} raw a GitHub issues payload entry
 * @returns {boolean}
 */
export function isBug(raw) {
	return labelNames(raw).some((name) => BUG_LABELS.includes(name.toLowerCase()));
}

/**
 * Does this issue claim to be an escaped defect? Either the label (applied by CI
 * from the template field) or the claim in the prose, which is how issues filed
 * before the template field existed are picked up.
 *
 * @param {object} raw a GitHub issues payload entry
 * @returns {boolean}
 */
export function claimsEscape(raw) {
	if (labelNames(raw).includes(DEFECT_LABEL)) return true;
	const body = typeof raw?.body === 'string' ? raw.body : '';
	return hasVerificationField(body) || ESCAPED_WORDING.test(body);
}

/** Normalise a GitHub issue payload entry into the fields we read. */
function normaliseIssue(raw) {
	if (!raw || typeof raw !== 'object') return null;
	if (raw.pull_request) return null; // the issues endpoint also returns PRs
	const created = Date.parse(raw.created_at ?? '');
	if (Number.isNaN(created)) return null;
	return {
		number: Number(raw.number),
		title: typeof raw.title === 'string' ? raw.title : '',
		url: typeof raw.html_url === 'string' ? raw.html_url : null,
		createdAt: created,
		labels: labelNames(raw),
		body: typeof raw.body === 'string' ? raw.body : ''
	};
}

/**
 * The Monday-anchored week a timestamp belongs to, keyed `YYYY-Www` (ISO week).
 *
 * Weeks start on Monday because the report is read on weekday mornings; a
 * Sunday-anchored week would make every Monday's report describe a week that
 * only just started.
 *
 * @param {number} ms epoch milliseconds
 * @returns {{key: string, start: number, end: number}}
 */
export function weekOf(ms) {
	const date = new Date(ms);
	const utcMidnight = Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate());
	const dayOfWeek = (new Date(utcMidnight).getUTCDay() + 6) % 7; // 0 = Monday
	const start = utcMidnight - dayOfWeek * DAY_MS;
	const end = start + WEEK_MS;
	return { key: mondayKey(start), start, end };
}

/** `YYYY-Www` for a Monday-anchored week start. */
function mondayKey(start) {
	const thursday = new Date(start + 3 * DAY_MS); // the week is named by its Thursday's year
	const year = thursday.getUTCFullYear();
	const firstThursday = Date.UTC(year, 0, 4);
	const firstMonday = firstThursday - ((new Date(firstThursday).getUTCDay() + 6) % 7) * DAY_MS;
	const week = Math.floor((start - firstMonday) / WEEK_MS) + 1;
	return `${year}-W${String(week).padStart(2, '0')}`;
}

/** Monday-anchored week starts for the `weeks` weeks up to and including now. */
export function recentWeekStarts(now, weeks) {
	const current = weekOf(now).start;
	return Array.from({ length: Math.max(1, weeks) }, (_, i) => current - (weeks - 1 - i) * WEEK_MS);
}

/**
 * The week boundaries the workflow queries for the per-week merged-PR
 * denominator, oldest first.
 *
 * @param {number} now epoch ms
 * @param {number} weeks
 * @returns {Array<{key: string, date: string}>} `date` is the Monday as YYYY-MM-DD (UTC)
 */
export function weekBoundaries(now, weeks) {
	return recentWeekStarts(now, weeks).map((start) => ({
		key: mondayKey(start),
		date: new Date(start).toISOString().slice(0, 10)
	}));
}

/**
 * Per-week merged-PR counts from cumulative `merged:>=<monday>` search totals.
 *
 * GitHub search has no two-sided `merged:` range (a one-sided query silently
 * returns the repo-wide total from that date), so each week's count is the
 * difference between its own boundary's cumulative total and the next one's. The
 * newest week is whatever has merged since its Monday. A boundary whose search
 * failed (`null`) makes both weeks it touches unknown: they are omitted, and
 * render as `n/a`, rather than inventing a difference that spans two weeks.
 *
 * @param {Array<{key: string, total: number|null}>} rows oldest first
 * @returns {Record<string, number>} week key → PRs merged that week
 */
export function weeklyMergedCounts(rows) {
	const list = Array.isArray(rows) ? rows : [];
	const counts = {};
	list.forEach((row, i) => {
		if (!Number.isInteger(row?.total)) return;
		const next = list[i + 1];
		if (i === list.length - 1) {
			counts[row.key] = row.total;
		} else if (Number.isInteger(next?.total) && row.total >= next.total) {
			counts[row.key] = row.total - next.total;
		}
	});
	return counts;
}

/**
 * Issues worth resolving PR state for: bugs that claim to have escaped. Only
 * these can be counted, so only their references need an API call. Newest first,
 * capped, so a long tail of old issues cannot exhaust the run.
 *
 * @param {Array<object>} issues
 * @param {number} [limit]
 * @returns {number[]} PR numbers, ascending
 */
export function candidateReferences(issues, limit = DEFAULT_REFERENCE_LIMIT) {
	const list = Array.isArray(issues) ? issues : [];
	const numbers = new Set();
	const candidates = list
		.filter((raw) => raw && !raw.pull_request && isBug(raw) && claimsEscape(raw))
		.sort((a, b) => Date.parse(b.created_at ?? '') - Date.parse(a.created_at ?? ''));
	for (const raw of candidates) {
		for (const number of parseReferences(raw.body).pullRequests) {
			if (numbers.size >= limit) break;
			numbers.add(number);
		}
	}
	return [...numbers].sort((a, b) => a - b);
}

/**
 * Bucket the escaped defects into weeks.
 *
 * @param {object} input
 * @param {Array<object>} input.issues       GitHub issues payload entries (PRs tolerated; they are dropped)
 * @param {Record<string, {merged_at?: string|null}>} [input.pullRequests]
 *        pull-request number (as a string key) → the PR's state; a PR counts as
 *        a merged reference only when `merged_at` is set and not after the issue
 * @param {number} [input.now]               epoch ms, injected so the output is testable
 * @param {number} [input.window]            how many weeks to report
 * @param {Record<string, number>} [input.mergedCounts]  week key → PRs merged that week (rate denominator)
 * @returns {{label: string, generatedAt: string, window: number, total: number,
 *            weeks: Array<object>, unlinked: Array<object>}}
 */
export function summariseDefects(input) {
	const now = Number.isFinite(input?.now) ? input.now : Date.now();
	const window = Number.isInteger(input?.window) && input.window > 0 ? input.window : 8;
	const mergedCounts = input?.mergedCounts ?? {};
	const pullRequests = input?.pullRequests ?? {};
	const issues = Array.isArray(input?.issues) ? input.issues : [];

	const starts = recentWeekStarts(now, window);
	const weeks = starts.map((start) => ({
		key: mondayKey(start),
		start,
		end: start + WEEK_MS,
		count: 0,
		mergedPrs: Number.isFinite(mergedCounts[mondayKey(start)]) ? mergedCounts[mondayKey(start)] : null,
		defects: []
	}));

	for (const raw of issues) {
		// The issues endpoint also returns pull requests; they are not defects.
		if (!raw || typeof raw !== 'object' || raw.pull_request) continue;
		if (!isBug(raw) || !claimsEscape(raw)) continue;
		const issue = normaliseIssue(raw);
		if (!issue) continue;
		const mergedRefs = parseReferences(issue.body).pullRequests.filter((number) =>
			mergedBeforeReport(pullRequests[String(number)], issue.createdAt)
		);
		// The claim has to be checkable: an escaped defect names something that
		// was merged before it was reported. A claim with no such reference is a
		// mistake in the report, and counting it would put a lie in the trend line.
		if (mergedRefs.length === 0) continue;

		const labelled = issue.labels.includes(DEFECT_LABEL);
		const defect = {
			number: issue.number,
			title: issue.title,
			url: issue.url,
			via: labelled ? 'label + merged reference' : 'merged reference',
			labelled,
			mergedRefs: mergedRefs.map((number) => ({
				number,
				url: pullRequests[String(number)]?.html_url ?? null,
				mergedAt: pullRequests[String(number)]?.merged_at ?? null
			}))
		};
		const bucket = weeks.find((candidate) => issue.createdAt >= candidate.start && issue.createdAt < candidate.end);
		if (bucket) {
			bucket.count += 1;
			bucket.defects.push(defect);
		}
	}

	weeks.forEach((week) => {
		week.defects.sort((a, b) => a.number - b.number);
		week.rate =
			week.count > 0 && Number.isFinite(week.mergedPrs) && week.mergedPrs > 0
				? Number((week.count / week.mergedPrs).toFixed(2))
				: null;
	});

	return {
		label: DEFECT_LABEL,
		generatedAt: new Date(now).toISOString(),
		window,
		total: weeks.reduce((sum, week) => sum + week.count, 0),
		weeks
	};
}

/** A PR is a valid cause only if it merged, and no later than the issue was filed. */
function mergedBeforeReport(pr, issueCreatedAt) {
	const mergedAt = Date.parse(pr?.merged_at ?? '');
	return !Number.isNaN(mergedAt) && mergedAt <= issueCreatedAt;
}

/**
 * Trend across the reported weeks: the latest week's count against the average
 * of the weeks before it. A single week is noise; the direction over three is
 * the signal, so the average is over the earlier reported weeks only.
 */
export function trend(weeks) {
	const list = Array.isArray(weeks) ? weeks : [];
	if (list.length === 0) return { latest: null, previousAverage: null, direction: 'unknown' };
	const latest = list[list.length - 1];
	const earlier = list.slice(0, -1).map((week) => week.count);
	if (earlier.length === 0) return { latest: latest.count, previousAverage: null, direction: 'unknown' };
	const previousAverage = earlier.reduce((sum, count) => sum + count, 0) / earlier.length;
	const direction = latest.count < previousAverage ? 'improving' : latest.count > previousAverage ? 'worsening' : 'flat';
	return {
		latest: latest.count,
		previousAverage: Number(previousAverage.toFixed(1)),
		direction
	};
}

/** Markdown section for the quality dashboard job summary. Empty for a missing summary. */
export function renderDefects(summary) {
	if (!summary) return '';
	const lines = [];
	lines.push('### Escaped defects (bugs found after the PR merged)');
	lines.push('');
	lines.push(
		`_${summary.total} over the last ${summary.window} weeks. The target is **0 per week** — ` +
			'every entry is a bug filed against code a green merge had let through (`docs/quality.md`). ' +
			'Only bugs that have an issue are seen._'
	);
	lines.push('');
	lines.push('| week | escaped defects | PRs merged | rate | links |');
	lines.push('|---|---|---|---|---|');
	for (const week of summary.weeks) {
		const shown = week.defects.slice(0, MAX_LINKS_PER_WEEK);
		const links =
			shown.length === 0
				? '—'
				: shown
						.map((defect) => {
							const issue = defect.url ? `[#${defect.number}](${defect.url})` : `#${defect.number}`;
							const prs = defect.mergedRefs
								.map((pr) => (pr.url ? `[PR #${pr.number}](${pr.url})` : `PR #${pr.number}`))
								.join(' ');
							return prs ? `${issue} (${prs})` : issue;
						})
						.join(', ');
		const more = week.defects.length - shown.length;
		lines.push(
			`| ${week.key} | ${week.count} | ${week.mergedPrs ?? 'n/a'} | ${week.rate ?? 'n/a'} | ` +
				`${more > 0 ? `${links}, _+${more} more_` : links} |`
		);
	}
	lines.push('');

	const stats = trend(summary.weeks);
	if (stats.previousAverage !== null) {
		lines.push(
			`_Trend: latest week ${stats.latest} against ${stats.previousAverage} per week over the earlier ` +
				`${summary.weeks.length - 1} week(s) — ${stats.direction}._`
		);
	} else {
		lines.push(`_Trend: latest week ${stats.latest}; not enough earlier weeks to compare yet._`);
	}
	lines.push('');
	lines.push(
		`_Attribution: a bug whose "Regressed by PR" field names a PR gets the \`${summary.label}\` label from ` +
			'`escaped-defect-label.yml`. A bug is counted only when it claims to have escaped AND names a PR that merged ' +
			'before it was filed; merely citing a merged PR is not a defect._'
	);
	lines.push('');
	return lines.join('\n');
}

function parseArgs(argv) {
	const args = { _: [] };
	for (let i = 0; i < argv.length; i += 1) {
		const arg = argv[i];
		if (arg.startsWith('--')) {
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

function readJson(path, fallback) {
	if (typeof path !== 'string' || !existsSync(path)) return fallback;
	try {
		return JSON.parse(readFileSync(path, 'utf8'));
	} catch {
		return fallback;
	}
}

/** The issues array from a `{issues: [...]}` or bare-array payload, or null when unreadable. */
function readIssues(path) {
	const payload = readJson(path, null);
	if (payload === null) return null;
	return Array.isArray(payload) ? payload : Array.isArray(payload.issues) ? payload.issues : null;
}

/** Weeks to report: `--weeks N`, else 8. */
function weeksArg(args) {
	const weeks = Number(args.weeks);
	return Number.isInteger(weeks) && weeks > 0 ? weeks : 8;
}

/**
 * `label --body <file>`: prints the label to apply, or `none`. Used by
 * `escaped-defect-label.yml` so the attribution rule has one implementation.
 */
function labelMode(args) {
	const body = typeof args.body === 'string' ? readFileSync(args.body, 'utf8') : '';
	process.stdout.write(`${hasVerificationField(body) ? DEFECT_LABEL : 'none'}\n`);
}

/**
 * The escaped-defect summary for the quality dashboard, or `null` when the
 * caller had no issues payload.
 *
 * `null` is the honest answer for "the issues API read failed": rendering a
 * zero there would paint a broken step as a clean week. The dashboard then
 * simply omits the section.
 *
 * @param {Record<string, unknown>} args `collect` flags, as parsed by the caller
 * @param {number} [now] epoch ms, injected so the output is testable
 * @returns {object|null}
 */
export function collectDefects(args, now = Date.now()) {
	const issues = readIssues(args?.issues);
	if (!issues) return null;
	return summariseDefects({
		issues,
		pullRequests: readJson(args['pull-requests'], {}),
		mergedCounts: readJson(args['merged-counts'], {}),
		window: weeksArg(args),
		now
	});
}

/** `collect --issues … --pull-requests … --merged-counts …`: the summary as JSON. */
function collectMode(args) {
	const summary = collectDefects(args);
	if (!summary) {
		process.stderr.write('escaped-defects: no issues payload; run with --issues <file>\n');
		process.exit(2);
	}
	writeOut(args, `${JSON.stringify(summary, null, 2)}\n`);
}

/**
 * `render --issues …`: the Markdown section, or NOTHING (exit 3) when there is no
 * issues payload, so the workflow omits the section instead of printing a clean week.
 */
function renderMode(args) {
	const summary = collectDefects(args);
	if (!summary) {
		process.stderr.write('escaped-defects: no issues payload; section omitted\n');
		process.exit(3);
	}
	writeOut(args, renderDefects(summary));
}

/** `refs --issues <file> [--limit N]`: PR numbers whose merge state the workflow must fetch. */
function refsMode(args) {
	const issues = readIssues(args.issues) ?? [];
	const limit = Number.isInteger(Number(args.limit)) && Number(args.limit) > 0 ? Number(args.limit) : undefined;
	process.stdout.write(candidateReferences(issues, limit).map((n) => `${n}\n`).join(''));
}

/** `boundaries [--weeks N]`: `<week-key> <YYYY-MM-DD>` lines for the denominator searches. */
function boundariesMode(args) {
	const now = typeof args.now === 'string' ? Date.parse(args.now) : Date.now();
	process.stdout.write(weekBoundaries(now, weeksArg(args)).map((b) => `${b.key} ${b.date}\n`).join(''));
}

/** `merged-counts --totals <file>`: JSON of week → merged PRs from `<key> <total|skip>` lines. */
function mergedCountsMode(args) {
	const text = typeof args.totals === 'string' && existsSync(args.totals) ? readFileSync(args.totals, 'utf8') : '';
	const rows = text
		.split('\n')
		.map((line) => line.trim().split(/\s+/))
		.filter((parts) => parts.length === 2)
		.map(([key, total]) => ({ key, total: /^\d+$/.test(total) ? Number(total) : null }));
	process.stdout.write(`${JSON.stringify(weeklyMergedCounts(rows))}\n`);
}

function writeOut(args, text) {
	if (args.out && args.out !== true) writeFileSync(args.out, text);
	else process.stdout.write(text);
}

const MODES = {
	label: labelMode,
	collect: collectMode,
	render: renderMode,
	refs: refsMode,
	boundaries: boundariesMode,
	'merged-counts': mergedCountsMode
};

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
	const args = parseArgs(process.argv.slice(2));
	const mode = MODES[args._[0]];
	if (!mode) {
		process.stderr.write(`usage: escaped-defects.mjs ${Object.keys(MODES).join('|')} [options]\n`);
		process.exit(2);
	}
	mode(args);
}
