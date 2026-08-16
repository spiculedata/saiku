<!--
  The canvas region for the schema designer: the @xyflow/svelte SvelteFlow
  pane (custom TableNode + CanvasEdge), the floating Source + DimSum overlays,
  the pick-mode bubble, the empty-state card, the "Joins (N)" reopen chip, and
  the selected-table detail strip.

  Presentational: reads/writes the shared SchemaCanvasStore directly and owns
  the canvas-local interaction wiring (drop / connect / canvas keys + the
  search-highlight, jump, pick-toggle and hide-panels effects). The parent
  owns nodes/edges ($state + sync effects, bound in here so SvelteFlow can
  write back) and the DimSum chat state (threaded in as the `dimSumWidget`
  snippet). SvelteFlow theming lives in this component's <style>.
-->
<script lang="ts">
	import {
		SvelteFlow,
		Background,
		Controls,
		MiniMap,
		type Node,
		type Edge,
		type Connection
	} from '@xyflow/svelte';
	import '@xyflow/svelte/dist/style.css';
	import { untrack, type Snippet } from 'svelte';
	import { ChevronRight, X, EyeOff } from '@lucide/svelte';
	import TableNode from './TableNode.svelte';
	import CanvasEdge from './CanvasEdge.svelte';
	import JumpHandler from './JumpHandler.svelte';
	import TableSidebar from './TableSidebar.svelte';
	import type { SchemaCanvasStore } from './state.svelte.js';
	import {
		parseConnectionJoin,
		parseDroppedTableCandidates,
		resolveDropOrigin
	} from './canvas-dnd.js';

	interface Props {
		store: SchemaCanvasStore;
		nodes: Node[];
		edges: Edge[];
		focusActive: boolean;
		joinsPanelOpen: boolean;
		sourceVisible: boolean;
		dimSumVisible: boolean;
		canvasRegionEl: HTMLDivElement | null;
		/** Source/Schema pickers snippet threaded to TableSidebar. */
		sourceContext?: Snippet;
	}

	let {
		store,
		nodes = $bindable(),
		edges = $bindable(),
		focusActive,
		joinsPanelOpen = $bindable(),
		sourceVisible = $bindable(),
		dimSumVisible = $bindable(),
		canvasRegionEl = $bindable(),
		sourceContext
	}: Props = $props();

	const nodeTypes = { table: TableNode };

	const edgeTypes = { canvasJoin: CanvasEdge } as any;

	const schemaFilteredSourceTables = $derived(
		store.selectedSchema
			? store.sourceTables.filter((t) => t.schema === store.selectedSchema)
			: store.sourceTables
	);

	// Column name driving the "Pull in N with <colname>" CTA.  Prefer
	// the click-driven highlight (its "add this column's peers" intent
	// is unambiguous), otherwise fall back to the sidebar search text
	// interpreted as an exact column name — that way the button also
	// appears when the user typed a full column name into the search
	// (which clears the highlight per setSidebarQueryFromUser).
	const pullInColname = $derived(store.highlightedColumn ?? store.sidebarQuery.trim());
	// "Pull in N with <colname>" — off-canvas tables that have a column
	// exactly matching pullInColname.
	const pullInMatches = $derived.by(() => {
		if (!pullInColname) return 0;
		const lc = pullInColname.toLowerCase();
		return store.sourceTables.filter(
			(c) =>
				!store.tableIdentitiesOnCanvas.has(c.schema ? `${c.schema}.${c.name}` : c.name) &&
				c.columns.some((col) => col.name.toLowerCase() === lc)
		).length;
	});

	function handleConnect(connection: Connection) {
		// Handle parsing (`<tableId>:<columnName>:in|out`) lives in ./canvas-dnd
		// so it's unit-testable; the store mutation stays here.
		const parsed = parseConnectionJoin(connection);
		if (!parsed) return;
		store.addJoin({ ...parsed, kind: 'inner' });
	}

	// Drag-from-sidebar — pane drop handler.
	function handlePaneDragOver(e: DragEvent) {
		e.preventDefault();
		if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy';
	}

	function handlePaneDrop(e: DragEvent) {
		e.preventDefault();
		// Prefer the multi-table payload (new). Fall back to the
		// single-table payload for any legacy drop source.
		const arrayPayload = e.dataTransfer?.getData('application/x-saiku-tables');
		const singlePayload = e.dataTransfer?.getData('application/x-saiku-table');
		// Payload parsing (multi/legacy formats + malformed guard) lives in
		// ./canvas-dnd; the position maths + store mutation stay here.
		const candidates = parseDroppedTableCandidates(arrayPayload, singlePayload);
		if (candidates.length === 0) return;
		const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
		// saiku#1634 #3: map the pointer's viewport coords into FLOW space via the
		// converter JumpHandler published, so a panned/zoomed canvas drops the node
		// under the cursor rather than off-screen. Falls back to pane-relative
		// pixels (identity-viewport behaviour) when the converter isn't available.
		const { x: baseX, y: baseY } = resolveDropOrigin(
			store.screenToFlowPosition,
			e.clientX,
			e.clientY,
			rect
		);
		// Each addTable / addJoin snapshots on its own so Undo peels the
		// drop off one table at a time (last-in-first-out).  Batching used
		// to wrap this in withUndoBatch — one snapshot for the whole drop
		// meant Undo wiped every table added in a single gesture, which
		// wasn't what users expected.
		candidates.forEach((candidate, i) => {
			const added = store.addTable(candidate, {
				x: baseX + i * 40,
				y: baseY + i * 280
			});
			if (store.autoSuggestOnDrop) {
				store.runAutoSuggestForTable(added.id);
			}
		});
		// Drop consumed the selection.
		store.sidebarSelectedIdentities = new Set();
	}

	function handleCanvasKey(e: KeyboardEvent) {
		// Don't steal keys when the user is typing in an input/textarea/select.
		const target = e.target as HTMLElement | null;
		const inField = target !== null && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName);
		if (inField) return;
		// ESC: cancel either an E-shift collection in progress, or a
		// pending click-to-join arm, in that order.
		if (e.key === 'Escape') {
			if (store.pickModeActive || store.eShiftPicks.length > 0) {
				store.cancelEShift();
				return;
			}
			if (store.pendingJoinSource !== null) {
				store.pendingJoinSource = null;
				store.highlightedColumn = null;
				store.sidebarQuery = '';
				return;
			}
		}
		// Backspace/Delete on a selected join removes it.
		if ((e.key === 'Backspace' || e.key === 'Delete') && store.selectedJoinId) {
			e.preventDefault();
			store.removeJoin(store.selectedJoinId);
		}
	}

	// Keyboard shortcuts for Create Joins Mode:
	//   ⌘/Ctrl+J → toggle the mode on/off (mnemonic: Join).  Requires
	//              the modifier so a stray `j` typed anywhere on the
	//              page (a div with focus after a click, a search box
	//              our INPUT guard misses) can't accidentally toggle it.
	//   Enter    → commit current picks (>=2) as join(s), using the
	//              auto-name from the picked columns.  The picks-panel
	//              input has its own local Enter handler, so this only
	//              fires when the input isn't focused.
	//   Escape   → cancel picks + exit the mode
	// Enter + Escape still ignore INPUT/TEXTAREA/SELECT focus so typing
	// in a search or the group-name field isn't hijacked.
	$effect(() => {
		function down(e: KeyboardEvent) {
			if (e.repeat) return;
			if ((e.key === 'j' || e.key === 'J') && (e.metaKey || e.ctrlKey)) {
				e.preventDefault();
				store.togglePickMode();
				return;
			}
			const target = e.target as HTMLElement | null;
			const inField = target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName);
			if (inField) return;
			if (e.key === 'Enter' && store.eShiftPicks.length >= 2) {
				e.preventDefault();
				store.commitEShiftPicks(store.autoPickGroupName());
				return;
			}
			if (e.key === 'Escape' && (store.pickModeActive || store.eShiftPicks.length > 0)) {
				e.preventDefault();
				store.cancelEShift();
				return;
			}
		}
		function up() {
			// no-op: pick mode is sticky.  Kept so the matching
			// addEventListener / removeEventListener pair stays balanced.
		}
		window.addEventListener('keydown', down);
		window.addEventListener('keyup', up);
		return () => {
			window.removeEventListener('keydown', down);
			window.removeEventListener('keyup', up);
		};
	});

	// `\` toggles BOTH side panels (Source + Joins) at once, matching
	// Figma's "hide UI" shortcut.  If either is closed, the tap opens
	// both; if both are open, it closes both.  Skipped when the user
	// is typing in an input.
	$effect(() => {
		function down(e: KeyboardEvent) {
			if (e.repeat) return;
			if (e.key !== '\\') return;
			const target = e.target as HTMLElement | null;
			if (target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return;
			const allOpen = sourceVisible && dimSumVisible && joinsPanelOpen;
			sourceVisible = !allOpen;
			dimSumVisible = !allOpen;
			joinsPanelOpen = !allOpen;
		}
		window.addEventListener('keydown', down);
		return () => window.removeEventListener('keydown', down);
	});

	// Sidebar search → active set, in auto mode. If the user types an
	// EXACT column name (case-insensitive) that exists on any canvas
	// table, light up every matching row via `highlightedColumn`. We
	// only clear the highlight when the user empties the search box
	// AFTER having driven the highlight via search — a click-initiated
	// highlight survives a manual search clear (tracked via
	// `store.highlightSource`).
	$effect(() => {
		const q = store.sidebarQuery.trim();
		const qLc = q.toLowerCase();
		untrack(() => {
			if (!q) {
				if (store.highlightSource === 'search') {
					store.highlightedColumn = null;
					store.highlightOriginTableId = null;
					store.highlightSource = null;
				}
				return;
			}
			// `activeSet` uses exact case-sensitive matches, so we surface
			// the actual on-canvas column name (not the user's typed
			// casing) when we find one.
			let canonical: string | null = null;
			for (const t of store.doc.tables) {
				for (const c of t.columns) {
					if (c.name.toLowerCase() === qLc) {
						canonical = c.name;
						break;
					}
				}
				if (canonical) break;
			}
			if (!canonical) {
				// Typing a substring like `cust` shouldn't trigger a glow;
				// also drop any stale search-driven highlight.
				if (store.highlightSource === 'search') {
					store.highlightedColumn = null;
					store.highlightOriginTableId = null;
					store.highlightSource = null;
				}
				return;
			}
			// Don't clobber a click-driven highlight on a different name.
			if (store.highlightSource === 'click' && store.highlightedColumn !== null) return;
			if (store.highlightedColumn !== canonical) {
				store.highlightedColumn = canonical;
				store.highlightOriginTableId = null;
				store.highlightSource = 'search';
			}
		});
	});

	// Jump-to-table from sidebar — when a result row's "on canvas" link
	// is clicked, the sidebar stamps `store.requestedJumpTarget`. The
	// actual `setCenter()` call happens inside the SvelteFlow tree via
	// the JumpHandler child below (which can call useSvelteFlow()).
	$effect(() => {
		const target = store.requestedJumpTarget;
		if (!target) return;
		untrack(() => {
			store.selectedTableId = target.tableId;
			if (target.columnName) {
				store.highlightedColumn = target.columnName;
				store.highlightOriginTableId = null;
				store.highlightSource = 'click';
			}
		});
	});
</script>

<!-- Sidebar Pull-in chip — passed to TableSidebar's `afterSearch`
     slot so it lands right under the search input, above any
     fields-matching results. Empty state stays out of the
     sidebar entirely. -->
{#snippet sidebarPullInChip()}
	{#if pullInColname}
		{@const colname = pullInColname}
		{#if pullInMatches > 0}
			<!-- Summary + CTA — clarifies what the green button actually
			     does (adds the off-canvas tables to the canvas, doesn't
			     join them unless Auto-join is on). -->
			<div class="space-y-1.5">
				<p class="text-[11px] leading-snug text-muted-foreground">
					{pullInMatches}
					{pullInMatches === 1 ? 'table has' : 'tables have'}
					<span class="font-mono text-foreground">{colname}</span>
					but
					{pullInMatches === 1 ? 'is' : 'are'}
					not on the canvas yet.
				</p>
				<button
					type="button"
					onclick={() => store.pullInMatchingColumnTables(colname)}
					class="inline-flex h-8 w-full items-center justify-center gap-1.5 rounded border border-success bg-success px-2 text-xs font-medium whitespace-nowrap text-success-foreground transition-opacity hover:opacity-90"
					data-testid="canvas-pull-in-matching"
					title={store.autoSuggestOnDrop
						? `Pull in ${pullInMatches} matching table(s) and auto-link by column name`
						: `Pull in ${pullInMatches} matching table(s) — turn on Auto-join to also link them`}
				>
					Pull in {pullInMatches}
					{pullInMatches === 1 ? 'matching table' : 'matching tables'}
				</button>
			</div>
		{/if}
	{/if}
{/snippet}

<div
	bind:this={canvasRegionEl}
	class="relative flex-1"
	role="region"
	aria-label="Schema canvas"
	tabindex={-1}
	ondragover={handlePaneDragOver}
	ondrop={handlePaneDrop}
	onkeydown={handleCanvasKey}
	style:--right-panel-offset="{store.joinsPanelWidth + 16}px"
>
	<!-- Pick-mode bubble — floats at the top centre of the canvas
	     while pick mode is active.  Tells the user what mode
	     they're in (⌘J to toggle), what to do (click columns
	     across tables), and what happens at the end (Match in
	     the right-rail panel, name the group). -->
	{#if store.pickModeActive}
		<div
			class="pointer-events-none absolute top-3 left-1/2 -translate-x-1/2"
			style:z-index="200"
			data-testid="canvas-pick-mode-bubble"
		>
			<div
				class="pointer-events-auto relative flex w-[min(420px,calc(100vw-2rem))] flex-col gap-1 rounded-lg border-2 border-primary/70 bg-primary/15 px-4 py-3 text-xs text-foreground shadow-[0_4px_18px_-2px_hsl(var(--primary)/0.35)] backdrop-blur"
			>
				<div class="text-[10px] font-semibold tracking-wider text-primary uppercase">
					Create Joins Mode
				</div>
				<p class="pr-6 leading-snug">
					Click columns across tables and press Enter to create a join, or name the join in the
					JOINS panel on the right before creating.
				</p>
				<button
					type="button"
					onclick={() => store.togglePickMode()}
					class="absolute top-2 right-2 inline-flex h-5 w-5 items-center justify-center rounded text-muted-foreground hover:bg-accent hover:text-accent-foreground"
					aria-label="Exit Create Joins Mode"
					title="Exit Create Joins Mode (⌘J)"
					data-testid="canvas-pick-mode-exit"
				>
					<X class="h-3 w-3" aria-hidden="true" />
				</button>
			</div>
		</div>
	{/if}
	<!-- Left overlay — SOURCE panel floats over the canvas
	     (top-left). DimSum used to stack under it here; it now lives
	     on the right beside JOINS (see SchemaCanvasView, which also
	     owns the JOINS + DimSum reopen chips). Container is
	     transparent / pointer-events-none so canvas underneath is
	     clickable everywhere the actual panel/chip isn't. -->
	<div
		class="pointer-events-none absolute inset-y-2 left-2 z-20 flex flex-col gap-2"
		style:width="{store.sidebarWidth}px"
	>
		{#if sourceVisible}
			<div
				class="pointer-events-auto flex min-h-0 flex-1 flex-col overflow-hidden rounded border border-border bg-card shadow-md"
				data-testid="canvas-source-panel-floating"
			>
				<header
					class="flex shrink-0 items-center justify-between gap-2 border-b border-border px-3 py-2 text-xs font-semibold tracking-wider uppercase"
					style:color="hsl(var(--muted-foreground))"
				>
					<span>Source Tables ({schemaFilteredSourceTables.length})</span>
					<button
						type="button"
						onclick={() => (sourceVisible = false)}
						class="inline-flex h-5 w-5 items-center justify-center rounded transition-colors hover:bg-accent hover:text-accent-foreground"
						aria-label="Hide source panel"
						title="Hide source panel"
						data-testid="canvas-tables-panel-close"
					>
						<EyeOff class="h-3.5 w-3.5" aria-hidden="true" />
					</button>
				</header>
				<div class="min-h-0 flex-1 overflow-hidden">
					<TableSidebar
						tables={schemaFilteredSourceTables}
						loading={store.sourceLoading}
						error={store.sourceError}
						query={store.sidebarQuery}
						onQueryChange={(q) => store.setSidebarQueryFromUser(q)}
						onCanvasIdentities={store.tableIdentitiesOnCanvas}
						width={store.sidebarWidth}
						onWidthChange={(px) => (store.sidebarWidth = px)}
						selectedIdentities={store.sidebarSelectedIdentities}
						onSelectionChange={(next) => (store.sidebarSelectedIdentities = next)}
						afterSearch={sidebarPullInChip}
						{sourceContext}
						onJumpToCanvasTable={(identity, columnName) => {
							const tbl = store.doc.tables.find(
								(t) => (t.schema ? `${t.schema}.${t.name}` : t.name) === identity
							);
							if (!tbl) return;
							store.requestedJumpTarget = {
								tableId: tbl.id,
								columnName,
								ts: Date.now()
							};
						}}
						onAddToCanvas={(t) => {
							const rightmost = store.doc.tables.reduce(
								(mx, tt) => Math.max(mx, tt.position.x + 200),
								0
							);
							store.addTable(t, {
								x: rightmost + 40,
								y: 80 + (store.doc.tables.length % 4) * 240
							});
						}}
					/>
				</div>
			</div>
		{:else}
			<button
				type="button"
				onclick={() => (sourceVisible = true)}
				class="pointer-events-auto inline-flex h-7 shrink-0 items-center gap-1 self-start rounded border border-input bg-card px-2 text-xs font-medium shadow-sm transition-colors hover:bg-accent hover:text-accent-foreground"
				data-testid="canvas-tables-panel-open"
				title="Show source panel"
			>
				<ChevronRight class="h-3.5 w-3.5" aria-hidden="true" />
				Source
			</button>
		{/if}
	</div>
	{#if store.doc.tables.length === 0}
		<!-- Empty-state (#1087) — flexes to whatever gap the canvas
		     column has between the flanking source-list + joins
		     panels.  `min-w-0` on the outer flex + `max-w-full` on
		     the card lets it shrink; `text-balance` on the label
		     keeps line breaks tidy at narrow widths so the message
		     never clips.  Below 280px container width, the message
		     collapses to a short-form label so cmux-narrow browsers
		     still get a readable prompt.
		     Left inset matches the SOURCE overlay's width when open
		     so the card centres in the *visible* canvas gap, not
		     behind the sidebar. -->
		<div
			class="@container pointer-events-none absolute top-0 bottom-0 z-10 flex min-w-0 items-center justify-center px-4 text-center"
			style:left="{sourceVisible ? store.sidebarWidth + 16 : 8}px"
			style:right="{joinsPanelOpen ? store.joinsPanelWidth + 16 : 8}px"
			data-testid="canvas-empty"
		>
			<div
				class="max-w-md min-w-0 rounded-lg border border-border bg-card p-4 text-card-foreground shadow-md"
			>
				<p class="text-sm font-medium text-balance">
					<span class="hidden @[280px]:inline">Drag a table from the left to get started.</span>
					<span class="@[280px]:hidden">Drag a table here.</span>
				</p>
				<p class="mt-1 text-xs text-balance text-muted-foreground @max-[280px]:hidden">
					Start creating joins by turning on Create Joins Mode or wire columns to one another.
				</p>
			</div>
		</div>
	{/if}
	<div onkeydown={handleCanvasKey} role="presentation" class="contents">
		<SvelteFlow
			bind:nodes
			bind:edges
			{nodeTypes}
			{edgeTypes}
			onconnect={handleConnect}
			onpaneclick={() => {
				store.clearAllInteractions();
				// SvelteFlow's bound edges/nodes arrays sometimes
				// retain `selected: true` after the pane click —
				// the sync $effect would then re-stamp the stale
				// selection back onto the store, undoing the
				// clear (the canvas would stay green-highlighted
				// even though clearAllInteractions ran).  Reset
				// both arrays here to keep the store as the
				// source of truth.
				edges = edges.map((e) => ({ ...e, selected: false }));
				nodes = nodes.map((n) => ({ ...n, selected: false }));
			}}
			proOptions={{ hideAttribution: true }}
			class={`flow-themed !bg-background${focusActive ? ' has-focus' : ''}`}
		>
			<Background />
			<!-- Controls sit at bottom-right, shifted LEFT of the
			     MiniMap so both share the same corner without
			     overlapping.  Vertical stack — matches the original
			     SvelteFlow default look.  Both are also pushed LEFT
			     by the right-hand panel column width so they don't
			     hide behind the JOINS / DimSum overlays; handled in
			     <style> via the inherited `--right-panel-offset`
			     custom property. -->
			<Controls position="bottom-right" orientation="vertical" />
			<MiniMap pannable zoomable position="bottom-right" />
			<JumpHandler {store} />
		</SvelteFlow>
	</div>
	<!--
		Selected-table detail strip.  Floats top-centre, appears when
		a table or a column is picked.  Answers "which one did I just
		click?" without the user having to hover the (fixed-width,
		sometimes truncated) node header.  Fixes #971 at the
		readability level while the w-60 node width stays intact for
		canvas density.

		Content ladder — prefer the more specific selection:
			column-highlight active → `schema.table > column`
			else table selected     → `schema.table`
	-->
	{#if store.selectedTableId || store.highlightOriginTableId}
		{@const highlightTable = store.highlightOriginTableId
			? store.doc.tables.find((t) => t.id === store.highlightOriginTableId)
			: null}
		{@const primaryTable =
			highlightTable ??
			(store.selectedTableId ? store.doc.tables.find((t) => t.id === store.selectedTableId) : null)}
		{#if primaryTable}
			{@const highlightedCol =
				highlightTable && store.highlightedColumn ? store.highlightedColumn : null}
			{@const highlightedColMeta = highlightedCol
				? primaryTable.columns.find((c) => c.name === highlightedCol)
				: null}
			<!-- Shift LEFT when the joins panel is collapsed — otherwise
			     this pill overlaps the "Joins (N)" open-button that sits
			     at top-right of the canvas.  When the panel is open,
			     it's a sibling column so top-3 right-3 is clear. -->
			<div
				class="pointer-events-none absolute top-3 z-20 flex max-w-[min(360px,calc(100%-2rem))] flex-col gap-0.5 rounded-md border border-border bg-card/95 px-3 py-2 text-xs shadow-md backdrop-blur {joinsPanelOpen
					? 'right-3'
					: 'right-28'}"
				data-testid="canvas-selected-table-strip"
			>
				<span class="text-[9px] font-semibold tracking-wider text-muted-foreground uppercase">
					Currently selected
				</span>
				<div class="flex items-center gap-1">
					<span
						class="truncate font-mono"
						title={primaryTable.schema
							? `${primaryTable.schema}.${primaryTable.name}`
							: primaryTable.name}
						data-testid="canvas-selected-table-name"
					>
						{#if primaryTable.schema}<span class="text-muted-foreground"
								>{primaryTable.schema}.</span
							>{/if}{primaryTable.name}
					</span>
					{#if highlightedCol}
						<span class="shrink-0 text-muted-foreground">›</span>
						<span
							class="truncate font-mono"
							title={highlightedColMeta
								? `${highlightedCol} (${highlightedColMeta.sqlType})`
								: highlightedCol}
							data-testid="canvas-selected-column-name"
						>
							{highlightedCol}{#if highlightedColMeta}<span class="ml-1 text-muted-foreground"
									>({highlightedColMeta.sqlType})</span
								>{/if}
						</span>
					{/if}
				</div>
			</div>
		{/if}
	{/if}
</div>

<style>
	/* SvelteFlow's `.svelte-flow__edge-label` is an HTML div, not SVG —
	   so `fill:` does nothing. Its colours are wired via two CSS vars
	   (`--xy-edge-label-color`, `--xy-edge-label-background-color`)
	   that default to white-on-inherit. Setting the vars on
	   `.svelte-flow` (SvelteFlow's own root class) puts them in scope
	   for every child, including the portal-rendered edge labels. The
	   non-global `.flow-themed` selector got Svelte-hashed and never
	   matched SvelteFlow's internal container — hence the previous
	   no-op. Use `:global()` to keep the selector raw. */
	:global(.svelte-flow.flow-themed) {
		/* Deep indigo-purple for join labels so they read as a different
		   "kind of thing" from the charcoal table cards. */
		--xy-edge-label-color: hsl(260 30% 92%);
		--xy-edge-label-background-color: hsl(263 55% 22%);
	}
	/* The xyflow EdgeLabel WRAPPER (`.svelte-flow__edge-label`) had its
	   own background + border + z-index in earlier versions of this
	   file. Both are now harmful: my CanvasEdge's inner `.canvas-edge-label`
	   div already paints the pill, so the wrapper background showed
	   as a SECOND rectangle behind it (Amelia's "absolutely opaque
	   over the tables" complaint). And the wrapper's `z-index: 99999`
	   forced labels above the focused table card. Everything below is
	   intentionally minimal — let the inner div be the label and let
	   xyflow's default stacking put labels under nodes when focus is
	   active. Without `.has-focus` we still want labels readable when
	   they cross unrelated tables, so the wrapper gets a high z-index
	   in the idle state only. */
	:global(.svelte-flow.flow-themed:not(.has-focus) .svelte-flow__edge-label),
	:global(.svelte-flow.flow-themed:not(.has-focus) .svelte-flow__edge-labels) {
		z-index: 99999 !important;
	}
	/* xyflow's default CSS sets a background + padding on the
	   `.svelte-flow__edge-label` wrapper, using the
	   `--xy-edge-label-background-color` var we hand it for the SVG
	   fallback path. That paints a SECOND rectangle behind our inner
	   `.canvas-edge-label` div — which is what kept showing up at
	   full purple even when we faded the inner div to opacity 0.06.
	   Strip the wrapper bare so only the inner div paints. */
	:global(.svelte-flow.flow-themed .svelte-flow__edge-label) {
		background: transparent !important;
		border: none !important;
		padding: 0 !important;
	}
	/* When focus is active, drop the whole labels container UNDER the
	   nodes container. This is the only reliable way to keep focused
	   table cards readable — z-index on individual label divs can't
	   escape their wrapper container's stacking. */
	:global(.svelte-flow.flow-themed.has-focus .svelte-flow__edge-labels) {
		z-index: 0 !important;
	}
	:global(.svelte-flow.flow-themed.has-focus .svelte-flow__nodes) {
		z-index: 1 !important;
	}
	/* SVG-rendered fallback path (used when `label` is a plain string
	   without rich content). Different properties — fill / stroke. */
	:global(.svelte-flow.flow-themed .svelte-flow__edge-text) {
		fill: hsl(var(--foreground)) !important;
		font-size: 11px;
		font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
	}
	:global(.svelte-flow.flow-themed .svelte-flow__edge-textbg) {
		fill: hsl(var(--muted)) !important;
		stroke: hsl(var(--border));
		stroke-width: 1;
	}
	:global(.svelte-flow.flow-themed .svelte-flow__edge.selected .svelte-flow__edge-textbg),
	:global(.svelte-flow.flow-themed .svelte-flow__edge:focus .svelte-flow__edge-textbg) {
		stroke: hsl(var(--primary)) !important;
		stroke-width: 1.5;
		filter: drop-shadow(0 0 8px hsl(var(--primary) / 0.55));
	}
	/* MiniMap — give it a real border + a sharper viewport mask so the
	   shape reads in both light and dark mode. The default ghosting was
	   especially bad in light mode where the mask had no contrast.
	   `right` is shifted LEFT by `--right-panel-offset` (set on the
	   canvas wrapper as `joinsPanelWidth + 16px`) so the minimap clears
	   the JOINS / Ask DimSum column that always reserves that width on
	   the right, whether panels or chips are showing. */
	:global(.svelte-flow__minimap) {
		background: hsl(var(--card));
		border: 1px solid hsl(var(--border));
		border-radius: 6px;
		box-shadow: 0 2px 6px hsl(0 0% 0% / 0.08);
		overflow: hidden;
		right: var(--right-panel-offset, 0px) !important;
	}
	:global(.svelte-flow__minimap-mask) {
		fill: hsl(var(--background) / 0.45);
		stroke: hsl(var(--foreground) / 0.25);
		stroke-width: 1.5;
	}
	/* Node thumbnails inside the minimap — give them a soft outline so
	   the object shapes are legible against the canvas background. */
	:global(.svelte-flow__minimap-node) {
		fill: hsl(var(--card-foreground) / 0.25);
		stroke: hsl(var(--foreground) / 0.4);
		stroke-width: 1;
	}
	/* Controls panel (zoom in / out / fit-view / lock) — same story; the
	   default white-on-white loses against the dashboard's dark card.
	   `right` sits 216px LEFT of the minimap (200px minimap width +
	   16px gutter), then plus `--right-panel-offset` so both clear the
	   right-hand JOINS / DimSum column. */
	:global(.svelte-flow__controls) {
		background: hsl(var(--card));
		border: 1px solid hsl(var(--border));
		box-shadow: none;
		right: calc(var(--right-panel-offset, 0px) + 216px) !important;
	}
	:global(.svelte-flow__controls-button) {
		background: hsl(var(--card));
		border-bottom: 1px solid hsl(var(--border));
		fill: hsl(var(--foreground));
	}
	:global(.svelte-flow__controls-button:hover) {
		background: hsl(var(--accent));
		fill: hsl(var(--accent-foreground));
	}
</style>
