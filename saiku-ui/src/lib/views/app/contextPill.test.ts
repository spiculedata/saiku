/*
 * Tests for the header context selector's pure logic.
 *
 * Before this the pill was decorative — it rendered "STORE / Portland #14 ▾"
 * and did nothing when clicked. These cover the three ways an author can name a
 * member (unique name, caption, or the ALL sentinel) and the "not bound to a
 * level" case where the pill is purely cosmetic.
 */

import { describe, expect, it } from 'vitest';
import type { AppContextPill } from '$lib/api/apps';
import {
	ALL_MEMBER,
	MAX_LEVEL_OPTIONS,
	effectiveLabel,
	isLevelSourced,
	isSelectable,
	levelOptionsTruncated,
	optionsFromMembers,
	optionByLabel,
	optionsFor,
	selectionFor
} from './contextPill';

const TARGET = { dimension: 'Store', hierarchy: 'Stores', level: 'Store Name' };

const PILL: AppContextPill = {
	label: 'Store',
	value: 'Portland #14',
	filter: TARGET,
	options: [
		{ label: 'Portland #14' },
		{ label: 'Seattle #3', member: '[Store].[Stores].[USA].[WA].[Seattle].[Store 3]' },
		{ label: 'All stores · National', member: ALL_MEMBER }
	]
};

describe('isSelectable', () => {
	it('is false without options — the pill stays static text', () => {
		expect(isSelectable(undefined)).toBe(false);
		expect(isSelectable({ label: 'Store', value: 'Portland #14' })).toBe(false);
		expect(isSelectable({ label: 'Store', value: 'x', options: [] })).toBe(false);
	});

	it('is true once the author lists options', () => {
		expect(isSelectable(PILL)).toBe(true);
	});
});

describe('optionsFor', () => {
	it("returns the author's list when it already contains the current value", () => {
		expect(optionsFor(PILL).map((o) => o.label)).toEqual([
			'Portland #14',
			'Seattle #3',
			'All stores · National'
		]);
	});

	/* Otherwise the pill would display a value its own dropdown doesn't offer,
	 * and the selector would look wrong the moment it opened. */
	it('prepends the current value when the list omits it', () => {
		const pill: AppContextPill = { label: 'Store', value: 'Denver #9', options: [{ label: 'A' }] };
		expect(optionsFor(pill).map((o) => o.label)).toEqual(['Denver #9', 'A']);
	});

	it('returns nothing for a pill with no options', () => {
		expect(optionsFor(undefined)).toEqual([]);
		expect(optionsFor({ label: 'Store', value: 'x' })).toEqual([]);
	});
});

describe('effectiveLabel', () => {
	it('shows the live selection', () => {
		expect(effectiveLabel(PILL, 'Seattle #3')).toBe('Seattle #3');
	});

	it('falls back to the default when nothing is selected', () => {
		expect(effectiveLabel(PILL, undefined)).toBe('Portland #14');
		expect(effectiveLabel(PILL, null)).toBe('Portland #14');
	});

	/* A selection carried over from a different app — or from options the author
	 * has since removed — must not display a value the pill no longer offers. */
	it('falls back when the selection is no longer an option', () => {
		expect(effectiveLabel(PILL, 'Denver #9')).toBe('Portland #14');
	});

	it("is empty for a pill that doesn't exist", () => {
		expect(effectiveLabel(undefined, 'x')).toBe('');
	});
});

describe('optionByLabel', () => {
	it('finds an option by its label', () => {
		expect(optionByLabel(PILL, 'Seattle #3')?.member).toContain('Store 3');
	});

	it('returns undefined for an unknown label', () => {
		expect(optionByLabel(PILL, 'Nope')).toBeUndefined();
	});
});

