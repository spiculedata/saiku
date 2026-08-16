<!--
  Canvas schema designer — left sidebar.

  Renders the source-table catalog (loaded from
  /api/inference/profile-connection) as a searchable, schema-grouped
  list. Each row is draggable onto the canvas — when the user drops, the
  parent canvas's onPaneDrop handler picks up the drag payload and
  creates a node.

  Tables already on the canvas are shown dimmed with an "on canvas"
  indicator so the user doesn't double-add them.
-->
<script lang="ts">
	import { Search, GripVertical, ChevronRight, Plus } from '@lucide/svelte';
	import type { SourceTableCandidate } from './types.js';

	interface Props {
		tables: SourceTableCandidate[];
		loading: boolean;
		error: string | null;
		query: string;
		onQueryChange: (q: string) => void;
		/** Set of `schema.name` identities currently on the canvas. */
		onCanvasIdentities: Set<string>;
		/** Identities whose row should be drag-disabled. Defaults to
		 *  `onCanvasIdentities` (Canvas view — can't add the same table
		 *  twice). Workbench passes its own working-set identities, so
		 *  on-canvas tables stay draggable to be added to the workbench. */
		disabledIdentities?: Set<string>;
		/** Pixel width — bound from the parent so the value persists across view swaps. */
		width: number;
		onWidthChange: (px: number) => void;
		/** Two-way selection set. Click toggles single, Shift/Cmd toggles
		 *  in-set; the parent's drop handler clears after a successful drop. */
		selectedIdentities: Set<string>;
		onSelectionChange: (next: Set<string>) => void;
		/** Optional snippet rendered between the search input and the
		 *  results list — used by the canvas view to drop the
		 *  "Pull in N with `<colname>`" chip in the right spot
		 *  (above the field-match results, below the search). */
		afterSearch?: import('svelte').Snippet;
		/** Optional snippet rendered at the very top of the sidebar,
		 *  above the "Source tables" header — used by the canvas view
		 *  to co-locate the source-connection + schema pickers with the
		 *  tables they list.  Frees the top toolbar for canvas-only
		 *  actions and gives the tables list its provenance context. */
		sourceContext?: import('svelte').Snippet;
		/** When set, the "on canvas" indicator on each result row becomes
		 *  an underlined link that calls this with the `schema.name`
		 *  identity (and, for field matches, the column name). The
		 *  Canvas view uses it to centre+select the matching table; the
		 *  Workbench view leaves it unset so the indicator stays a pill. */
		onJumpToCanvasTable?: (identity: string, columnName?: string) => void;
		/** Called when the user clicks the hover-revealed "+ Add to canvas"
		 *  button on a table row.  Same shape as a drag-drop or "add via
		 *  MCP" — the parent decides where to place the node.  Left unset
		 *  by hosts that don't want the affordance (e.g. Workbench source
		 *  panel). */
		onAddToCanvas?: (t: SourceTableCandidate) => void;
	}

	let {
		tables,
		loading,
		error,
		query,
		onQueryChange,
		onCanvasIdentities,
		disabledIdentities,
		width,
		onWidthChange,
		selectedIdentities,
		onSelectionChange,
		afterSearch,
		sourceContext,
		onJumpToCanvasTable,
		onAddToCanvas
	}: Props = $props();
	// Default: same set as on-canvas (Canvas view's behaviour). Workbench
	// overrides to its working-set so on-canvas-but-not-in-workbench
	// tables stay draggable.
	// Only EXPLICIT disabledIdentities count as disabled.  Tables that
	// are already on canvas are NOT disabled — clicking such a row
	// jumps the canvas to that table instead of arming a drag.
	const disabledSet = $derived(disabledIdentities ?? new Set<string>());

	const MIN_WIDTH = 220;
	const MAX_WIDTH = 560;

	function handleSplitterPointerDown(e: PointerEvent) {
		e.preventDefault();
		const startX = e.clientX;
		const startWidth = width;
		(e.target as HTMLElement).setPointerCapture(e.pointerId);
		function move(ev: PointerEvent) {
			const next = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, startWidth + (ev.clientX - startX)));
			onWidthChange(next);
		}
		function up(ev: PointerEvent) {
			(e.target as HTMLElement).releasePointerCapture(ev.pointerId);
			window.removeEventListener('pointermove', move);
			window.removeEventListener('pointerup', up);
		}
		window.addEventListener('pointermove', move);
		window.addEventListener('pointerup', up);
	}

	function ident(t: SourceTableCandidate): string {
		return t.schema ? `${t.schema}.${t.name}` : t.name;
	}
	function isOnCanvas(t: SourceTableCandidate): boolean {
		return onCanvasIdentities.has(ident(t));
	}
	function isDisabled(t: SourceTableCandidate): boolean {
		return disabledSet.has(ident(t));
	}
	function isSelected(t: SourceTableCandidate): boolean {
		return selectedIdentities.has(ident(t));
	}
	// Range-select anchor (#1083) — the identity of the last row the user
	// individually clicked (plain or Cmd/Ctrl click). Shift-click selects
	// the contiguous range between this anchor and the clicked row; it does
	// NOT move the anchor. Null until the first individual click.
	let anchorId = $state<string | null>(null);

	function handleRowClick(e: MouseEvent, t: SourceTableCandidate) {
		if (isDisabled(t)) return;
		const id = ident(t);
		// Modifier semantics follow the macOS/list convention:
		//   • Shift        → range-select from the anchor to this row.
		//   • Cmd / Ctrl   → toggle THIS row in/out of the selection.
		//   • plain click  → single-select (replace with just this row).
		const isRange = e.shiftKey;
		const isToggle = e.metaKey || e.ctrlKey;

		// Plain click on an on-canvas table jumps the canvas to it (the
		// modifier paths below intentionally fall through to selection so
		// the user can still multi-pick on-canvas rows).
		if (!isRange && !isToggle && isOnCanvas(t) && onJumpToCanvasTable) {
			onJumpToCanvasTable(id);
			return;
		}

		if (isRange) {
			// Resolve indices over the CURRENT visible order. With no anchor
			// yet (or a stale anchor no longer visible), Shift behaves like a
			// plain click and seeds the anchor.
			const order = flatVisibleIdentities;
			const from = anchorId === null ? -1 : order.indexOf(anchorId);
			const to = order.indexOf(id);
			if (from === -1 || to === -1) {
				const single = new Set<string>([id]);
				anchorId = id;
				onSelectionChange(single);
				return;
			}
			const [lo, hi] = from <= to ? [from, to] : [to, from];
			// Add the contiguous range to the existing selection (union).
			// Anchor stays put so the user can grow/shrink the range.
			const next = new Set(selectedIdentities);
			for (const rid of order.slice(lo, hi + 1)) next.add(rid);
			onSelectionChange(next);
			return;
		}

		const next = new Set(selectedIdentities);
		if (isToggle) {
			if (next.has(id)) next.delete(id);
			else next.add(id);
		} else {
			// Plain click — replace selection with just this row.
			next.clear();
			next.add(id);
		}
		// Both plain and toggle clicks re-anchor to this row.
		anchorId = id;
		onSelectionChange(next);
	}

	// Two parallel result sets when the user is actively searching:
	//   - Table matches  — tables whose qualified name contains the query.
	//   - Field matches  — tables that have one or more columns matching
	//                      the query; we keep the matching column names so
	//                      they can be displayed under the table.
	// When the query is empty we just show the schema-grouped table list.
	const tableMatches = $derived.by(() => {
		const q = query.trim().toLowerCase();
		if (!q) return tables;
		return tables.filter((t) => {
			const qualified = (t.schema ? `${t.schema}.${t.name}` : t.name).toLowerCase();
			return qualified.includes(q);
		});
	});

	// Per-column-mention rows. If `customer_id` appears in 3 tables, that
	// becomes 3 rows — column name on the left, parent table name beside
	// it. Sorted alphabetically by column then by table.
	const fieldMatches = $derived.by<Array<{ column: string; table: SourceTableCandidate }>>(() => {
		const q = query.trim().toLowerCase();
		if (!q) return [];
		const out: Array<{ column: string; table: SourceTableCandidate }> = [];
		for (const t of tables) {
			const qualified = (t.schema ? `${t.schema}.${t.name}` : t.name).toLowerCase();
			// Skip tables whose NAME matched — they live in the Tables section.
			if (qualified.includes(q)) continue;
			for (const c of t.columns) {
				if (c.name.toLowerCase().includes(q)) {
					out.push({ column: c.name, table: t });
				}
			}
		}
		out.sort((a, b) => {
			const ca = a.column.localeCompare(b.column);
			if (ca !== 0) return ca;
			return a.table.name.localeCompare(b.table.name);
		});
		return out;
	});

	// Sort control state — clickable column headers cycle through
	// (name-asc → name-desc → cols-desc → cols-asc → name-asc).  Split
	// into (key, direction) so the header can render its active arrow
	// independently.  Persisted per-workspace so the choice sticks
	// across reloads.
	const SORT_STORAGE_KEY = 'saiku.canvas.sidebar.sort';
	type SortKey = 'name' | 'cols';
	type SortDir = 'asc' | 'desc';
	let sortBy = $state<SortKey>('cols');
	let sortDir = $state<SortDir>('desc');
	$effect(() => {
		if (typeof window === 'undefined') return;
		try {
			const raw = window.localStorage.getItem(SORT_STORAGE_KEY);
			if (!raw) return;
			const parsed = JSON.parse(raw) as { by?: SortKey; dir?: SortDir };
			if (parsed.by === 'name' || parsed.by === 'cols') sortBy = parsed.by;
			if (parsed.dir === 'asc' || parsed.dir === 'desc') sortDir = parsed.dir;
		} catch {
			/* corrupt / unavailable — keep defaults */
		}
	});
	function toggleSort(next: SortKey): void {
		if (sortBy === next) {
			sortDir = sortDir === 'asc' ? 'desc' : 'asc';
		} else {
			sortBy = next;
			// Sensible defaults: name → asc (A→Z), cols → desc (biggest first).
			sortDir = next === 'name' ? 'asc' : 'desc';
		}
		try {
			window.localStorage.setItem(SORT_STORAGE_KEY, JSON.stringify({ by: sortBy, dir: sortDir }));
		} catch {
			/* quota / private browsing — non-fatal */
		}
	}

	const grouped = $derived.by(() => {
		const byGroup = new Map<string, SourceTableCandidate[]>();
		for (const t of tableMatches) {
			const key = t.schema ?? '(no schema)';
			if (!byGroup.has(key)) byGroup.set(key, []);
			byGroup.get(key)!.push(t);
		}
		const cmp = (a: SourceTableCandidate, b: SourceTableCandidate) => {
			const flip = sortDir === 'asc' ? 1 : -1;
			if (sortBy === 'name') return a.name.localeCompare(b.name) * flip;
			return (a.columns.length - b.columns.length) * flip;
		};
		return [...byGroup.entries()]
			.sort(([a], [b]) => a.localeCompare(b))
			.map(([schema, tables]) => ({
				schema,
				tables: [...tables].sort(cmp)
			}));
	});

	const isSearching = $derived(query.trim().length > 0);

	// Flattened identity list in the exact order the grouped rows render
	// (schema group order, then each group's sorted tables). Drives
	// Shift-click range resolution (#1083) so the range matches what the
	// user actually sees.
	const flatVisibleIdentities = $derived(
		grouped.flatMap((group) => group.tables.map((t) => ident(t)))
	);

	function handleDragStart(e: DragEvent, t: SourceTableCandidate) {
		if (!e.dataTransfer) return;
		// On-canvas tables are non-draggable (#1084) — belt-and-braces
		// no-op in case a drag still starts (e.g. keyboard-driven DnD).
		// Re-dragging an on-canvas table would otherwise add a duplicate
		// node.
		if (isOnCanvas(t)) {
			e.preventDefault();
			return;
		}
		e.dataTransfer.effectAllowed = 'copy';
		// If the dragged row is part of the current selection, drag the
		// WHOLE selection. Otherwise just this one (don't disturb
		// whatever was selected — matches Finder behaviour).
		const inSelection = isSelected(t);
		const bundle = inSelection ? tables.filter((c) => selectedIdentities.has(ident(c))) : [t];
		// Drop targets prefer the array payload but fall back to the
		// singular for backwards-compat with anything we missed.
		e.dataTransfer.setData('application/x-saiku-tables', JSON.stringify(bundle));
		e.dataTransfer.setData('application/x-saiku-table', JSON.stringify(t));
		e.dataTransfer.setData(
			'text/plain',
			bundle.map((c) => (c.schema ? `${c.schema}.${c.name}` : c.name)).join(', ')
		);
	}
