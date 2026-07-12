<script lang="ts">
	import { onMount } from 'svelte';
	import type { ECharts, EChartsOption } from 'echarts';
	import type { ChartRow } from '$lib/types';

	type ChartKind = 'bar' | 'hbar' | 'donut';

	interface Props {
		type: ChartKind;
		data: ChartRow[];
		color?: string;
	}

	let { type, data, color }: Props = $props();

	const AXIS_LINE_COLOR = '#1e2231';
	const SPLIT_LINE_COLOR = '#1e2231';
	const TEXT_COLOR = '#8b90a3';
	const DEFAULT_BAR_COLOR = '#57d6e6';
	const HBAR_COLOR = '#f5b544';
	const LONG_LABEL_THRESHOLD = 6;
	const LABEL_ROTATION_DEGREES = 30;
	const DONUT_PALETTE = ['#57d6e6', '#f5b544', '#5fe0a0', '#9b8cff', '#ff5d6c', '#c8ccda', '#8b90a3', '#3a4160'];

	const numberFormatter = new Intl.NumberFormat('en-GB');

	function formatValue(value: number): string {
		return numberFormatter.format(value);
	}

	// Compact axis labels so wide numbers (25,000,000) don't clip the grid edge.
	function compact(value: number): string {
		if (Math.abs(value) >= 1e6) return `${(value / 1e6).toFixed(0)}M`;
		if (Math.abs(value) >= 1e3) return `${(value / 1e3).toFixed(0)}k`;
		return String(value);
	}

	function baseOption(): EChartsOption {
		return {
			backgroundColor: 'transparent',
			textStyle: { color: TEXT_COLOR, fontFamily: 'monospace' },
			grid: { top: 20, right: 16, bottom: 8, left: 8, containLabel: true }
		};
	}

	function barOption(rows: ChartRow[]): EChartsOption {
		const hasLongLabels = rows.some((r) => r.label.length > LONG_LABEL_THRESHOLD);
		return {
			...baseOption(),
			tooltip: { trigger: 'axis', valueFormatter: (v) => formatValue(Number(v)) },
			xAxis: {
				type: 'category',
				data: rows.map((r) => r.label),
				axisLine: { lineStyle: { color: AXIS_LINE_COLOR } },
				axisTick: { show: false },
				axisLabel: hasLongLabels
					? { rotate: LABEL_ROTATION_DEGREES, color: TEXT_COLOR }
					: { color: TEXT_COLOR }
			},
			yAxis: {
				type: 'value',
				axisLine: { show: false },
				splitLine: { lineStyle: { color: SPLIT_LINE_COLOR } },
				axisLabel: { color: TEXT_COLOR, formatter: (v: number) => compact(v) }
			},
			series: [
				{
					type: 'bar',
					data: rows.map((r) => r.value),
					itemStyle: { color: color ?? DEFAULT_BAR_COLOR, borderRadius: [3, 3, 0, 0] },
					barMaxWidth: 36
				}
			]
		};
	}

	function hbarOption(rows: ChartRow[]): EChartsOption {
		// Reverse so the biggest value renders first, then set yAxis.inverse so it
		// still ends up on top (ECharts places category index 0 at the bottom by default).
		const ordered = [...rows].reverse();
		return {
			...baseOption(),
			tooltip: { trigger: 'axis', valueFormatter: (v) => formatValue(Number(v)) },
			xAxis: {
				type: 'value',
				axisLine: { show: false },
				splitLine: { lineStyle: { color: SPLIT_LINE_COLOR } },
				axisLabel: { color: TEXT_COLOR, formatter: (v: number) => compact(v) }
			},
			yAxis: {
				type: 'category',
				data: ordered.map((r) => r.label),
				inverse: true,
				axisLine: { lineStyle: { color: AXIS_LINE_COLOR } },
				axisTick: { show: false },
				// cap the reserved label column so long names truncate cleanly with an
				// ellipsis instead of bleeding off the left edge of the card
				axisLabel: { color: TEXT_COLOR, width: 168, overflow: 'truncate', fontSize: 11 }
			},
			series: [
				{
					type: 'bar',
					data: ordered.map((r) => r.value),
					itemStyle: { color: color ?? HBAR_COLOR, borderRadius: [0, 3, 3, 0] },
					barMaxWidth: 22
				}
			]
		};
	}

	function donutOption(rows: ChartRow[]): EChartsOption {
		return {
			...baseOption(),
			color: DONUT_PALETTE,
			tooltip: { trigger: 'item', valueFormatter: (v) => formatValue(Number(v)) },
			legend: {
				orient: 'vertical',
				right: 4,
				top: 'middle',
				textStyle: { color: TEXT_COLOR, fontFamily: 'monospace', fontSize: 11 },
				itemWidth: 10,
				itemHeight: 10
			},
			series: [
				{
					type: 'pie',
					radius: ['52%', '78%'],
					center: ['38%', '50%'],
					avoidLabelOverlap: false,
					label: { show: false },
					labelLine: { show: false },
					data: rows.map((r) => ({ name: r.label, value: r.value }))
				}
			]
		};
	}

	function buildOption(kind: ChartKind, rows: ChartRow[]): EChartsOption {
		if (kind === 'hbar') return hbarOption(rows);
		if (kind === 'donut') return donutOption(rows);
		return barOption(rows);
	}

	let container: HTMLDivElement | undefined = $state();
	let chart: ECharts | undefined;

	onMount(() => {
		let disposed = false;
		let resizeObserver: ResizeObserver | undefined;

		(async () => {
			const echarts = await import('echarts');
			if (disposed || !container) return;
			chart = echarts.init(container);
			chart.setOption(buildOption(type, data));

			resizeObserver = new ResizeObserver(() => chart?.resize());
			resizeObserver.observe(container);
		})();

		return () => {
			disposed = true;
			resizeObserver?.disconnect();
			chart?.dispose();
			chart = undefined;
		};
	});

	$effect(() => {
		chart?.setOption(buildOption(type, data), true);
	});
</script>

<div class="chart-host" bind:this={container}></div>

<style>
	.chart-host {
		width: 100%;
		height: 260px;
	}
</style>
