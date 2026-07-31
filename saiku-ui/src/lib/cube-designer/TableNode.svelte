<!--
  Canvas schema designer — TableNode.

  Custom @xyflow/svelte node type that renders one table on the canvas.
  Headers carry the table name + role badge (Fact / Dimension / Bridge).
  Rows below list each column with a small handle on each side so the
  user can drag column-to-column to wire a join.

  Visual states:
   - normal: muted border, card background.
   - selected: brand-red ring.
   - fact: heavier border, brand-tinted background.
-->
<script lang="ts">
	import { Handle, Position, type NodeProps } from '@xyflow/svelte';
	import { X, ChevronsDownUp, ChevronsUpDown } from 'lucide-svelte';
	import type { SchemaCanvasTable } from './types.js';

	interface TableNodeData {
		table: SchemaCanvasTable;
		highlightedColumn: string | null;
		/** Armed source for click-to-join. When a column on this table is
		 *  the pending source, the row gets a brand-ring + helper hint. */
		pendingJoinSource: { tableId: string; columnName: string } | null;
		/** While the user is holding E, every clicked column lands here.
		 *  On release, the store joins every distinct pair across the set. */
		eShiftPicks: Array<{ tableId: string; columnName: string }>;
		/** Global "show first N columns" preference from the canvas store.
		 *  0 = headers only. Per-table `collapsed` overrides this. */
		defaultColumnsShown: number;
		/** When focus is active and this table isn't in scope, fade. */
		isFaded: boolean;
		/** `${tableId}:${columnName}` keys for every column row that
		 *  participates in a focused join — TableNode highlights matching
		 *  rows green so the user can see WHICH columns are wired. */
		focusedColumnKeys: Set<string>;
		/** `${tableId}:${columnName}` keys for every column that has a
		 *  join. Handle dots are visible on those rows; on un-joined
		 *  rows the dots are hidden until the row is hovered (still
		 *  draggable to make new joins). */
		connectedColumnKeys: Set<string>;
		/** When this table is part of a focus, auto-show every column
		 *  so the wired columns are visible without manual expansion. */
		forceExpandedByFocus: boolean;
		onPromoteToFact: (tableId: string) => void;
		onRemove: (tableId: string) => void;
		onColumnClick: (tableId: string, columnName: string, metaOrCtrl: boolean) => void;
		onToggleCollapsed: (tableId: string) => void;
		onExpandFully: (tableId: string) => void;
	}

	let { data, selected }: NodeProps & { data: TableNodeData } = $props();
	const table = $derived(data.table);
	const hasHighlightedColumn = $derived(
		data.highlightedColumn !== null && table.columns.some((c) => c.name === data.highlightedColumn)
	);

	/**
	 * How many columns to actually render in the body. Priority order:
	 *   1. Per-table user override (`table.collapsed`: true → 0; false → all)
	 *   2. Global default from the store (`defaultColumnsShown`)
	 *   3. Fallback: show all
	 * Returning 0 makes the body collapse to a single "anchor" handle row
	 * so existing joins still point at a DOM element.
	 */
	const visibleCount = $derived.by(() => {
		if (data.forceExpandedByFocus) return table.columns.length;
		if (table.collapsed === true) return 0;
		if (table.collapsed === false) return table.columns.length;
		return Math.max(0, Math.min(data.defaultColumnsShown, table.columns.length));
	});
	const visibleColumns = $derived(table.columns.slice(0, visibleCount));
	const hiddenCount = $derived(table.columns.length - visibleCount);
</script>

<div
	class="w-60 rounded-md border bg-card text-sm shadow-sm transition-opacity"
	class:border-border={!selected && !hasHighlightedColumn}
	class:ring-2={selected}
	class:ring-primary={selected}
	class:ring-success={!selected && hasHighlightedColumn}
	class:shadow-success={hasHighlightedColumn}
	style:box-shadow={hasHighlightedColumn ? '0 0 24px 2px hsl(var(--success) / 0.45)' : undefined}
	style:opacity={data.isFaded ? 0.2 : 1}
	data-testid="canvas-table-node"
	data-table-id={table.id}
