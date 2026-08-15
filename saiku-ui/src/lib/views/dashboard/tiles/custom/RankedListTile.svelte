<script lang="ts">
	/*
	 * Custom tile renderer: `ranked-list` (saiku#1441).
	 *
	 * The "Movers" card as a real tile — a ranked run of rows, each a label and a
	 * signed value, numbered 1..n. It replaces the `.tile:has(tbody)` custom-CSS
	 * hack the FoodMart Ops app used to need (see rankedList.ts for why that had
	 * to go).
	 *
	 * Everything visual comes from the app's `--saiku-app-*` tokens, so the card
	 * re-themes with the app instead of pinning its own colours. Data + query
	 * reuse is identical to the sibling custom renderers: the shared
	 * TileQueryState + runTileQueryEffect hook, so filters, dedupe, retry and
	 * auto-refresh all behave the same.
	 *
	 * Row click → cross-filter mirrors the built-in chart tile: the clicked label
	 * is resolved to a real member unique name on the tile's first row axis.
	 */

	import type { DashboardTile, DashboardFilter, CubeRef } from '$lib/api/dashboards';
	import { TileQueryState, runTileQueryEffect } from '$lib/hooks/useTileQuery.svelte';
	import { activeFilters } from '$lib/stores/activeFilters.svelte';
	import { schemaCache } from '$lib/stores/schemaCache.svelte';
	import { type SchemaLike } from '$lib/dashboard/effectiveQuery';
	import {
		projectRankedList,
		validateRankedListConfig,
		type RankedListConfig
	} from '$lib/dashboard/custom/rankedList';
	import { searchMembers } from '$lib/api/aiQuery';
	import { pickMemberUniqueName } from '$lib/dashboard/clickFilterMember';
	import { dashboardStore } from '$lib/stores/dashboard.svelte';
	import TileLoading from '../TileLoading.svelte';
	import TileError from '../TileError.svelte';
	import TileEmpty from '../TileEmpty.svelte';

	interface Props {
		tile: DashboardTile;
		onClickFilter?: (filter: DashboardFilter) => void;
	}

	let { tile, onClickFilter }: Props = $props();

	let validation = $derived(validateRankedListConfig(tile.custom?.options));
	let config = $derived<RankedListConfig>(
		validation.ok ? (validation.value as RankedListConfig) : {}
	);

	const q = new TileQueryState();
	let response = $derived(q.response);
	let loading = $derived(q.loading);
	let error = $derived(q.error);
	function retry(): void {
		q.retry();
	}

	let schema = $state<SchemaLike | null>(null);
	let resolvedCube = $state<CubeRef | null>(null);

	$effect(() => {
		if (tile.cube) resolvedCube = tile.cube;
	});

	$effect(() => {
		void schemaCache.version;
		if (!resolvedCube) {
			schema = null;
			return;
		}
		const cached = schemaCache.peek(resolvedCube) as SchemaLike | null;
		if (cached) schema = cached;
		else void schemaCache.get(resolvedCube).catch(() => {});
	});

	$effect(() => {
		void q.retryTick;
		void q.refreshTick;
		runTileQueryEffect(q, {
			tile,
			activeFilters: activeFilters.all,
			schema,
			sharedResponse: null
		});
	});

	let rows = $derived(
		response?.status === 'SUCCESS'
			? projectRankedList(response.data as Array<Record<string, unknown>>, config)
			: []
	);
	let isEmpty = $derived(
		!!response && response.status === 'SUCCESS' && (response.data?.length ?? 0) === 0
	);
	let hasEffectiveFilters = $derived(
		activeFilters.all.some((f) => (f.filter.members?.length ?? 0) > 0)
	);
	function resetFilters(): void {
		activeFilters.resetTransient();
		dashboardStore.resetPanelFiltersToSaved();
	}

	/* Click a row → filter the dashboard on that member. Inline tiles only: the
	 * row axis is read straight off the request body, mirroring the built-in
	 * chart tile's handler. */
	const memberCache = new Map<string, Promise<{ uniqueName: string; caption: string }[]>>();

	function handleRowClick(label: string): void {
		if (!onClickFilter || tile.query?.kind !== 'inline') return;
		const body = tile.query.body as {
			rows?: Array<{ dimension: string; hierarchy: string; level: string }>;
		};
		const axis = body.rows?.[0];
		const cube = resolvedCube;
		if (!axis || !cube || !label) return;

		const { dimension, hierarchy, level } = axis;
		const key = `${cube.connectionName}/${cube.catalog}/${cube.schema}/${cube.cubeName}|${dimension}/${hierarchy}/${level}|${label}`;
		let lookup = memberCache.get(key);
		if (!lookup) {
			lookup = searchMembers(cube, dimension, hierarchy, level, label);
			memberCache.set(key, lookup);
		}
		void lookup.then((hits) => {
			const uniqueName = pickMemberUniqueName(hits, label);
			if (!uniqueName) {
				memberCache.delete(key); // don't cache a miss — allow a retry
				return;
			}
			onClickFilter({ dimension, hierarchy, level, members: [uniqueName] });
		});
	}

	let clickable = $derived(!!onClickFilter && tile.query?.kind === 'inline');