</script>

<!-- min-h-0 on the aside lets `flex-1` on the scroll region below
     actually take effect when the sidebar is height-constrained by
     a narrow viewport; without it the sourceContext + header + search
     stack can eat all the space and leave the tables list with no
     scroll area (the "spills into the section above" bug from #1003). -->
<!-- SOURCE header + EyeOff removed — the wrapping card in
     SchemaCanvasView now owns those, matching the DimSum card style. -->
<!-- No bg-card / border on the aside — the wrapping card in
     SchemaCanvasView owns the panel chrome now. -->
<aside
	class="relative flex h-full min-h-0 flex-1 shrink-0 flex-col gap-3 p-3"
	style:width="{width}px"
	aria-label="Source"
	data-testid="canvas-sidebar"
>
	<!-- Tables sub-header removed — count now lives inline in the panel
	     header ("Source Tables (N)"), matching the JOINS panel pattern. -->

	<div class="relative shrink-0">
		<Search
			class="pointer-events-none absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 text-muted-foreground"
			aria-hidden="true"
		/>
		<input
			type="search"
			placeholder="Search tables…"
			aria-label="Search tables"
			class="h-9 w-full rounded border border-input bg-background pr-3 pl-9 text-sm"
			value={query}
			oninput={(e) => onQueryChange((e.currentTarget as HTMLInputElement).value)}
			data-testid="canvas-sidebar-search"
		/>
	</div>

	{#if afterSearch}
		<div class="shrink-0">
			{@render afterSearch()}
		</div>
	{/if}

	{#if loading}
		<p class="text-xs text-muted-foreground" data-testid="canvas-sidebar-loading">
			Profiling your data source — this can take a moment for big warehouses…
		</p>
	{:else if error}
		<p
			class="rounded border border-destructive/40 bg-destructive/10 p-2 text-xs text-destructive"
			data-testid="canvas-sidebar-error"
		>
			{error}
		</p>
	{:else if isSearching && tableMatches.length === 0 && fieldMatches.length === 0}
		<p class="text-xs text-muted-foreground" data-testid="canvas-sidebar-empty">
			No tables or fields match "{query}".
		</p>
	{:else if tables.length === 0}
		<p class="text-xs text-muted-foreground" data-testid="canvas-sidebar-empty">
			No tables found in this data source.
		</p>
	{:else}
		<!-- min-h-0 is the flex-column contract that unlocks the scroll —
		     `flex-1` alone gives the container 100% remaining height ONLY
		     if the flex parent lets children shrink below their intrinsic
		     content height, which requires min-h-0.  Without it a tall
		     rows list forces the parent to grow past the viewport and
		     the scroll region silently disappears (see #1003). -->
		<div class="min-h-0 flex-1 overflow-y-auto" data-testid="canvas-sidebar-list">
			<!-- Tables section — shown either as schema-grouped (no search)
			     or as a flat list of name-matching tables (when searching). -->
			{#if tableMatches.length > 0}
				{#if isSearching}
					<div
						class="mb-1 border-b border-border pb-1 text-[10px] font-medium tracking-wider text-muted-foreground uppercase"
					>
						Tables matching "{query}" ({tableMatches.length})
					</div>
				{/if}
				<!-- Sort controls — two clickable "column headers" (Name /
				     Cols) that cycle between asc/desc.  Active column
				     shows an arrow (↑ asc, ↓ desc); inactive shows a
				     dim ↕ hinting it's clickable. -->
				<div
					class="mb-1 flex items-center justify-between gap-2 border-b border-border pb-1"
					data-testid="canvas-sidebar-sort"
				>
					<button
						type="button"
						onclick={() => toggleSort('name')}
						class="text-[10px] font-medium tracking-wider uppercase hover:text-foreground {sortBy ===
						'name'
							? 'text-foreground'
							: 'text-muted-foreground'}"
						data-testid="canvas-sidebar-sort-name"
						title="Sort tables by name"
					>
						Name
						<span class="ml-0.5 opacity-70">
							{sortBy === 'name' ? (sortDir === 'asc' ? '↑' : '↓') : '↕'}
						</span>
					</button>
					<button
						type="button"
						onclick={() => toggleSort('cols')}
						class="text-[10px] font-medium tracking-wider uppercase hover:text-foreground {sortBy ===
						'cols'
							? 'text-foreground'
							: 'text-muted-foreground'}"
						data-testid="canvas-sidebar-sort-cols"
						title="Sort tables by column count"
					>
						Cols
						<span class="ml-0.5 opacity-70">
							{sortBy === 'cols' ? (sortDir === 'asc' ? '↑' : '↓') : '↕'}
						</span>
					</button>
				</div>
				{#each grouped as group (group.schema)}
					<div class="mb-2">
						{#if !isSearching}
							<div
								class="sticky top-0 z-10 bg-card py-1 text-[10px] font-medium tracking-wider text-muted-foreground uppercase"
							>
								{group.schema}
								<span class="opacity-70">({group.tables.length})</span>
							</div>
						{/if}
						<ul class="space-y-0.5">
							{#each group.tables as t (`${group.schema}.${t.name}`)}
								{@const rowDisabled = isDisabled(t)}
								{@const rowOnCanvas = isOnCanvas(t)}
								{@const rowSelected = isSelected(t)}
								<li>
									<div
										role="button"
										tabindex={rowDisabled ? -1 : 0}
										aria-disabled={rowDisabled ? 'true' : undefined}
										draggable={!rowDisabled && !rowOnCanvas}
										ondragstart={(e) => handleDragStart(e, t)}
										onclick={(e) => handleRowClick(e, t)}
										onkeydown={(e) => {
											if (rowDisabled) return;
											if (e.key !== 'Enter' && e.key !== ' ') return;
											e.preventDefault();
											handleRowClick(e as unknown as MouseEvent, t);
										}}
										class="group/tblrow flex w-full items-center justify-between gap-2 rounded px-2 py-1 text-left text-xs transition-[filter] hover:brightness-125"
										class:cursor-grab={!rowOnCanvas && !rowDisabled}
										class:active:cursor-grabbing={!rowOnCanvas && !rowDisabled}
										class:cursor-pointer={rowOnCanvas && !rowDisabled}
										class:opacity-50={rowDisabled}
										class:cursor-not-allowed={rowDisabled}
										class:bg-primary={rowSelected}
										class:text-primary-foreground={rowSelected}
										class:hover:bg-accent={!rowSelected}
										class:hover:text-accent-foreground={!rowSelected}
										data-testid="canvas-sidebar-table"
										data-identity={ident(t)}
										data-selected={rowSelected}
										data-oncanvas={rowOnCanvas}
										title={t.schema ? `${t.schema}.${t.name}` : t.name}
									>
										<!-- Always render the schema prefix on the title row so
										     these read clearly as TABLES (vs. column-match rows
										     in the Fields section, which omit the prefix). -->
										<span class="truncate font-mono">
											{#if t.schema}<span class="opacity-60">{t.schema}.</span>{/if}{t.name}
										</span>
										<!-- Right side: hover-swaps `N cols` → `+ Add to canvas`
										     when the host wired `onAddToCanvas` AND the row isn't
										     already on canvas.  On-canvas rows keep their label
										     since the whole-row click already jumps. -->
										{#if rowOnCanvas || !onAddToCanvas || rowDisabled}
											<span class="shrink-0 text-[10px] text-muted-foreground">
												{#if rowOnCanvas}on canvas{:else}{t.columns.length} cols{/if}
											</span>
										{:else}
											<span
												class="shrink-0 text-[10px] text-muted-foreground group-hover/tblrow:hidden"
												>{t.columns.length} cols</span
											>
											<button
												type="button"
												onclick={(e) => {
													e.stopPropagation();
													onAddToCanvas?.(t);
												}}
												class="hidden shrink-0 items-center gap-1 rounded border border-border bg-background px-1.5 py-0.5 text-[10px] font-semibold text-foreground transition-colors group-hover/tblrow:inline-flex hover:bg-accent hover:text-accent-foreground"
												aria-label={`Add ${t.schema ? `${t.schema}.${t.name}` : t.name} to canvas`}
												title="Add to canvas"
												data-testid="canvas-sidebar-add-to-canvas"
											>
												<Plus class="h-3 w-3" aria-hidden="true" />
												Add to canvas
											</button>
										{/if}
									</div>
								</li>
							{/each}
						</ul>
					</div>
				{/each}
			{/if}

			<!-- Fields section — one row per (column, table) pair. -->
			{#if isSearching && fieldMatches.length > 0}
				<div
					class="mt-2 mb-1 border-t border-b border-border py-1 text-[10px] font-medium tracking-wider text-muted-foreground uppercase"
				>
					Fields matching "{query}" ({fieldMatches.length})
				</div>
				<ul class="space-y-1">
					{#each fieldMatches as fm (`${fm.table.schema ?? ''}.${fm.table.name}::${fm.column}`)}
						{@const t = fm.table}
						{@const onCanvas = isOnCanvas(t)}
						{@const disabled = isDisabled(t)}
						<li>
							<button
								type="button"
								draggable={!disabled && !onCanvas}
								ondragstart={(e) => handleDragStart(e, t)}
								onclick={(e) => handleRowClick(e, t)}
								class="grid w-full grid-cols-[1fr_auto] gap-x-2 gap-y-0.5 rounded border border-border px-2 py-1.5 text-left text-xs transition-[filter] hover:brightness-125"
								class:cursor-grab={!onCanvas && !disabled}
								class:active:cursor-grabbing={!onCanvas && !disabled}
								class:cursor-pointer={onCanvas && !disabled}
								class:opacity-50={disabled}
								class:cursor-not-allowed={disabled}
								class:bg-primary={isSelected(t)}
								class:text-primary-foreground={isSelected(t)}
								class:hover:bg-accent={!isSelected(t)}
								class:hover:text-accent-foreground={!isSelected(t)}
								{disabled}
								data-testid="canvas-sidebar-field"
								data-identity={ident(t)}
								data-selected={isSelected(t)}
								data-oncanvas={onCanvas}
							>
								<span class="truncate font-mono">{fm.column}</span>
								{#if onCanvas && onJumpToCanvasTable}
									<span
										role="link"
										tabindex="0"
										onclick={(e) => {
											e.stopPropagation();
											onJumpToCanvasTable?.(ident(t), fm.column);
										}}
										onkeydown={(e) => {
											if (e.key !== 'Enter' && e.key !== ' ') return;
											e.preventDefault();
											e.stopPropagation();
											onJumpToCanvasTable?.(ident(t), fm.column);
										}}
										class="inline-flex shrink-0 cursor-pointer items-center gap-0.5 text-[10px] text-primary underline underline-offset-2 hover:text-primary/80"
										data-testid="canvas-sidebar-jump-field"
										title="Centre this table on the canvas and highlight {fm.column}"
									>
										on canvas
										<ChevronRight class="h-3 w-3" aria-hidden="true" />
									</span>
								{:else if onCanvas}
									<span class="shrink-0 text-[10px] text-muted-foreground">on canvas</span>
								{:else}
									<span class="shrink-0 text-[10px] text-muted-foreground">
										{t.columns.length} cols
									</span>
								{/if}
								<span
									class="col-span-2 truncate text-[10px] text-muted-foreground"
									title={t.schema ? `${t.schema}.${t.name}` : t.name}
								>
									in <span class="font-mono">{t.schema ? `${t.schema}.` : ''}{t.name}</span>
								</span>
							</button>
						</li>
					{/each}
				</ul>
			{/if}
		</div>
	{/if}

	<!-- Splitter handle — drag the right edge of the sidebar to resize. -->
	<button
		type="button"
		aria-label="Resize sidebar"
		title="Drag to resize"
		class="absolute top-0 right-[-4px] bottom-0 z-20 flex w-2 cursor-ew-resize items-center justify-center text-muted-foreground/40 transition-colors hover:bg-primary/20 hover:text-foreground"
		onpointerdown={handleSplitterPointerDown}
		data-testid="canvas-sidebar-splitter"
	>
		<GripVertical class="h-3 w-3" aria-hidden="true" />
	</button>

	<!-- Source info block — anchored to the BOTTOM of the aside.  Was at
	     the top; moved so the Tables list dominates the vertical space
	     and the source is a footer reminder, not a heading. -->
	{#if sourceContext}
		<div class="-mx-3 shrink-0 border-t border-border/60"></div>
		<div class="shrink-0 pt-1" data-testid="canvas-sidebar-source-context">
			{@render sourceContext()}
		</div>
	{/if}
</aside>
