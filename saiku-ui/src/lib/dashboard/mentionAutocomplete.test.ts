import { describe, expect, it } from 'vitest';
import { applyMention, currentMentionToken, matchUsers } from './mentionAutocomplete';

describe('currentMentionToken', () => {
	it('detects an @-mention in progress at the caret', () => {
		const t = 'hey @bo';
		expect(currentMentionToken(t, t.length)).toEqual({ start: 4, query: 'bo' });
	});

	it('detects an empty mention right after @', () => {
		const t = 'hi @';
		expect(currentMentionToken(t, t.length)).toEqual({ start: 3, query: '' });
	});

	it('ignores a completed mention (whitespace after)', () => {
		const t = 'hi @bob ';
		expect(currentMentionToken(t, t.length)).toBeNull();
	});

	it('ignores an email-style @ (mid-word)', () => {
		const t = 'mail me at user@exa';
		expect(currentMentionToken(t, t.length)).toBeNull();
	});

	it('uses the caret, not the end of the string', () => {
		const t = '@al and @bo';
		expect(currentMentionToken(t, 3)).toEqual({ start: 0, query: 'al' });
	});
});

describe('applyMention', () => {
	it('replaces the partial token with @username + trailing space', () => {
		const t = 'hey @bo';
		const r = applyMention(t, t.length, 'bob');
		expect(r.text).toBe('hey @bob ');
		expect(r.caret).toBe(r.text.length);
	});

	it('keeps text after the caret intact', () => {
		const t = 'hey @bo!';
		const r = applyMention(t, 7, 'bob'); // caret before "!"
		expect(r.text).toBe('hey @bob !');
	});

	it('is a no-op outside a mention', () => {
		const t = 'no mention here';
		expect(applyMention(t, t.length, 'bob')).toEqual({ text: t, caret: t.length });
	});
});

describe('matchUsers', () => {
	it('filters case-insensitively and caps the count', () => {
		const users = ['admin', 'bob', 'bobby', 'krishna', 'smith'];
		expect(matchUsers(users, 'bo')).toEqual(['bob', 'bobby']);
		expect(matchUsers(users, '')).toHaveLength(5);
		expect(matchUsers(users, '', 2)).toHaveLength(2);
		expect(matchUsers(users, 'ADMIN')).toEqual(['admin']);
	});
});
