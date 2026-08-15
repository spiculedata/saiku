/*
 * Unit tests for the schema-generator page view-model.
 *
 * These helpers encapsulate the "is button X enabled?" / "what colour is the
 * stage pill?" decisions that the +page.svelte template hands off to, so the
 * decisions can be exercised without booting a browser.
 */

import { describe, expect, it } from 'vitest';

import type { Stage } from '$lib/api/schemaGen';
import {
	canCancel,
	canSave,
	canStart,
	deltaBannerText,
	hasDeltaChanges,
	stageLabel,
	stagePillColor
} from './pageViewModel';

const ALL_STAGES: ReadonlyArray<Stage | null> = [
	null,
	'PENDING',
	'INTROSPECTING',
	'INFERRING',
	'ENRICHING',
	'READY',
	'SAVED',
	'FAILED'
];

describe('canStart', () => {
	it('is true when no session is running (stage === null), false while in flight', () => {
		expect(canStart(null)).toBe(true);
		for (const s of ALL_STAGES.filter((x) => x !== null && x !== 'FAILED')) {
			expect(canStart(s as Stage)).toBe(false);
		}
	});

	it('is true again after a terminal failure (user may retry)', () => {
		// Failure is terminal but we want the user to be able to re-start.
		// Implementation: the page treats null *or* FAILED as "startable".
		expect(canStart('FAILED')).toBe(true);
	});
});

describe('canSave', () => {
	it('is true only at stage READY', () => {
		expect(canSave('READY')).toBe(true);
		for (const s of ALL_STAGES.filter((x) => x !== 'READY')) {
			expect(canSave(s)).toBe(false);
		}
	});
});

describe('canCancel', () => {
	it('is true whenever a session is in flight (non-null, non-terminal)', () => {
		expect(canCancel(null)).toBe(false);
		expect(canCancel('PENDING')).toBe(true);
		expect(canCancel('INTROSPECTING')).toBe(true);
		expect(canCancel('INFERRING')).toBe(true);
		expect(canCancel('ENRICHING')).toBe(true);
		// Cancel is still allowed at READY so the user can bail without saving.
		expect(canCancel('READY')).toBe(true);
		// Terminal non-recoverable states hide Cancel.
		expect(canCancel('SAVED')).toBe(false);
		expect(canCancel('FAILED')).toBe(false);
	});
});

describe('stagePillColor', () => {
	it('returns a non-empty token for every stage (incl. null)', () => {
		for (const s of ALL_STAGES) {
			expect(stagePillColor(s).length).toBeGreaterThan(0);
		}
	});

	it('maps READY/SAVED to success and FAILED to danger', () => {
		expect(stagePillColor('READY')).toBe('success');
		expect(stagePillColor('SAVED')).toBe('success');
		expect(stagePillColor('FAILED')).toBe('danger');
	});

	it('maps in-flight stages to a progress colour', () => {
		expect(stagePillColor('PENDING')).toBe('muted');
		expect(stagePillColor('INTROSPECTING')).toBe('info');
		expect(stagePillColor('INFERRING')).toBe('info');
		expect(stagePillColor('ENRICHING')).toBe('info');
	});

	it('returns muted when no session is running', () => {
		expect(stagePillColor(null)).toBe('muted');
	});
});

describe('hasDeltaChanges', () => {
	it('is false when there is no delta info at all', () => {
		expect(hasDeltaChanges(null)).toBe(false);
		expect(hasDeltaChanges(undefined)).toBe(false);
		expect(hasDeltaChanges({ deltaNewCount: 0, deltaRemovedCount: 0 })).toBe(false);
	});

	it('is true when any delta count is non-zero', () => {
		expect(hasDeltaChanges({ deltaNewCount: 1, deltaRemovedCount: 0 })).toBe(true);
		expect(hasDeltaChanges({ deltaNewCount: 0, deltaRemovedCount: 3 })).toBe(true);
		expect(hasDeltaChanges({ deltaNewCount: 2, deltaRemovedCount: 5 })).toBe(true);
	});
});

describe('deltaBannerText', () => {
	it('summarises new and removed counts upstream', () => {
		expect(deltaBannerText({ deltaNewCount: 2, deltaRemovedCount: 3 })).toBe(
			'Changes detected: 2 new, 3 removed upstream.'
		);
		expect(deltaBannerText({ deltaNewCount: 1, deltaRemovedCount: 0 })).toBe(
			'Changes detected: 1 new, 0 removed upstream.'
		);
	});
});

describe('stageLabel', () => {
	it("returns 'Idle' for null and a human label for every stage", () => {
		expect(stageLabel(null)).toBe('Idle');
		expect(stageLabel('PENDING')).toMatch(/pending/i);
		expect(stageLabel('INTROSPECTING')).toMatch(/introspect/i);
		expect(stageLabel('INFERRING')).toMatch(/infer/i);
		expect(stageLabel('ENRICHING')).toMatch(/enrich/i);
		expect(stageLabel('READY')).toMatch(/ready/i);
		expect(stageLabel('SAVED')).toMatch(/saved/i);
		expect(stageLabel('FAILED')).toMatch(/fail/i);
	});
});
