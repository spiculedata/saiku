<script lang="ts">
	/*
	 * Dashboards route.
	 *
	 * Gates on the session store the same way the root workspace does —
	 * unauthenticated visitors get LoginForm, not a broken-fetch tile
	 * full of "NetworkError" messages. After login the session store
	 * re-resolves and the editor mounts with a live cookie.
	 */

	import { session } from '$lib/stores/session.svelte';
	import LoginForm from '$lib/views/LoginForm.svelte';
	// saiku#2618: a denied caller with a direct link gets the refusal panel —
	// the nav hiding in the topbar is cosmetic, not the enforcement.
	import FeatureDenied from '$lib/components/FeatureDenied.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';
	import DashboardEditor from '$lib/views/dashboard/DashboardEditor.svelte';
	import DashboardIndex from '$lib/views/dashboard/DashboardIndex.svelte';
	// App Builder Phase 2 (saiku#1441): register built-in custom tile renderers
	// (echarts-option) by import side effect so the tile registry is populated
	// wherever dashboards render — enables the "Custom…" add-tile entry + the
	// custom-tile dispatch. Localised to the dashboards route (which already loads
	// ECharts) so it stays out of the root layout chunk.
	import '$lib/dashboard/custom/registerBuiltinRenderers';

	let { data } = $props();
</script>

{#if session.loading}
	<div class="m-auto text-fg-muted">Loading…</div>
{:else if session.current}
	{#if !session.hasFeature('dashboards')}
		<FeatureDenied featureLabel={i18n.t('topbar.dashboards', 'Dashboards')} />
	{:else if !data.dashboardPath}
		<DashboardIndex />
	{:else}
		<DashboardEditor dashboardPath={data.dashboardPath} />
	{/if}
{:else}
	<LoginForm />
{/if}
