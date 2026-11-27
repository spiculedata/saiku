<script lang="ts">
	/**
	 * saiku#2618 — the "this feature is turned off for your account" panel a
	 * denied route renders when a caller follows a direct link. The nav hiding
	 * in the topbar is cosmetic; this panel plus the server-side gate filter
	 * (FeatureGateFilter on the feature's REST prefixes) is what makes the
	 * denial hold even with a bookmarked URL.
	 *
	 * Token utilities only — the ESLint config bans raw tone classes outside
	 * the design system.
	 */
	import { i18n } from '$lib/stores/i18n.svelte';

	let { featureLabel } = $props();
</script>

<div class="m-auto max-w-md text-center">
	<h1 class="text-lg font-semibold">{featureLabel}</h1>
	<p class="mt-2 text-fg-muted">
		{i18n.t(
			'features.denied',
			'This feature has been turned off for your account by an administrator.'
		)}
	</p>
</div>
