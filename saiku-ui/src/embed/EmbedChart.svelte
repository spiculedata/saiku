<script lang="ts">
	/*
	 * Records → ECharts renderer. Imports ECharts modularly (core + bar +
	 * line + pie + the four common components) rather than the full
	 * `echarts` umbrella so the embed bundle stays well below the 200 KB
	 * gz forecast — tree-shaking drops the rest.
	 *
	 * Maps a records-format response to a {category → series} chart:
	 *   - First non-numeric column → category axis labels (xAxis)
	 *   - Every numeric column → its own series
	 *   - Cell `value` (not `formatted`) feeds the chart; `formatted`
	 *     drives the tooltip so the host page sees the same string the
	 *     workbench would.
	 */
	import { onMount } from 'svelte';
	import { use, init } from 'echarts/core';
	import { BarChart, LineChart, PieChart } from 'echarts/charts';
	import {
		GridComponent,
		LegendComponent,
		TitleComponent,
		TooltipComponent
	} from 'echarts/components';
	import { CanvasRenderer } from 'echarts/renderers';
	import type { EChartsType } from 'echarts/types/dist/shared';
	import type { EmbedRow } from './types';
	// #1103: host-page chart theming via CSS custom properties.
	import { readEmbedChartTheme, type EmbedChartTheme } from './embedChartTheme';
	import { buildEmbedChartOption } from './embedChartOption';

	// Register modules once at module scope. ECharts dedupes a repeat
	// `use()` so this is safe across many <saiku-embed> instances.
	use([
		BarChart,
		LineChart,
		PieChart,
		GridComponent,
		LegendComponent,
		TitleComponent,
		TooltipComponent,
		CanvasRenderer
	]);

	interface Props {
		rows: EmbedRow[];
		/** `bar` (default), `line`, or `pie`. */
		mode?: string;
	}

	let { rows, mode = 'bar' }: Props = $props();

	let container = $state<HTMLDivElement | undefined>(undefined);
	let chart: EChartsType | null = null;

	onMount(() => {
		if (!container) return;
		chart = init(container);
		// Resize on host-page layout changes so the chart fills its grid
		// cell as the dashboard reflows.
		const observer = new ResizeObserver(() => chart?.resize());
		observer.observe(container);
		return () => {
			observer.disconnect();
			chart?.dispose();
			chart = null;
		};
	});

	/* Re-derive the option each time rows / mode change. ECharts merges
	 * the new option onto the prior state by default, so we pass
	 * { notMerge: true } to keep series counts honest when the shape
	 * shifts (e.g. a column appearing / disappearing). */
	$effect(() => {
		if (!chart) return;
		// #1103: resolve host-set chart theming from CSS vars on this element
		// (custom properties inherit through the shadow boundary). Unstyled →
		// empty theme → ECharts defaults (back-compat).
		const el = container;
		const theme: EmbedChartTheme = el
			? readEmbedChartTheme((n) => getComputedStyle(el).getPropertyValue(n))
			: { palette: [] };
		const opt = buildEmbedChartOption(rows, mode, theme);
		chart.setOption(opt, { notMerge: true });
	});
</script>

<div bind:this={container} class="chart" role="img" aria-label="Saiku embed chart"></div>

<style>
	/* ECharts measures its container synchronously on init, so the div MUST have a
   * concrete height before init runs. Tailwind classes aren't in the embed
   * bundle CSS (only the SvelteKit app has them), so we set width+height in
   * scoped shadow-DOM CSS. Min-height covers the case where the host page's
   * layout provides no explicit height. */
	.chart {
		width: 100%;
		height: 100%;
		min-height: 240px;
	}
</style>
