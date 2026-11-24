<script lang="ts">
	// saiku#2577 — Saiku has no paid edition; this small top-bar link points signed-in users
	// at GitHub Sponsors. The server decides (capabilities.sponsorLink, opted out with
	// SAIKU_SPONSOR_LINK=false); anything but an explicit `true` renders nothing.
	import { Heart } from '@lucide/svelte';
	import { buttonVariants } from '$lib/components/ui';
	import { platform } from '$lib/stores/platform.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';

	const SPONSOR_URL = 'https://github.com/sponsors/spiculedata';
</script>

{#if platform.capabilities?.sponsorLink === true}
	<a
		class={buttonVariants({ variant: 'ghost', size: 'sm' })}
		href={SPONSOR_URL}
		target="_blank"
		rel="noopener noreferrer"
		title={i18n.t('topbar.sponsorTitle')}
		data-testid="app-sponsor"
	>
		<Heart size={14} /><span>{i18n.t('topbar.sponsor')}</span>
	</a>
{/if}
