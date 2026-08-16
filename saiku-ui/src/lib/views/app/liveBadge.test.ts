/*
 * Tests for the header status badge.
 *
 * The behaviour being fixed: the badge rendered the author's "Live · Saiku"
 * string and a green dot unconditionally, so it claimed a healthy connection
 * with the backend down.
 */

import { describe, expect, it } from 'vitest';
import { badgeFor } from './liveBadge';

describe('badgeFor', () => {
	it("shows the author's wording, in green, when connected", () => {
		const b = badgeFor('Live · Saiku', 'live');
		expect(b).toMatchObject({ text: 'Live · Saiku', tone: 'positive' });
	});

	it('says Offline in red when the probe fails', () => {
		const b = badgeFor('Live · Saiku', 'offline');
		expect(b).toMatchObject({ text: 'Offline', tone: 'danger' });
		expect(b?.text).not.toBe('Live · Saiku');
	});

	it('says Demo data in amber on a demo instance', () => {
		expect(badgeFor('Live · Saiku', 'demo')).toMatchObject({
			text: 'Demo data',
			tone: 'warning'
		});
	});

	/* Asserting "Live" before anything has answered is the original bug in
	 * miniature — so the first paint is explicitly neutral. */
	it('stays neutral until the first probe answers', () => {
		expect(badgeFor('Live · Saiku', 'checking')).toMatchObject({
			text: 'Live · Saiku',
			tone: 'neutral'
		});
	});

	it('is hidden when the author configured no badge', () => {
		for (const empty of [undefined, null, '', '   ']) {
			expect(badgeFor(empty, 'live')).toBeNull();
		}
	});

	it('carries an explanatory hint for every state', () => {
		for (const s of ['live', 'demo', 'offline', 'checking'] as const) {
			expect(badgeFor('Live · Saiku', s)?.hint.length ?? 0).toBeGreaterThan(0);
		}
	});
});
