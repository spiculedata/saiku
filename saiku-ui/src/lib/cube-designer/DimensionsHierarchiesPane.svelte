<!--
  DimensionsHierarchiesPane — the Dimensions / Attributes / Hierarchies column
  cluster extracted from WorkbenchView.svelte (audit finding #1039). Owns the
  three reorderable columns of the schema designer's Columns (workbench) mode:
  the Dimensions pane (source-table / join-group picker + saved-dimension
  list), the Attributes pane (per-dimension attribute checklist), and the
  Hierarchies pane (hierarchy list + level drop zones).

  Like FactsMeasuresPane, it owns NO durable state. The shell (WorkbenchView)
  keeps the single source of truth for every selection / drag / edit variable
  and threads it in via props + `bind:`. This component only READS props,
  mutates the shared reactive `store` it is handed in place, and calls shell
  callbacks for every structural mutation — so behaviour is byte-for-byte
  identical to the inline snippets it replaced. Every value the template
  reassigns is a `$bindable()` prop bound from the shell (never a duplicated
  source of truth). The local type aliases (PaneKind / FocusKind / DimSortKey /
  DropTarget / JoinGroupRow) mirror the shell's — simple shapes kept in
  lock-step rather than widening a shared module surface.
-->
<script lang="ts">
	import {
		Database,
		ChevronDown,
		ChevronUp,
		GripVertical,
		Plus,
		X,
		Layers,
		ListTree,
		Search,
		AlertCircle,
		KeyRound
	} from '@lucide/svelte';
	import Popover from './primitives/Popover.svelte';
	import SortableColumnHeader from './primitives/SortableColumnHeader.svelte';
	import { dimKeyIdentity, resolveKeyAttribute } from './state.svelte.js';
	import type { SchemaCanvasStore } from './state.svelte.js';
	import {
		ATTRIBUTE_MIME,
		readAttributeDrag,
		readLevelMoveDrag,
		isContentDrag
	} from './workbench-dnd';
	import type { AttributeDragPayload } from './workbench-dnd';
	import type { SchemaCanvasTable, SchemaCanvasDimension, SchemaCanvasHierarchy } from './types.js';

	// Mirrors the shell's local unions / shapes (kept in lock-step).
	type PaneKind = 'dimensions' | 'attributes' | 'hierarchies';
	type FocusKind = 'dim' | 'hierarchy' | 'attribute' | 'measure' | 'mg' | 'cube' | 'calc';
	type DimSortKey = 'idx' | 'name' | 'type' | 'cols';
	type DropTarget =
		| { kind: 'dim-zone' }
		| { kind: 'dim'; id: string }
		| { kind: 'hierarchy'; dimensionId: string; hierarchyId: string }
		| { kind: 'measure-zone' };
	interface JoinGroupRow {
		key: string;
		label: string;
		tableNames: string[];
		tableIds: string[];
	}

	interface Props {
		store: SchemaCanvasStore;
		columnSlots: PaneKind[];
		paneDragIndex: number | null;
		paneDragOverIndex: number | null;
		dimSortKey: DimSortKey;
		dimSortDir: 'asc' | 'desc';
		attributeCount: number;
		hierarchyCount: number;
		levelCount: number;
		selectedDimension: SchemaCanvasDimension | null;
		selectedHierarchy: SchemaCanvasHierarchy | null;
		joinGroupsRail: JoinGroupRow[];
		PANE_KIND_ICONS: Record<PaneKind, typeof Layers>;
		PANE_KIND_LABELS: Record<PaneKind, string>;
		// ── bindable: shell owns the source of truth, child writes via bind: ──
		attributesPaneMode: 'edit' | 'saved';
		contentDragOverHierarchyId: string | null;
		deletingDimId: string | null;
		dimDragOverHierName: boolean;
		dimensionPaneFilter: string;
		dimensionPaneMode: 'edit' | 'saved';
		dimensionPreviewKey: string | null;
		editingDimNameId: string | null;
		editingHierNameId: string | null;
		focusDimId: string | null;
		hierarchyExplicitlyClicked: boolean;
		manualAddDimName: string;
		manualAddDimOpen: boolean;
		manualAddDimSourceKey: string;
		selectedAttributeKey: string | null;
		selectedLevelId: string | null;
		selectedDimensionId: string | null;
		selectedHierarchyId: string | null;
		selectedMeasureId: string | null;
		// ── shell callbacks (every structural mutation routes back here) ──
		focus: (kind: FocusKind, id: string) => void;
		isFocused: (kind: FocusKind, id: string | null | undefined) => boolean;
		isContext: (kind: FocusKind, id: string | null | undefined) => boolean;
		autoFocus: (node: HTMLInputElement, shouldFocus: boolean) => { update(active: boolean): void };
		columnTypeFor: (tableId: string, columnName: string) => string | undefined;
		dropRingFor: (target: DropTarget) => string;
		addHierarchyFromAttribute: (dimId: string, tableId: string, columnName: string) => void;
		commitDimensionName: (dimId: string) => void;
		commitHierarchyName: (dimId: string, hierId: string) => void;
		createDimFromJoinGroup: (group: JoinGroupRow) => SchemaCanvasDimension;
		createDimFromTable: (table: SchemaCanvasTable) => SchemaCanvasDimension;
		handleAttributeDragStart: (e: DragEvent, payload: AttributeDragPayload) => void;
		handleDimensionDrop: (e: DragEvent, dimension: SchemaCanvasDimension) => void;
		handleDragLeave: (e: DragEvent, target: DropTarget) => void;
		handleDragOver: (e: DragEvent, target: DropTarget) => void;
		handlePaneDragEnd: () => void;
		handlePaneDragLeave: (idx: number) => void;
		handlePaneDragOver: (e: DragEvent, idx: number) => void;
		handlePaneDragStart: (e: DragEvent, idx: number) => void;
		handlePaneDrop: (e: DragEvent, targetIdx: number) => void;
		renameDimension: (id: string, name: string) => void;
		renameHierarchy: (dimId: string, hierId: string, name: string) => void;
		requestDeleteHierarchy: (dimId: string, hierId: string) => void;
		resetManualAddDim: () => void;
		toggleDimSort: (key: DimSortKey) => void;
	}

	let {
		store,
		columnSlots,
		paneDragIndex,
		paneDragOverIndex,
		dimSortKey,
		dimSortDir,
		attributeCount,
		hierarchyCount,
		levelCount,
		selectedDimension,
		selectedHierarchy,
		joinGroupsRail,
		PANE_KIND_ICONS,
		PANE_KIND_LABELS,
		attributesPaneMode = $bindable(),
		contentDragOverHierarchyId = $bindable(),
		deletingDimId = $bindable(),
		dimDragOverHierName = $bindable(),
		dimensionPaneFilter = $bindable(),
		dimensionPaneMode = $bindable(),
		dimensionPreviewKey = $bindable(),
		editingDimNameId = $bindable(),
		editingHierNameId = $bindable(),
		focusDimId = $bindable(),
		 
		hierarchyExplicitlyClicked = $bindable(),
		manualAddDimName = $bindable(),
		manualAddDimOpen = $bindable(),
		manualAddDimSourceKey = $bindable(),
		 
		selectedAttributeKey = $bindable(),
		selectedLevelId = $bindable(),
		selectedDimensionId = $bindable(),
		 
		selectedHierarchyId = $bindable(),
		 
		selectedMeasureId = $bindable(),
		focus,
		isFocused,
		isContext,
		autoFocus,
		columnTypeFor,
		dropRingFor,
		addHierarchyFromAttribute,
		commitDimensionName,
		commitHierarchyName,
		createDimFromJoinGroup,
		createDimFromTable,
		handleAttributeDragStart,
		handleDimensionDrop,
		handleDragLeave,
		handleDragOver,
		handlePaneDragEnd,
		handlePaneDragLeave,
		handlePaneDragOver,
		handlePaneDragStart,
		handlePaneDrop,
		renameDimension,
		renameHierarchy,
		requestDeleteHierarchy,
		resetManualAddDim,
		toggleDimSort
	}: Props = $props();

	// Attribute-edit DRAFT — All / Clear / checkbox mutations write here
	// instead of directly to the store, so switching dims mid-edit doesn't
	// silently commit whatever the user was toying with.  Confirm pill in
	// the pane header commits (or a click on saved-mode Edit re-seeds the
	// draft).  Discarded on dim change.  Reported 2026-07-31 — attribute
	// setup should feel intentional; casual clicks shouldn't persist.
	type AttrDraftEntry = { tableId: string; columnName: string };
	let attrsDraft = $state<AttrDraftEntry[] | null>(null);

	// Seed the draft whenever the user enters edit mode or picks a
	// different dim while already in edit mode.  Dropping the draft back
	// to null on exit / switch keeps the mental model clean — the pane
	// only holds pending edits while it's actively in edit mode.
	$effect(() => {
		if (attributesPaneMode !== 'edit') {
			attrsDraft = null;
			return;
		}
		if (!selectedDimension) {
			attrsDraft = null;
			return;
		}
		const dimId = selectedDimension.id;
		attrsDraft = (selectedDimension.attributes ?? []).map((a) => ({
			tableId: a.tableId,
			columnName: a.columnName
		}));
		// Explicit read of dimId so this re-runs on dim switch.
		void dimId;
	});

	// True when the draft differs from the committed attributes on the
	// selected dim — drives the Confirm pill's "unsaved" visual + tells
	// the commit path whether it has work to do.
	const attrsDraftDirty = $derived.by(() => {
		if (attrsDraft === null || !selectedDimension) return false;
		const committed = selectedDimension.attributes ?? [];
		if (attrsDraft.length !== committed.length) return true;
		const key = (a: AttrDraftEntry) => `${a.tableId}::${a.columnName}`;
		const draftKeys = new Set(attrsDraft.map(key));
		return committed.some((a) => !draftKeys.has(key(a)));
	});

	function commitAttrsDraft(dimId: string) {
		if (attrsDraft === null) return;
		store.setAttributes(dimId, attrsDraft);
	}
</script>

<!-- Root markup = the former `columnsPanes` snippet body. -->
<div class="flex h-full min-h-0 flex-1 gap-3 overflow-hidden" data-testid="workbench-columns-view">
	{#each columnSlots as kind, idx (idx)}
		{@render columnsPane(idx, kind)}
	{/each}
</div>

{#snippet columnsPane(idx: number, kind: PaneKind)}
	{@const Icon = PANE_KIND_ICONS[kind]}
	{@const isReorderTarget = paneDragOverIndex === idx && paneDragIndex !== idx}
	{@const isReorderSource = paneDragIndex === idx}
	<!-- Cross-pane "disabled" rule: the Hierarchies pane (kind +
	     header AND body) dims ONLY when there's an actual gate to
	     enforce — a dim is selected AND its attributes are still
	     in edit mode.  Previously the outer disabled fired whenever
	     `attributesPaneMode === 'edit'` regardless of whether a dim
	     had been picked, so the pane showed greyed-out even in its
	     empty state ("Compose a hierarchy") when there was nothing
	     to gate against.  Other panes are never disabled by
	     another pane's state. -->
	{@const isDisabled =
		kind === 'hierarchies' && !!selectedDimension && attributesPaneMode === 'edit'}
	<section
		class="flex h-full min-w-0 flex-1 flex-col rounded-md border bg-elev-1 transition-colors {isReorderTarget
			? 'border-primary'
			: ''} {isReorderSource ? 'opacity-50' : ''} {isDisabled ? 'workbench-pane-disabled' : ''}"
		style:border-color={isReorderTarget ? '' : 'hsl(var(--border))'}
		ondragover={(e) => handlePaneDragOver(e, idx)}
		ondragleave={() => handlePaneDragLeave(idx)}
		ondrop={(e) => handlePaneDrop(e, idx)}
		data-testid="workbench-columns-pane"
		data-pane-index={idx}
		data-pane-kind={kind}
	>
		<!-- Pane header: grip + icon + LABEL + per-kind context + action
		     pill (Edit / + Add).  This is the ONLY title strip per
		     pane — no inner sub-headers; the body just renders the
		     content boxes.  Background is bg-muted so the title strip
		     reads as a distinct strata above the white content. -->
		<header
			class="flex shrink-0 items-center gap-2 border-b bg-elev-1 px-2.5 py-2 transition-opacity"
			style:border-color="hsl(var(--border))"
		>
			<span
				role="button"
				tabindex="0"
				draggable="true"
				ondragstart={(e) => handlePaneDragStart(e, idx)}
				ondragend={handlePaneDragEnd}
				class="-ml-1 shrink-0 cursor-grab rounded p-1 text-muted-foreground hover:bg-accent hover:text-accent-foreground active:cursor-grabbing"
				aria-label="Drag to reorder this column"
				title="Drag to reorder"
				data-testid="workbench-pane-grip"
			>
				<GripVertical class="h-3 w-3" aria-hidden="true" />
			</span>
			<Icon class="h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
			<span
				class="shrink-0 text-[11px] font-semibold tracking-wider uppercase"
				data-testid="workbench-pane-label"
			>
				{PANE_KIND_LABELS[kind]}
			</span>
			{@render paneHeaderContext(kind)}
			<div class="ml-auto flex shrink-0 items-center gap-1">
				{@render paneHeaderActions(kind)}
			</div>
		</header>

		<!-- `scrollbar-gutter: stable both-edges` reserves matching space on
		     LEFT and RIGHT so adding/removing a scrollbar doesn't shift
		     content AND the left/right padding visually balances even when
		     there's no scrollbar yet (the prior `stable` alone reserved
		     only on the right, giving the pane an uneven look). -->
		<div class="min-h-0 flex-1 [scrollbar-gutter:stable_both-edges] overflow-y-auto bg-card p-2">
			{#if kind === 'dimensions'}
				{@render paneDimensions()}
			{:else if kind === 'attributes'}
				{@render paneAttributes()}
			{:else if kind === 'hierarchies'}
				{@render paneHierarchies()}
			{/if}
		</div>
	</section>
{/snippet}

{#snippet paneHeaderContext(kind: PaneKind)}
	<!--
		Pane header eyebrow counts.  Written in plain English (`7 attributes`
		not `|A|=7`) so the number reads without math notation.  Fixes #970.
		Each entry gets a `title` tooltip echoing the same phrase for the
		truncation tail.
	-->
	{#if kind === 'dimensions'}
		{@const n = store.dimensions.length}
		<span
			class="truncate font-mono text-[10px]"
			style:color="hsl(var(--muted-foreground))"
			title="{n} dimension{n === 1 ? '' : 's'}"
		>
			· {n} dimension{n === 1 ? '' : 's'}
		</span>
	{:else if kind === 'attributes'}
		<!-- Pane header shows the CUBE-WIDE count, not the selected dim's
		     count — the pane body already renders the selected dim's
		     attrs beneath, so repeating "· Store ·" up here was noise. -->
		<span
			class="truncate font-mono text-[10px]"
			style:color="hsl(var(--muted-foreground))"
			title="{attributeCount} attribute{attributeCount === 1 ? '' : 's'}"
		>
			· {attributeCount} attribute{attributeCount === 1 ? '' : 's'}
		</span>
	{:else if kind === 'hierarchies'}
		<span
			class="truncate font-mono text-[10px]"
			style:color="hsl(var(--muted-foreground))"
			title="{hierarchyCount} hierarch{hierarchyCount === 1
				? 'y'
				: 'ies'} · {levelCount} level{levelCount === 1 ? '' : 's'}"
		>
			· {hierarchyCount} hierarch{hierarchyCount === 1 ? 'y' : 'ies'} ·
			{levelCount} level{levelCount === 1 ? '' : 's'}
		</span>
	{/if}
{/snippet}

{#snippet paneHeaderActions(kind: PaneKind)}
	{#if kind === 'dimensions'}
		<!-- Edit pill: flips the pane between the source picker (which is
		     the only place to CREATE a new dimension by binding it to a
		     source table or join group) and the saved list.  The 054d
		     restructure removed the per-row pencil but accidentally
		     yanked this pill too, leaving no way back to edit once dims
		     existed — regression fix. -->
		<button
			type="button"
			onclick={() => (dimensionPaneMode = dimensionPaneMode === 'edit' ? 'saved' : 'edit')}
			class="inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[10px] font-semibold tracking-wider uppercase transition-colors {dimensionPaneMode ===
			'edit'
				? 'border-primary bg-primary text-primary-foreground'
				: 'border-border text-muted-foreground hover:bg-accent hover:text-accent-foreground'}"
			data-testid="workbench-pane-dimensions-toggle"
			title={dimensionPaneMode === 'edit'
				? 'Done picking — switch to saved dim list'
				: 'Add / edit dim source bindings'}
		>
			{dimensionPaneMode === 'edit' ? 'Confirm' : 'Edit'}
		</button>
	{:else if kind === 'attributes'}
		{#if selectedDimension}
			<button
				type="button"
				onclick={() => {
					if (attributesPaneMode === 'edit' && selectedDimension) {
						commitAttrsDraft(selectedDimension.id);
					}
					attributesPaneMode = attributesPaneMode === 'edit' ? 'saved' : 'edit';
				}}
				class="inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[10px] font-semibold tracking-wider uppercase transition-colors {attributesPaneMode ===
				'edit'
					? 'border-primary bg-primary text-primary-foreground'
					: 'border-border text-muted-foreground hover:bg-accent hover:text-accent-foreground'}"
				title={attributesPaneMode === 'edit'
					? attrsDraftDirty
						? 'Save changes to this dimension'
						: 'Close edit mode (no changes to save)'
					: 'Edit the attributes on this dimension'}
				data-testid="workbench-pane-attributes-toggle"
			>
				{attributesPaneMode === 'edit' ? 'Confirm' : 'Edit'}
			</button>
		{/if}
	{:else if kind === 'hierarchies'}
		<!-- Hierarchies are always editable when not disabled by the
		     Attributes-pane lock, so no Edit pill — just the Add button
		     in the corner. -->
		{#if selectedDimension}
			<button
				type="button"
				onclick={() => {
					const h = store.addHierarchy(selectedDimension.id);
					if (h) {
						selectedHierarchyId = h.id;
						hierarchyExplicitlyClicked = true;
						editingHierNameId = h.id;
					}
				}}
				class="inline-flex items-center gap-1 rounded-full border border-border px-2 py-0.5 text-[10px] font-semibold tracking-wider text-muted-foreground uppercase hover:bg-accent hover:text-accent-foreground"
				data-testid="workbench-pane-add-hierarchy"
				title="Add a hierarchy"
			>
				<Plus class="h-2.5 w-2.5" aria-hidden="true" />
				Add hierarchy
			</button>
		{/if}
	{/if}
{/snippet}

{#snippet paneDimensions()}
	<!-- Phase 1 Column 1: the dim pane IS the source picker.  Two
	     modes —
	       edit:  checklist of every on-canvas table + join group;
	              check → instantly create a dim bound to that source.
	       saved: numbered list of dims with rename-inline + original
	              source subtitle.
	     The pill button in the sub-header flips between them ("EDIT ON"
	     in edit mode, "EDIT" off in saved mode).  Defaults to edit
	     when there are no dims yet so the empty state is a useful
	     pick-something surface, not a blank wall. -->
	{#if dimensionPaneMode === 'edit'}
		{@render paneDimensionsEdit()}
	{:else}
		{@render paneDimensionsSaved()}
	{/if}
{/snippet}

<!-- eslint-disable-next-line @typescript-eslint/no-unused-vars -->
{#snippet paneDimensionsSubHeader(showSave: boolean)}
	<div class="flex shrink-0 items-center justify-between gap-2 pb-2">
		<span
			class="text-[10px] font-semibold tracking-wider uppercase"
			style:color="hsl(var(--muted-foreground))"
		>
			{showSave ? 'Pick tables & joins for dimensions' : 'Saved dimensions'}
		</span>
		<button
			type="button"
			onclick={() => (dimensionPaneMode = showSave ? 'saved' : 'edit')}
			class="inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[10px] font-semibold tracking-wider uppercase transition-colors {showSave
				? 'border-primary bg-primary text-primary-foreground'
				: 'border-border text-muted-foreground hover:bg-accent hover:text-accent-foreground'}"
			data-testid="workbench-pane-dim-toggle"
			title={showSave ? 'Done picking — switch to saved list' : 'Pick more / unpick sources'}
		>
			{#if showSave}
				Edit · on
			{:else}
				Edit
			{/if}
		</button>
	</div>
{/snippet}

{#snippet paneDimensionsEdit()}
	{@const tableLabel = (t: SchemaCanvasTable) => (t.schema ? `${t.schema}.${t.name}` : t.name)}
	{@const tableEntries = store.doc.tables.map((t) => ({
		kind: 'table' as const,
		id: t.id,
		key: `table:${t.id}`,
		label: tableLabel(t),
		suffix: 'tbl',
		ref: t
	}))}
	{@const joinEntries = joinGroupsRail.map((g) => ({
		kind: 'join' as const,
		id: g.key,
		key: `join:${g.key}`,
		label: g.label,
		suffix: 'Join',
		ref: g
	}))}
	{@const allEntries = [...tableEntries, ...joinEntries].sort((a, b) =>
		a.label.localeCompare(b.label)
	)}
	{@const filterNeedle = dimensionPaneFilter.trim().toLowerCase()}
	{@const filtered =
		filterNeedle === ''
			? allEntries
			: allEntries.filter((e) => e.label.toLowerCase().includes(filterNeedle))}
	{@const dimsForTable = (tableId: string) =>
		store.dimensions.filter(
			(d) => (d.sourceTableId ?? d.primaryKeyTableId) === tableId && !d.foreignKey
		)}
	{@const dimsForJoinGroup = (group: (typeof joinEntries)[number]['ref']) =>
		store.dimensions.filter((d) => d.sourceJoinGroupKey === group.key)}
	<div class="flex h-full flex-col gap-2" data-testid="workbench-pane-dimensions-edit">
		<!-- One table can back many dimensions (role-playing dims:
		     order_date / ship_date / invoice_date all use `date`).  Each
		     row's +Add dim button creates ANOTHER dim from that source;
		     names auto-dedupe (Store → Store (1) → Store (2)).  Delete
		     happens in the Saved pane (✕ on each row). -->
		<p class="shrink-0 px-1 text-[11px] leading-snug" style:color="hsl(var(--muted-foreground))">
			Click <span class="font-medium">+ Add dim</span> on a source to bind a new dimension. Multiple dims
			per source are fine — names dedupe automatically.
		</p>
		<!-- Search across the unified source list -->
		<div class="relative shrink-0">
			<Search
				class="pointer-events-none absolute top-1/2 left-2 h-3 w-3 -translate-y-1/2 opacity-50"
				aria-hidden="true"
			/>
			<input
				type="search"
				bind:value={dimensionPaneFilter}
				placeholder="Search tables or joins…"
				aria-label="Filter sources"
				class="h-7 w-full rounded border bg-background py-1 pr-7 pl-7 text-[11px] focus:border-ring focus:ring-1 focus:ring-ring focus:outline-none"
				style:border-color="hsl(var(--border))"
				data-testid="workbench-pane-dim-search"
			/>
			{#if dimensionPaneFilter}
				<button
					type="button"
					onclick={() => (dimensionPaneFilter = '')}
					class="absolute top-1/2 right-1 inline-flex h-5 w-5 -translate-y-1/2 items-center justify-center rounded text-muted-foreground hover:bg-accent hover:text-accent-foreground"
					aria-label="Clear filter"
					title="Clear filter"
				>
					<X class="h-3 w-3" aria-hidden="true" />
				</button>
			{/if}
		</div>

		{#if allEntries.length === 0}
			<div class="flex flex-1 flex-col items-center justify-center gap-1 p-3 text-center">
				<Database class="h-5 w-5" aria-hidden="true" style="color: hsl(var(--muted-foreground))" />
				<p class="text-xs font-medium">Nothing on the canvas yet</p>
				<p class="text-[11px]" style="color: hsl(var(--muted-foreground))">
					Flip back to Schema Canvas to add tables.
				</p>
			</div>
		{:else}
			<ol class="flex flex-col gap-0.5">
				{#each filtered as entry (entry.key)}
					{@const existingDims =
						entry.kind === 'table'
							? dimsForTable(entry.id)
							: dimsForJoinGroup(entry.ref as JoinGroupRow)}
					{@const existingCount = existingDims.length}
					<li class="flex flex-col">
						<!--
							Row is display-only in edit mode.  The +Add dim button
							is the sole action; the row body doesn't toggle any
							Attribute-pane preview (edit mode is for building the
							dim list, attributes come later in the saved view).
						-->
						<div
							class="flex items-center gap-2 rounded px-1.5 py-1.5 text-[11px]"
							data-testid="workbench-pane-source-row"
							data-source-kind={entry.kind}
						>
							{#if entry.kind === 'table'}
								<Database class="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
							{:else}
								<Layers class="h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
							{/if}
							<span class="min-w-0 flex-1 truncate font-mono">{entry.label}</span>
							<span class="shrink-0 font-mono text-[9px] tracking-wide uppercase opacity-50">
								{entry.suffix}
							</span>
							{#if existingCount > 0}
								<span
									class="shrink-0 rounded-full border px-1.5 py-0.5 font-mono text-[10px]"
									style:background-color="hsl(var(--elev-1))"
									style:border-color="hsl(var(--border))"
									style:color="hsl(var(--foreground))"
									title="{existingCount} dim{existingCount === 1
										? ''
										: 's'} already drawn from this source"
								>
									{existingCount} dim{existingCount === 1 ? '' : 's'}
								</span>
							{/if}
							<button
								type="button"
								onclick={() => {
									// Edit mode: add the dim and leave the Attributes
									// pane alone.  The user came here to build a dim
									// list, not to bounce the attribute selection
									// around every time they click Add.
									if (entry.kind === 'table') {
										createDimFromTable(entry.ref as SchemaCanvasTable);
									} else {
										createDimFromJoinGroup(entry.ref as JoinGroupRow);
									}
								}}
								class="inline-flex shrink-0 items-center gap-1 rounded border border-border bg-background px-2.5 py-1 text-[11px] text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
								data-testid="workbench-pane-source-add-dim"
								data-source-key={entry.key}
								title="Bind a new dimension to {entry.label}"
							>
								<Plus class="h-3 w-3" aria-hidden="true" />
								Add dim
							</button>
						</div>
						<!-- Preview lands in the Attributes pane (disabled style) rather
						     than inline under the row — see paneAttributes when no dim is
						     selected.  Keeps the picker compact + lets the user see the
						     columns in the same place where committed attributes show up. -->
					</li>
				{/each}
				{#if filtered.length === 0}
					<li
						class="rounded border border-dashed p-3 text-center text-[11px]"
						style:border-color="hsl(var(--border))"
						style:color="hsl(var(--muted-foreground))"
					>
						No source matches "{dimensionPaneFilter}".
					</li>
				{/if}
			</ol>
		{/if}
	</div>
{/snippet}

{#snippet paneDimensionsSaved()}
	{@const savedTableEntries = store.doc.tables.map((t) => ({
		kind: 'table' as const,
		id: t.id,
		key: `table:${t.id}`,
		label: t.schema ? `${t.schema}.${t.name}` : t.name,
		suffix: 'tbl',
		ref: t
	}))}
	{@const savedJoinEntries = joinGroupsRail.map((g) => ({
		kind: 'join' as const,
		id: g.key,
		key: `join:${g.key}`,
		label: g.label,
		suffix: 'Join',
		ref: g
	}))}
	{@const savedAllEntries = [...savedTableEntries, ...savedJoinEntries].sort((a, b) =>
		a.label.localeCompare(b.label)
	)}
	{@const sortedDims = (() => {
		const withMeta = store.dimensions.map((dim, idx) => {
			const dimSrcId = dim.sourceTableId ?? dim.primaryKeyTableId;
			const sourceTable = dimSrcId ? store.doc.tables.find((t) => t.id === dimSrcId) : null;
			const isJoinSourced = !!dim.sourceJoinGroupKey;
			const isHanger = !!dim.hanger;
			// Split the "(unbound)" case: was a source ever picked
			// (dimSrcId set) that now doesn't resolve — likely a
			// stale ID from persisted state — or is the dim truly
			// bare (no source ever bound).
			const staleSource = !!dimSrcId && !sourceTable && !isJoinSourced;
			const sourceLabel = isHanger
				? '(hanger)'
				: isJoinSourced
					? (dim.sourceJoinGroupKey ?? '')
					: sourceTable
						? sourceTable.schema
							? `${sourceTable.schema}.${sourceTable.name}`
							: sourceTable.name
						: staleSource
							? '(source removed from canvas)'
							: '(unbound)';
			const sourceKind: 'join' | 'table' | 'unbound' | 'hanger' = isHanger
				? 'hanger'
				: isJoinSourced
					? 'join'
					: sourceTable
						? 'table'
						: 'unbound';
			// Show the count of attributes PICKED for this dim, not the
			// total columns on the source table.  The saved list is
			// per-dim, so "24" was misleading — it read as "this dim
			// has 24 attributes" when it really meant "the source
			// table has 24 columns".  Attributes are the semantic
			// unit anyway.
			const sourceColCount = dim.attributes?.length ?? 0;
			// PK-missing flag — fires as soon as the dim has any
			// attributes but its stored key doesn't resolve to one.
			// Applies uniformly to dims created any way (drag-to-add,
			// per-source +Add dim button, bottom-of-pane +Add Dimension
			// form) — Mondrian requires a key on every non-hanger dim
			// regardless of whether hierarchies exist yet, so the alert
			// no longer waits on a hierarchy to appear.
			const pkResolvedKey = resolveKeyAttribute(dim);
			const pkMissing = !isHanger && !pkResolvedKey && (dim.attributes ?? []).length > 0;
			const pkMissingReason = pkMissing
				? dimKeyIdentity(dim)
					? `${dim.name} carries a key value ("${dimKeyIdentity(dim)}") that matches more than one attribute — pick which one in the Inspector.`
					: `${dim.name} has no primary key — Mondrian needs one on every dim. Set it in the Inspector.`
				: null;
			return {
				dim,
				idx,
				sourceLabel,
				sourceKind,
				sourceColCount,
				isHanger,
				pkMissing,
				pkMissingReason,
				staleSource
			};
		});
		// Hangers always render; they're rendered disabled/greyed
		// downstream so the user can see them but can't act on them
		// (we don't fully support them yet).  They sort with everything
		// else — the "(hanger)" label + disabled style tell the story.
		if (dimSortKey === 'idx') return withMeta;
		const sign = dimSortDir === 'asc' ? 1 : -1;
		return [...withMeta].sort((a, b) => {
			if (dimSortKey === 'name') {
				return sign * (a.dim.name || '').localeCompare(b.dim.name || '');
			}
			if (dimSortKey === 'type') {
				return sign * a.sourceKind.localeCompare(b.sourceKind);
			}
			if (dimSortKey === 'cols') {
				return sign * (a.sourceColCount - b.sourceColCount);
			}
			return 0;
		});
	})()}
	<div class="flex h-full min-h-0 flex-col gap-2" data-testid="workbench-pane-dimensions-saved">
		<!-- Tabular layout: Name · Type · Cols · X.  Each header (except X)
			     is clickable to sort; same column twice flips direction, third
			     click restores creation order.  Row index badges were removed —
			     insertion order isn't semantic (sort by name if you need it). -->
		<div
			class="grid shrink-0 grid-cols-[1fr_4rem_3.5rem_1.75rem] items-center gap-1 border-b px-2 py-1 text-[9px] font-semibold tracking-wider uppercase"
			style:border-color="hsl(var(--border))"
			style:color="hsl(var(--muted-foreground))"
		>
			<SortableColumnHeader
				label="Name"
				sortKey="name"
				activeKey={dimSortKey}
				direction={dimSortDir}
				onToggle={toggleDimSort}
			/>
			<SortableColumnHeader
				label="Type"
				sortKey="type"
				activeKey={dimSortKey}
				direction={dimSortDir}
				onToggle={toggleDimSort}
			/>
			<div class="justify-self-center">
				<SortableColumnHeader
					label="Attrs"
					sortKey="cols"
					activeKey={dimSortKey}
					direction={dimSortDir}
					onToggle={toggleDimSort}
				/>
			</div>
			<span></span>
		</div>
		<div
			class="flex min-h-0 flex-1 flex-col overflow-y-auto rounded border bg-elev-2"
			style:border-color="hsl(var(--border))"
		>
			{#each sortedDims as { dim, sourceLabel, sourceKind, sourceColCount, pkMissing, pkMissingReason, staleSource }, displayIdx (dim.id)}
				{@const isActive = isFocused('dim', dim.id)}
				{@const isCtx = isContext('dim', dim.id)}
				{@const isHangerRow = !!dim.hanger}
				{@const isConfirmingDelete = deletingDimId === dim.id}
				<div
					role="button"
					tabindex={isHangerRow ? -1 : 0}
					aria-disabled={isHangerRow ? 'true' : undefined}
					title={isHangerRow
						? "Hanger dimension — the schema designer can't author these yet."
						: undefined}
					onclick={() => {
						if (isHangerRow) return;
						if (isConfirmingDelete) return;
						selectedDimensionId = dim.id;
						selectedHierarchyId = null;
						hierarchyExplicitlyClicked = false;
						selectedMeasureId = null;
						selectedAttributeKey = null;
						focus('dim', dim.id);
						attributesPaneMode = (dim.attributes?.length ?? 0) === 0 ? 'edit' : 'saved';
					}}
					onkeydown={(e) => {
						if (isHangerRow) return;
						if (isConfirmingDelete) return;
						if (e.key === 'Enter' || e.key === ' ') {
							e.preventDefault();
							selectedDimensionId = dim.id;
							selectedHierarchyId = null;
							hierarchyExplicitlyClicked = false;
							selectedMeasureId = null;
							selectedAttributeKey = null;
							focus('dim', dim.id);
							attributesPaneMode = (dim.attributes?.length ?? 0) === 0 ? 'edit' : 'saved';
						}
					}}
					class="grid {isConfirmingDelete
						? 'grid-cols-[1fr_auto]'
						: 'grid-cols-[1fr_4rem_3.5rem_1.75rem]'} items-center gap-1 border-b px-2 py-1.5 text-[11px] transition-colors {isHangerRow
						? 'cursor-not-allowed opacity-50'
						: 'cursor-pointer'} {isConfirmingDelete
						? 'workbench-row-confirming-delete'
						: isActive
							? 'workbench-row-selected'
							: isCtx
								? 'workbench-row-context'
								: `border-border ${isHangerRow ? '' : 'hover:bg-accent/40'} ${displayIdx % 2 === 0 ? 'bg-muted/8' : 'bg-elev-2'}`}"
					style:border-color={isActive || isCtx ? '' : 'hsl(var(--border))'}
					style:background-color={isConfirmingDelete ? 'hsl(var(--destructive) / 0.08)' : undefined}
					style:border-left={isConfirmingDelete ? '2px solid hsl(var(--destructive))' : undefined}
					data-testid="workbench-pane-dimension"
					data-dimension-id={dim.id}
				>
					<div class="flex min-w-0 flex-col">
						{#if editingDimNameId === dim.id}
							<input
								type="text"
								value={dim.name}
								oninput={(e) => renameDimension(dim.id, e.currentTarget.value)}
								onclick={(e) => e.stopPropagation()}
								onkeydown={(e) => {
									e.stopPropagation();
									if (e.key === 'Enter' || e.key === 'Escape') {
										(e.currentTarget as HTMLInputElement).blur();
									}
								}}
								onblur={() => {
									commitDimensionName(dim.id);
									editingDimNameId = null;
								}}
								placeholder="Dimension name"
								aria-label="Dimension name"
								class="h-5 min-w-0 rounded border bg-background px-1 font-medium focus:border-ring focus:ring-1 focus:ring-ring focus:outline-none"
								style:border-color="hsl(var(--border))"
								use:autoFocus={focusDimId === dim.id || editingDimNameId === dim.id}
							/>
						{:else}
							<span class="flex min-w-0 items-center gap-1">
								{#if pkMissing}
									<!-- PK-missing warning — yellow AlertCircle, tooltip
										     explains what the engine will reject.  Sits
										     BEFORE the name so the eye catches it. -->
									<AlertCircle
										class="h-3 w-3 shrink-0 text-warning"
										aria-label={pkMissingReason ?? 'Primary key missing'}
									/>
								{/if}
								<span
									role="button"
									tabindex="0"
									ondblclick={(e) => {
										e.stopPropagation();
										editingDimNameId = dim.id;
									}}
									onkeydown={(e) => {
										if (e.key === 'F2') {
											e.preventDefault();
											e.stopPropagation();
											editingDimNameId = dim.id;
										}
									}}
									class="block min-w-0 truncate leading-5 font-medium"
									title="Double-click to rename"
								>
									{dim.name || 'Untitled dimension'}
								</span>
							</span>
						{/if}
						<span
							class="flex min-w-0 items-center gap-1 truncate font-mono text-[9px] {staleSource
								? 'text-warning'
								: ''}"
							style:color={staleSource ? undefined : 'hsl(var(--muted-foreground))'}
							title={staleSource
								? `Source table for "${dim.name}" is no longer on the canvas. Rebind it in the Inspector.`
								: sourceLabel}
						>
							{#if staleSource}
								<AlertCircle class="h-3 w-3 shrink-0" aria-hidden="true" />
							{/if}
							<span class="min-w-0 truncate">{sourceLabel}</span>
						</span>
					</div>
					{#if isConfirmingDelete}
						<!-- Right-side inline confirm — replaces Type / Cols / ✕
							     with "Delete? [Yes] [No]" on the SAME row.  No modal,
							     no new row underneath. -->
						<div
							class="flex shrink-0 items-center gap-3 pl-2"
							onclick={(e) => e.stopPropagation()}
							onkeydown={(e) => e.stopPropagation()}
							role="group"
							aria-label="Confirm deletion"
						>
							<span
								class="text-[11px] font-semibold tracking-wide"
								style:color="hsl(var(--destructive))"
							>
								Delete?
							</span>
							<button
								type="button"
								onclick={() => {
									const removingId = dim.id;
									deletingDimId = null;
									store.removeDimension(removingId);
									if (selectedDimensionId === removingId) {
										selectedDimensionId = null;
										selectedHierarchyId = null;
									}
									if (focusDimId === removingId) focusDimId = null;
								}}
								class="inline-flex min-w-[3.5rem] items-center justify-center rounded border px-3 py-1 text-[11px] font-semibold text-white shadow-sm transition-colors hover:opacity-90"
								style:background-color="hsl(var(--destructive))"
								style:border-color="hsl(var(--destructive))"
								data-testid="workbench-pane-dim-delete-confirm"
							>
								Yes
							</button>
							<button
								type="button"
								onclick={() => (deletingDimId = null)}
								class="inline-flex min-w-[3.5rem] items-center justify-center rounded border border-border bg-background px-3 py-1 text-[11px] font-semibold hover:bg-accent hover:text-accent-foreground"
								data-testid="workbench-pane-dim-delete-cancel"
							>
								No
							</button>
						</div>
					{:else}
						<span
							class="shrink-0 font-mono text-[9px] tracking-wide uppercase"
							style:color="hsl(var(--muted-foreground))"
						>
							{sourceKind}
						</span>
						<span
							class="shrink-0 justify-self-center text-center font-mono text-[10px] tabular-nums"
						>
							{sourceColCount}
						</span>
						{#if isHangerRow}
							<span class="shrink-0 justify-self-center"></span>
						{:else}
							<button
								type="button"
								onclick={(e) => {
									e.stopPropagation();
									deletingDimId = dim.id;
								}}
								class="inline-flex h-5 w-5 shrink-0 items-center justify-center justify-self-center rounded text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
								aria-label="Delete {dim.name || 'this dimension'}"
								title="Delete {dim.name || 'this dimension'}"
								data-testid="workbench-pane-dim-delete"
							>
								<X class="h-3 w-3" aria-hidden="true" />
							</button>
						{/if}
					{/if}
				</div>
			{/each}

			<!-- Manual add path — dashed "Add Dimension" pill lives as
				     the LAST ITEM in the list itself.  When the list has
				     dims, it sits below the last row; when the list is
				     empty, it appears at the top (only content in the
				     container).  Flows with the list; not fixed to the
				     bottom of the pane. -->
			{#if savedAllEntries.length > 0}
				<div class="p-2">
					{#if !manualAddDimOpen}
						<button
							type="button"
							onclick={() => {
								manualAddDimOpen = true;
								manualAddDimName = '';
								manualAddDimSourceKey = '';
							}}
							class="flex w-full items-center justify-center gap-1.5 rounded border-2 border-dashed px-3 py-2 text-[11px] font-semibold tracking-wide text-muted-foreground uppercase transition-colors hover:border-primary/50 hover:bg-accent hover:text-accent-foreground"
							style:border-color="hsl(var(--border))"
							data-testid="workbench-pane-add-dimension-manual"
						>
							<Plus class="h-3.5 w-3.5" aria-hidden="true" />
							Add Dimension
						</button>
					{:else}
						<div
							class="flex flex-col gap-2 rounded border-2 border-dashed p-2"
							style:border-color="hsl(var(--primary) / 0.5)"
							data-testid="workbench-pane-add-dimension-form"
						>
							<label class="flex flex-col gap-1">
								<span
									class="text-[9px] font-semibold tracking-wider uppercase"
									style:color="hsl(var(--muted-foreground))">Name</span
								>
								<input
									type="text"
									bind:value={manualAddDimName}
									placeholder="e.g. Order Date"
									class="h-7 rounded border bg-background px-2 text-[11px] focus:border-ring focus:ring-1 focus:ring-ring focus:outline-none"
									style:border-color="hsl(var(--border))"
									data-testid="workbench-pane-add-dimension-name"
								/>
							</label>
							<label class="flex flex-col gap-1">
								<span
									class="text-[9px] font-semibold tracking-wider uppercase"
									style:color="hsl(var(--muted-foreground))">Source</span
								>
								<!-- Flat picker — appearance-none strips macOS's glossy
									     chrome, and a hand-drawn ChevronDown overlays via
									     the relative <span>. -->
								<span class="relative">
									<select
										bind:value={manualAddDimSourceKey}
										class="h-7 w-full appearance-none rounded border bg-background px-2 pr-7 font-mono text-[11px] focus:border-ring focus:ring-1 focus:ring-ring focus:outline-none"
										style:border-color="hsl(var(--border))"
										data-testid="workbench-pane-add-dimension-source"
									>
										<option value="" disabled>— pick a table or join —</option>
										{#each savedAllEntries as entry (entry.key)}
											<option value={entry.key}>{entry.label} · {entry.suffix}</option>
										{/each}
									</select>
									<ChevronDown
										class="pointer-events-none absolute top-1/2 right-2 size-3.5 -translate-y-1/2 text-muted-foreground"
										aria-hidden="true"
									/>
								</span>
							</label>
							<div class="flex items-center justify-end gap-2 pt-1">
								<button
									type="button"
									onclick={resetManualAddDim}
									class="inline-flex min-w-[3.5rem] items-center justify-center rounded border border-border bg-background px-3 py-1 text-[11px] font-semibold hover:bg-accent hover:text-accent-foreground"
									data-testid="workbench-pane-add-dimension-cancel"
								>
									Cancel
								</button>
								<button
									type="button"
									disabled={!manualAddDimSourceKey}
									onclick={() => {
										const key = manualAddDimSourceKey;
										if (!key) return;
										const entry = savedAllEntries.find((e) => e.key === key);
										if (!entry) return;
										const trimmedName = manualAddDimName.trim();
										if (entry.kind === 'table') {
											const t = entry.ref as SchemaCanvasTable;
											store.addDimension({
												name: trimmedName || t.name,
												tableId: t.id
											});
										} else {
											const group = entry.ref as JoinGroupRow;
											const dim = store.addDimension({
												name: trimmedName || group.key,
												tableId: group.tableIds[0]
											});
											store.updateDimension(dim.id, { sourceJoinGroupKey: group.key });
										}
										resetManualAddDim();
									}}
									class="inline-flex min-w-[3.5rem] items-center justify-center rounded border border-primary bg-primary px-3 py-1 text-[11px] font-semibold text-primary-foreground shadow-sm transition-colors hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
									data-testid="workbench-pane-add-dimension-confirm"
								>
									Add
								</button>
							</div>
						</div>
					{/if}
				</div>
			{/if}
		</div>
	</div>
{/snippet}

<!-- eslint-disable-next-line @typescript-eslint/no-unused-vars -->
{#snippet paneDimensionRow(dim: SchemaCanvasDimension)}
	{@const isSelected = isFocused('dim', dim.id)}
	<!-- Row is a div (not a button) so it can host an editable name
	     input.  Clicking the row sets selection; clicking the input
	     focuses for renaming without triggering the row's selection
	     handler (the input's pointer event terminates there).  The
	     `use:autoFocus` action picks up the newly-created dim id and
	     auto-selects its placeholder text, matching the Cards-view
	     "click Add → type immediately" UX. -->
	<div
		role="button"
		tabindex="0"
		onclick={() => {
			selectedDimensionId = dim.id;
			selectedHierarchyId = dim.hierarchies[0]?.id ?? null;
			selectedMeasureId = null;
			focus('dim', dim.id);
		}}
		onkeydown={(e) => {
			if (e.key === 'Enter' || e.key === ' ') {
				e.preventDefault();
				selectedDimensionId = dim.id;
				selectedHierarchyId = dim.hierarchies[0]?.id ?? null;
				selectedMeasureId = null;
				focus('dim', dim.id);
			}
		}}
		ondragover={(e) => handleDragOver(e, { kind: 'dim', id: dim.id })}
		ondragleave={(e) => handleDragLeave(e, { kind: 'dim', id: dim.id })}
		ondrop={(e) => handleDimensionDrop(e, dim)}
		class="flex items-center gap-1.5 rounded border px-2 py-1.5 text-left text-xs transition-colors {isSelected
			? 'workbench-row-selected'
			: 'border-transparent hover:bg-accent/40'} {dropRingFor({ kind: 'dim', id: dim.id })}"
		data-testid="workbench-pane-dimension"
		data-dimension-id={dim.id}
	>
		<Layers class="h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
		<input
			type="text"
			value={dim.name}
			oninput={(e) => renameDimension(dim.id, e.currentTarget.value)}
			onclick={(e) => e.stopPropagation()}
			onkeydown={(e) => e.stopPropagation()}
			placeholder="Dimension name"
			aria-label="Dimension name"
			class="h-5 min-w-0 flex-1 rounded border border-transparent bg-transparent px-1 font-medium hover:border-input focus:border-ring focus:bg-background focus:ring-1 focus:ring-ring focus:outline-none"
			data-testid="workbench-pane-dimension-name"
			use:autoFocus={focusDimId === dim.id}
		/>
		<span class="shrink-0 font-mono text-[10px] text-muted-foreground">
			{dim.hierarchies.reduce((n, h) => n + h.levels.length, 0)} levels
		</span>
		<button
			type="button"
			onclick={(e) => {
				e.stopPropagation();
				store.removeDimension(dim.id);
				if (selectedDimensionId === dim.id) {
					selectedDimensionId = null;
					selectedHierarchyId = null;
				}
			}}
			class="shrink-0 rounded p-0.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground"
			aria-label="Delete dimension"
			title="Delete dimension"
		>
			<X class="h-2.5 w-2.5" aria-hidden="true" />
		</button>
	</div>
{/snippet}

<!-- eslint-disable-next-line @typescript-eslint/no-unused-vars -->
{#snippet paneSubHeader(leftLabel: string, editing: boolean, setEditing: (next: boolean) => void)}
	<div class="flex shrink-0 items-center justify-between gap-2 pb-2">
		<span
			class="text-[10px] font-semibold tracking-wider uppercase"
			style:color="hsl(var(--muted-foreground))"
		>
			{leftLabel}
		</span>
		<button
			type="button"
			onclick={() => setEditing(!editing)}
			class="inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[10px] font-semibold tracking-wider uppercase transition-colors {editing
				? 'border-primary bg-primary text-primary-foreground'
				: 'border-border text-muted-foreground hover:bg-accent hover:text-accent-foreground'}"
			data-testid="workbench-pane-sub-toggle"
			title={editing ? 'Done editing — show saved list' : 'Edit the full list'}
		>
			{#if editing}
				Edit · on
			{:else}
				Edit
			{/if}
		</button>
	</div>
{/snippet}

{#snippet paneAttributes()}
	{@const dim = selectedDimension}
	{@const previewKey = dimensionPreviewKey ?? ''}
	{@const previewTable = previewKey.startsWith('table:')
		? (store.doc.tables.find((t) => t.id === previewKey.slice('table:'.length)) ?? null)
		: null}
	{#if !dim && previewTable}
		<!--
			Preview from the Dimensions picker.  The user clicked a table row
			but hasn't checked it yet — show its columns here disabled so they
			can verify what's inside before committing.  Once they check the
			row, the dim is created + selected and the regular Attributes view
			takes over.
		-->
		<div
			class="flex h-full min-h-0 flex-col gap-2 p-2"
			data-testid="workbench-pane-attributes-preview"
		>
			<header class="flex shrink-0 items-center gap-1.5">
				<Database class="h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
				<span
					class="min-w-0 flex-1 truncate text-[10px] font-semibold tracking-wider uppercase"
					style:color="hsl(var(--muted-foreground))"
				>
					Preview · {previewTable.schema
						? `${previewTable.schema}.${previewTable.name}`
						: previewTable.name}
				</span>
				<button
					type="button"
					onclick={() => (dimensionPreviewKey = null)}
					class="shrink-0 rounded px-1.5 py-0.5 text-[10px] font-semibold tracking-wider text-muted-foreground uppercase hover:bg-accent hover:text-accent-foreground"
					aria-label="Clear preview"
					title="Clear preview"
				>
					Clear
				</button>
			</header>
			<ul
				class="flex min-h-0 flex-1 flex-col gap-0.5 overflow-y-auto rounded border bg-muted/30 px-2 py-1.5 opacity-60"
				style:border-color="hsl(var(--border))"
				aria-label="Columns in {previewTable.name} (preview only)"
			>
				{#each previewTable.columns as col (col.name)}
					<li
						class="flex cursor-not-allowed items-center gap-2 rounded px-1.5 py-1 text-[11px]"
						aria-disabled="true"
					>
						<Database class="h-3 w-3 shrink-0 text-muted-foreground" aria-hidden="true" />
						<span class="min-w-0 flex-1 truncate font-mono">{col.name}</span>
						{#if col.sqlType}
							<span class="shrink-0 font-mono text-[9px] tracking-wide uppercase opacity-60">
								{col.sqlType}
							</span>
						{/if}
					</li>
				{/each}
			</ul>
			<p
				class="shrink-0 text-[10px] leading-snug italic"
				style:color="hsl(var(--muted-foreground))"
			>
				Read-only preview. Check this table in the Dimensions list to start editing its attributes.
			</p>
		</div>
	{:else if !dim}
		<div class="flex h-full flex-col items-center justify-center gap-1 p-3 text-center">
			<Database class="h-5 w-5" aria-hidden="true" style="color: hsl(var(--muted-foreground))" />
			<p class="text-xs font-medium">Pick a dimension</p>
			<p class="text-[11px]" style="color: hsl(var(--muted-foreground))">
				Click a saved dim to define its attributes, or click a table in the Dimensions list to
				preview its columns.
			</p>
		</div>
	{:else}
		{@const boundTable =
			(dim.sourceTableId ?? dim.primaryKeyTableId)
				? (store.doc.tables.find((t) => t.id === (dim.sourceTableId ?? dim.primaryKeyTableId)) ??
					null)
				: null}
		{#if !boundTable}
			<!-- Fallback state: dim has no table binding.  Happens when
			     an imported XML declared a table that isn't on the
			     canvas, or when a fresh dim hasn't been linked yet.
			     The Inspector is the fix location (Properties tab → set
			     the PK; picking one binds the dim to that table).
			     If the schema intended this dim to be degenerate (members
			     live on the fact), that's also set from the Inspector. -->
			<div class="flex h-full flex-col items-center justify-center gap-1 p-3 text-center">
				<Database class="h-5 w-5" aria-hidden="true" style="color: hsl(var(--muted-foreground))" />
				<p class="text-xs font-medium">No table bound</p>
				<p class="text-[11px]" style="color: hsl(var(--muted-foreground))">
					Set a primary key in the Inspector to bind this dim to a table.
				</p>
			</div>
		{:else}
			{@const dimHasHierarchies = dim.hierarchies.length > 0}
			{@const needsKey = !!(dim.sourceTableId ?? dim.primaryKeyTableId) && dimHasHierarchies}
			{@const keyMissing = needsKey && !resolveKeyAttribute(dim)}
			<!-- Wrap the pane body + inline key alert so the alert sits at
			     the TOP, right under the section header.  Icon-only so it
			     doesn't crowd the pane; the full explanation lives in the
			     Inspector (hover the icon for a tooltip).  The engine
			     rejects a dim-with-hierarchies without a join key, so
			     surfacing it here saves the user a round-trip. -->
			<div class="flex h-full min-h-0 flex-col gap-2">
				{#if keyMissing}
					<div
						class="flex shrink-0 items-center justify-end"
						data-testid="workbench-pane-attributes-key-warning"
					>
						<span
							class="inline-flex h-5 items-center gap-1 rounded-full border border-warning/50 bg-warning/15 px-2 text-warning"
							title="Mondrian needs a join key for a dimension with hierarchies — the engine will reject this schema at load. Pick a key attribute in the Inspector."
						>
							<AlertCircle class="h-3 w-3 shrink-0" aria-hidden="true" />
							<span class="font-mono text-[9px] font-semibold tracking-wider uppercase">
								Key missing
							</span>
						</span>
					</div>
				{/if}
				<div class="flex min-h-0 flex-1 flex-col">
					{#if attributesPaneMode === 'edit'}
						{@render paneAttributesEdit(boundTable)}
					{:else}
						{@render paneAttributesSaved(dim, boundTable)}
					{/if}
				</div>
			</div>
		{/if}
	{/if}
{/snippet}

{#snippet paneAttributesEdit(boundTable: SchemaCanvasTable)}
	{@const sortedCols = [...boundTable.columns].sort((a, b) => a.name.localeCompare(b.name))}
	<!-- Read the DRAFT rather than dim.attributes — see the top-of-file
	     $effect that seeds it whenever the user enters edit mode or
	     switches dims.  Nothing here writes to the store; the Confirm
	     pill in the pane header does. -->
	{@const draft = attrsDraft ?? []}
	{@const allIn = sortedCols.every((c) =>
		draft.some((a) => a.tableId === boundTable.id && a.columnName === c.name)
	)}
	<div class="flex h-full flex-col gap-2" data-testid="workbench-pane-attributes-edit">
		<!-- All / Clear bulk replace.  No Include / Exclude flip —
		     checkboxes mean "in draft" unambiguously now; the two bulk
		     buttons cover the "want most" / "want few" extremes. -->
		<div class="flex items-center gap-1.5 text-[10px]">
			<button
				type="button"
				onclick={() => {
					attrsDraft = sortedCols.map((c) => ({
						tableId: boundTable.id,
						columnName: c.name
					}));
				}}
				class="rounded px-1.5 py-0.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground disabled:opacity-50"
				disabled={allIn}
			>
				All
			</button>
			<button
				type="button"
				onclick={() => {
					attrsDraft = [];
				}}
				class="rounded px-1.5 py-0.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground disabled:opacity-50"
				disabled={draft.length === 0}
			>
				Clear
			</button>
			<span class="ml-auto text-muted-foreground">
				{draft.length} of {sortedCols.length} selected{attrsDraftDirty ? ' · unsaved' : ''}
			</span>
		</div>
		<ol class="flex flex-col gap-0.5">
			{#each sortedCols as col (col.name)}
				{@const inA = draft.some((a) => a.tableId === boundTable.id && a.columnName === col.name)}
				<li>
					<label
						class="flex cursor-pointer items-center gap-1.5 rounded border bg-elev-2 px-1.5 py-0.5 text-[11px] hover:bg-accent/40"
						style:border-color="hsl(var(--border))"
					>
						<input
							type="checkbox"
							checked={inA}
							onchange={(e) => {
								if (e.currentTarget.checked && !inA) {
									attrsDraft = [
										...(attrsDraft ?? []),
										{ tableId: boundTable.id, columnName: col.name }
									];
								} else if (!e.currentTarget.checked && inA) {
									attrsDraft = (attrsDraft ?? []).filter(
										(a) => !(a.tableId === boundTable.id && a.columnName === col.name)
									);
								}
							}}
							class="h-3 w-3 shrink-0"
						/>
						<span class="min-w-0 flex-1 truncate font-mono">{col.name}</span>
						{#if col.sqlType}
							<span class="shrink-0 font-mono text-[9px] tracking-wide uppercase opacity-50">
								{col.sqlType}
							</span>
						{/if}
					</label>
				</li>
			{/each}
		</ol>
	</div>
{/snippet}

{#snippet paneAttributesSaved(dim: SchemaCanvasDimension, boundTable: SchemaCanvasTable)}
	{@const A = dim.attributes ?? []}
	{@const sortedA = [...A].sort((a, b) => a.columnName.localeCompare(b.columnName))}
	<!-- Single resolved key attribute — one shared source of truth for the
	     KEY badge across every row, so ambiguous schemas (Product with two
	     attributes sharing columnName='product_id') don't double-light. -->
	{@const paneResolvedKey = resolveKeyAttribute(dim)}
	<div class="flex h-full flex-col gap-2" data-testid="workbench-pane-attributes-saved">
		{#if sortedA.length === 0}
			<p
				class="rounded border border-dashed p-2 text-center text-[11px]"
				style:border-color="hsl(var(--border))"
				style:color="hsl(var(--muted-foreground))"
			>
				No attributes saved. Hit <span class="font-medium">Edit</span> to pick from
				<span class="font-mono">{boundTable.name}</span>.
			</p>
		{:else}
			<!-- Column headers — NAME | KEY | TYPE, aligned to the data rows below.
			     Setting the key here is now interactive: click the key icon on
			     any row to set / unset the dim's key.  Matches the Mondrian
			     model where exactly one attribute is the dim key. -->
			<div
				class="flex shrink-0 items-center gap-2 border-b px-2 pb-1 text-[9px] font-semibold tracking-wider text-muted-foreground uppercase"
				style:border-color="hsl(var(--border))"
			>
				<span class="h-3 w-3 shrink-0" aria-hidden="true"></span>
				<span class="min-w-0 flex-1">Name</span>
				<span class="flex w-6 shrink-0 justify-center">Key</span>
				<span class="w-14 shrink-0 text-right">Type</span>
			</div>
			<ul class="flex flex-col gap-1">
				{#each sortedA as a, __ai (`${a.tableId}::${a.columnName}::${a.name ?? __ai}`)}
					{@const dimSrcIdSaved = dim.sourceTableId ?? dim.primaryKeyTableId}
					{@const sqlType = columnTypeFor(a.tableId, a.columnName)}
					{@const attrKey = `${a.tableId}::${a.columnName}::${a.name ?? __ai}`}
					{@const attrIdent = a.name ?? a.columnName}
					{@const isKeyAttr =
						!!paneResolvedKey &&
						paneResolvedKey.attr.tableId === a.tableId &&
						paneResolvedKey.attr.columnName === a.columnName &&
						(paneResolvedKey.attr.name ?? paneResolvedKey.attr.columnName) === attrIdent}
					{@const displayName = a.name ?? a.columnName}
					{@const hasCaption = a.name && a.name !== a.columnName}
					<li
						draggable="true"
						ondragstart={(e) =>
							handleAttributeDragStart(e, {
								tableId: a.tableId,
								columnName: a.columnName
							})}
						onclick={() => {
							selectedAttributeKey = attrKey;
							selectedMeasureId = null;
							selectedHierarchyId = null;
							focus('attribute', attrKey);
						}}
						onkeydown={(e) => {
							if (e.key === 'Enter' || e.key === ' ') {
								e.preventDefault();
								selectedAttributeKey = attrKey;
								selectedMeasureId = null;
								selectedHierarchyId = null;
								focus('attribute', attrKey);
							}
						}}
						role="button"
						tabindex="0"
						class="group flex cursor-grab items-start gap-2 rounded border bg-elev-2 px-2 py-1 text-[11px] transition-colors active:cursor-grabbing {isFocused(
							'attribute',
							attrKey
						)
							? 'workbench-row-selected'
							: 'hover:bg-accent/40'} {isKeyAttr ? 'bg-primary/5' : ''}"
						style:border-color={isFocused('attribute', attrKey) ? '' : 'hsl(var(--border))'}
						data-testid="workbench-pane-saved-attribute"
						data-attribute={a.columnName}
						title="Click to inspect · drag into a hierarchy in the Content pane"
					>
						<GripVertical
							class="mt-0.5 h-3 w-3 shrink-0 text-muted-foreground"
							aria-hidden="true"
						/>
						<!-- Name cell: display-name caption from the schema on the
						     primary line, raw SQL column name below in mono
						     when a caption exists AND differs.  Falls back to
						     the raw column name if no caption. -->
						<span class="flex min-w-0 flex-1 flex-col leading-tight">
							<span class="min-w-0 truncate text-foreground">{displayName}</span>
							{#if hasCaption}
								<span class="min-w-0 truncate font-mono text-[9px] text-muted-foreground">
									{a.columnName}
								</span>
							{/if}
						</span>
						<!-- Interactive Key toggle — always visible when the row IS
						     the key, appears on hover otherwise.  Stops row-click
						     propagation so setting the key doesn't also select
						     the row for inspection. -->
						<button
							type="button"
							onclick={(e) => {
								e.stopPropagation();
								if (isKeyAttr) {
									store.updateDimension(dim.id, {
										foreignKey: undefined,
										primaryKey: undefined,
										primaryKeyTableId: undefined
									});
								} else {
									// primaryKey = attribute IDENTITY (logical name
									// if set, columnName as fallback) so two attrs
									// on the same column disambiguate.  foreignKey
									// stays as the raw column since that's what
									// the fact's ForeignKeyLink expects.
									store.updateDimension(dim.id, {
										foreignKey: a.columnName,
										primaryKey: attrIdent,
										primaryKeyTableId: a.tableId !== dimSrcIdSaved ? a.tableId : undefined
									});
								}
							}}
							onkeydown={(e) => e.stopPropagation()}
							class="mt-0.5 flex h-5 w-6 shrink-0 items-center justify-center rounded border transition-colors {isKeyAttr
								? 'border-primary bg-primary/10 text-primary'
								: 'border-border text-muted-foreground opacity-0 group-hover:opacity-100 hover:border-primary/60 hover:text-primary'}"
							aria-pressed={isKeyAttr}
							aria-label={isKeyAttr ? 'Unmark as key' : 'Set as key'}
							title={isKeyAttr ? 'Unmark as key' : 'Set as key'}
							data-testid="workbench-attr-iskey-{a.columnName}"
						>
							<KeyRound class="h-3 w-3" aria-hidden="true" />
						</button>
						<!-- Type cell — fixed width, right-aligned, well-separated
						     from the Key chip so the two read as distinct chips. -->
						<span
							class="w-14 shrink-0 pt-0.5 text-right font-mono text-[9px] tracking-wide uppercase opacity-50"
						>
							{sqlType ?? ''}
						</span>
					</li>
				{/each}
			</ul>
		{/if}
	</div>
{/snippet}

{#snippet paneHierarchies()}
	{@const dim = selectedDimension}
	{@const disabledForAttrs = attributesPaneMode === 'edit'}
	{#if !dim}
		<!-- Empty state carries the direction, not "disabled" chrome.
		     Colour reads as regular foreground so the header + prompt
		     don't collapse into a wash of grey (the pane still has a
		     job to do, it just needs a dim first). -->
		<div class="flex h-full flex-col items-center justify-center gap-1.5 p-4 text-center">
			<ListTree class="h-6 w-6 text-primary" aria-hidden="true" />
			<p class="text-sm font-semibold">Compose a hierarchy</p>
			<p class="max-w-[28ch] text-[11px] leading-relaxed text-muted-foreground">
				Pick a dimension on the left, then stack its attributes into levels here.
			</p>
		</div>
	{:else}
		<!-- Disable the whole Hierarchies pane while Attributes is in
		     edit mode.  Hierarchies are composed from A (the saved
		     attribute set), so editing them while A is still in flux
		     creates dangling state and confuses the user.  Switch
		     Attributes to saved to unlock. -->
		<div class="flex h-full flex-col">
			{#if disabledForAttrs}
				<!-- Attributes still in edit mode — hierarchies compose
				     FROM saved attributes, so we gate until confirm.
				     Phrased as a forward-looking prompt rather than a
				     locked-out banner. -->
				<p
					class="mb-1 shrink-0 rounded border border-primary/30 bg-primary/5 p-1.5 text-center text-[10px] font-medium text-primary"
				>
					Choose an attribute — save the Attributes column to unlock hierarchies.
				</p>
			{/if}
			<div
				class="flex min-h-0 flex-1 flex-col {disabledForAttrs ? 'pointer-events-none' : ''}"
				aria-disabled={disabledForAttrs}
			>
				{@render paneHierarchiesEdit(dim)}
			</div>
		</div>
	{/if}
{/snippet}

{#snippet hierarchyDropZone(dim: SchemaCanvasDimension, hier: SchemaCanvasHierarchy)}
	{@const dropActive = contentDragOverHierarchyId === hier.id}
	<!-- Levels render in AUTHORED order (coarse → fine: Year → Quarter → Month
	     → Day), NOT alphabetically — sorting by column name scrambled the
	     hierarchy so it no longer made sense (reported 2026-07-29). The array
	     order is the source of truth (addLevel appends; the model documents it
	     as ordered coarse → fine). -->
	{@const displayLevels = hier.levels}
	<!-- Generous per-hierarchy drop zone.  Designed to fill the
	     RIGHT-side panel of the new Hierarchies pane (flex-1 +
	     min-h-0 in the parent), so the dashed target box is big and
	     obvious, not a tiny sliver. -->
	<div
		class="flex min-h-0 flex-1 flex-col overflow-y-auto rounded border-2 border-dashed bg-elev-2 p-1 transition-colors {dropActive
			? 'border-primary bg-primary/5'
			: ''}"
		style:border-color={dropActive ? '' : 'hsl(var(--border))'}
		ondragover={(e) => {
			if (!isContentDrag(e)) return;
			e.preventDefault();
			if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy';
			contentDragOverHierarchyId = hier.id;
		}}
		ondragleave={(e) => {
			// Only clear if the cursor genuinely leaves the box (browser
			// fires dragleave when crossing into a child element too).
			const t = e.relatedTarget as Node | null;
			if (!t || !(e.currentTarget as HTMLElement).contains(t)) {
				if (contentDragOverHierarchyId === hier.id) contentDragOverHierarchyId = null;
			}
		}}
		ondrop={(e) => {
			contentDragOverHierarchyId = null;
			const attr = readAttributeDrag(e);
			if (attr) {
				e.preventDefault();
				e.stopPropagation();
				// Seed the level's display name from the attribute's caption
				// (if any) so the two-line "Display / raw_col" pattern shows
				// up automatically — otherwise every fresh level defaults to
				// name = columnName and both lines read identical.
				const sourceAttr = (dim.attributes ?? []).find(
					(a) => a.tableId === attr.tableId && a.columnName === attr.columnName
				);
				store.addLevel(dim.id, hier.id, {
					tableId: attr.tableId,
					columnName: attr.columnName,
					name: sourceAttr?.name
				});
				return;
			}
			const move = readLevelMoveDrag(e);
			if (move) {
				e.preventDefault();
				e.stopPropagation();
				if (move.hierId === hier.id) return;
				const srcDim = store.dimensions.find((d) => d.id === move.dimId);
				const srcHier = srcDim?.hierarchies.find((h) => h.id === move.hierId);
				const srcLevel = srcHier?.levels.find((l) => l.id === move.levelId);
				if (!srcLevel) return;
				store.removeLevel(move.dimId, move.hierId, move.levelId);
				store.addLevel(dim.id, hier.id, {
					tableId: srcLevel.tableId,
					columnName: srcLevel.columnName,
					name: srcLevel.name
				});
			}
		}}
		data-testid="workbench-pane-hierarchy-drop"
		data-hierarchy-id={hier.id}
	>
		{#if hier.levels.length === 0}
			<!-- Empty state:
			     • TOP: dashed "+ Add Level" pill (matches +Add Dimension /
			       +Add Hierarchy across the pane; opens the same attribute
			       picker Popover as the below-list button does).
			     • CENTER: hint text reminding you the drop zone is live too. -->
			<div class="flex flex-1 flex-col">
				{#if (dim.attributes ?? []).length > 0}
					<div class="shrink-0 px-1 pt-1">
						<Popover side="bottom" align="center" sideOffset={6}>
							{#snippet trigger({ props })}
								<button
									{...props}
									type="button"
									class="flex w-full items-center justify-center gap-1.5 rounded border-2 border-dashed px-3 py-2 text-[11px] font-semibold tracking-wide text-muted-foreground uppercase transition-colors hover:border-primary/50 hover:bg-accent hover:text-accent-foreground"
									style:border-color="hsl(var(--border))"
									data-testid="workbench-pane-hierarchy-add-picker"
								>
									<Plus class="h-3.5 w-3.5" aria-hidden="true" />
									Add Level
								</button>
							{/snippet}
							{#snippet content()}
								<div class="flex w-56 flex-col gap-1 p-2">
									<span
										class="mb-1 text-[9px] font-semibold tracking-wider uppercase"
										style:color="hsl(var(--muted-foreground))"
									>
										Add attributes
									</span>
									<ul class="flex max-h-56 flex-col gap-0.5 overflow-y-auto">
										{#each dim.attributes ?? [] as attr, __ai (`${attr.tableId}::${attr.columnName}::${attr.name ?? __ai}`)}
											{@const inHier = hier.levels.some(
												(l) => l.tableId === attr.tableId && l.columnName === attr.columnName
											)}
											<li>
												<label
													class="flex w-full cursor-pointer items-center gap-1.5 rounded px-2 py-1 text-[11px] hover:bg-accent/40"
												>
													<input
														type="checkbox"
														class="h-3 w-3"
														checked={inHier}
														onchange={() => {
															if (inHier) {
																const lvl = hier.levels.find(
																	(l) =>
																		l.tableId === attr.tableId && l.columnName === attr.columnName
																);
																if (lvl) store.removeLevel(dim.id, hier.id, lvl.id);
															} else {
																store.addLevel(dim.id, hier.id, {
																	tableId: attr.tableId,
																	columnName: attr.columnName
																});
															}
														}}
														data-testid="workbench-pane-hierarchy-add-checkbox"
														data-column={attr.columnName}
													/>
													<span class="flex min-w-0 flex-1 flex-col leading-tight">
														<span class="min-w-0 truncate">
															{attr.name || attr.columnName}
														</span>
														{#if attr.name && attr.name !== attr.columnName}
															<span
																class="min-w-0 truncate font-mono text-[9px] text-muted-foreground"
															>
																{attr.columnName}
															</span>
														{/if}
													</span>
												</label>
											</li>
										{/each}
									</ul>
								</div>
							{/snippet}
						</Popover>
					</div>
				{/if}
				<div class="flex flex-1 items-center justify-center px-4">
					<p
						class="max-w-[24ch] text-center text-[11px] leading-relaxed italic"
						style:color="hsl(var(--muted-foreground))"
					>
						Drag and drop attributes to add as hierarchy levels.
					</p>
				</div>
			</div>
		{:else}
			<!-- Members as simple table-style rows.  Two-line name cell
			     mirrors the Attributes pane: display name on top (falls
			     back to the underlying attribute's name), raw column
			     underneath in mono when they differ. -->
			<ul class="flex flex-col">
				{#each displayLevels as lvl, idx (lvl.id)}
					{@const sqlType = columnTypeFor(lvl.tableId, lvl.columnName)}
					{@const sourceAttr = (dim.attributes ?? []).find(
						(a) => a.tableId === lvl.tableId && a.columnName === lvl.columnName
					)}
					{@const attrRemoved = !sourceAttr}
					{@const displayName = lvl.name || sourceAttr?.name || lvl.columnName}
					{@const showRawUnderneath = displayName !== lvl.columnName}
					{@const levelSelected = selectedLevelId === lvl.id}
					<li
						class="flex cursor-pointer items-start gap-2 border-b px-2 py-1 text-[11px] hover:bg-accent/40 {levelSelected
							? 'ring-1 ring-primary ring-inset'
							: ''} {attrRemoved ? '' : idx % 2 === 0 ? 'bg-muted/40' : 'bg-muted/15'}"
						style:border-color={attrRemoved ? 'hsl(var(--warning) / 0.5)' : 'hsl(var(--border))'}
						style:background-color={attrRemoved ? 'hsl(var(--warning) / 0.08)' : undefined}
						style:border-left={attrRemoved ? '2px solid hsl(var(--warning))' : undefined}
						role="button"
						tabindex="0"
						aria-pressed={levelSelected}
						onclick={() => {
							selectedHierarchyId = hier.id;
							selectedLevelId = lvl.id;
							selectedAttributeKey = null;
						}}
						onkeydown={(e) => {
							if (e.key === 'Enter' || e.key === ' ') {
								e.preventDefault();
								selectedHierarchyId = hier.id;
								selectedLevelId = lvl.id;
								selectedAttributeKey = null;
							}
						}}
						data-testid="workbench-pane-hierarchy-row"
						data-selected={levelSelected ? 'true' : undefined}
						data-attr-removed={attrRemoved ? 'true' : undefined}
					>
						{#if attrRemoved}
							<AlertCircle
								class="mt-0.5 h-3 w-3 shrink-0 text-warning"
								aria-hidden="true"
								aria-label="Source attribute removed"
							/>
						{/if}
						<span
							class="flex min-w-0 flex-1 flex-col leading-tight {attrRemoved ? 'text-warning' : ''}"
							title={attrRemoved
								? `"${lvl.columnName}" is no longer in this dimension's attributes. Re-add the attribute, or remove this level.`
								: undefined}
						>
							<span class="min-w-0 truncate">{displayName}</span>
							{#if showRawUnderneath}
								<span class="min-w-0 truncate font-mono text-[9px] text-muted-foreground">
									{lvl.columnName}
								</span>
							{/if}
						</span>
						{#if attrRemoved}
							<span
								class="mt-0.5 shrink-0 font-mono text-[9px] font-semibold tracking-wide text-warning uppercase"
								title="This level references an attribute that was removed from the dimension."
							>
								Source removed
							</span>
						{:else if sqlType}
							<span class="mt-0.5 shrink-0 font-mono text-[9px] tracking-wide uppercase opacity-60">
								{sqlType}
							</span>
						{/if}
						<!-- Reorder within the hierarchy (coarse↔fine). Drag-to-reorder
						     in a text list is fiddly, so use explicit up/down controls. -->
						<button
							type="button"
							disabled={idx === 0}
							onclick={(e) => {
								e.stopPropagation();
								store.moveLevel(dim.id, hier.id, lvl.id, -1);
							}}
							class="mt-0.5 shrink-0 rounded p-0.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground disabled:pointer-events-none disabled:opacity-30"
							aria-label="Move level up (coarser)"
							title="Move up (coarser)"
							data-testid="workbench-pane-level-up"
						>
							<ChevronUp class="h-2.5 w-2.5" aria-hidden="true" />
						</button>
						<button
							type="button"
							disabled={idx === displayLevels.length - 1}
							onclick={(e) => {
								e.stopPropagation();
								store.moveLevel(dim.id, hier.id, lvl.id, 1);
							}}
							class="mt-0.5 shrink-0 rounded p-0.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground disabled:pointer-events-none disabled:opacity-30"
							aria-label="Move level down (finer)"
							title="Move down (finer)"
							data-testid="workbench-pane-level-down"
						>
							<ChevronDown class="h-2.5 w-2.5" aria-hidden="true" />
						</button>
						<button
							type="button"
							onclick={(e) => {
								e.stopPropagation();
								store.removeLevel(dim.id, hier.id, lvl.id);
							}}
							class="mt-0.5 shrink-0 rounded p-0.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground"
							aria-label="Remove member"
							title="Remove from this hierarchy"
						>
							<X class="h-2.5 w-2.5" aria-hidden="true" />
						</button>
					</li>
				{/each}
			</ul>
			<!-- Bottom-of-content dashed "+ Add Level" pill.  Mirror of
			     the +Add Dimension and +Add Hierarchy buttons.  Reuses the
			     same attribute picker Popover the empty state offers, so
			     the interaction stays consistent whether the hierarchy is
			     fresh or already has levels. -->
			{#if (dim.attributes ?? []).length > 0}
				<div class="mt-1 px-1">
					<Popover side="bottom" align="center" sideOffset={6}>
						{#snippet trigger({ props })}
							<button
								{...props}
								type="button"
								class="flex w-full items-center justify-center gap-1.5 rounded border-2 border-dashed px-3 py-2 text-[11px] font-semibold tracking-wide text-muted-foreground uppercase transition-colors hover:border-primary/50 hover:bg-accent hover:text-accent-foreground"
								style:border-color="hsl(var(--border))"
								data-testid="workbench-pane-hierarchy-add-level-bottom"
							>
								<Plus class="h-3.5 w-3.5" aria-hidden="true" />
								Add Level
							</button>
						{/snippet}
						{#snippet content()}
							<div class="flex w-56 flex-col gap-1 p-2">
								<span
									class="mb-1 text-[9px] font-semibold tracking-wider uppercase"
									style:color="hsl(var(--muted-foreground))"
								>
									Add attributes
								</span>
								<ul class="flex max-h-56 flex-col gap-0.5 overflow-y-auto">
									{#each dim.attributes ?? [] as attr, __ai (`${attr.tableId}::${attr.columnName}::${attr.name ?? __ai}`)}
										{@const inHier = hier.levels.some(
											(l) => l.tableId === attr.tableId && l.columnName === attr.columnName
										)}
										<li>
											<label
												class="flex w-full cursor-pointer items-center gap-1.5 rounded px-2 py-1 text-[11px] hover:bg-accent/40"
											>
												<input
													type="checkbox"
													class="h-3 w-3"
													checked={inHier}
													onchange={() => {
														if (inHier) {
															const lvl = hier.levels.find(
																(l) =>
																	l.tableId === attr.tableId && l.columnName === attr.columnName
															);
															if (lvl) store.removeLevel(dim.id, hier.id, lvl.id);
														} else {
															// Carry the attribute's display name onto the level
															// so the two-line pattern shows up from the first
															// frame (see hierarchyDropZone ondrop for context).
															store.addLevel(dim.id, hier.id, {
																tableId: attr.tableId,
																columnName: attr.columnName,
																name: attr.name
															});
														}
													}}
													data-testid="workbench-pane-hierarchy-add-level-checkbox"
													data-column={attr.columnName}
												/>
												<span class="min-w-0 flex-1 truncate font-mono">
													{attr.columnName}
												</span>
											</label>
										</li>
									{/each}
								</ul>
							</div>
						{/snippet}
					</Popover>
				</div>
			{/if}
		{/if}
	</div>
{/snippet}

{#snippet hierarchyListRow(dim: SchemaCanvasDimension, hier: SchemaCanvasHierarchy)}
	{@const isSelected = isFocused('hierarchy', hier.id)}
	<li
		role="button"
		tabindex="0"
		onclick={() => {
			selectedHierarchyId = hier.id;
			hierarchyExplicitlyClicked = true;
			focus('hierarchy', hier.id);
		}}
		onkeydown={(e) => {
			if (e.key === 'Enter' || e.key === ' ') {
				e.preventDefault();
				selectedHierarchyId = hier.id;
				hierarchyExplicitlyClicked = true;
				focus('hierarchy', hier.id);
			}
		}}
		class="flex cursor-pointer items-center gap-1.5 rounded border bg-elev-2 px-2 py-1 text-[11px] transition-colors {isSelected
			? 'workbench-row-selected'
			: 'border-border hover:bg-accent/40'}"
		data-testid="workbench-pane-hierarchy-list-row"
		data-hierarchy-id={hier.id}
	>
		<ListTree class="h-3 w-3 shrink-0 text-primary" aria-hidden="true" />
		{#if editingHierNameId === hier.id}
			<input
				type="text"
				value={hier.name}
				oninput={(e) => renameHierarchy(dim.id, hier.id, e.currentTarget.value)}
				onclick={(e) => e.stopPropagation()}
				onkeydown={(e) => {
					e.stopPropagation();
					if (e.key === 'Enter' || e.key === 'Escape') {
						(e.currentTarget as HTMLInputElement).blur();
					}
				}}
				onblur={() => {
					commitHierarchyName(dim.id, hier.id);
					editingHierNameId = null;
				}}
				placeholder="Hierarchy name"
				aria-label="Hierarchy name"
				class="h-5 min-w-0 flex-1 rounded border bg-background px-1 font-medium focus:border-ring focus:ring-1 focus:ring-ring focus:outline-none"
				style:border-color="hsl(var(--border))"
				use:autoFocus={editingHierNameId === hier.id}
			/>
		{:else}
			<span
				role="button"
				tabindex="0"
				ondblclick={(e) => {
					e.stopPropagation();
					editingHierNameId = hier.id;
				}}
				onkeydown={(e) => {
					if (e.key === 'F2' || e.key === 'Enter') {
						e.preventDefault();
						e.stopPropagation();
						editingHierNameId = hier.id;
					}
				}}
				class="block min-w-0 flex-1 truncate leading-5 font-medium"
				title="Double-click to rename"
			>
				{hier.name || 'Untitled hierarchy'}
			</span>
		{/if}
		<span class="shrink-0 font-mono text-[9px] text-muted-foreground">
			{hier.levels.length}
		</span>
		<button
			type="button"
			onclick={(e) => {
				e.stopPropagation();
				requestDeleteHierarchy(dim.id, hier.id);
			}}
			class="shrink-0 rounded p-0.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground"
			aria-label="Delete hierarchy"
			title="Delete hierarchy"
		>
			<X class="h-2.5 w-2.5" aria-hidden="true" />
		</button>
	</li>
{/snippet}

{#snippet hierarchyRightPanel(dim: SchemaCanvasDimension)}
	{@const hier = selectedHierarchy}
	<div class="flex min-h-0 flex-1 flex-col">
		{#if hier}
			<!-- Inline header lives on the Content eyebrow row now, so
			     no separate header line here. -->
			{@render hierarchyDropZone(dim, hier)}
		{:else}
			<div
				class="flex h-full flex-col items-center justify-center gap-1 rounded border-2 border-dashed p-3 text-center"
				style:border-color="hsl(var(--border))"
				style:color="hsl(var(--muted-foreground))"
			>
				<ListTree class="h-5 w-5" aria-hidden="true" />
				{#if dim.hierarchies.length === 0}
					<p class="text-xs font-medium" style:color="hsl(var(--foreground))">No hierarchies yet</p>
					<p class="text-[11px]">
						Hit <span class="font-medium">+ Add</span> to make one.
					</p>
				{:else}
					<p class="text-xs font-medium" style:color="hsl(var(--foreground))">Pick a hierarchy</p>
					<p class="text-[11px]">Click one on the left to see its content.</p>
				{/if}
			</div>
		{/if}
	</div>
{/snippet}

{#snippet paneHierarchiesEdit(dim: SchemaCanvasDimension)}
	<div class="flex h-full flex-col gap-2" data-testid="workbench-pane-hierarchies-edit">
		<!-- Two-column body per the sketch.
		     Left:  the LIST of hierarchies (name + count + X per row).
		     Right: a big drop zone showing the SELECTED hierarchy's
		            content.  Empty state when nothing's picked. -->
		{@render hierarchyTwoColumnBody(dim)}
	</div>
{/snippet}

{#snippet hierarchyTwoColumnBody(dim: SchemaCanvasDimension)}
	<!-- Shared body for both edit + saved.  Two columns side by side
	     with "Name" / "Content" eyebrow labels above each.  The Name
	     column always shows a hint when the dim has no hierarchies
	     yet; the Content column always renders its own empty state. -->
	<div class="flex min-h-0 flex-1 gap-2">
		<div class="flex w-2/5 shrink-0 flex-col gap-1">
			<span
				class="text-[10px] font-semibold tracking-wider uppercase"
				style:color="hsl(var(--muted-foreground))"
			>
				Name
			</span>
			<!-- Whole Name column body is a drop target — dropping an
			     attribute creates a single-level hierarchy named after
			     it.  Mirror of the facts side's drag-into-Name. -->
			<div
				class="flex min-h-0 flex-1 flex-col gap-1 overflow-y-auto rounded px-0.5 transition-colors {dimDragOverHierName
					? 'bg-primary/5 ring-1 ring-primary/40'
					: ''}"
				ondragover={(e) => {
					const tt = e.dataTransfer?.types ?? [];
					if (!Array.from(tt).includes(ATTRIBUTE_MIME)) return;
					e.preventDefault();
					if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy';
					dimDragOverHierName = true;
				}}
				ondragleave={(e) => {
					const t = e.relatedTarget as Node | null;
					if (!t || !(e.currentTarget as HTMLElement).contains(t)) {
						dimDragOverHierName = false;
					}
				}}
				ondrop={(e) => {
					dimDragOverHierName = false;
					const attr = readAttributeDrag(e);
					if (attr) {
						e.preventDefault();
						e.stopPropagation();
						addHierarchyFromAttribute(dim.id, attr.tableId, attr.columnName);
					}
				}}
				data-testid="workbench-pane-hierarchy-name-col"
			>
				{#if dim.hierarchies.length === 0}
					<!-- Empty state — first-touch affordance is a real button that
					     creates a hierarchy (was a hint pointing at the header
					     Add).  Drop-a-single-attribute path stays live via the
					     drop handler on this same column. -->
					<button
						type="button"
						onclick={() => {
							const h = store.addHierarchy(dim.id);
							if (h) {
								selectedHierarchyId = h.id;
								hierarchyExplicitlyClicked = true;
								editingHierNameId = h.id;
							}
						}}
						class="flex w-full flex-col items-center justify-center gap-1 rounded border border-dashed p-3 text-center text-[11px] transition-colors hover:border-primary hover:bg-primary/5"
						style:border-color="hsl(var(--border))"
						style:color="hsl(var(--muted-foreground))"
						data-testid="workbench-pane-hierarchy-empty-add"
					>
						<Plus class="h-3.5 w-3.5" aria-hidden="true" />
						<span>Add hierarchy</span>
						<span class="text-[9px] opacity-60">or drop a single attribute</span>
					</button>
				{:else}
					<ul class="flex flex-col gap-1" data-testid="workbench-pane-hierarchy-list">
						{#each dim.hierarchies as hier (hier.id)}
							{@render hierarchyListRow(dim, hier)}
						{/each}
					</ul>
					<!-- Bottom-of-panel dashed "Add hierarchy" button, mirroring
					     the +Add Dimension affordance under the Dimensions saved
					     list.  Header +Add is still there for muscle memory; this
					     one is the natural next-gesture after eyeballing the
					     current hierarchies. -->
					<button
						type="button"
						onclick={() => {
							const h = store.addHierarchy(dim.id);
							if (h) {
								selectedHierarchyId = h.id;
								hierarchyExplicitlyClicked = true;
								editingHierNameId = h.id;
							}
						}}
						class="mt-1 flex w-full items-center justify-center gap-1.5 rounded border-2 border-dashed px-3 py-2 text-[11px] font-semibold tracking-wide text-muted-foreground uppercase transition-colors hover:border-primary/50 hover:bg-accent hover:text-accent-foreground"
						style:border-color="hsl(var(--border))"
						data-testid="workbench-pane-hierarchy-add-bottom"
					>
						<Plus class="h-3.5 w-3.5" aria-hidden="true" />
						Add Hierarchy
					</button>
				{/if}
			</div>
			<!-- Drop-hint footnote — sits under the Hierarchies name column.
			     Kept text-left + readable (11px, not italic) so it reads as
			     help copy, not a footnote-in-a-footnote. -->
			<p
				class="shrink-0 self-start px-1 pt-1 text-left text-[11px] leading-snug transition-colors {dimDragOverHierName
					? 'font-medium text-primary'
					: ''}"
				style:color={dimDragOverHierName ? '' : 'hsl(var(--muted-foreground))'}
			>
				{#if dimDragOverHierName}
					Drop to create a one-attribute hierarchy
				{:else}
					Drop a single attribute here to auto-create a hierarchy for it.
				{/if}
			</p>
		</div>
		<div class="flex min-h-0 flex-1 flex-col gap-1">
			<!-- "CONTENT · [name] · count" inline header — no extra row
			     for the selected hierarchy name.  Same line that the
			     other column eyebrows use. -->
			<span
				class="flex shrink-0 items-baseline gap-1.5 text-[10px] font-semibold tracking-wider uppercase"
				style:color="hsl(var(--muted-foreground))"
			>
				<span>Content</span>
				{#if selectedHierarchy}
					<span class="font-mono text-[10px] normal-case opacity-80">
						· [{selectedHierarchy.name || 'untitled'}]
					</span>
					<span class="ml-auto font-mono text-[9px] opacity-60">
						{selectedHierarchy.levels.length}
					</span>
				{/if}
			</span>
			{@render hierarchyRightPanel(dim)}
		</div>
	</div>
{/snippet}
