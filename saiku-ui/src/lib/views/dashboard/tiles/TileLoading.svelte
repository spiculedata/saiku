<script lang="ts">
	/*
	 * Issue #933 — per-tile loading skeleton.
	 *
	 * A shimmering placeholder shaped to the tile type so the tile reads as
	 * "waiting on data" rather than "broken": bar shapes for charts, a header
	 * + grid for tables, a big-number outline for KPIs. Purely presentational;
	 * the owning tile decides when to show it (first load, before any response).
	 */
	interface Props {
		/** Skeleton silhouette to draw. `radial` = a shimmer ring for
		 *  pie/donut/sunburst; `chart` = rising bars for everything else. */
		variant?: 'chart' | 'radial' | 'table' | 'kpi';
	}
	let { variant = 'chart' }: Props = $props();

	// Fixed, deterministic bar heights so the skeleton doesn't reflow between
	// renders (Math.random is also unavailable in some of our tooling).
	const BAR_HEIGHTS = [55, 80, 42, 95, 64, 78, 50];
</script>

<div class="box-border flex h-full w-full p-3" role="status" aria-busy="true" aria-label="Loading">
	{#if variant === 'table'}
		<div class="sk-table">
			<div class="sk-row sk-row--head">
				{#each [0, 1, 2, 3] as c (c)}<div class="sk skeleton-cell"></div>{/each}
			</div>
			{#each [0, 1, 2, 3, 4] as r (r)}
				<div class="sk-row">
					{#each [0, 1, 2, 3] as c (c)}<div class="sk skeleton-cell"></div>{/each}
				</div>
			{/each}
		</div>
	{:else if variant === 'kpi'}
		<div class="sk-kpi">
			<div class="sk sk-number"></div>
			<div class="sk sk-label"></div>
		</div>
	{:else if variant === 'radial'}
		<div class="flex flex-1 items-center justify-center">
			<div class="sk sk-ring"></div>
		</div>
	{:else}
		<div class="flex flex-1 items-end justify-around gap-2">
			{#each BAR_HEIGHTS as h, i (i)}
				<div class="sk min-w-0 flex-1" style="height: {h}%"></div>
			{/each}
		</div>
	{/if}
</div>

<style>
	/* Shared shimmer fill. */
	.sk {
		background: hsl(var(--bg-subtle));
		border-radius: 4px;
		position: relative;
		overflow: hidden;
	}
	.sk::after {
		content: '';
		position: absolute;
		inset: 0;
		background: linear-gradient(
			90deg,
			transparent 0%,
			color-mix(in srgb, hsl(var(--fg)) 8%, transparent) 50%,
			transparent 100%
		);
		transform: translateX(-100%);
		animation: tile-shimmer 1.3s ease-in-out infinite;
	}
	@keyframes tile-shimmer {
		100% {
			transform: translateX(100%);
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.sk::after {
			animation: none;
		}
	}
	/* Chart: bars rising from a shared baseline. */
	/* Radial: a shimmer ring (pie / donut / sunburst). The mask punches a
     hole in the centre so the disc reads as a ring. */
	.sk-ring {
		height: 85%;
		max-width: 85%;
		aspect-ratio: 1;
		border-radius: 50%;
		-webkit-mask: radial-gradient(circle, transparent 38%, #000 39%);
		mask: radial-gradient(circle, transparent 38%, #000 39%);
	}
	/* Table: header strip + body rows of equal cells. */
	.sk-table {
		flex: 1;
		display: flex;
		flex-direction: column;
		gap: 0.4rem;
	}
	.sk-row {
		display: flex;
		gap: 0.5rem;
	}
	.skeleton-cell {
		flex: 1;
		height: 0.85rem;
	}
	.sk-row--head .skeleton-cell {
		height: 1.05rem;
		background: hsl(var(--bg-muted));
	}
	/* KPI: a big number block + a small caption block. */
	.sk-kpi {
		flex: 1;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 0.6rem;
	}
	.sk-number {
		width: 55%;
		height: 2.5rem;
	}
	.sk-label {
		width: 32%;
		height: 0.75rem;
	}
</style>
