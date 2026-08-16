<script lang="ts">
	/*
	 * Embed variant of the `echarts-option` custom tile (App Builder Phase 2,
	 * saiku#1441). Token-scoped, read-only, self-contained: it renders inside the
	 * <saiku-embed/> bundle, so it uses ONLY relative imports (no `$lib` alias),
	 * modular ECharts (to keep the bundle lean), and takes its data as the `rows`
	 * prop that <EmbedGrid> fetches through the guarded per-tile embed query path.
	 *
	 * The author's option is validated with the SAME safe-subset validator the
	 * in-app tile uses (echartsOption.ts is pure — no app stores), then the
	 * token-scoped rows are projected + merged in exactly as the in-app tile does.
	 */

	import { onMount } from 'svelte';
	import { use, init } from 'echarts/core';
	import { BarChart, LineChart, PieChart, ScatterChart, RadarChart } from 'echarts/charts';
	import {
		GridComponent,
		LegendComponent,
		TitleComponent,
		TooltipComponent,
		DataZoomComponent,
		VisualMapComponent,
		MarkLineComponent,
		MarkPointComponent,
		MarkAreaComponent,
		PolarComponent,
		AxisPointerComponent,
		AriaComponent
	} from 'echarts/components';
	import { CanvasRenderer } from 'echarts/renderers';
	import type { EChartsType } from 'echarts/types/dist/shared';
	import {
		validateEchartsOption,
		applyDataToEchartsOption,
		type EChartsDataProjection
	} from '../../../../dashboard/custom/echartsOption';
	import { applyValueAxisFormat } from '../../../../dashboard/custom/valueAxisFormat';

	// Register the module set once at module scope (ECharts dedupes repeats).
	use([
		BarChart,
		LineChart,
		PieChart,
		ScatterChart,
		RadarChart,
		GridComponent,
		LegendComponent,
		TitleComponent,
		TooltipComponent,
		DataZoomComponent,
		VisualMapComponent,
		MarkLineComponent,
		MarkPointComponent,
		MarkAreaComponent,
		PolarComponent,
		AxisPointerComponent,
		AriaComponent,
		CanvasRenderer
	]);

	/** Minimal cell shape (matches the embed EmbedCell) — kept local so this
	 *  component doesn't reach into the embed module graph. */
	interface Cell {
		value: number | null;
		formatted: string;
	}
	type Row = Record<string, Cell>;

	interface Props {
		tile: {
			custom?: { renderer: string; options?: Record<string, unknown>; valueFormat?: string };
		};
		/** Token-scoped rows from <EmbedGrid>. undefined/null = still loading. */
		rows?: Row[] | null;
	}

	let { tile, rows }: Props = $props();

	let validation = $derived(validateEchartsOption(tile.custom?.options));
	let hasOption = $derived(!!tile.custom?.options && Object.keys(tile.custom.options).length > 0);

	let container = $state<HTMLDivElement | undefined>(undefined);
	let chart: EChartsType | null = null;

	onMount(() => {
		if (!container) return;
		chart = init(container);
		const observer = new ResizeObserver(() => chart?.resize());
		observer.observe(container);
		return () => {
			observer.disconnect();
			chart?.dispose();
			chart = null;
		};
	});

	/** True when every non-null cell in a column is numeric (mirrors the embed
	 *  chart's column-type detection). */
	function isNumericColumn(rs: Row[], col: string): boolean {
		for (const r of rs) {
			const v = r[col]?.value;
			if (v === null || v === undefined) continue;
			if (typeof v !== 'number' || Number.isNaN(v)) return false;
		}
		return true;
	}

	/** Project embed rows into the generic {categories, series} shape the merge
	 *  understands: first non-numeric column → categories, each numeric column →
	 *  a series. */
	function project(rs: Row[]): EChartsDataProjection {
		if (rs.length === 0) return { categories: [], series: [] };
		const cols = Object.keys(rs[0]);
		const numericCols = cols.filter((c) => isNumericColumn(rs, c));
		const categoryCol = cols.find((c) => !isNumericColumn(rs, c)) ?? cols[0];
		return {
			categories: rs.map((r) => r[categoryCol]?.formatted ?? ''),
			series: numericCols.map((c) => ({ name: c, data: rs.map((r) => r[c]?.value ?? null) }))
		};
	}

	$effect(() => {
		if (!chart) return;
		if (!validation.ok) {
			chart.clear();
			return;
		}
		const rs = rows ?? [];
		const option = applyDataToEchartsOption(validation.value, project(rs));
		// Same declarative value-axis format as the in-app tile.
		applyValueAxisFormat(option, tile.custom?.valueFormat);
		chart.setOption(option, { notMerge: true });
	});
</script>

{#if !hasOption}
	<div class="state muted">No option configured.</div>
{:else if !validation.ok}
	<div class="state error" role="alert">Invalid ECharts option: {validation.error}</div>
{:else if rows === undefined || rows === null}
	<div class="state muted">Loading…</div>
{:else}
	<div bind:this={container} class="chart" role="img" aria-label="Saiku embed chart"></div>
{/if}

<style>
	.chart {
		width: 100%;
		height: 100%;
		min-height: 240px;
	}
	.state {
		padding: 12px;
		font-family: system-ui, sans-serif;
		font-size: 13px;
	}
	.state.muted {
		color: var(--saiku-embed-muted, #6b7280);
	}
	.state.error {
		color: var(--saiku-embed-error, #b91c1c);
	}
</style>
