<script lang="ts">
	/*
	 * Dashboard renderer for the embed surface — fetches /embed/dashboard/{path}
	 * once for the layout, then delegates rendering to the shared <EmbedGrid>,
	 * wiring its tile / member fetchers to the token-scoped dashboard endpoints.
	 *
	 * The per-tile dispatch + filter bus live in EmbedGrid (shared with EmbedApp);
	 * this component only owns the dashboard fetch + lifecycle.
	 */
	import {
		fetchDashboard,
		fetchDashboardTile,
		fetchTileMembers,
		EmbedFetchError,
		type EmbedFilterOverride
	} from './api';
	import type { EmbedDashboardLayout } from './types';
	import EmbedGrid from './EmbedGrid.svelte';

	interface Props {
		server: string;
		token: string;
		path: string;
	}

	let { server, token, path }: Props = $props();

	let dashboard = $state<EmbedDashboardLayout | null>(null);
	let error = $state<string | null>(null);
	let loading = $state(false);

	$effect(() => {
		const s = server.trim();
		const p = path.trim();
		const t = token.trim();
		if (!p) {
			dashboard = null;
			error = null;
			return;
		}
		let cancelled = false;
		loading = true;
		error = null;
		dashboard = null;
		fetchDashboard(s, p, t || undefined)
			.then((dash) => {
				if (cancelled) return;
				dashboard = dash;
			})
			.catch((e: unknown) => {
				if (cancelled) return;
				error = friendlyError(e);
			})
			.finally(() => {
				if (!cancelled) loading = false;
			});
		return () => {
			cancelled = true;
		};
	});

	function friendlyError(e: unknown): string {
		if (e instanceof EmbedFetchError) {
			if (e.status === 401) return 'This embed is unavailable.';
			return e.body.error ?? `Embed failed (${e.status}).`;
		}
		return 'Embed failed to load.';
	}

	function tileFetch(tileId: string, overrides: EmbedFilterOverride[]) {
		return fetchDashboardTile(
			server.trim(),
			path.trim(),
			tileId,
			token.trim() || undefined,
			overrides
		);
	}
	function memberFetch(tileId: string, q?: string, limit?: number) {
		return fetchTileMembers(
			server.trim(),
			path.trim(),
			tileId,
			token.trim() || undefined,
			q,
			limit
		);
	}
</script>

{#if loading && !dashboard}
	<div class="state">Loading dashboard…</div>
{:else if error}
	<div class="state error" role="alert">{error}</div>
{:else if dashboard}
	<EmbedGrid layout={dashboard.layout} fetchTile={tileFetch} fetchMembers={memberFetch} />
{/if}

<style>
	.state {
		padding: 12px;
		font-family: system-ui, sans-serif;
		font-size: 13px;
	}
	.state.error {
		color: var(--saiku-embed-error, #b91c1c);
	}
</style>
