import { describe, it, expect } from 'vitest';
import { isDrillthroughColumnDiscovered } from './drillthroughColumnMatch';

describe('isDrillthroughColumnDiscovered (saiku#822/#823)', () => {
	it('matches a measure uniqueName by exact equality', () => {
		const discovered = new Set(['[Measures].[Store Sales]', '[Time].[Time].[Year]']);
		expect(isDrillthroughColumnDiscovered('[Measures].[Store Sales]', discovered)).toBe(true);
	});

	it('matches a dimension uniqueName as a bracket-prefix of a discovered level', () => {
		const discovered = new Set(['[Time].[Time].[Year]', '[Measures].[Store Sales]']);
		expect(isDrillthroughColumnDiscovered('[Time]', discovered)).toBe(true);
	});

	it('rejects a dimension whose name only shares a textual prefix, not a bracket boundary', () => {
		// [TimeZone] must not match just because it starts with the same
		// characters as [Time] — the '.' boundary is what makes it a real
		// ancestor relationship in MDX-qualified names.
		const discovered = new Set(['[TimeZone].[TimeZone].[Offset]']);
		expect(isDrillthroughColumnDiscovered('[Time]', discovered)).toBe(false);
	});

	it('returns false for a column absent from discovery', () => {
		const discovered = new Set(['[Time].[Time].[Year]']);
		expect(isDrillthroughColumnDiscovered('[Product]', discovered)).toBe(false);
	});

	it('returns false against an empty discovered set', () => {
		expect(isDrillthroughColumnDiscovered('[Time]', new Set())).toBe(false);
	});
});
