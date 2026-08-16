<script lang="ts">
	/*
	 * "+ Add tile" toolbar dropdown.
	 *
	 * saiku#1837: this used to be a FLAT list of nine entries that mixed three
	 * different kinds of decision — what to show (Chart / Table / KPI), which
	 * happened to be implemented as a custom renderer (Graph / Ranked list), and
	 * raw escape hatches (ECharts option / Plugin). An app author faced three
	 * separate "visualise data" entries and had to know they were disjoint
	 * pools: Chart then offered 16 types, Graph offered 2 layouts, Ranked list
	 * offered rows. Worse, all four custom renderers shared one useless hint
	 * ("Custom renderer — configure in the tile's ⚙ editor"), so the menu said
	 * nothing about what any of them drew.
	 *
	 * Now there is ONE "Chart" entry. It opens a grouped type gallery containing
	 * the built-in chart types AND any renderer registered with
	 * placement: 'chart' — so Graph sits beside Bar, which is where an author
	 * looks for it. Whether a type is a built-in `chart` tile or a `custom` tile
	 * bound to a renderer is a dispatch detail they never see.
	 *
	 * Escape hatches (placement: 'advanced') live below a divider. They're real,
	 * but they aren't peers of "Chart" for someone assembling an app.
	 *
	 * Tile-specific config (cube binding, query mode, filter target) is still set
	 * later via the tile's ⚙ edit button — see TileEditorModal.
	 */

	import type { TileType } from '$lib/api/dashboards';
	import { listTileRenderers } from '$lib/dashboard/tileRegistry';
	import { CHART_TYPES } from '$lib/views/chartTypes';
	import { Button } from '$lib/components/ui';

	interface Props {
		onPick: (type: TileType, chartType?: string) => void;
		/** Seed a custom-renderer tile of the given registered renderer id. When
		 *  omitted, custom renderers aren't offered at all. */
		onAddCustom?: (rendererId: string) => void;
		disabled?: boolean;
		/** Dropdown edge alignment. "right" (default) opens leftward — correct for a
		 *  right-anchored dashboard toolbar. "left" opens rightward — used by the App
		 *  Builder page toolbar so the menu opens INTO the page content instead of
		 *  over the left nav rail. */
		align?: 'left' | 'right';
	}

	let { onPick, onAddCustom, disabled = false, align = 'right' }: Props = $props();

	let open = $state(false);
	/** The Chart entry expands in place rather than opening a nested flyout —
	 *  a flyout at this size is fiddly to hit and needs its own escape handling. */
	let chartsOpen = $state(false);

	// Snapshotted when the menu opens: all import-side-effect registrations have
	// run by then.
	const renderers = $derived(open && onAddCustom ? listTileRenderers() : []);
	/** Renderers that draw data — shown INSIDE the chart gallery. */
	const chartRenderers = $derived(renderers.filter((r) => r.placement === 'chart'));
	/** Escape hatches — shown under the Advanced divider. Default when unset, so
	 *  a third-party renderer can't quietly land in the primary gallery. */
	const advancedRenderers = $derived(renderers.filter((r) => r.placement !== 'chart'));

	/** Built-in chart types, grouped in CHART_TYPES' own order (Bars, Lines, …). */
	const chartGroups = $derived.by(() => {
		const groups: { group: string; items: { id: string; label: string }[] }[] = [];
		for (const ct of CHART_TYPES) {
			let g = groups.find((x) => x.group === ct.group);
			if (!g) groups.push((g = { group: ct.group, items: [] }));
			g.items.push({ id: ct.id, label: ct.label });
		}
		return groups;
	});

	function close() {
		open = false;
		chartsOpen = false;
	}

	function pick(type: TileType, chartType?: string) {
		close();
		onPick(type, chartType);
	}

	function pickCustom(rendererId: string) {
		close();
		onAddCustom?.(rendererId);
	}
</script>

