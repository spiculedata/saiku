/*
 * Unit tests for the pure path helpers in notebooks.ts (issue #1108),
 * mirroring dashboards.test.ts for the same helpers on the dashboard side.
 */

import { describe, expect, it } from 'vitest';

import { displayPath, normaliseNotebookPath, normaliseRepoPath, toRepoRelative } from './notebooks';

describe('normaliseNotebookPath', () => {
	it('prefixes a bare filename with homes/<user>', () => {
		expect(normaliseNotebookPath('oops.saikunb', 'juan')).toBe('homes/juan/oops.saikunb');
	});

	it('prefixes a relative subfolder path with homes/<user>', () => {
		expect(normaliseNotebookPath('marketing/q4.saikunb', 'juan')).toBe(
			'homes/juan/marketing/q4.saikunb'
		);
	});

	it('leaves an existing homes/... path untouched', () => {
		expect(normaliseNotebookPath('homes/juan/foo.saikunb', 'juan')).toBe('homes/juan/foo.saikunb');
		expect(normaliseNotebookPath('homes/admin/x.saikunb', 'juan')).toBe('homes/admin/x.saikunb');
	});

	it('strips a leading slash and otherwise preserves an explicit absolute path', () => {
		expect(normaliseNotebookPath('/marketing/q4.saikunb', 'juan')).toBe('marketing/q4.saikunb');
	});

	it('trims surrounding whitespace before deciding', () => {
		expect(normaliseNotebookPath('  oops.saikunb  ', 'juan')).toBe('homes/juan/oops.saikunb');
	});

	it('throws on an empty or whitespace-only path', () => {
		expect(() => normaliseNotebookPath('', 'juan')).toThrow(/required/);
		expect(() => normaliseNotebookPath('   ', 'juan')).toThrow(/required/);
	});

	it('throws on a relative path with no current user', () => {
		expect(() => normaliseNotebookPath('oops.saikunb', '')).toThrow(/no current user/);
	});
});

describe('toRepoRelative', () => {
	it('strips the saiku-home filesystem prefix down to the repo-relative path', () => {
		expect(
			toRepoRelative('/Users/x/saiku-home/repository/data/unknown/homes/admin/foo.saikunb')
		).toBe('homes/admin/foo.saikunb');
	});

	it('passes an already-relative path through unchanged (normalised)', () => {
		expect(toRepoRelative('homes/admin/foo.saikunb')).toBe('homes/admin/foo.saikunb');
	});
});

describe('normaliseRepoPath', () => {
	it('strips leading/trailing slashes and collapses duplicates', () => {
		expect(normaliseRepoPath('/homes//admin/foo.saikunb/')).toBe('homes/admin/foo.saikunb');
	});
});

describe('displayPath', () => {
	it('drops the .saikunb extension', () => {
		expect(displayPath('homes/admin/sales.saikunb')).toBe('homes/admin/sales');
	});

	it('leaves a path without the extension unchanged', () => {
		expect(displayPath('homes/admin/sales')).toBe('homes/admin/sales');
	});
});