describe('selectionFor', () => {
	it('uses an explicit unique name verbatim', () => {
		const s = selectionFor(PILL, optionByLabel(PILL, 'Seattle #3'));
		expect(s.kind).toBe('set');
		if (s.kind === 'set') {
			expect(s.filter.members).toEqual(['[Store].[Stores].[USA].[WA].[Seattle].[Store 3]']);
			expect(s.filter.dimension).toBe('Store');
		}
	});

	it('asks the caller to resolve a caption when no member is given', () => {
		const s = selectionFor(PILL, optionByLabel(PILL, 'Portland #14'));
		expect(s.kind).toBe('resolve');
		if (s.kind === 'resolve') {
			expect(s.caption).toBe('Portland #14');
			expect(s.target).toEqual(TARGET);
		}
	});

	it('clears the filter for the ALL sentinel', () => {
		const s = selectionFor(PILL, optionByLabel(PILL, 'All stores · National'));
		expect(s.kind).toBe('clear');
	});

	/* A pill with options but no bound level is a legitimate configuration — the
	 * author just wants the header text to change. It must never fabricate a
	 * filter against an empty dimension. */
	it('does nothing when the pill is not bound to a level', () => {
		const cosmetic: AppContextPill = { ...PILL, filter: undefined };
		expect(selectionFor(cosmetic, optionByLabel(cosmetic, 'Seattle #3')).kind).toBe('none');
	});

	it('does nothing when the binding is incomplete', () => {
		const partial: AppContextPill = {
			...PILL,
			filter: { dimension: 'Store', hierarchy: '', level: 'Store Name' }
		};
		expect(selectionFor(partial, optionByLabel(partial, 'Seattle #3')).kind).toBe('none');
	});

	it("treats a blank-labelled, memberless option as ALL rather than resolving ''", () => {
		const pill: AppContextPill = {
			label: 'Store',
			value: 'x',
			filter: TARGET,
			options: [{ label: '  ' }]
		};
		expect(selectionFor(pill, pill.options?.[0]).kind).toBe('clear');
	});

	it('returns none for a missing option', () => {
		expect(selectionFor(PILL, undefined).kind).toBe('none');
	});
});

/* A hand-typed option list goes stale the moment a store is opened, closed or
 * renamed. Sourcing from the bound level means the cube — the thing that
 * actually knows — supplies the choices. */
