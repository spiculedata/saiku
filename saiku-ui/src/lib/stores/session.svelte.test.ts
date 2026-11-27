/*
 * Unit tests for the session store's feature gate (saiku#2618).
 *
 * The backend session response carries the caller's effective feature map
 * (feature key -> enabled) that the admin panel's per-role toggles produce.
 * hasFeature() is what the global nav and each route's direct-link guard read:
 *   - an absent map (older backend) means every feature is on;
 *   - the map is authoritative — an admin's map from the server is all-true,
 *     and a false entry hides/gates that feature.
 *
 * The session store is a module singleton, so each test loads it fresh via
 * vi.resetModules(). Its collaborators ($lib/api/session and the two stores it
 * clears on logout) are mocked so no fetch fires.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('$lib/api/session', () => ({
	getCurrentSession: vi.fn(async () => null),
	login: vi.fn(),
	logout: vi.fn()
}));
vi.mock('$lib/stores/datasources.svelte', () => ({
	datasources: { clear: vi.fn() }
}));
vi.mock('$lib/stores/selection.svelte', () => ({
	selection: { clear: vi.fn() }
}));

type SessionModule = typeof import('./session.svelte');

let sessionStore: InstanceType<SessionModule['SessionStore']>;

beforeEach(async () => {
	vi.resetModules();
	const mod = await import('./session.svelte');
	sessionStore = new mod.SessionStore();
});
const user = (features?: Record<string, boolean>) => ({
	username: 'bob',
	roles: ['ROLE_USER'],
	sessionid: 's1',
	language: 'en',
	isadmin: false,
	...(features ? { features } : {})
});

describe('session.hasFeature', () => {
	it('passes when nobody is signed in (pre-login rendering)', () => {
		expect(sessionStore.current).toBeNull();
		expect(sessionStore.hasFeature('notebooks')).toBe(true);
	});

	it('passes when the backend carries no feature map (older backend)', () => {
		sessionStore.current = user();
		expect(sessionStore.hasFeature('notebooks')).toBe(true);
		expect(sessionStore.hasFeature('sql-workbench')).toBe(true);
	});

	it('denies only the features the map marks false', () => {
		sessionStore.current = user({
			notebooks: false,
			dashboards: true,
			apps: true,
			workbench: true,
			'sql-workbench': true
		});
		expect(sessionStore.hasFeature('notebooks')).toBe(false);
		expect(sessionStore.hasFeature('dashboards')).toBe(true);
		expect(sessionStore.hasFeature('apps')).toBe(true);
		expect(sessionStore.hasFeature('workbench')).toBe(true);
		expect(sessionStore.hasFeature('sql-workbench')).toBe(true);
	});

	it('denies a feature missing from the map only when the map is present', () => {
		// The server lists every catalogue key; a present-but-partial map is
		// treated as "everything the server did not deny stays on".
		sessionStore.current = user({ notebooks: false });
		expect(sessionStore.hasFeature('notebooks')).toBe(false);
		expect(sessionStore.hasFeature('not-a-known-feature')).toBe(true);
	});

	it('an admin session sees every feature (the server sends an all-true map)', () => {
		sessionStore.current = user({
			notebooks: true,
			dashboards: true,
			apps: true,
			workbench: true,
			'sql-workbench': true
		});
		(sessionStore.current as { isadmin: boolean }).isadmin = true;
		expect(sessionStore.isAdmin).toBe(true);
		expect(sessionStore.hasFeature('notebooks')).toBe(true);
	});
});