>
	<!-- Peer model — every table is equal.  No "fact" badge, no
	     brand-red border.

	     Header layout — table name only.  User already knows which
	     schema is bound (it's the one they picked in the toolbar
	     dropdown); a per-node schema chip is redundant noise.  Names
	     wrap up to 2 lines (line-clamp-2) before ellipsising; the whole
	     header carries a native `title` tooltip with the qualified name
	     for the tail cases that still overflow.  Fixes #969, #971. -->
	<div
		class="flex items-start justify-between gap-2 rounded-t-md border-b border-border bg-muted px-3 py-2"
		title={table.schema ? `${table.schema}.${table.name}` : table.name}
	>
		<div class="min-w-0 flex-1">
			<div
				class="line-clamp-2 font-mono text-xs leading-tight break-all"
				data-testid="canvas-table-name"
			>
				{table.name}
			</div>
		</div>
		<div class="flex items-center gap-1">
			<button
				type="button"
				class="nodrag rounded p-1 text-xs opacity-70 hover:bg-background/30 hover:opacity-100"
				title={table.collapsed ? 'Expand columns' : 'Collapse to header'}
				aria-label={table.collapsed ? 'Expand columns' : 'Collapse to header'}
				onpointerdown={(e) => e.stopPropagation()}
				onclick={(e) => {
					e.stopPropagation();
					data.onToggleCollapsed(table.id);
				}}
				data-testid="canvas-toggle-collapsed"
			>
				{#if table.collapsed}
					<ChevronsUpDown class="h-3.5 w-3.5" aria-hidden="true" />
				{:else}
					<ChevronsDownUp class="h-3.5 w-3.5" aria-hidden="true" />
				{/if}
			</button>
			<button
				type="button"
				class="nodrag rounded p-1 text-xs opacity-70 hover:bg-background/30 hover:opacity-100"
				title="Remove from canvas"
				aria-label="Remove from canvas"
				onpointerdown={(e) => e.stopPropagation()}
				onclick={(e) => {
					e.stopPropagation();
					data.onRemove(table.id);
				}}
				data-testid="canvas-remove-table"
			>
				<X class="h-3.5 w-3.5" aria-hidden="true" />
			</button>
		</div>
	</div>
	<!-- Visible-columns slice = user-collapsed?0 : user-expanded?all : global default.
	     Scrolling was banned (it clipped handles off-screen so SvelteFlow
	     couldn't track join endpoints), so any hidden columns are
	     surfaced via the footer below. -->
	{#if visibleCount > 0}
		<ul class="py-1 text-xs">
			{#each visibleColumns as col (col.name)}
				{@const isHighlighted = data.highlightedColumn === col.name}
				{@const ePickIndex = data.eShiftPicks.findIndex(
					(p) => p.tableId === table.id && p.columnName === col.name
				)}
				{@const isEPicked = ePickIndex >= 0}
				{@const isJoinFocused = data.focusedColumnKeys.has(`${table.id}:${col.name}`)}
				{@const isConnected = data.connectedColumnKeys.has(`${table.id}:${col.name}`)}
				<!-- Two distinct row colours so manual picks vs auto matches
				     never blur into each other:
				       • GREEN (success) — name-matched by search/click, or
				         currently in a focused join.  "The system found this."
				       • YELLOW (warning) — explicit user pick in pick mode.
				         "I chose this."
				     A row that's BOTH highlighted AND picked falls under
				     pick-priority (yellow wins) because the intent is more
				     deliberate. -->
				{@const showPick = isEPicked}
				{@const showHighlight = !isEPicked && (isHighlighted || isJoinFocused)}
				<li
					class="group relative flex items-center justify-between gap-2 px-3 py-1 transition-colors"
					class:bg-warning={showPick}
					class:text-warning-foreground={showPick}
					class:hover:bg-warning={showPick}
					class:bg-success={showHighlight}
					class:text-success-foreground={showHighlight}
					class:hover:bg-success={showHighlight}
					class:hover:bg-accent={!showPick && !showHighlight}
					class:hover:text-accent-foreground={!showPick && !showHighlight}
					class:row-connected={isConnected}
					data-testid="canvas-table-column"
					data-column-name={col.name}
				>
					<!-- Stacked handles so the chosen side picks the shortest
				     path. Each column has BOTH a source and a target on
				     each side (left = `:in-left` / `:out-left`,
				     right = `:in-right` / `:out-right`). flowEdges picks
				     the actual handle IDs based on which side of source
				     vs. target the table sits on, so the line takes the
				     straightest route. Visually only one dot per side
				     reads because the pair overlaps. -->
					<Handle
						type="target"
						position={Position.Left}
						id={`${table.id}:${col.name}:in-left`}
						class="!h-2 !w-2 !border-input !bg-background"
					/>
					<Handle
						type="source"
						position={Position.Left}
						id={`${table.id}:${col.name}:out-left`}
						class="!h-2 !w-2 !border-input !bg-background"
					/>
					<button
						type="button"
						class="nodrag flex flex-1 cursor-pointer items-center justify-between gap-2 truncate text-left"
						onpointerdown={(e) => e.stopPropagation()}
						onclick={(e) => {
							e.stopPropagation();
							data.onColumnClick(table.id, col.name, e.metaKey || e.ctrlKey);
						}}
						title={`${col.name} · ${col.sqlType}`}
					>
						<span class="truncate font-mono">{col.name}</span>
						{#if isEPicked}
							<span
								class="shrink-0 rounded bg-success-foreground/15 px-1.5 py-0.5 text-[9px] font-medium tracking-wider uppercase"
								title="E-shift pick #{ePickIndex + 1}"
							>
								#{ePickIndex + 1}
							</span>
						{:else}
							<span class="shrink-0 text-[10px] uppercase opacity-60">{col.sqlType}</span>
						{/if}
					</button>
					<Handle
						type="source"
						position={Position.Right}
						id={`${table.id}:${col.name}:out-right`}
						class="!h-2 !w-2 !border-input !bg-background"
					/>
					<Handle
						type="target"
						position={Position.Right}
						id={`${table.id}:${col.name}:in-right`}
						class="!h-2 !w-2 !border-input !bg-background"
					/>
				</li>
			{/each}
		</ul>
		{#if hiddenCount > 0}
			<!-- "+ N more" footer — clickable button that expands the
			     table to show every column. Also anchors hidden columns'
			     handles so any joins to them still point at a DOM
			     element while the footer is showing. -->
			<button
				type="button"
				class="nodrag relative flex w-full items-center justify-center border-t border-border px-3 py-1 text-[10px] font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
				title="Show all {table.columns.length} columns"
				onpointerdown={(e) => e.stopPropagation()}
				onclick={(e) => {
					e.stopPropagation();
					data.onExpandFully(table.id);
				}}
				data-testid="canvas-table-expand-more"
			>
				+ {hiddenCount} more
				{#each table.columns.slice(visibleCount) as col (col.name)}
					<Handle
						type="target"
						position={Position.Left}
						id={`${table.id}:${col.name}:in-left`}
						class="!h-2 !w-2 !border-input !bg-background"
						style="top: 50%;"
					/>
					<Handle
						type="source"
						position={Position.Left}
						id={`${table.id}:${col.name}:out-left`}
						class="!h-2 !w-2 !border-input !bg-background"
						style="top: 50%;"
					/>
					<Handle
						type="source"
						position={Position.Right}
						id={`${table.id}:${col.name}:out-right`}
						class="!h-2 !w-2 !border-input !bg-background"
						style="top: 50%;"
					/>
					<Handle
						type="target"
						position={Position.Right}
						id={`${table.id}:${col.name}:in-right`}
						class="!h-2 !w-2 !border-input !bg-background"
						style="top: 50%;"
					/>
				{/each}
			</button>
		{/if}
	{:else}
		<!-- Fully collapsed: every column's source/target handles stack at
		     the header midline so existing joins point at a DOM element. -->
		<div class="relative h-2 w-full">
			{#each table.columns as col (col.name)}
				<Handle
					type="target"
					position={Position.Left}
					id={`${table.id}:${col.name}:in-left`}
					class="!h-2 !w-2 !border-input !bg-background"
					style="top: 50%;"
				/>
				<Handle
					type="source"
					position={Position.Left}
					id={`${table.id}:${col.name}:out-left`}
					class="!h-2 !w-2 !border-input !bg-background"
					style="top: 50%;"
				/>
				<Handle
					type="source"
					position={Position.Right}
					id={`${table.id}:${col.name}:out-right`}
					class="!h-2 !w-2 !border-input !bg-background"
					style="top: 50%;"
				/>
				<Handle
					type="target"
					position={Position.Right}
					id={`${table.id}:${col.name}:in-right`}
					class="!h-2 !w-2 !border-input !bg-background"
					style="top: 50%;"
				/>
			{/each}
		</div>
	{/if}
</div>

<style>
	/* Handle dots are visible only when the column has a join, OR when
	   the user hovers the row (so unjoined columns are still draggable
	   to make new connections). Cuts visual noise on tall tables where
	   most columns aren't wired. */
	li :global(.svelte-flow__handle) {
		opacity: 0;
		transition: opacity 120ms ease;
	}
	li.row-connected :global(.svelte-flow__handle),
	li:hover :global(.svelte-flow__handle) {
		opacity: 1;
	}

	/* Light-mode override for the column-highlight green. The default
	   `--success` token resolves to a saturated emerald that's hard on
	   the eyes against the white column row. Soften to pale mint with
	   deep mint text so it still reads "highlighted" without shouting. */
	:global(:root:not([data-theme='dark'])) li.bg-success {
		background-color: hsl(150 55% 88%);
		color: hsl(150 55% 22%);
	}
	:global(:root:not([data-theme='dark'])) li.hover\:bg-success:hover {
		background-color: hsl(150 55% 84%);
		color: hsl(150 55% 22%);
	}
	/* Light-mode override for the pick yellow.  Same softening logic as
	   the green — the raw `--warning` is a saturated amber that pops too
	   hard against a white row.  Pale butter with deep ochre text. */
	:global(:root:not([data-theme='dark'])) li.bg-warning {
		background-color: hsl(45 90% 86%);
		color: hsl(35 70% 22%);
	}
	:global(:root:not([data-theme='dark'])) li.hover\:bg-warning:hover {
		background-color: hsl(45 90% 80%);
		color: hsl(35 70% 22%);
	}

	/* Dark-mode overrides — the raw `--success-foreground` /
	   `--warning-foreground` tokens are white, which is unreadable against
	   the bright meadow / honey highlight colors on the row.  Force deep
	   ink text so column name + SQL type stay legible on both states. */
	:global(:root[data-theme='dark']) li.bg-success {
		color: hsl(150 60% 12%);
	}
	:global(:root[data-theme='dark']) li.hover\:bg-success:hover {
		color: hsl(150 60% 12%);
	}
	:global(:root[data-theme='dark']) li.bg-warning {
		color: hsl(35 70% 14%);
	}
	:global(:root[data-theme='dark']) li.hover\:bg-warning:hover {
		color: hsl(35 70% 14%);
	}
</style>