describe('cube-sourced options', () => {
	const LEVEL_PILL: AppContextPill = {
		label: 'Store',
		value: 'Portland',
		optionsSource: 'level',
		filter: TARGET
	};
	const MEMBERS = [
		{ caption: 'Portland', uniqueName: '[Store].[Stores].[USA].[OR].[Portland]' },
		{ caption: 'Seattle', uniqueName: '[Store].[Stores].[USA].[WA].[Seattle]' }
	];

	describe('isLevelSourced', () => {
		it('is true only with a source AND a complete binding', () => {
			expect(isLevelSourced(LEVEL_PILL)).toBe(true);
			expect(isLevelSourced({ ...LEVEL_PILL, filter: undefined })).toBe(false);
			expect(isLevelSourced({ ...LEVEL_PILL, optionsSource: 'list' })).toBe(false);
			expect(isLevelSourced(PILL)).toBe(false);
			expect(isLevelSourced(undefined)).toBe(false);
		});

		it('is false when the binding is only half filled in', () => {
			expect(
				isLevelSourced({ ...LEVEL_PILL, filter: { dimension: 'Store', hierarchy: '', level: 'x' } })
			).toBe(false);
		});
	});

	describe('optionsFromMembers', () => {
		it("uses each member's real unique name, not its caption", () => {
			expect(optionsFromMembers(LEVEL_PILL, MEMBERS)).toEqual([
				{ label: 'Portland', member: '[Store].[Stores].[USA].[OR].[Portland]' },
				{ label: 'Seattle', member: '[Store].[Stores].[USA].[WA].[Seattle]' }
			]);
		});

		it('prepends an All entry on request', () => {
			const out = optionsFromMembers({ ...LEVEL_PILL, includeAll: true }, MEMBERS);
			expect(out[0]).toEqual({ label: 'All', member: ALL_MEMBER });
			expect(out).toHaveLength(3);
		});

		it('honours a custom All label', () => {
			const out = optionsFromMembers(
				{ ...LEVEL_PILL, includeAll: true, allLabel: 'All stores · National' },
				MEMBERS
			);
			expect(out[0].label).toBe('All stores · National');
		});

		it('skips members missing a caption or unique name', () => {
			const out = optionsFromMembers(LEVEL_PILL, [
				...MEMBERS,
				{ caption: '', uniqueName: '[x]' },
				{ caption: 'Ghost', uniqueName: '  ' }
			]);
			expect(out).toHaveLength(2);
		});

		/* A native select with thousands of entries is unusable — the cap is real,
		 * and callers are told when it bit rather than shown a partial list as if
		 * it were complete. */
		it('caps a huge level and reports the truncation', () => {
			const many = Array.from({ length: MAX_LEVEL_OPTIONS + 50 }, (_, i) => ({
				caption: `M${i}`,
				uniqueName: `[M].[${i}]`
			}));
			expect(optionsFromMembers(LEVEL_PILL, many)).toHaveLength(MAX_LEVEL_OPTIONS);
			expect(levelOptionsTruncated(many.length)).toBe(true);
			expect(levelOptionsTruncated(MEMBERS.length)).toBe(false);
		});

		it('returns nothing without a pill', () => {
			expect(optionsFromMembers(undefined, MEMBERS)).toEqual([]);
		});
	});

	describe('integration with the existing helpers', () => {
		it('is not selectable until the members have loaded', () => {
			expect(isSelectable(LEVEL_PILL)).toBe(false);
			expect(isSelectable(LEVEL_PILL, [])).toBe(false);
			expect(isSelectable(LEVEL_PILL, optionsFromMembers(LEVEL_PILL, MEMBERS))).toBe(true);
		});

		it('renders the resolved options rather than any stale typed list', () => {
			const stale: AppContextPill = { ...LEVEL_PILL, options: [{ label: 'Closed store' }] };
			const labels = optionsFor(stale, optionsFromMembers(stale, MEMBERS)).map((o) => o.label);
			expect(labels).toEqual(['Portland', 'Seattle']);
			expect(labels).not.toContain('Closed store');
		});

		it('selects a cube-sourced option by its real member', () => {
			const resolved = optionsFromMembers(LEVEL_PILL, MEMBERS);
			const s = selectionFor(LEVEL_PILL, optionByLabel(LEVEL_PILL, 'Seattle', resolved));
			expect(s.kind).toBe('set');
			if (s.kind === 'set') {
				expect(s.filter.members).toEqual(['[Store].[Stores].[USA].[WA].[Seattle]']);
			}
		});

		it('validates the live label against the resolved list', () => {
			const resolved = optionsFromMembers(LEVEL_PILL, MEMBERS);
			expect(effectiveLabel(LEVEL_PILL, 'Seattle', resolved)).toBe('Seattle');
			// A store that has since closed falls back to a real option.
			expect(effectiveLabel(LEVEL_PILL, 'Bakersfield', resolved)).toBe('Portland');
		});

		/* A configured default that no member matches used to be synthesised as a
		 * leading entry carrying the ALL sentinel — a row reading "Portland #14"
		 * that silently cleared the filter when picked. */
		it("never synthesises an entry for a default the cube doesn't have", () => {
			const mismatched: AppContextPill = { ...LEVEL_PILL, value: 'Portland #14' };
			const resolved = optionsFromMembers(mismatched, MEMBERS);
			const labels = optionsFor(mismatched, resolved).map((o) => o.label);
			expect(labels).toEqual(['Portland', 'Seattle']);
			expect(labels).not.toContain('Portland #14');
		});

		it("displays a real option when the configured default doesn't exist", () => {
			const mismatched: AppContextPill = { ...LEVEL_PILL, value: 'Portland #14' };
			const resolved = optionsFromMembers(mismatched, MEMBERS);
			expect(effectiveLabel(mismatched, undefined, resolved)).toBe('Portland');
		});

		it("prefers the All entry as the initial label, which is what's true", () => {
			const withAll: AppContextPill = { ...LEVEL_PILL, value: 'nope', includeAll: true };
			const resolved = optionsFromMembers(withAll, MEMBERS);
			expect(effectiveLabel(withAll, undefined, resolved)).toBe('All');
		});

		it('keeps a configured default that DOES match a member', () => {
			const resolved = optionsFromMembers(LEVEL_PILL, MEMBERS);
			expect(effectiveLabel(LEVEL_PILL, undefined, resolved)).toBe('Portland');
		});
	});
});
