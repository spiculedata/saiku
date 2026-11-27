<script lang="ts">
	/*
	 * Apps route — the App Builder analogue of the dashboards route.
	 *
	 * Gates on the session store the same way the dashboards route does:
	 * unauthenticated visitors get LoginForm, not a broken-fetch shell. When
	 * no path is present it shows the AppIndex catalogue; with a path it mounts
	 * AppEditor, which loads the .saikuapp and renders AppShell.
	 */

	import { session } from '$lib/stores/session.svelte';
	import LoginForm from '$lib/views/LoginForm.svelte';
	// saiku#2618: a denied caller with a direct link gets the refusal panel —
	// the nav hiding in the topbar is cosmetic, not the enforcement.
	import FeatureDenied from '$lib/components/FeatureDenied.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';
	import AppEditor from '$lib/views/app/AppEditor.svelte';
	import AppIndex from '$lib/views/app/AppIndex.svelte';
	// App Builder Phase 2 (saiku#1441): register built-in custom tile renderers
	// (echarts-option) by import side effect so the tile registry is populated in
	// the App Builder too — enables the "Custom…" add-tile entry + custom-tile
	// dispatch on app pages. Localised here (routes already load ECharts) so it
	// stays out of the root layout chunk.
	import '$lib/dashboard/custom/registerBuiltinRenderers';

	let { data } = $props();
</script>

{#if session.loading}
	<div class="m-auto text-fg-muted">Loading…</div>
{:else if session.current}
	{#if !session.hasFeature('apps')}
		<FeatureDenied featureLabel={i18n.t('topbar.apps', 'Apps')} />
	{:else if !data.appPath}
		<AppIndex />
	{:else}
		<AppEditor appPath={data.appPath} />
	{/if}
{:else}
	<LoginForm />
{/if}
