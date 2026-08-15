<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import * as echarts from 'echarts';
	import type { QueryResult } from '$lib/api/query';
	import { deriveLeafRows, parseCellset, toNumber } from '$lib/views/cellsetUtils';
	import type { ChartType, ChartOptions } from '$lib/views/chartTypes';
	import { DEFAULT_CHART_OPTIONS } from '$lib/views/chartTypes';
	import { isSingleMeasureKind, smallMultipleRowCount } from '$lib/dashboard/smallMultiples';
	import { theme } from '$lib/stores/theme.svelte';
	import { resolveThemeTokens } from '$lib/views/chartTheme';
	import { buildChartOption, type ChartProjection } from '$lib/charts/build';
	// #1086: map an ECharts click back to absolute cellset coords for drillthrough.
	import { chartDrillTarget } from '$lib/charts/chartDrillCoord';
	// #1083: client-side category sort + top-N (transient, no re-query).
	import { applySortLimit, sortLimitOrder } from '$lib/charts/sortLimit';
	// #1090: accessible data-table mirror of the chart for screen readers.
	import { chartSummary } from '$lib/charts/a11y';
	// issue #1071: map charts need the GeoJSON registered with ECharts before
	// setOption — done lazily here (the builder stays pure).
	import { ensureGeoMap, isGeoMapRegistered } from '$lib/charts/geoMaps';
	// #1092: preserve the dataZoom window (+ brush) across re-renders (resize /
	// theme flip / same-shape data refresh). Pure decision lives in the helper;
	// the getOption()/setOption() I/O stays here.
	import { reconcileZoomState } from '$lib/charts/zoomState';

	interface Props {
		result: QueryResult;
		type: ChartType;
		options?: ChartOptions;
	}

	let { result, type, options = DEFAULT_CHART_OPTIONS }: Props = $props();

	let host: HTMLDivElement | null = null;
	let chart: echarts.ECharts | null = null;

	// #1083 sort + top-N now live on the persisted ChartOptions (the
	// floating toolbar above the chart was relocated into the chart-options
	// modal per user feedback 2026-06-07 — "sort and top should go in
	// chart options"). Reads are reactive against the options prop.

	// #1053: single-measure kinds (pie/donut/treemap/sunburst) with >1 measure
	// render as small multiples — 2 per row. Grow the host to N rows so each
	// chart stays full-size; the surrounding wrapper scrolls.
	let smallMultipleRows = $derived.by(() => {
		const measureCount = parseCellset(result).columnCategories.length;
		return isSingleMeasureKind(type) && measureCount > 1 ? smallMultipleRowCount(measureCount) : 1;
	});

	// Project the workspace cellset into the shared {rows, cols, matrix} shape.
	// Rollup filtering (which needs cellset depth) happens here, before
	// projection, since the builder treats rows as final. Shared by the chart
	// builder and the a11y data-table mirror (#1090) so both see the same data.
	// Leaf-filtered projection (BEFORE the #1083 sort/limit) + the cellset
	// body-row index for each leaf position (undefined when 1:1). Factored out so
	// projectResult and the drill-coordinate mapping share ONE source of truth for
	// the rollup-leaf reindex — they must agree or click-to-drill drills the
	// wrong row.
	function leafProjection(
		r: QueryResult,
		o: ChartOptions
	): { projection: ChartProjection; leafIndices: number[] | undefined } {
		const parsed = parseCellset(r);
		// Multi-level row hierarchies (Year > Quarter, Country > City, …) come back
		// with both rollup and leaf rows in the same cellset. Showing the rollups on
		// a chart dwarfs the leaves; deriveLeafRows drops them and promotes each
		// leaf's parent context into the label (e.g. "2024 / Q1"). The grid view is
		// untouched.
		let rows = parsed.rowCategories;
		let matrix: (number | null)[][] = parsed.dataRows.map((row) => row.map(toNumber));
		let leafIndices: number[] | undefined;
		if (o.hideRollupRows) {
			// deriveLeafRows is a no-op for single-level rowsets (all rows at the same
			// depth) and for empty results, so no extra guard is needed here.
			const leaf = deriveLeafRows(parsed);
			if (leaf.indices.length > 0 && leaf.indices.length < matrix.length) {
				rows = leaf.labels;
				matrix = leaf.indices.map((i) => matrix[i]);
				leafIndices = leaf.indices;
			}
		}
		// #1596: default category-axis title = the row hierarchy's dimension/level
		// caption. It lives in the ROW_HEADER_HEADER corner cells (the top-left block
		// of the column-header rows, one per pinned row-header column). Collect the
		// non-empty captions in order, dedupe, and join — e.g. "Year", or
		// "Product / Category" for a two-level row hierarchy. Undefined when the
		// cellset carries no corner caption (e.g. arrow-decoded results), in which
		// case the builder leaves the category axis untitled unless overridden.
		const cornerNames: string[] = [];
		for (const headerRow of parsed.columnHeaderRows) {
			for (let c = 0; c < parsed.rowHeaderColCount; c++) {
				const cell = headerRow[c];
				const v = cell?.value?.trim();
				if (v && cell?.type === 'ROW_HEADER_HEADER' && !cornerNames.includes(v)) {
					cornerNames.push(v);
				}
			}
		}
		const categoryAxisName = cornerNames.join(' / ') || undefined;
		return {
			projection: {
				rowCategories: rows,
				columnCategories: parsed.columnCategories,
				matrix,
				categoryAxisName
			},
			leafIndices
		};
	}

	function projectResult(r: QueryResult, o: ChartOptions): ChartProjection {
		// #1083: re-order / trim the categories CLIENT-SIDE (sort by the first
		// measure, then keep the top-N) before either the chart builder or the a11y
		// table sees them, so both stay in sync. A no-op when the controls are off.
		return applySortLimit(leafProjection(r, o).projection, {
			direction: o.sortDirection,
			measureIndex: 0,
			topN: o.topN
		});
	}

	// Delegate to the single canonical builder (#1076). The workspace is the
	// "roomy" (non-compact) surface.
	function buildOption(
		r: QueryResult,
		t: ChartType,
		o: ChartOptions
	): Record<string, unknown> | null {
		const tk = resolveThemeTokens();
		const p = projectResult(r, o);
		// Aspect-aware radius keeps each small-multiple the same on-screen size
		// regardless of how many there are (#1053); chartWidth drives the derived
		// per-label axis truncation width.
		const aspect = host && host.clientHeight > 0 ? host.clientWidth / host.clientHeight : 1;
		const chartWidth = host?.clientWidth ?? 0;
		return buildChartOption(p, t, o, tk, { aspect, chartWidth, compact: false });
	}

	// #1086 + #1083: map a clicked chart category (its DISPLAYED index, after the
	// rollup-leaf filter AND the client-side sort/top-N) back to the absolute
	// cellset body-row index. Without composing the sort permutation here, a click
	// after sorting/trimming would drill the wrong row. Returns an array indexed by
	// displayed position → body-row index, or undefined when the mapping is 1:1
	// (no leaf filter and no sort/limit) so chartDrillTarget treats it as identity.
	function currentDisplayIndices(r: QueryResult, o: ChartOptions): number[] | undefined {
		const { projection, leafIndices } = leafProjection(r, o);
		const order = sortLimitOrder(projection, {
			direction: o.sortDirection,
			measureIndex: 0,
			topN: o.topN
		});
		const isIdentity = leafIndices === undefined && order.every((v, i) => v === i);
		if (isIdentity) return undefined;
		// order[displayedPos] = leaf-projection index; leafIndices maps that to the
		// raw cellset body row (or it's already the body row when no leaf filter).
		return order.map((leafPos) => (leafIndices ? leafIndices[leafPos] : leafPos));
	}

	// #1086: ECharts click → reuse the workspace's existing drillthrough flow.
	// The grid (CellsetTable) drills by dispatching a bubbling `saiku-drillthrough`
	// CustomEvent with absolute cellset coords; QueryCanvas listens for it and
	// opens the DrillthroughModal. We translate the clicked data point into the
	// same coords and dispatch the identical event — no new backend path.
	function handleChartClick(params: { dataIndex?: number; seriesIndex?: number }) {
		if (!host) return;
		const categoryIndex = params.dataIndex;
		// Pie/donut element events sometimes omit seriesIndex; a pie is one series.
		const seriesIndex = params.seriesIndex ?? 0;
		if (typeof categoryIndex !== 'number') return; // background click → no-op
		const parsed = parseCellset(result);
		const target = chartDrillTarget(
			parsed,
			categoryIndex,
			seriesIndex,
			currentDisplayIndices(result, options)
		);
		if (!target) return; // out-of-range / "All"-style click → no-op
		host.dispatchEvent(
			new CustomEvent('saiku-drillthrough', {
				bubbles: true,
				detail: { row: target.row, col: target.col }
			})
		);
	}

	// #1090: accessible data-table mirror of the chart for screen readers. The
	// canvas is aria-hidden (invisible to AT anyway); this exposes the same data.
	let a11y = $derived(chartSummary(type, options.title ?? '', projectResult(result, options)));

	function render() {
		if (!chart) return;
		// issue #1071: defer the first map render until its GeoJSON is registered,
		// then re-render. Avoids ECharts warning on an unknown map name + an empty
		// flash. Once registered this is a cheap sync check.
		if (type === 'map' && !isGeoMapRegistered('world')) {
			void ensureGeoMap('world')
				.then(() => render())
				.catch((err) => console.warn('[saiku] failed to load world map:', err));
			return;
		}
		let opt: Record<string, unknown> | null;
		try {
			opt = buildOption(result, type, options);
		} catch (err) {
			// If buildOption throws (e.g. cellset shape doesn't fit the requested
			// chart type), clear the canvas instead of leaving the previous chart's
			// series on screen. Without this, switching from a "broken" chart to a
			// valid one would still render the broken state because the stale series
			// would merge with the new option.
			console.warn('[saiku] chart buildOption failed; clearing canvas:', err);
			chart.clear();
			return;
		}
		if (!opt) {
			// Unsupported kind / empty projection — clear rather than keep stale series.
			chart.clear();
			return;
		}
		// #1092: capture the live zoom/brush BEFORE we overwrite the option, so we
		// can put the user's window back after. reconcileZoomState() only returns
		// something when the chart is "the same chart" (same type + category count),
		// so a type switch or a new dataset starts fresh at full range.
		const preserved = reconcileZoomState(chart.getOption() as Record<string, unknown>, opt, type);
		// Include "series" in replaceMerge — switching chart types must drop the
		// previous series wholesale, otherwise a stacked-bar's series would merge
		// with the next radar's etc., producing the "stale chart" symptom.
		chart.setOption(opt, {
			notMerge: false,
			replaceMerge: ['xAxis', 'yAxis', 'legend', 'tooltip', 'title', 'visualMap', 'radar', 'series']
		});
		// #1092: re-apply the saved window/selection (merge, so colours/data from the
		// fresh render stay and only the zoom/brush slice is overlaid).
		if (preserved) chart.setOption(preserved, { notMerge: false });
	}

	onMount(() => {
		if (host) {
			chart = echarts.init(host, null);
			// #1086: click a data point → drill via the existing drillthrough flow.
			chart.on('click', (params) =>
				handleChartClick(params as { dataIndex?: number; seriesIndex?: number })
			);
			render();
			// Re-render (not just resize) so the aspect-aware small-multiple radius
			// recomputes for the new canvas size (#1053).
			const ro = new ResizeObserver(() => {
				chart?.resize();
				render();
			});
			ro.observe(host);
			return () => ro.disconnect();
		}
	});

	$effect(() => {
		// Track dependencies explicitly so any field on `options` triggers re-render.
		void result;
		void type;
		void options.title;
		void options.xAxisLabel;
		void options.yAxisLabel;
		void options.showLegend;
		void options.legendPosition;
		void options.trendLine;
		void options.trendPeriod;
		void options.hideRollupRows;
		void options.dualAxis;
		void options.seriesAxis;
		// issue #1071: map colour ramp + missing-data behaviour.
		void options.colorRamp;
		void options.mapMissing;
		// Re-theme when the effective theme flips.
		void theme.effective;
		// #1091: repaint when the colour-blind-safe pref flips.
		void theme.colorBlindSafe;
		// #1083: repaint when the persisted sort / top-N change.
		void options.sortDirection;
		void options.topN;
		if (chart) render();
	});

	onDestroy(() => {
		chart?.dispose();
		chart = null;
	});
