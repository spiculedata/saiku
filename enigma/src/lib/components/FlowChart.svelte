<script lang="ts">
	import { onMount } from 'svelte';
	import type { ECharts, EChartsOption } from 'echarts';
	import type { FlowData } from '../../routes/borderlines/+page.server';

	interface Props {
		data: FlowData;
	}
	let { data }: Props = $props();

	const TEXT_COLOR = '#c8ccda';
	const numberFormatter = new Intl.NumberFormat('en-GB');
	// Left = owner countries (cyan), right = jurisdictions (amber). The trailing-space
	// marker on jurisdiction node names (see loader) also lets us tint the two sides.
	const OWNER_COLOR = '#57d6e6';
	const JURIS_COLOR = '#f5b544';

	function isRightNode(name: string): boolean {
		return name.endsWith(' ');
	}

	function buildOption(flow: FlowData): EChartsOption {
		return {
			backgroundColor: 'transparent',
			textStyle: { color: TEXT_COLOR, fontFamily: 'monospace' },
			tooltip: {
				trigger: 'item',
				triggerOn: 'mousemove',
				formatter: (p: unknown) => {
					const param = p as { dataType?: string; data?: Record<string, unknown>; name?: string };
					if (param.dataType === 'edge') {
						const d = param.data as { source: string; target: string; value: number };
						return `${d.source.trim()} → ${d.target.trim()}<br/><b>${numberFormatter.format(d.value)}</b> interests`;
					}
					return (param.name ?? '').trim();
				}
			},
			series: [
				{
					type: 'sankey',
					left: 8,
					right: 130,
					top: 10,
					bottom: 10,
					nodeGap: 10,
					nodeWidth: 12,
					draggable: false,
					emphasis: { focus: 'adjacency' },
					data: flow.nodes.map((n) => ({
						name: n.name,
						itemStyle: { color: isRightNode(n.name) ? JURIS_COLOR : OWNER_COLOR, borderColor: 'transparent' }
					})),
					links: flow.links.map((l) => ({ ...l })),
					label: {
						color: TEXT_COLOR,
						fontFamily: 'monospace',
						fontSize: 11,
						formatter: (p: unknown) => ((p as { name: string }).name ?? '').trim()
					},
					lineStyle: { color: 'gradient', opacity: 0.32, curveness: 0.5 }
				}
			]
		};
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
			chart.setOption(buildOption(data));
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
		chart?.setOption(buildOption(data), true);
	});
</script>

<div class="flow-host" bind:this={container}></div>

<style>
	.flow-host {
		width: 100%;
		height: 440px;
	}
</style>