</script>

{#if !tile.query}
	<div class="p-4 text-sm text-fg-muted">Tile has no query binding — open ⚙ to set one.</div>
{:else if !validation.ok}
	<div class="ranked__invalid p-4 text-sm">{validation.error}</div>
{:else if loading && !response}
	<TileLoading variant="table" />
{:else if error}
	<TileError message={error} onRetry={retry} />
{:else if isEmpty}
	<TileEmpty filtered={hasEffectiveFilters} onReset={resetFilters} cubeName={tile.cube?.cubeName} />
{:else}
	<div class="ranked">
		{#if config.subtitle}
			<div class="ranked__subtitle">{config.subtitle}</div>
		{/if}
		<ol class="ranked__list">
			{#each rows as row (row.rank + row.label)}
				<li class="ranked__row">
					{#if config.showRank !== false}
						<span class="ranked__rank" aria-hidden="true">{row.rank}</span>
					{/if}
					{#if clickable}
						<button type="button" class="ranked__label" onclick={() => handleRowClick(row.label)}>
							{row.label}
						</button>
					{:else}
						<span class="ranked__label">{row.label}</span>
					{/if}
					<span class="ranked__value" data-tone={row.tone}>{row.formatted}</span>
				</li>
			{/each}
		</ol>
	</div>
{/if}

<style>
	/* Colours/typography deliberately reference the app tokens with a neutral
     fallback, so the card themes with the app and still reads correctly in a
     plain dashboard where those tokens are absent. */
	.ranked {
		height: 100%;
		overflow-y: auto;
		padding: 0 0.25rem 0.25rem;
		box-sizing: border-box;
	}
	.ranked__subtitle {
		font-size: 0.72rem;
		color: var(--saiku-app-muted, hsl(var(--fg-muted)));
		padding: 0 0.75rem 0.4rem;
	}
	.ranked__list {
		list-style: none;
		margin: 0;
		padding: 0;
	}
	.ranked__row {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		padding: 0.5rem 0.75rem;
		border-top: 1px dashed var(--saiku-app-card-border, hsl(var(--border)));
	}
	.ranked__row:first-child {
		border-top: 0;
	}
	.ranked__rank {
		flex: none;
		width: 1.25rem;
		font-family: var(--saiku-app-font-display, inherit);
		font-style: italic;
		font-size: 0.95rem;
		color: var(--saiku-app-muted, hsl(var(--fg-muted)));
	}
	.ranked__label {
		flex: 1;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		font-size: 0.88rem;
		font-weight: 550;
		color: var(--saiku-app-fg, hsl(var(--fg)));
		text-align: left;
	}
	/* The clickable variant is a real <button>; strip the chrome so it reads as
     the same row text (the app's global button reset doesn't reach in here). */
	button.ranked__label {
		background: none;
		border: 0;
		padding: 0;
		font: inherit;
		font-weight: 550;
		cursor: pointer;
	}
	button.ranked__label:hover {
		text-decoration: underline;
	}
	.ranked__value {
		flex: none;
		font-family: var(--saiku-app-font-numeric, var(--saiku-app-font-body, inherit));
		font-variant-numeric: tabular-nums;
		font-size: 0.82rem;
		font-weight: 650;
	}
	.ranked__value[data-tone='positive'] {
		color: var(--saiku-app-positive, #2e7d55);
	}
	.ranked__value[data-tone='negative'] {
		color: var(--saiku-app-danger, #c0492b);
	}
	.ranked__value[data-tone='flat'] {
		color: var(--saiku-app-muted, hsl(var(--fg-muted)));
	}
	.ranked__invalid {
		color: var(--saiku-app-danger, hsl(var(--fg-muted)));
	}
</style>
