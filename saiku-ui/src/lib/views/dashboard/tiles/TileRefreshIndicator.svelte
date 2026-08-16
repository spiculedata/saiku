<script lang="ts">
	/*
	 * Issue #931 — per-tile auto-refresh indicator.
	 *
	 * A compact "Last updated X ago" line with a refresh icon that spins while
	 * the tile is querying. Shared by the chart / table / KPI tiles so the
	 * affordance reads identically everywhere. Display-only: the owning tile
	 * drives the auto-refresh timer (see useTileAutoRefresh) and passes down
	 * the last-success timestamp + whether a fetch is currently in flight.
	 *
	 * The relative-time string re-renders on a 1s heartbeat the parent feeds
	 * via `now` (so the parent owns the single ticking interval, keeping this
	 * component pure). When auto-refresh is off OR no successful fetch has
	 * happened yet, the parent simply doesn't render this.
	 */
	import { RefreshCw } from '@lucide/svelte';
	import { formatRelativeTime } from '$lib/dashboard/autoRefresh';
	import { i18n } from '$lib/stores/i18n.svelte';

	interface Props {
		/** Epoch ms of the last successful fetch. */
		lastUpdated: number;
		/** Whether a query is currently in flight (spins the icon). */
		spinning?: boolean;
		/** Current epoch ms — fed by the parent's 1s heartbeat so the relative
		 *  label stays fresh without this component owning a timer. */
		now: number;
	}

	let { lastUpdated, spinning = false, now }: Props = $props();

	let relative = $derived(formatRelativeTime(lastUpdated, now));
	let label = $derived(
		i18n.t('dashboard.refresh.lastUpdated', 'Last updated {time}').replace('{time}', relative)
	);
</script>

<div class="tile-refresh" title={label}>
	<RefreshCw
		size={11}
		class={spinning ? 'tile-refresh__icon spin' : 'tile-refresh__icon'}
		aria-hidden="true"
	/>
	<span class="overflow-hidden text-ellipsis whitespace-nowrap">{label}</span>
</div>

<style>
	.tile-refresh {
		display: inline-flex;
		align-items: center;
		gap: 0.25rem;
		font-size: 0.6875rem;
		color: hsl(var(--fg-muted));
		line-height: 1;
		pointer-events: none;
		max-width: 100%;
		overflow: hidden;
	}
	/* lucide renders an <svg>; target it via :global since the class is passed
     through the icon component's `class` prop. */
	.tile-refresh :global(.tile-refresh__icon) {
		flex-shrink: 0;
	}
	.tile-refresh :global(.tile-refresh__icon.spin) {
		animation: tile-refresh-spin 0.8s linear infinite;
	}
	@keyframes tile-refresh-spin {
		100% {
			transform: rotate(360deg);
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.tile-refresh :global(.tile-refresh__icon.spin) {
			animation: none;
		}
	}
</style>