<div class="add-tile" class:add-tile--left={align === 'left'}>
	<Button variant="outline" size="sm" {disabled} onclick={() => (open = !open)}>+ Add tile</Button>

	{#if open}
		<div class="menu" class:menu--left={align === 'left'} role="menu">
			<button
				type="button"
				class="menu-item"
				role="menuitem"
				aria-expanded={chartsOpen}
				onclick={() => (chartsOpen = !chartsOpen)}
			>
				<span class="icon" aria-hidden="true">📊</span>
				<span>
					<span class="block font-medium">Chart{chartsOpen ? '' : '…'}</span>
					<span class="hint">Bar, line, pie, map, graph and more — pick a type.</span>
				</span>
			</button>

			{#if chartsOpen}
				<div class="gallery">
					{#each chartGroups as g (g.group)}
						<span class="gallery-group">{g.group}</span>
						{#each g.items as ct (ct.id)}
							<button
								type="button"
								class="gallery-item"
								role="menuitem"
								onclick={() => pick('chart', ct.id)}>{ct.label}</button
							>
						{/each}
					{/each}
					{#if chartRenderers.length > 0}
						<span class="gallery-group">Relationships</span>
						{#each chartRenderers as r (r.id)}
							<button
								type="button"
								class="gallery-item"
								role="menuitem"
								title={r.description}
								onclick={() => pickCustom(r.id)}>{r.label}</button
							>
						{/each}
					{/if}
				</div>
			{/if}

			<button type="button" class="menu-item" role="menuitem" onclick={() => pick('table')}>
				<span class="icon" aria-hidden="true">🧮</span>
				<span>
					<span class="block font-medium">Table</span>
					<span class="hint">Records of measure cells with row headers.</span>
				</span>
			</button>
			<button type="button" class="menu-item" role="menuitem" onclick={() => pick('kpi')}>
				<span class="icon" aria-hidden="true">📈</span>
				<span>
					<span class="block font-medium">KPI</span>
					<span class="hint"
						>A single measure as a big number, with optional comparison + sparkline.</span
					>
				</span>
			</button>
			<button type="button" class="menu-item" role="menuitem" onclick={() => pick('text')}>
				<span class="icon" aria-hidden="true">📝</span>
				<span>
					<span class="block font-medium">Text / note</span>
					<span class="hint">Markdown annotation; no data.</span>
				</span>
			</button>
			<button type="button" class="menu-item" role="menuitem" onclick={() => pick('image')}>
				<span class="icon" aria-hidden="true">🖼️</span>
				<span>
					<span class="block font-medium">Image</span>
					<span class="hint">A logo, diagram or screenshot from a URL or upload.</span>
				</span>
			</button>

			{#if onAddCustom && advancedRenderers.length > 0}
				<span class="divider">Advanced</span>
				{#each advancedRenderers as r (r.id)}
					<button type="button" class="menu-item" role="menuitem" onclick={() => pickCustom(r.id)}>
						<span class="icon" aria-hidden="true">{r.icon ?? '🧩'}</span>
						<span>
							<span class="block font-medium">{r.label}</span>
							<span class="hint">{r.description ?? 'Custom renderer.'}</span>
						</span>
					</button>
				{/each}
			{/if}
		</div>
	{/if}
</div>

<style>
	.menu {
		position: absolute;
		top: calc(100% + 4px);
		right: 0;
		background: hsl(var(--bg));
		/* Explicit chrome foreground — the App Builder renders this menu inside a
       light-themed app canvas, so without this the item LABELS (which set no
       colour of their own) inherit the app's dark text and render nearly
       invisible on the dark popover. saiku#1636. */
		color: hsl(var(--fg));
		border: 1px solid hsl(var(--border));
		border-radius: 6px;
		box-shadow: 0 8px 24px rgba(0, 0, 0, 0.08);
		padding: 0.25rem;
		z-index: 10;
		min-width: 18rem;
	}
	.menu--left {
		right: auto;
		left: 0;
	}
	.menu-item {
		display: flex;
		align-items: flex-start;
		gap: 0.5rem;
		width: 100%;
		padding: 0.5rem;
		border: none;
		background: transparent;
		text-align: left;
		cursor: pointer;
		border-radius: 4px;
		font-size: 0.875rem;
	}
	.menu-item:hover,
	.menu-item:focus {
		background: hsl(var(--bg-subtle));
		outline: none;
	}
	.menu-item:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}
	.menu-item:disabled:hover,
	.menu-item:disabled:focus {
		background: transparent;
	}
	.icon {
		font-size: 1.125rem;
		line-height: 1;
		padding-top: 0.125rem;
	}
	.hint {
		display: block;
		font-size: 0.75rem;
		color: hsl(var(--fg-muted));
		margin-top: 0.125rem;
	}

	/* Chart gallery — a dense grid inside the menu rather than a nested flyout.
	   A flyout at this width is fiddly to hit and needs its own dismiss
	   handling; expanding in place keeps one focus trap and one Escape. */
	.gallery {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 0.125rem;
		padding: 0.25rem 0.5rem 0.5rem 2rem;
	}
	.gallery-group {
		grid-column: 1 / -1;
		font-size: 0.6875rem;
		text-transform: uppercase;
		letter-spacing: 0.06em;
		color: hsl(var(--fg-muted));
		margin-top: 0.375rem;
	}
	.gallery-item {
		border: none;
		background: transparent;
		color: inherit;
		text-align: left;
		cursor: pointer;
		border-radius: 4px;
		padding: 0.25rem 0.375rem;
		font-size: 0.8125rem;
	}
	.gallery-item:hover,
	.gallery-item:focus {
		background: hsl(var(--bg-subtle));
		outline: none;
	}
	.divider {
		display: block;
		font-size: 0.6875rem;
		text-transform: uppercase;
		letter-spacing: 0.06em;
		color: hsl(var(--fg-muted));
		padding: 0.5rem 0.5rem 0.25rem;
		border-top: 1px solid hsl(var(--border));
		margin-top: 0.25rem;
	}
</style>
