<!--
  Custom edge for the Confirm cube Canvas.  Renders a bezier path with
  a foreignObject-hosted HTML label at the midpoint — full CSS control,
  so the label is guaranteed readable on any theme (no SvelteFlow-
  default white <rect> covering the text).
-->
<script lang="ts">
	import { BaseEdge, EdgeLabel, getBezierPath, type EdgeProps } from '@xyflow/svelte';

	interface FKLabelEdgeData {
		label: string;
	}

	let {
		id,
		sourceX,
		sourceY,
		targetX,
		targetY,
		sourcePosition,
		targetPosition,
		markerEnd,
		style,
		data
	}: EdgeProps & { data?: FKLabelEdgeData } = $props();

	const path = $derived(
		getBezierPath({
			sourceX,
			sourceY,
			sourcePosition,
			targetX,
			targetY,
			targetPosition
		})
	);
</script>

<BaseEdge {id} path={path[0]} {markerEnd} {style} />
{#if data?.label}
	<!-- `transparent={true}` on EdgeLabel drops the default
	     .svelte-flow__edge-label background frame; only our themed
	     pill inside stays visible. -->
	<EdgeLabel x={path[1]} y={path[2]} transparent>
		<div
			class="pointer-events-none rounded bg-card/90 px-1.5 py-0.5 font-mono text-[10px] whitespace-nowrap text-foreground"
		>
			{data.label}
		</div>
	</EdgeLabel>
{/if}
