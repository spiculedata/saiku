/*
 * Tests for the rail user disc's initials.
 *
 * The behaviour being fixed: FoodMart Ops hard-coded "RM", so every viewer saw
 * the same fictional manager's initials. Back-compat matters as much as the new
 * behaviour — an existing app that only carries a literal must keep rendering
 * that literal.
 */

import { describe, expect, it } from 'vitest';
import { resolveAvatar, userInitials } from './userInitials';

describe('userInitials', () => {
	it.each([
		['Tom Barber', 'TB'],
		['tom.barber', 'TB'],
		['tom_barber', 'TB'],
		['tom-barber', 'TB'],
		['tom@spicule.co.uk', 'TS'],
		['  Tom   Barber  ', 'TB']
	])('derives two initials from %s', (input, expected) => {
		expect(userInitials(input)).toBe(expected);
	});

	it('takes the first two letters of a single-word name', () => {
		// A lone "A" reads as a rendering bug; "AD" reads as a monogram.
		expect(userInitials('admin')).toBe('AD');
		expect(userInitials('smith')).toBe('SM');
	});

	it('uppercases regardless of input case', () => {
		expect(userInitials('ada lovelace')).toBe('AL');
	});

	it('never exceeds two glyphs', () => {
		expect(userInitials('a b c d e')).toHaveLength(2);
	});

	it('skips punctuation rather than rendering it', () => {
		expect(userInitials('!tom .barber')).toBe('TB');
	});

	it('handles a one-letter name without padding', () => {
		expect(userInitials('x')).toBe('X');
	});

	it('returns empty for nothing usable', () => {
		expect(userInitials('')).toBe('');
		expect(userInitials(null)).toBe('');
		expect(userInitials(undefined)).toBe('');
		expect(userInitials('...')).toBe('');
	});

	it('handles non-Latin names', () => {
		expect(userInitials('Ада Лавлейс')).toBe('АЛ');
	});
});

describe('resolveAvatar', () => {
	it("follows the signed-in user when the source is 'user'", () => {
		expect(resolveAvatar({ avatarSource: 'user' }, 'tom.barber')).toBe('TB');
	});

	it('ignores any stale literal when following the user', () => {
		expect(resolveAvatar({ avatarSource: 'user', avatar: 'RM' }, 'tom.barber')).toBe('TB');
	});

	/* An app authored before avatarSource existed carries only the literal — it
	 * must keep rendering exactly what it rendered before the change. */
	it("treats a missing source as 'fixed' so legacy apps are unchanged", () => {
		expect(resolveAvatar({ avatar: 'RM' }, 'tom.barber')).toBe('RM');
	});

	it("uses the literal when the source is explicitly 'fixed'", () => {
		expect(resolveAvatar({ avatarSource: 'fixed', avatar: 'RM' }, 'tom.barber')).toBe('RM');
	});

	it('returns empty when there is nothing to show', () => {
		expect(resolveAvatar(null, 'tom')).toBe('');
		expect(resolveAvatar(undefined, 'tom')).toBe('');
		expect(resolveAvatar({}, 'tom')).toBe('');
		expect(resolveAvatar({ avatar: '   ' }, 'tom')).toBe('');
	});

	it("returns empty when following a user who isn't signed in", () => {
		expect(resolveAvatar({ avatarSource: 'user' }, null)).toBe('');
	});
});
