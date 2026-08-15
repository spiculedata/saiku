import { describe, expect, it } from 'vitest';
import { friendlyExecuteError } from './query.svelte';

describe('friendlyExecuteError', () => {
	it('returns a contact-support message for Mondrian UnsupportedTranslation', () => {
		const raw =
			'mondrian.calcite.UnsupportedTranslation: cannot translate operator TIMESTAMP_TRUNC to dialect H2';
		expect(friendlyExecuteError(raw)).toBe('Query failed to run. Please contact support.');
	});

	it('returns the raw message for ordinary MDX parse errors', () => {
		const raw = "MDX syntax error at line 1: unexpected token 'WITHH'";
		expect(friendlyExecuteError(raw)).toBe(raw);
	});

	it('matches case-insensitively across the engine error family', () => {
		expect(friendlyExecuteError('Caused by NullPointerException at row 4')).toBe(
			'Query failed to run. Please contact support.'
		);
		expect(friendlyExecuteError('CalciteContextException at line 3: parse error')).toBe(
			'Query failed to run. Please contact support.'
		);
		expect(friendlyExecuteError('RolapEvaluatorException: internal state')).toBe(
			'Query failed to run. Please contact support.'
		);
	});

	it("passes through messages that don't name an internal exception", () => {
		expect(friendlyExecuteError('Datasource not found: foodmart-postgres')).toBe(
			'Datasource not found: foodmart-postgres'
		);
	});
});