</script>

<!-- #1083 sort + top-N moved into the chart-options modal (2026-06-07
     user feedback). The floating toolbar above the canvas felt like
     toolbar clutter; the persisted option lives with the rest of the
     chart config now. -->
<!-- Scrollbar only when small-multiples grow the canvas beyond the
     wrapper height. With a single chart we render the canvas at exactly
     60vh / 320px so the wrapper's overflow can stay hidden — otherwise
     ECharts' internal padding shoves the content over by a pixel or two
     and a phantom vertical scrollbar appears (user feedback 2026-06-07
     "scrollbar that isn't needed"). -->
<div class="chart-scroll" class:chart-scroll--scrollable={smallMultipleRows > 1}>
	<!-- #1090: the canvas is decorative to assistive tech; the sr-only table below
       is the accessible representation, so hide the canvas from screen readers. -->
	<div
		class="chart"
		bind:this={host}
		aria-hidden="true"
		style="height: {smallMultipleRows * 60}vh; min-height: {smallMultipleRows * 320}px;"
	></div>
	<!-- #1090: visually-hidden data-table mirror for screen readers. -->
	<table class="sr-only">
		<caption>{a11y.caption}</caption>
		{#if !a11y.empty}
			<thead>
				<tr>
					{#each a11y.headers as h, i (i)}
						<th scope="col">{h}</th>
					{/each}
				</tr>
			</thead>
			<tbody>
				{#each a11y.rows as row, ri (ri)}
					<tr>
						<th scope="row">{row[0]}</th>
						{#each row.slice(1) as cell, ci (ci)}
							<td>{cell}</td>
						{/each}
					</tr>
				{/each}
			</tbody>
		{/if}
	</table>
</div>

<style>
	/* #1083 sort + top-N styles removed — controls live in ChartEditorModal now. */
	/* #1053: the frame stays one viewport tall; small multiples grow the inner
     chart to N rows and this wrapper scrolls, keeping each chart full-size. */
	.chart-scroll {
		width: 100%;
		height: 60vh;
		min-height: 320px;
		overflow-y: hidden;
		overflow-x: hidden;
		background: hsl(var(--bg));
		border: 1px solid hsl(var(--border));
		border-radius: var(--radius-sm);
	}
	/* Only show the scrollbar when small-multiples actually overflow. */
	.chart {
		width: 100%;
		/* height + min-height are set inline = smallMultipleRows × the single size. */
	}
	/* #1090: visually hide the a11y data table while keeping it in the
     accessibility tree (standard sr-only / visually-hidden pattern). */
	.sr-only {
		position: absolute;
		width: 1px;
		height: 1px;
		padding: 0;
		margin: -1px;
		overflow: hidden;
		clip: rect(0, 0, 0, 0);
		white-space: nowrap;
		border: 0;
	}
</style>
