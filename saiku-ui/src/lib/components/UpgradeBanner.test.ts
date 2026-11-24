/**
 * UpgradeBanner — the "a new version is available" notice (saiku#2579).
 * Populated from the capabilities probe's update section (server side of the telemetry
 * check); server render, same approach as SponsorLink.test.ts.
 */
import { describe, it, expect, vi } from 'vitest';
import { render } from 'svelte/server';

vi.mock('$lib/stores/i18n.svelte', () => ({
	i18n: { t: (key: string) => key }
}));

const { mockPlatform } = vi.hoisted(() => ({
	mockPlatform: {
		version: '4.9.1' as string | null,
		newVersionAvailable: false,
		latestVersion: null as string | null,
		capabilities: null as Record<string, unknown> | null
	}
}));

vi.mock('$lib/stores/platform.svelte', () => ({ platform: mockPlatform }));

import UpgradeBanner from './UpgradeBanner.svelte';

describe('UpgradeBanner', () => {
	it('renders nothing when no newer release was found', () => {
		expect(render(UpgradeBanner).body).not.toContain('upgrade-banner');
	});

	it('shows the latest version number and the current one when a newer release exists', () => {
		mockPlatform.newVersionAvailable = true;
		mockPlatform.latestVersion = '4.10.0';
		const { body } = render(UpgradeBanner);
		expect(body).toContain('upgrade-banner');
		// C2: the notice carries the latest version (after "A newer version ... available:")
		// and the running one in parentheses.
		expect(body).toContain('upgrade.available');
		expect(body).toContain(': 4.10.0');
		expect(body).toContain('(upgrade.current: 4.9.1)');
	});

	it('links release notes at the spiculedata repo, pinned to the latest release (C3)', () => {
		mockPlatform.newVersionAvailable = true;
		mockPlatform.latestVersion = '4.10.0';
		const { body } = render(UpgradeBanner);
		expect(body).toContain('href="https://github.com/spiculedata/saiku/releases/latest"');
		expect(body).not.toContain('github.com/OSBI/saiku');
	});

	it('carries one Support Saiku link when the server reports sponsorLink (C4)', () => {
		mockPlatform.newVersionAvailable = true;
		mockPlatform.latestVersion = '4.10.0';
		mockPlatform.capabilities = { sponsorLink: true };
		const { body } = render(UpgradeBanner);
		expect(body).toContain('href="https://github.com/sponsors/spiculedata"');
		expect(body).toContain('upgrade.sponsor');
	});

	it('carries no sponsor link when the server keeps it off (C4)', () => {
		mockPlatform.newVersionAvailable = true;
		mockPlatform.latestVersion = '4.10.0';
		mockPlatform.capabilities = { sponsorLink: false };
		expect(render(UpgradeBanner).body).not.toContain('sponsors/spiculedata');
	});

	it('still renders when the probe answered available without a version (defensive)', () => {
		mockPlatform.newVersionAvailable = true;
		mockPlatform.latestVersion = null;
		mockPlatform.capabilities = null;
		const { body } = render(UpgradeBanner);
		expect(body).toContain('upgrade-banner');
	});
});
