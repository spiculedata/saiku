<!--
  Canvas schema designer — CanvasEdge.

  Custom @xyflow/svelte edge type that renders one curve per
  table-pair and stacks role-playing-dimension joins into a single
  popover-style label.

  Why aggregate at the table-pair level: if a fact joins `date` three
  times via order_date_id / ship_date_id / invoice_date_id, three
  bezier curves end up stacking with their labels piled on the same
  midpoint and the user can't tell them apart. We render ONE curve per
  pair (using the first join's handles for geometry) and a count badge;
  click the badge to see + delete each individual join.

  Selection + delete are wired via `EdgeLabel`'s `selectEdgeOnClick`
  (one click selects the edge, no fighting with focus state) plus the
  inline X buttons inside the popover.
-->
<script lang="ts">
	import {
		BaseEdge,
		EdgeLabel,
		getBezierPath,
		getSmoothStepPath,
		type EdgeProps
	} from '@xyflow/svelte';
	import { X, Layers } from '@lucide/svelte';
	import type { SchemaCanvasJoin } from './types.js';

	interface CanvasEdgeData {
		/** All joins between this edge's source/target tables. */
		joins: SchemaCanvasJoin[];
		/** Path rendering style — chosen at the canvas level. */
		edgeStyle: 'bezier' | 'smoothstep' | 'step';
		/** Focus dimming — when ON, drop the whole edge to low opacity. */
		isFaded: boolean;
		/** True when any focus / selection is active on the canvas.
		 *  We use it to drop ALL labels (even highlighted ones) below
		 *  the focused node's z-index so the focused table card stays
		 *  readable — otherwise the join label for "fact_count ↔
		 *  fact_count" sits on top of the table's column rows. */
		focusActive: boolean;
		onDelete: (joinId: string) => void;
		/** Picking a row in the multi-join popover marks the parent
		 *  edge as selected so the curve gets the same red glow as
		 *  clicking the line directly. */
		onSelect?: (joinId: string) => void;
	}

	let {
		id,
		sourceX,
		sourceY,
		targetX,
		targetY,
		sourcePosition,
		targetPosition,
		selected,
		data,
		markerEnd,
		style
	}: EdgeProps & { data: CanvasEdgeData } = $props();

	// `bezier` = the original smooth curves. `smoothstep` = orthogonal
	// with rounded corners (8px). `step` = same path with zero corner
	// radius for sharp right angles — both use the same underlying
	// xyflow helper just differently parameterised.
	const path = $derived.by(() => {
		const args = {
			sourceX,
			sourceY,
			sourcePosition,
			targetX,
			targetY,
			targetPosition
		};
		if (data.edgeStyle === 'smoothstep') return getSmoothStepPath({ ...args, borderRadius: 8 });
		if (data.edgeStyle === 'step') return getSmoothStepPath({ ...args, borderRadius: 0 });
		return getBezierPath(args);
	});
	const edgePath = $derived(path[0]);
	const labelX = $derived(path[1]);
	const labelY = $derived(path[2]);
	const joins = $derived(data.joins);
	const single = $derived(joins.length === 1);

	// Popover for the multi-join case — opens on click of the count
	// badge. We don't auto-open because users hover-scan the canvas.
	let popoverOpen = $state(false);
</script>

<BaseEdge
	{id}
	path={edgePath}
	{markerEnd}
	style={(style ?? '') + (data.isFaded ? '; opacity: 0.2;' : '')}
/>

<EdgeLabel x={labelX} y={labelY} selectEdgeOnClick>
	{#if single}
		{@const j = joins[0]}
		<div
			class="canvas-edge-label"
			class:selected
			class:faded={data.isFaded}
			class:focus-active={data.focusActive}
			data-testid="canvas-edge-label"
		>
			<span class="font-mono">
				{j.sourceColumnName} <span class="opacity-70">↔</span>
				{j.targetColumnName}
			</span>
			<button
				type="button"
				class="canvas-edge-delete"
				title="Delete join"
				aria-label="Delete join"
				onclick={(e) => {
					e.stopPropagation();
					data.onDelete(j.id);
				}}
				data-testid="canvas-edge-delete"
			>
				<X class="h-3 w-3" aria-hidden="true" />
			</button>
		</div>
	{:else}
		<div
			class="canvas-edge-label"
			class:selected
			class:faded={data.isFaded}
			class:focus-active={data.focusActive}
			class:popover-open={popoverOpen}
			data-testid="canvas-edge-label-multi"
		>
			<button
				type="button"
				class="canvas-edge-pill"
				onclick={(e) => {
					e.stopPropagation();
					popoverOpen = !popoverOpen;
				}}
				data-testid="canvas-edge-multi-toggle"
			>
				<Layers class="h-3 w-3" aria-hidden="true" />
				{joins.length} joins
			</button>
			{#if popoverOpen}
				<div class="canvas-edge-popover" role="menu">
					<div class="canvas-edge-popover-header">Joins</div>
					{#each joins as j (j.id)}
						<!-- Click anywhere on the row → mark the parent edge
						     selected (so the curve glows red) and close the
						     popover. Delete × still stops propagation so it
						     can't be hit by accident. -->
						<button
							type="button"
							class="canvas-edge-popover-row"
							onclick={(e) => {
								e.stopPropagation();
								data.onSelect?.(j.id);
								popoverOpen = false;
							}}
							data-testid="canvas-edge-popover-row"
						>
							<span class="truncate font-mono">
								{j.sourceColumnName} <span class="opacity-70">↔</span>
								{j.targetColumnName}
							</span>
							<span
								role="button"
								tabindex="0"
								class="canvas-edge-delete"
								title="Delete join"
								aria-label="Delete join"
								onclick={(e) => {
									e.stopPropagation();
									data.onDelete(j.id);
								}}
								onkeydown={(e) => {
									if (e.key === 'Enter' || e.key === ' ') {
										e.preventDefault();
										e.stopPropagation();
										data.onDelete(j.id);
									}
								}}
								data-testid="canvas-edge-popover-delete"
							>
								<X class="h-3 w-3" aria-hidden="true" />
							</span>
						</button>
					{/each}
				</div>
			{/if}
		</div>
	{/if}
</EdgeLabel>

<style>
	.canvas-edge-label {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		padding: 2px 4px 2px 8px;
		border-radius: 4px;
		/* Light-mode default — soft lavender card with deep indigo
		   text. Brand-red glow still wins on selected. */
		border: 1px solid hsl(263 40% 75%);
		background: hsl(263 60% 94%);
		/* Widen the paint area so the underlying edge stroke can't
		   peek through where the label sits — SVG paths are drawn in
		   the same layer and a selected edge (bumped z within xyflow)
		   can otherwise appear ON TOP of the label. The box-shadow
		   halo is a solid card-colour ring around the label that acts
		   as a bigger opaque canvas covering the line. */
		box-shadow: 0 0 0 3px hsl(263 60% 94%);
		color: hsl(263 60% 25%);
		font-size: 11px;
		line-height: 1.2;
		font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
		white-space: nowrap;
		pointer-events: all;
		transition:
			border-color 120ms ease,
			box-shadow 120ms ease;
		position: relative;
		/* Escape SvelteFlow's node stacking context — labels MUST be
		   above any table card they happen to cross. !important
		   because xyflow's EdgeLabel wrapper sets its own stacking
		   context that can swallow a non-important z-index. The label
		   has `pointer-events: all` only on itself, not the layer
		   above it, so table clicks elsewhere pass through fine. */
		z-index: 99999 !important;
		position: relative;
		isolation: isolate;
	}
	/* When the popover is open, lift the entire label above its
	   siblings so neighbouring edge labels can't paint over the
	   expansion. Same trick as the focused-node zIndex bump. */
	.canvas-edge-label.popover-open {
		z-index: 100001 !important;
	}
	.canvas-edge-label.faded {
		opacity: 0.2;
		/* Strip the filled rectangle on faded labels so a 6%-opacity
		   purple block doesn't read as a faint card on the dark
		   canvas. Pure-text fade is what the user actually wants. */
		background: transparent !important;
		border-color: transparent !important;
		box-shadow: none !important;
	}
	/* z-index inside .canvas-edge-label can't beat xyflow's outer
	   labels container, which paints above the nodes container by
	   source order. The real fix lives in flow-themed.has-focus
	   below, which flips the two containers' stacking. */
	.canvas-edge-label.selected {
		border-color: hsl(var(--primary));
		box-shadow:
			0 0 0 1px hsl(var(--primary) / 0.5),
			0 0 14px 2px hsl(var(--primary) / 0.4);
	}
	.canvas-edge-pill {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		padding: 1px 6px 1px 4px;
		border: none;
		background: transparent;
		color: inherit;
		font-size: 11px;
		font-weight: 500;
		font-family: inherit;
		cursor: pointer;
	}
	.canvas-edge-delete {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 16px;
		height: 16px;
		border-radius: 3px;
		border: none;
		background: transparent;
		color: hsl(var(--muted-foreground));
		cursor: pointer;
		transition:
			background 120ms ease,
			color 120ms ease;
	}
	.canvas-edge-delete:hover {
		background: hsl(var(--destructive));
		color: hsl(var(--destructive-foreground));
	}
	.canvas-edge-popover {
		position: absolute;
		top: calc(100% + 4px);
		left: 50%;
		transform: translateX(-50%);
		min-width: 220px;
		max-width: 340px;
		padding: 4px;
		border-radius: 6px;
		border: 1px solid hsl(var(--border));
		background: hsl(var(--card));
		/* Pin the row text to the theme's foreground so the rows aren't
		   stuck inheriting the lavender label color (unreadable on a
		   white card in light mode). */
		color: hsl(var(--foreground));
		box-shadow: 0 8px 24px hsl(0 0% 0% / 0.35);
		z-index: 50;
	}
	.canvas-edge-popover-header {
		padding: 4px 8px;
		border-bottom: 1px solid hsl(var(--border));
		font-size: 10px;
		font-weight: 500;
		letter-spacing: 0.04em;
		text-transform: uppercase;
		color: hsl(var(--muted-foreground));
	}
	.canvas-edge-popover-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 6px;
		width: 100%;
		padding: 4px 6px;
		border-radius: 3px;
		border: none;
		background: transparent;
		font-size: 11px;
		font-family: inherit;
		color: hsl(var(--foreground));
		text-align: left;
		cursor: pointer;
	}
	.canvas-edge-popover-row:hover {
		background: hsl(var(--accent));
		color: hsl(var(--accent-foreground));
	}

	/* Dark-mode flip: deep indigo card + soft lavender text for the
	   pill label, keeping the popover on theme tokens. */
	:global([data-theme='dark']) .canvas-edge-label {
		border-color: hsl(263 50% 35%);
		background: hsl(263 55% 22%);
		color: hsl(260 30% 92%);
	}
</style>
