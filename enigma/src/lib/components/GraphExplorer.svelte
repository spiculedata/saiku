<script lang="ts">
	import { onMount } from 'svelte';
	import { base } from '$app/paths';
	import type { Core, ElementDefinition, StylesheetJson } from 'cytoscape';
	import type { OwnershipGraph } from '$lib/types';

	const DEFAULT_DEPTH = 4;

	interface Props {
		rootId: string;
		onGraph?: (g: OwnershipGraph) => void;
	}

	let { rootId, onGraph }: Props = $props();

	let container: HTMLDivElement | undefined = $state();
	let loading = $state(true);
	let failed = $state(false);
	let hasCycle = $state(false);
	let isEmpty = $state(false);
	let cy: Core | undefined;

	function toElements(graph: OwnershipGraph): ElementDefinition[] {
		const nodes: ElementDefinition[] = graph.nodes.map((n) => ({
			data: { id: n.id, label: n.label },
			classes: n.kind + (n.id === graph.rootId ? ' subject' : '')
		}));
		const edges: ElementDefinition[] = graph.edges.map((e) => ({
			data: {
				id: `${e.owner}>${e.owned}`,
				source: e.owned,
				target: e.owner,
				pct: e.percentage != null ? `${Math.round(e.percentage)}%` : ''
			},
			classes: e.cycle ? 'cycle' : ''
		}));
		return [...nodes, ...edges];
	}

	const stylesheet: StylesheetJson = [
		{
			selector: 'node',
			style: {
				'background-color': '#141826',
				'border-width': 2,
				label: 'data(label)',
				color: '#e7e9f0',
				'font-family': 'monospace',
				'font-size': 9,
				'text-valign': 'center',
				'text-wrap': 'wrap',
				'text-max-width': '90px',
				width: 34,
				height: 34
			}
		},
		{
			selector: 'node.subject',
			style: {
				'border-color': '#57d6e6',
				width: 46,
				height: 46,
				'border-width': 3
			}
		},
		{
			selector: 'node.entity',
			style: { 'border-color': '#c8ccda' }
		},
		{
			selector: 'node.person',
			style: { 'border-color': '#f5b544', 'background-color': '#2a2410' }
		},
		{
			selector: 'edge',
			style: {
				width: 1.5,
				'line-color': '#3a4160',
				'target-arrow-color': '#3a4160',
				'target-arrow-shape': 'triangle',
				'curve-style': 'bezier',
				label: 'data(pct)',
				'font-size': 8,
				color: '#8b90a3'
			}
		},
		{
			selector: 'edge.cycle',
			style: {
				'line-color': '#ff5d6c',
				'target-arrow-color': '#ff5d6c',
				'line-style': 'dashed',
				width: 2
			}
		}
	];

	onMount(() => {
		let disposed = false;

		(async () => {
			const [{ default: cytoscape }, r] = await Promise.all([
				import('cytoscape'),
				fetch(`${base}/api/graph/${encodeURIComponent(rootId)}?depth=${DEFAULT_DEPTH}`)
			]);
			if (disposed) return;
			if (!r.ok) {
				failed = true;
				loading = false;
				return;
			}
			const graph = (await r.json()) as OwnershipGraph;
			if (disposed) return;
			isEmpty = graph.edges.length === 0;
			hasCycle = graph.hasCycle;
			onGraph?.(graph);
			loading = false;
			if (isEmpty || !container) return;
			cy = cytoscape({
				container,
				elements: toElements(graph),
				style: stylesheet,
				layout: { name: 'cose', animate: false, padding: 20, nodeRepulsion: 8000, idealEdgeLength: 90 }
			});
		})().catch(() => {
			if (!disposed) {
				failed = true;
				loading = false;
			}
		});

		return () => {
			disposed = true;
			cy?.destroy();
			cy = undefined;
		};
	});
</script>

<div class="wrap">
	<div class="stage grid-bg" bind:this={container}></div>
	{#if loading}
		<div class="overlay mono">Loading ownership graph…</div>
	{:else if failed}
		<div class="overlay mono">Graph unavailable.</div>
	{:else if isEmpty}
		<div class="overlay mono">No ownership relationships found.</div>
	{/if}
	{#if hasCycle}
		<div class="alert mono">⚠ Circular ownership detected</div>
	{/if}
</div>

<style>
	.wrap {
		position: relative;
		height: 100%;
		min-height: 420px;
	}
	.stage {
		position: absolute;
		inset: 0;
		background-image:
			radial-gradient(circle at 50% 40%, rgba(87, 214, 230, 0.06), transparent 60%),
			linear-gradient(rgba(255, 255, 255, 0.02) 1px, transparent 1px),
			linear-gradient(90deg, rgba(255, 255, 255, 0.02) 1px, transparent 1px);
		background-size:
			auto,
			34px 34px,
			34px 34px;
	}
	.overlay {
		position: absolute;
		inset: 0;
		display: flex;
		align-items: center;
		justify-content: center;
		color: var(--muted);
		font-size: 13px;
		pointer-events: none;
	}
	.alert {
		position: absolute;
		left: 16px;
		bottom: 16px;
		padding: 8px 14px;
		border-radius: 8px;
		background: rgba(255, 93, 108, 0.12);
		border: 1px solid var(--red);
		color: var(--red);
		font-size: 12px;
	}
</style>
