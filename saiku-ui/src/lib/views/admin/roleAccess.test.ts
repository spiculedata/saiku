import { describe, expect, it } from 'vitest';
import {
	accessHint,
	accessLabel,
	accessTone,
	modeLabel,
	parseRoleList,
	toggle
} from './roleAccess';

describe('roleAccess (saiku#779)', () => {
	it('labels and tones every access outcome', () => {
		expect(accessLabel('SCOPED')).toBe('Scoped');
		expect(accessTone('SCOPED')).toBe('success');
		expect(accessTone('DENIED')).toBe('error');
		expect(accessTone('UNSECURED')).toBe('warning');
		expect(accessHint('FULL_ADMIN')).toMatch(/root/);
	});

	it('labels security modes', () => {
		expect(modeLabel('LOOKUP')).toBe('Lookup table');
		expect(modeLabel('DISABLED')).toBe('Off');
	});

	it('parses comma and newline separated role lists', () => {
		expect(parseRoleList(' ROLE_A, ROLE_B\nROLE_A,,\n ')).toEqual(['ROLE_A', 'ROLE_B']);
		expect(parseRoleList('')).toEqual([]);
	});

	it('toggles a role in a list', () => {
		expect(toggle(['a', 'b'], 'a')).toEqual(['b']);
		expect(toggle(['a'], 'b')).toEqual(['a', 'b']);
	});
});
