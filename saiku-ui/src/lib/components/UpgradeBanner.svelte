<script lang="ts">
	import { browser } from '$app/env';
	import { platform } from '$lib/stores/platform.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';
	import { FeedbackBanner } from '$lib/design-system';
	import { X } from '@lucide/svelte';

	// saiku#2579 (C3): the project moved; release notes now live on the spiculedata repo,
	// pinned to the latest release rather than the bare list.
	const RELEASES_URL = 'https://github.com/spiculedata/saiku/releases/latest';
	// saiku#2579 (C4): admins who keep Saiku current are the most likely sponsors — when the
	// server says the sponsor link is on, the banner carries one short link alongside it.
	const SPONSOR_URL = 'https://github.com/sponsors/spiculedata';
	const STORAGE_KEY = 'saiku.upgrade.dismissed';

	// saiku#2579 (C5): the dismissal is keyed by the version it dismissed, so dismissing 4.9.1
	// doesn't hide the 4.10.0 notice. The old unkeyed format stored '1', which never matches a
	// version — those users simply see the next notice once more.
	let dismissedVersion = $state<string | null>(browser ? localStorage.getItem(STORAGE_KEY) : null);

	function isDismissed(): boolean {
		// Only a dismissal recorded FOR this latest version hides it; a null latestVersion (the
		// server always pairs it with available:true, but defensively) never counts as dismissed.
		return platform.latestVersion != null && dismissedVersion === platform.latestVersion;
	}

	function dismiss() {
		dismissedVersion = platform.latestVersion;
		if (browser && platform.latestVersion) {
			localStorage.setItem(STORAGE_KEY, platform.latestVersion);
		}
	}
</script>

{#if platform.newVersionAvailable && !isDismissed()}
	<FeedbackBanner tone="warning" size="sm" testid="upgrade-banner">
		<div class="flex items-center gap-3">
			<span class="flex-1">
				{i18n.t('upgrade.available')}{platform.latestVersion
					? `: ${platform.latestVersion}`
					: ''}{platform.version ? ` (${i18n.t('upgrade.current')}: ${platform.version})` : ''}.
				<a href={RELEASES_URL} target="_blank" rel="noopener" class="underline">
					{i18n.t('upgrade.releaseNotes')}
				</a>
				{#if platform.capabilities?.sponsorLink === true}
					·
					<a href={SPONSOR_URL} target="_blank" rel="noopener noreferrer" class="underline">
						{i18n.t('upgrade.sponsor')}
					</a>
				{/if}
			</span>
			<button
				type="button"
				class="ml-auto inline-flex items-center justify-center rounded p-1 hover:bg-warning/20"
				onclick={dismiss}
				aria-label={i18n.t('upgrade.dismiss')}
			>
				<X size={14} />
			</button>
		</div>
	</FeedbackBanner>
{/if}
