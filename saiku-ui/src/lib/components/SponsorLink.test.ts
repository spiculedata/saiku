/**
 * SponsorLink — the small top-bar "Sponsor" link to GitHub Sponsors (saiku#2577).
 * Shown only when the capabilities probe reports `sponsorLink: true`; hidden while
 * the probe hasn't answered, on an older server that doesn't send the flag, and when
 * the operator opts out. Server render, same approach as SetsPanel.test.ts.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from 'svelte/server';

vi.mock('$lib/stores/i18n.svelte', () => ({
	i18n: { t: (key: string) => key }
}));

const { mockPlatform } = vi.hoisted(() => ({
	mockPlatform: { capabilities: null as Record<string, unknown> | null }
}));

vi.mock('$lib/stores/platform.svelte', () => ({ platform: mockPlatform }));

import SponsorLink from './SponsorLink.svelte';

describe('SponsorLink', () => {
	beforeEach(() => {
		mockPlatform.capabilities = null;
	});

	it('links to the spiculedata sponsors page in a new tab when enabled', () => {
		mockPlatform.capabilities = { sponsorLink: true };
		const { body } = render(SponsorLink);
		expect(body).toContain('href="https://github.com/sponsors/spiculedata"');
		expect(body).toContain('target="_blank"');
		expect(body).toMatch(/rel="noopener[^"]*"/);
		expect(body).toContain('topbar.sponsor');
	});

	it('renders nothing before the capabilities probe answers', () => {
		expect(render(SponsorLink).body).not.toContain('sponsors/spiculedata');
	});

	it('renders nothing when the operator opts out', () => {
		mockPlatform.capabilities = { sponsorLink: false };
		expect(render(SponsorLink).body).not.toContain('sponsors/spiculedata');
	});

	it('renders nothing on a server that does not send the flag', () => {
		mockPlatform.capabilities = { demoMode: false };
		expect(render(SponsorLink).body).not.toContain('sponsors/spiculedata');
	});
});
