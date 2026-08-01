<!--
  FactsMeasuresPane — the Facts & Measures column cluster extracted from
  WorkbenchView.svelte (audit finding #1039). Owns the three reorderable panes
  (Fact & Measures / Measure Groups / Calculations) and the per-measure-group
  editor (fact table picker, measures checklist, dimension links) that live in
  the schema designer's Facts mode.

  Deliberately owns NO durable state. The shell (WorkbenchView) keeps the
  single source of truth for every cube-swap / persistence variable and threads
  it in via props + `bind:`. This component only READS props, mutates the
  shared reactive structures it is handed in place (`store`,
  `factsMeasureGroups` items, `factsSelectedMeasures`), and calls shell
  callbacks for every structural mutation — so behaviour is byte-for-byte
  identical to the inline snippets it replaced. Every value the template
  reassigns is a `$bindable()` prop bound from the shell (never a duplicated
  source of truth).
-->
<script lang="ts">
	import {
		Database,
		ChevronDown,
		GripVertical,
		Plus,
		X,
		Layers,
		Sigma,
		ListTree,
		Search,
		Pencil,
		Check,
		AlertCircle
	} from 'lucide-svelte';
	import { Select as SelectPrimitive } from 'bits-ui';
	import type { SvelteSet } from 'svelte/reactivity';
	import { calcMode } from './workbench-cubes';
	import { FACTS_MEASURE_MIME, FACTS_MEASURE_GROUP_MIME } from './workbench-dnd';
	import type { FactsCalc, FactsCalcMode, WorkbenchMeasureGroup } from './workbench-cubes';
	import type { SchemaCanvasMeasure } from './types.js';
	import type { SchemaCanvasStore } from './state.svelte.js';

	// Mirrors the shell's local unions (kept in lock-step; simple string
	// literals, so re-declaring here beats widening the module surface).
	type FactsPaneKind = 'fact' | 'groups' | 'calculations';
	type FocusKind = 'dim' | 'hierarchy' | 'attribute' | 'measure' | 'mg' | 'cube' | 'calc';
	type CalcOp = '+' | '-' | '*' | '/';

	interface Props {
		store: SchemaCanvasStore;
		factsSlots: FactsPaneKind[];
		factsPaneDragIndex: number | null;
		factsPaneDragOverIndex: number | null;
		AGGREGATORS: SchemaCanvasMeasure['aggregator'][];
		ALLOW_GROUP_DROP_ON_CALC: boolean;
		/** SvelteSet owned by the shell; the pane mutates it in place. */
		factsSelectedMeasures: SvelteSet<string>;
		// ── bindable: shell owns the source of truth, child writes via bind: ──
		factsEditMode: boolean;
		factsSelectedTableId: string | null;
		factsTableSearch: string;
		factsTableConfirmed: boolean;
		factsMeasureGroups: WorkbenchMeasureGroup[];
		factsSelectedGroupId: string | null;
		factsEditingMeasureGroupId: string | null;
		focusGroupId: string | null;
		factsCalcs: FactsCalc[];
		factsEditingCalcId: string | null;
		editingMeasureCaption: string | null;
		// ── shell callbacks (every structural mutation routes back here) ──
		// Selection setters are write-only from this pane (it never reads the
		// selection back), so they route through callbacks rather than bound
		// props — the shell keeps the single source of truth.
		selectMgMeasure: (measureId: string) => void;
		selectMgDimension: (dimId: string) => void;
		selectFactsCalc: (calcId: string) => void;
		handleFactsPaneDragStart: (e: DragEvent, idx: number) => void;
		handleFactsPaneDragOver: (e: DragEvent, idx: number) => void;
		handleFactsPaneDragLeave: (idx: number) => void;
		handleFactsPaneDrop: (e: DragEvent, targetIdx: number) => void;
		handleFactsPaneDragEnd: () => void;
		addFactsMeasureGroup: () => void;
		removeFactsMeasureGroup: (id: string) => void;
		renameFactsMeasureGroup: (id: string, name: string) => void;
		addFactsCalc: () => void;
		removeFactsCalc: (calcId: string) => void;
		renameFactsCalc: (calcId: string, name: string) => void;
		addCalcMeasure: (calcId: string, name: string, fromGroup?: boolean) => void;
		addCalcOperator: (calcId: string, op: CalcOp) => void;
		popCalcToken: (calcId: string) => void;
		clearCalcTokens: (calcId: string) => void;
		setCalcMode: (calcId: string, mode: FactsCalcMode) => void;
		updateCalcFormula: (calcId: string, formula: string) => void;
		calcNeedsMeasure: (c: FactsCalc) => boolean;
		formatCalcOp: (op: CalcOp) => string;
		commitMeasureCaption: (g: WorkbenchMeasureGroup, col: string, nextValue: string) => void;
		focus: (kind: FocusKind, id: string) => void;
		isFocused: (kind: FocusKind, id: string | null | undefined) => boolean;
		autoFocus: (node: HTMLInputElement, shouldFocus: boolean) => { update(active: boolean): void };
	}

	let {
		store,
		factsSlots,
		factsPaneDragIndex,
		factsPaneDragOverIndex,
		AGGREGATORS,
		ALLOW_GROUP_DROP_ON_CALC,
		factsSelectedMeasures,
		factsEditMode = $bindable(),
		factsSelectedTableId = $bindable(),
		factsTableSearch = $bindable(),
		factsTableConfirmed = $bindable(),
		factsMeasureGroups = $bindable(),
		factsSelectedGroupId = $bindable(),
		factsEditingMeasureGroupId = $bindable(),
		focusGroupId = $bindable(),
		factsCalcs = $bindable(),
		factsEditingCalcId = $bindable(),
		editingMeasureCaption = $bindable(),
		selectMgMeasure,
		selectMgDimension,
		selectFactsCalc,
		handleFactsPaneDragStart,
		handleFactsPaneDragOver,
		handleFactsPaneDragLeave,
		handleFactsPaneDrop,
		handleFactsPaneDragEnd,
		addFactsMeasureGroup,
		removeFactsMeasureGroup,
		renameFactsMeasureGroup,
		addFactsCalc,
		removeFactsCalc,
		renameFactsCalc,
		addCalcMeasure,
		addCalcOperator,
		popCalcToken,
		clearCalcTokens,
		setCalcMode,
		updateCalcFormula,
		calcNeedsMeasure,
		formatCalcOp,
		commitMeasureCaption,
		focus,
		isFocused,
		autoFocus
	}: Props = $props();

	// These three were `{@const}` at the root of the `factsPanePrototype`
	// snippet; at a component root `{@const}` has no valid parent, so they move
	// to `$derived` (identical reactive semantics — read-only in the template).
	const tablesOnCanvas = $derived(store.doc.tables);
	const chosenTable = $derived(tablesOnCanvas.find((t) => t.id === factsSelectedTableId) ?? null);
	const numericCols = $derived(
		chosenTable?.columns.filter((c) =>
			['INT2', 'INT4', 'INT8', 'NUMERIC', 'FLOAT4', 'FLOAT8', 'DECIMAL'].some((t) =>
				(c.sqlType ?? '').toUpperCase().includes(t)
			)
		) ?? []
	);
</script>

<!-- Reorderable: drag the grip in either pane's header to swap
	     Fact & Measures and Measure Groups.  Same UX as the dim
	     columnSlots reorder, but with its own MIME so drags can't
	     cross over. -->
{#each factsSlots as kind, idx (kind)}
	{@const isReorderTarget = factsPaneDragOverIndex === idx && factsPaneDragIndex !== idx}
	{@const isReorderSource = factsPaneDragIndex === idx}
	{#if kind === 'fact'}
		<!-- Pane 1: UNIFIED FACT + MEASURES (single column, four states).
	     Encodes Amelia's sketch:
	       1. Pick     — full picker (search + tabular list, radios)
	       2. Picked   — one row selected, "Confirm fact table" CTA
	                     visible; Measures still gated
	       3. Confirmed — Fact collapses to chosen-table summary with a
	                      Change pill; Measures section becomes active
	                      with its own Edit toggle + checkbox list
	       4. Saved    — outer Edit OFF; both sections collapse to a
	                     read-only "Table name + chosen measures"
	     The whole pane's Edit pill governs state 4; the inner Confirm
	     gates the 2→3 transition; Measures' Edit pill toggles inside 3. -->
		<section
			class="flex min-w-0 flex-1 flex-col overflow-hidden rounded-md border bg-elev-1 transition-colors {isReorderTarget
				? 'border-primary'
				: ''} {isReorderSource ? 'opacity-50' : ''}"
			style:border-color={isReorderTarget ? '' : 'hsl(var(--border))'}
			ondragover={(e) => handleFactsPaneDragOver(e, idx)}
			ondragleave={() => handleFactsPaneDragLeave(idx)}
			ondrop={(e) => handleFactsPaneDrop(e, idx)}
			data-pane-index={idx}
			data-pane-kind={kind}
		>
			<header
				class="flex shrink-0 items-center justify-between gap-2 border-b bg-elev-1 px-2.5 py-2"
				style:border-color="hsl(var(--border))"
			>
				<div class="flex min-w-0 items-center gap-1.5">
					<span
						role="button"
						tabindex="0"
						draggable="true"
						ondragstart={(e) => handleFactsPaneDragStart(e, idx)}
						ondragend={handleFactsPaneDragEnd}
						class="-ml-1 shrink-0 cursor-grab rounded p-1 text-muted-foreground hover:bg-accent hover:text-accent-foreground active:cursor-grabbing"
						aria-label="Drag to reorder this pane"
						title="Drag to reorder"
					>
						<GripVertical class="h-3 w-3" aria-hidden="true" />
					</span>
					<Database class="h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
					<span class="text-[11px] font-semibold tracking-wider uppercase"> Fact & Measures </span>
				</div>
				<button
					type="button"
					onclick={() => (factsEditMode = !factsEditMode)}
					class="inline-flex shrink-0 items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-semibold tracking-wider uppercase {factsEditMode
						? 'border border-primary bg-primary text-primary-foreground'
						: 'border border-border text-muted-foreground hover:bg-accent hover:text-accent-foreground'}"
					title="Toggle edit mode"
				>
					Edit · {factsEditMode ? 'on' : 'off'}
				</button>
			</header>
			<div
				class="flex min-h-0 flex-1 [scrollbar-gutter:stable] flex-col gap-2 overflow-y-auto bg-card p-2"
			>
				{#if tablesOnCanvas.length === 0}
					<p
						class="rounded border border-dashed p-3 text-center text-[11px]"
						style:border-color="hsl(var(--border))"
						style:color="hsl(var(--muted-foreground))"
					>
						No tables on canvas. Flip back to Schema Canvas to add some.
					</p>
				{:else if !factsEditMode}
					<!-- State 4 — saved.  Concise read-only summary. -->
					{#if chosenTable}
						<p
							class="text-[10px] tracking-wide uppercase"
							style:color="hsl(var(--muted-foreground))"
						>
							Fact table
						</p>
						<div
							class="flex items-center gap-1.5 rounded border bg-elev-2 px-1.5 py-1 text-[11px]"
							style:border-color="hsl(var(--border))"
						>
							<Database class="h-3 w-3 shrink-0 text-primary" aria-hidden="true" />
							<span class="min-w-0 flex-1 truncate font-mono">
								{chosenTable.schema ? `${chosenTable.schema}.` : ''}{chosenTable.name}
							</span>
						</div>
						<p
							class="mt-2 text-[10px] tracking-wide uppercase"
							style:color="hsl(var(--muted-foreground))"
						>
							Chosen measures · {factsSelectedMeasures.size}
						</p>
						<!-- Saved-view rows are still draggable into a measure group's
					     Content drop zone — same pattern as the dim Saved
					     Attributes pane that drags into hierarchies.  Grip
					     handle + the whole row are the drag source. -->
						<div class="flex flex-col gap-1">
							{#each numericCols.filter((c) => factsSelectedMeasures.has(c.name)) as col (col.name)}
								<div
									class="flex cursor-grab items-center gap-1.5 rounded border bg-elev-2 px-1.5 py-1 text-[11px] hover:bg-accent/30 active:cursor-grabbing"
									style:border-color="hsl(var(--border))"
									draggable="true"
									ondragstart={(e) => {
										if (!e.dataTransfer) return;
										e.dataTransfer.setData(FACTS_MEASURE_MIME, col.name);
										e.dataTransfer.effectAllowed = 'copy';
									}}
									title="Drag into a measure group’s Content column"
									data-testid="workbench-fact-measure-chosen-row"
								>
									<GripVertical
										class="h-3 w-3 shrink-0 text-muted-foreground/60"
										aria-hidden="true"
									/>
									<Sigma class="h-3 w-3 shrink-0 text-primary" aria-hidden="true" />
									<span class="min-w-0 flex-1 truncate font-mono">{col.name}</span>
								</div>
							{/each}
							{#if factsSelectedMeasures.size === 0}
								<p
									class="rounded border border-dashed p-2 text-center text-[10px]"
									style:border-color="hsl(var(--border))"
									style:color="hsl(var(--muted-foreground))"
								>
									No measures picked. Turn edit on to choose some.
								</p>
							{/if}
						</div>
					{:else}
						<p
							class="rounded border border-dashed p-3 text-center text-[11px]"
							style:border-color="hsl(var(--border))"
							style:color="hsl(var(--muted-foreground))"
						>
							No fact table picked. Turn edit on to choose one.
						</p>
					{/if}
				{:else if !factsTableConfirmed}
					<!-- States 1 + 2 — pick the fact table.  When a row is
				     selected, a Confirm CTA appears.  Measures section is
				     gated (greyed stub) until confirm. -->
					<p class="text-[10px] tracking-wide uppercase" style:color="hsl(var(--muted-foreground))">
						Step 1 — pick the fact table (one per cube).
					</p>
					<div class="relative shrink-0">
						<Search
							class="pointer-events-none absolute top-1/2 left-2 h-3 w-3 -translate-y-1/2 opacity-50"
							aria-hidden="true"
						/>
						<input
							type="search"
							bind:value={factsTableSearch}
							placeholder="Search tables…"
							aria-label="Search fact tables"
							class="h-7 w-full rounded border bg-elev-1 py-1 pr-2 pl-7 text-[11px] focus:border-ring focus:ring-1 focus:ring-ring focus:outline-none"
							style:border-color="hsl(var(--border))"
						/>
					</div>
					{@const _q = factsTableSearch.trim().toLowerCase()}
					{@const filteredTables = _q
						? tablesOnCanvas.filter((t) => {
								const full = (t.schema ? `${t.schema}.` : '') + t.name;
								return full.toLowerCase().includes(_q);
							})
						: tablesOnCanvas}
					<p class="shrink-0 text-[10px]" style:color="hsl(var(--muted-foreground))">
						{#if _q}
							Showing {filteredTables.length} of {tablesOnCanvas.length}
						{:else}
							{tablesOnCanvas.length}
							{tablesOnCanvas.length === 1 ? 'table' : 'tables'} available
						{/if}
					</p>
					<div
						class="flex min-h-0 flex-col overflow-hidden rounded border bg-elev-1"
						style:border-color="hsl(var(--border))"
					>
						<div
							class="flex shrink-0 items-center gap-2 border-b bg-elev-1 px-2 py-1.5 text-[9px] font-semibold tracking-wider uppercase"
							style:border-color="hsl(var(--border))"
							style:color="hsl(var(--muted-foreground))"
						>
							<span class="w-4 shrink-0"></span>
							<span class="min-w-0 flex-1">Fact Table Name</span>
							<span class="shrink-0">Columns</span>
						</div>
						<div class="flex flex-col overflow-y-auto">
							{#if filteredTables.length === 0}
								<p class="p-3 text-center text-[11px]" style:color="hsl(var(--muted-foreground))">
									No tables match "{factsTableSearch}".
								</p>
							{:else}
								{#each filteredTables as t (t.id)}
									{@const isSel = t.id === factsSelectedTableId}
									<label
										class="flex cursor-pointer items-center gap-2 border-b px-2 py-1.5 text-[11px] last:border-b-0 hover:bg-accent/40 {isSel
											? 'bg-accent'
											: ''}"
										style:border-color="hsl(var(--border))"
									>
										<input
											type="radio"
											name="fact-table-proto"
											checked={isSel}
											onchange={() => {
												factsSelectedTableId = t.id;
												factsSelectedMeasures.clear();
											}}
											class="h-3 w-3 shrink-0"
										/>
										<span class="min-w-0 flex-1 truncate font-mono">
											{t.schema ? `${t.schema}.` : ''}{t.name}
										</span>
										<span class="shrink-0 font-mono text-[10px] tabular-nums opacity-70">
											{t.columns.length}
										</span>
									</label>
								{/each}
							{/if}
						</div>
					</div>
					<!-- Confirm CTA appears once a row is selected (state 2). -->
					{#if chosenTable}
						<div class="mt-1 flex shrink-0 justify-end">
							<button
								type="button"
								onclick={() => {
									factsTableConfirmed = true;
								}}
								class="inline-flex items-center gap-1 rounded-full border border-primary bg-primary px-2.5 py-1 text-[10px] font-semibold tracking-wider text-primary-foreground uppercase hover:bg-primary/90"
							>
								Confirm fact table
							</button>
						</div>
					{/if}
				{:else if chosenTable}
					<!-- State 3 — confirmed.  Fact collapses to a one-row summary
				     with a Change pill; Measures becomes the live surface. -->
					<p class="text-[10px] tracking-wide uppercase" style:color="hsl(var(--muted-foreground))">
						Fact table
					</p>
					<div
						class="flex items-center gap-1.5 rounded border bg-elev-2 px-1.5 py-1 text-[11px]"
						style:border-color="hsl(var(--border))"
					>
						<Database class="h-3 w-3 shrink-0 text-primary" aria-hidden="true" />
						<span class="min-w-0 flex-1 truncate font-mono">
							{chosenTable.schema ? `${chosenTable.schema}.` : ''}{chosenTable.name}
						</span>
						{#if factsEditMode}
							<!-- Change pill — only visible while the whole pane is
						     in edit mode.  Resets confirmation so the table
						     picker reopens above. -->
							<button
								type="button"
								onclick={() => {
									factsTableConfirmed = false;
								}}
								class="inline-flex shrink-0 items-center gap-1 rounded-full border border-border px-1.5 py-0.5 text-[9px] font-semibold tracking-wider text-muted-foreground uppercase hover:bg-accent hover:text-accent-foreground"
								title="Change fact table"
							>
								<Pencil class="h-2.5 w-2.5" aria-hidden="true" />
								Change
							</button>
						{/if}
					</div>

					<!-- Measures section — own header + Edit pill. -->
					<div class="mt-2 flex shrink-0 items-center justify-between">
						<div class="flex items-center gap-1.5">
							<Sigma class="h-3 w-3 shrink-0 text-primary" aria-hidden="true" />
							<span
								class="text-[10px] font-semibold tracking-wider uppercase"
								style:color="hsl(var(--muted-foreground))"
							>
								Measures
							</span>
							<span class="font-mono text-[10px] opacity-60">
								· {factsSelectedMeasures.size}/{numericCols.length}
							</span>
						</div>
					</div>
					{#if numericCols.length === 0}
						<p
							class="rounded border border-dashed p-2 text-center text-[10px]"
							style:border-color="hsl(var(--border))"
							style:color="hsl(var(--muted-foreground))"
						>
							No numeric columns on this fact table.
						</p>
					{:else}
						<!-- All / Clear bulk replace — same shape as the dim
					     Attributes pane.  Provides the "want most" /
					     "want few" shortcuts for big fact tables. -->
						<div class="flex shrink-0 items-center gap-1.5 text-[10px]">
							<button
								type="button"
								onclick={() => {
									factsSelectedMeasures.clear();
									for (const c of numericCols) factsSelectedMeasures.add(c.name);
								}}
								class="rounded px-1.5 py-0.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground disabled:opacity-50"
								disabled={factsSelectedMeasures.size === numericCols.length}
							>
								All
							</button>
							<button
								type="button"
								onclick={() => factsSelectedMeasures.clear()}
								class="rounded px-1.5 py-0.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground disabled:opacity-50"
								disabled={factsSelectedMeasures.size === 0}
							>
								Clear
							</button>
						</div>
						<div class="flex flex-col gap-1">
							{#each numericCols as col (col.name)}
								{@const checked = factsSelectedMeasures.has(col.name)}
								<!-- Plain div (not <label>) so the inner checkbox
							     doesn't eat the mousedown that initiates a
							     drag.  Grip + name area are the drag source;
							     the checkbox is its own click target. -->
								<div
									class="flex items-center gap-1.5 rounded border bg-elev-2 px-1.5 py-1 text-[11px] hover:bg-accent/30 {checked
										? 'cursor-grab active:cursor-grabbing'
										: ''}"
									style:border-color="hsl(var(--border))"
									draggable={checked}
									ondragstart={(e) => {
										if (!checked || !e.dataTransfer) return;
										e.dataTransfer.setData(FACTS_MEASURE_MIME, col.name);
										e.dataTransfer.effectAllowed = 'copy';
									}}
									title={checked
										? 'Drag into a measure group’s Content column'
										: 'Tick first, then drag into a measure group'}
									data-testid="workbench-fact-measure-row"
								>
									<GripVertical
										class="h-3 w-3 shrink-0 text-muted-foreground/60 {checked ? '' : 'opacity-40'}"
										aria-hidden="true"
									/>
									<input
										type="checkbox"
										{checked}
										onchange={(e) => {
											if (e.currentTarget.checked) factsSelectedMeasures.add(col.name);
											else factsSelectedMeasures.delete(col.name);
										}}
										class="h-3 w-3 shrink-0 cursor-pointer"
										aria-label="Pick {col.name}"
									/>
									<!-- Name area is plain spans (no button) so the
								     dragstart on the parent div is never eaten by
								     a click target.  Toggling the checkbox is
								     the input's own click handler. -->
									<span
										role="presentation"
										onclick={() => {
											if (factsSelectedMeasures.has(col.name))
												factsSelectedMeasures.delete(col.name);
											else factsSelectedMeasures.add(col.name);
										}}
										class="flex min-w-0 flex-1 cursor-pointer items-center gap-1.5"
									>
										<span class="min-w-0 flex-1 truncate font-mono">{col.name}</span>
										{#if col.sqlType}
											<span
												class="shrink-0 font-mono text-[9px] tracking-wide uppercase opacity-50"
											>
												{col.sqlType}
											</span>
										{/if}
									</span>
								</div>
							{/each}
						</div>
					{/if}
				{/if}
			</div>
		</section>
	{:else if kind === 'groups'}
		<!-- Pane 2: MEASURE GROUPS — fact-side analogue of dim Hierarchies.
	     Two sub-columns, mirroring hierarchyTwoColumnBody exactly:
	       Name    — list of groups (w-2/5, with add-group + drag-to-
	                 create from a chosen measure)
	       Content — the picked group's included measures (flex-1, drop
	                 zone that accepts measures from Fact & Measures and
	                 cross-group moves).
	     Note: this body is a near-verbatim copy of hierarchyTwoColumnBody
	     rather than a shared snippet — the dim and fact sides use
	     different state shapes / drop MIMEs / row chrome, so factoring
	     them into one generic snippet would balloon the call-signature
	     for no real reuse win.  Calc storage moved to cube-scope
	     factsCalcs and rendered by its own pane (kind === 'calculations').
	     v2 (Mondrian 4): a measure group is its own authoring surface —
	     pick a fact table later per-MG, not up-front for the cube.  Add-MG
	     no longer gates on a chosen fact + picked measures; it just spawns
	     an empty named row in label-edit mode. -->
		{@const factsGroupsLocked = false}
		{@const selectedGroup = factsMeasureGroups.find((g) => g.id === factsSelectedGroupId) ?? null}
		<!-- 2x width vs the calculations pane: MG editor now hosts the
			     fact + measures + dimensions sub-columns side-by-side, so it
			     needs the bulk of the horizontal real estate. -->
		<section
			class="flex min-w-0 flex-[2] flex-col overflow-hidden rounded-md border bg-elev-1 transition-colors {isReorderTarget
				? 'border-primary'
				: ''} {isReorderSource ? 'opacity-50' : ''}"
			style:border-color={isReorderTarget ? '' : 'hsl(var(--border))'}
			ondragover={(e) => handleFactsPaneDragOver(e, idx)}
			ondragleave={() => handleFactsPaneDragLeave(idx)}
			ondrop={(e) => handleFactsPaneDrop(e, idx)}
			data-pane-index={idx}
			data-pane-kind={kind}
		>
			<header
				class="flex shrink-0 items-center justify-between gap-2 border-b bg-elev-1 px-2.5 py-2"
				style:border-color="hsl(var(--border))"
			>
				<div class="flex min-w-0 items-center gap-1.5">
					<span
						role="button"
						tabindex="0"
						draggable="true"
						ondragstart={(e) => handleFactsPaneDragStart(e, idx)}
						ondragend={handleFactsPaneDragEnd}
						class="-ml-1 shrink-0 cursor-grab rounded p-1 text-muted-foreground hover:bg-accent hover:text-accent-foreground active:cursor-grabbing"
						aria-label="Drag to reorder this pane"
						title="Drag to reorder"
					>
						<GripVertical class="h-3 w-3" aria-hidden="true" />
					</span>
					<ListTree class="h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
					<span class="text-[11px] font-semibold tracking-wider uppercase"> Measure Groups </span>
					{#if chosenTable}
						<span class="font-mono text-[10px] text-muted-foreground">
							· {factsMeasureGroups.length}
						</span>
					{/if}
				</div>
				<div class="flex items-center gap-1">
					<!-- Identical to the dim Hierarchies pane: no Edit pill —
				     groups are always editable.  An Add button creates a
				     new (empty, single-measure-friendly) group; the rest
				     of the editing affordances (rename, remove, drag) are
				     always visible. -->
					<button
						type="button"
						onclick={() => addFactsMeasureGroup()}
						disabled={factsGroupsLocked}
						class="inline-flex items-center gap-1 rounded-full border border-border px-2 py-0.5 text-[10px] font-semibold tracking-wider text-muted-foreground uppercase hover:bg-accent hover:text-accent-foreground disabled:cursor-not-allowed disabled:opacity-50"
						title="Add a measure group"
					>
						<Plus class="h-2.5 w-2.5" aria-hidden="true" />
						Add
					</button>
				</div>
			</header>
			<div class="flex min-h-0 flex-1 flex-col overflow-hidden bg-card p-2">
				{#if true}
					<!-- Two-column body — Name (w-2/5) + Content (flex-1),
				     same proportions and shell as hierarchyTwoColumnBody.
				     Mondrian 4 reverse: the user can author measure groups
				     before picking measures or even a fact table — those
				     get bound later, per MG, in the row's detail. -->
					<div class="flex min-h-0 flex-1 gap-2">
						<!-- ── NAME COLUMN ─────────────────────────────────────── -->
						<!-- Equal-width 3-column layout once MG pane spans 2x:
							     Name | Fact+Measures | Dimensions all flex-1 so a wide
							     workbench reads as a clean tabular form. -->
						<div class="flex min-w-0 flex-1 flex-col gap-1">
							<span
								class="truncate text-[10px] font-semibold tracking-wider uppercase"
								style:color="hsl(var(--muted-foreground))"
							>
								Name
							</span>
							<div
								class="flex min-h-0 flex-1 flex-col gap-1 overflow-y-auto rounded px-0.5"
								data-testid="workbench-pane-measure-groups-name-col"
							>
								{#if factsMeasureGroups.length === 0}
									<button
										type="button"
										onclick={() => addFactsMeasureGroup()}
										class="flex flex-1 flex-col items-center justify-center gap-1 rounded border border-dashed p-3 text-center text-[11px] hover:bg-accent/30"
										style:border-color="hsl(var(--border))"
										style:color="hsl(var(--muted-foreground))"
										title="Create a new measure group"
									>
										<Plus class="h-4 w-4" aria-hidden="true" />
										<span>New measure group</span>
									</button>
								{:else}
									<ul class="flex flex-col gap-1">
										{#each factsMeasureGroups as g (g.id)}
											{@const isSel = isFocused('mg', g.id)}
											<!-- Mirrors hierarchyListRow: <li role=button + tabindex=0,
												     icon + (input when editing, else span with dblclick-to-rename)
												     + count + delete X.  Drag-into-calc bindings (draggable +
												     ondragstart + title) are gated by ALLOW_GROUP_DROP_ON_CALC
												     so flipping the const back to `true` re-enables BOTH the
												     source-side drag affordance and the calc-pane drop target
												     in lockstep — nothing visual happens while the flag is off. -->
											<li
												role="button"
												tabindex="0"
												onclick={() => {
													factsSelectedGroupId = g.id;
													focus('mg', g.id);
												}}
												onkeydown={(e) => {
													if (e.key === 'Enter' || e.key === ' ') {
														e.preventDefault();
														factsSelectedGroupId = g.id;
														focus('mg', g.id);
													}
												}}
												class="flex cursor-pointer items-center gap-1.5 rounded border bg-elev-2 px-2 py-1 text-[11px] transition-colors {isSel
													? 'workbench-row-selected'
													: 'border-border hover:bg-accent/40'}"
												data-testid="workbench-measure-group-list-row"
												data-group-id={g.id}
												draggable={ALLOW_GROUP_DROP_ON_CALC}
												ondragstart={(e) => {
													if (!ALLOW_GROUP_DROP_ON_CALC) return;
													if (!e.dataTransfer) return;
													e.dataTransfer.setData(FACTS_MEASURE_GROUP_MIME, g.id);
													e.dataTransfer.effectAllowed = 'copy';
												}}
												title={ALLOW_GROUP_DROP_ON_CALC
													? 'Drag into a calculation’s formula'
													: undefined}
											>
												<ListTree
													class="h-3 w-3 shrink-0 text-muted-foreground"
													aria-hidden="true"
												/>
												{#if factsEditingMeasureGroupId === g.id}
													<input
														type="text"
														value={g.name}
														oninput={(e) => renameFactsMeasureGroup(g.id, e.currentTarget.value)}
														onclick={(e) => e.stopPropagation()}
														onkeydown={(e) => {
															e.stopPropagation();
															if (e.key === 'Enter' || e.key === 'Escape') {
																(e.currentTarget as HTMLInputElement).blur();
															}
														}}
														onblur={() => {
															if (focusGroupId === g.id) focusGroupId = null;
															factsEditingMeasureGroupId = null;
														}}
														placeholder="Group name"
														aria-label="Group name"
														class="h-5 min-w-0 flex-1 rounded border bg-background px-1 font-medium focus:border-ring focus:ring-1 focus:ring-ring focus:outline-none"
														style:border-color="hsl(var(--border))"
														use:autoFocus={focusGroupId === g.id ||
															factsEditingMeasureGroupId === g.id}
													/>
												{:else}
													<span
														role="button"
														tabindex="0"
														ondblclick={(e) => {
															e.stopPropagation();
															factsEditingMeasureGroupId = g.id;
														}}
														onkeydown={(e) => {
															if (e.key === 'F2' || e.key === 'Enter') {
																e.preventDefault();
																e.stopPropagation();
																factsEditingMeasureGroupId = g.id;
															}
														}}
														class="block min-w-0 flex-1 truncate leading-5 font-medium"
														title="Double-click to rename"
													>
														{g.name || 'Untitled group'}
													</span>
												{/if}
												<span class="shrink-0 font-mono text-[9px] text-muted-foreground">
													{g.measureColumns.length}
												</span>
												{#if !g.factTableId || (g.dimensionLinks ?? []).length === 0}
													<!-- MG incomplete — Mondrian 4 requires every measure
														     group to bind to ONE fact table and link to 1+
														     dimensions.  Warning flags whichever is missing. -->
													<span
														class="shrink-0 text-warning"
														title={!g.factTableId && (g.dimensionLinks ?? []).length === 0
															? 'Pick a fact table and at least one dimension'
															: !g.factTableId
																? 'No fact table picked — pick one in the editor'
																: 'No dimensions linked yet — pick at least one in the editor'}
														aria-label="Measure group is incomplete"
														data-testid="workbench-measure-group-incomplete"
													>
														<AlertCircle class="h-3 w-3" aria-hidden="true" />
													</span>
												{/if}
												<button
													type="button"
													onclick={(e) => {
														e.stopPropagation();
														factsEditingMeasureGroupId = g.id;
														focusGroupId = g.id;
													}}
													class="shrink-0 rounded p-0.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground"
													aria-label="Rename group"
													title="Rename group"
													data-testid="workbench-measure-group-rename"
												>
													<Pencil class="h-2.5 w-2.5" aria-hidden="true" />
												</button>
												<button
													type="button"
													onclick={(e) => {
														e.stopPropagation();
														removeFactsMeasureGroup(g.id);
													}}
													class="shrink-0 rounded p-0.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground"
													aria-label="Remove group"
													title="Remove group"
												>
													<X class="h-2.5 w-2.5" aria-hidden="true" />
												</button>
											</li>
										{/each}
									</ul>
								{/if}
							</div>
						</div>
						<!-- ── CONTENT COLUMN ──────────────────────────────────── -->
						<!-- Per-MG editor.  A measure group binds to ONE fact table
							     (Mondrian 4 lets different MGs in the same cube point at
							     different facts) and links to 1+ shared dimensions.  This
							     column hosts all three picks for the selected MG: fact
							     table radio, measures checklist (cols of the chosen fact),
							     dim links checklist (every schema dim).  Checkbox UX over
							     drag/drop — easier to reverse, easier to see what's on
							     and what's off at a glance. -->
						{@render paneMeasureGroupContent(selectedGroup)}
					</div>
				{/if}
			</div>
		</section>
	{:else if kind === 'calculations'}
		<!-- Pane 3: CALCULATIONS — cube-scope <CalculatedMember> editor.
	     Mondrian 4 puts these directly under <Cube>, not nested in a
	     measure group, so the pane lists every calc in the cube as a
	     stacked card list and a calc can freely reference any measure
	     on any group.  Selection is pane-local (factsSelectedCalcId).
	     v2 (post-MG-restructure): the cube no longer has a single fact
	     table — measures live per-MG.  Builder is accessible at all
	     times; the chip palette draws from the union of every MG's
	     measureColumns. -->
		<!-- Builder is always accessible — measure tokens come from
			     drag-from-MG-chip via FACTS_MEASURE_MIME. -->
		<section
			class="flex min-w-0 flex-1 flex-col overflow-hidden rounded-md border bg-elev-1 transition-colors {isReorderTarget
				? 'border-primary'
				: ''} {isReorderSource ? 'opacity-50' : ''}"
			style:border-color={isReorderTarget ? '' : 'hsl(var(--border))'}
			ondragover={(e) => handleFactsPaneDragOver(e, idx)}
			ondragleave={() => handleFactsPaneDragLeave(idx)}
			ondrop={(e) => handleFactsPaneDrop(e, idx)}
			data-pane-index={idx}
			data-pane-kind={kind}
		>
			<header
				class="flex shrink-0 items-center justify-between gap-2 border-b bg-elev-1 px-2.5 py-2"
				style:border-color="hsl(var(--border))"
			>
				<div class="flex min-w-0 flex-1 items-center gap-1.5">
					<span
						role="button"
						tabindex="0"
						draggable="true"
						ondragstart={(e) => handleFactsPaneDragStart(e, idx)}
						ondragend={handleFactsPaneDragEnd}
						class="-ml-1 shrink-0 cursor-grab rounded p-1 text-muted-foreground hover:bg-accent hover:text-accent-foreground active:cursor-grabbing"
						aria-label="Drag to reorder this pane"
						title="Drag to reorder"
					>
						<GripVertical class="h-3 w-3" aria-hidden="true" />
					</span>
					<Sigma class="h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
					<span class="min-w-0 truncate text-[11px] font-semibold tracking-wider uppercase">
						Calculations
					</span>
					{#if chosenTable}
						<span class="shrink-0 font-mono text-[10px] text-muted-foreground">
							· {factsCalcs.length}
						</span>
					{/if}
				</div>
				<div class="flex shrink-0 items-center justify-end gap-1">
					<button
						type="button"
						onclick={() => addFactsCalc()}
						class="inline-flex items-center gap-1 rounded-full border border-border px-2 py-0.5 text-[10px] font-semibold tracking-wider text-muted-foreground uppercase hover:bg-accent hover:text-accent-foreground"
						title="Add a calculated measure"
					>
						<Plus class="h-2.5 w-2.5" aria-hidden="true" />
						<span class="hidden md:inline">Add calc</span>
					</button>
					<!-- Pane-level Edit pill removed — calcs edit per-card
				     via the pencil icon in each calc header. -->
				</div>
			</header>
			<div class="flex min-h-0 flex-1 flex-col overflow-hidden bg-card p-2">
				{#if factsCalcs.length === 0}
					<button
						type="button"
						onclick={() => addFactsCalc()}
						class="flex flex-1 flex-col items-center justify-center gap-1 rounded border border-dashed p-3 text-center text-[11px] hover:bg-accent/30"
						style:border-color="hsl(var(--border))"
						style:color="hsl(var(--muted-foreground))"
						title="Add the first calculated measure"
					>
						<Plus class="h-4 w-4" aria-hidden="true" />
						<span>New calculated measure</span>
						<span class="text-[9px] opacity-70">e.g. Profit = [Sales] − [Cost]</span>
					</button>
				{:else}
					<div
						class="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto rounded border bg-elev-2 p-1.5"
						style:border-color="hsl(var(--border))"
					>
						{#each factsCalcs as c (c.id)}
							{@const mode = calcMode(c)}
							{@const needsMeasure = calcNeedsMeasure(c)}
							{@const isSelected = isFocused('calc', c.id)}
							{@const isEditingThis = factsEditingCalcId === c.id}
							<section
								class="flex flex-col gap-1.5 rounded border bg-elev-2 p-1.5 transition-colors {isSelected
									? 'workbench-row-selected-outline'
									: 'border-border'}"
								style:border-color={isSelected ? '' : 'hsl(var(--border))'}
								onclick={() => {
									selectFactsCalc(c.id);
								}}
								onkeydown={(e) => {
									if (e.key === 'Enter' || e.key === ' ') {
										e.preventDefault();
										selectFactsCalc(c.id);
									}
								}}
								role="button"
								tabindex="0"
							>
								<header class="flex items-center gap-1.5">
									<Sigma class="h-3 w-3 shrink-0 text-primary" aria-hidden="true" />
									{#if isEditingThis}
										<input
											type="text"
											value={c.name}
											oninput={(e) => renameFactsCalc(c.id, e.currentTarget.value)}
											onclick={(e) => e.stopPropagation()}
											placeholder="Name the result (e.g. Profit)"
											class="h-5 min-w-0 flex-1 rounded border border-transparent bg-transparent px-1 text-[11px] font-medium focus:border-border focus:bg-elev-1"
											aria-label="Calculation name"
										/>
										<button
											type="button"
											onclick={(e) => {
												e.stopPropagation();
												removeFactsCalc(c.id);
											}}
											class="shrink-0 rounded p-0.5 text-muted-foreground hover:bg-destructive/15 hover:text-destructive"
											aria-label="Remove calculation"
											title="Remove"
										>
											<X class="h-3 w-3" aria-hidden="true" />
										</button>
									{:else}
										<span class="min-w-0 flex-1 truncate text-[11px] font-medium">
											{c.name || 'Untitled calculation'}
										</span>
									{/if}
									<!-- Pencil / Check toggle — flips THIS calc into
								     edit mode without affecting others.  Read-only
								     by default so the user can review without
								     accidentally mutating tokens. -->
									<button
										type="button"
										onclick={(e) => {
											e.stopPropagation();
											factsEditingCalcId = isEditingThis ? null : c.id;
										}}
										class="shrink-0 rounded p-0.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground"
										aria-label={isEditingThis ? 'Done editing' : 'Edit calculation'}
										title={isEditingThis ? 'Done editing' : 'Edit'}
									>
										{#if isEditingThis}
											<Check class="h-3 w-3" aria-hidden="true" />
										{:else}
											<Pencil class="h-3 w-3" aria-hidden="true" />
										{/if}
									</button>
								</header>
								{#if isEditingThis}
									<div
										class="inline-flex shrink-0 self-start overflow-hidden rounded border text-[9px] font-semibold tracking-wider uppercase"
										style:border-color="hsl(var(--border))"
										role="tablist"
									>
										<button
											type="button"
											role="tab"
											aria-selected={mode === 'build'}
											onclick={(e) => {
												e.stopPropagation();
												setCalcMode(c.id, 'build');
											}}
											class="px-2 py-0.5 {mode === 'build'
												? 'bg-primary text-primary-foreground'
												: 'bg-muted/40 text-muted-foreground hover:bg-accent/40'}"
										>
											Build
										</button>
										<button
											type="button"
											role="tab"
											aria-selected={mode === 'expression'}
											onclick={(e) => {
												e.stopPropagation();
												setCalcMode(c.id, 'expression');
											}}
											class="border-l px-2 py-0.5 {mode === 'expression'
												? 'bg-primary text-primary-foreground'
												: 'text-muted-foreground hover:bg-accent/40'}"
											style:border-color="hsl(var(--border))"
										>
											Expression
										</button>
									</div>
								{/if}
								{#if mode === 'build'}
									<div
										class="flex flex-wrap items-center gap-1 rounded border-2 border-dashed bg-elev-2 p-1.5 font-mono text-[11px] transition-colors hover:bg-accent/10"
										style:border-color="hsl(var(--border))"
										ondragover={(e) => {
											if (!needsMeasure) return;
											const tt = e.dataTransfer?.types ?? [];
											// Group-drop is feature-flagged off; only accept it
											// when `ALLOW_GROUP_DROP_ON_CALC` is true.
											const ok =
												Array.from(tt).includes(FACTS_MEASURE_MIME) ||
												(ALLOW_GROUP_DROP_ON_CALC &&
													Array.from(tt).includes(FACTS_MEASURE_GROUP_MIME));
											if (!ok) return;
											e.preventDefault();
											if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy';
										}}
										ondrop={(e) => {
											if (!needsMeasure) return;
											const tt = e.dataTransfer?.types ?? [];
											if (Array.from(tt).includes(FACTS_MEASURE_MIME)) {
												e.preventDefault();
												e.stopPropagation();
												const col = e.dataTransfer?.getData(FACTS_MEASURE_MIME);
												if (col) addCalcMeasure(c.id, col);
												return;
											}
											// Group-drop branch — guarded by the feature flag.
											if (
												ALLOW_GROUP_DROP_ON_CALC &&
												Array.from(tt).includes(FACTS_MEASURE_GROUP_MIME)
											) {
												e.preventDefault();
												e.stopPropagation();
												const groupId = e.dataTransfer?.getData(FACTS_MEASURE_GROUP_MIME);
												const group = factsMeasureGroups.find((mg) => mg.id === groupId);
												if (!group) return;
												// Expand every measure in the group into tokens
												// joined by `+`.  Lets the user quickly build a
												// sum-of-group calc; they can rewire operators
												// after the fact via the per-token undo.
												group.measureColumns.forEach((name, i) => {
													if (i > 0) addCalcOperator(c.id, '+');
													addCalcMeasure(c.id, name, true);
												});
											}
										}}
										role="region"
										aria-label="Calculation formula — drop measures or click the picker"
									>
										{#if c.tokens.length === 0}
											<span class="text-[10px] italic" style:color="hsl(var(--muted-foreground))">
												Drop a measure here, or click one below.
											</span>
										{:else}
											{#each c.tokens as t, i (i)}
												{#if t.kind === 'measure'}
													<span
														class="rounded px-1.5 py-0.5 {t.fromGroup
															? 'bg-primary/15 text-primary'
															: ''}"
														style:background-color={t.fromGroup ? '' : 'hsl(150 55% 88%)'}
														style:color={t.fromGroup ? '' : 'hsl(150 55% 22%)'}
													>
														[{t.name}]
													</span>
												{:else}
													<span class="px-0.5 opacity-70">{formatCalcOp(t.op)}</span>
												{/if}
											{/each}
										{/if}
										{#if isEditingThis && c.tokens.length > 0}
											<button
												type="button"
												onclick={(e) => {
													e.stopPropagation();
													popCalcToken(c.id);
												}}
												class="ml-auto shrink-0 rounded px-1 py-0.5 text-[10px] text-muted-foreground hover:bg-accent hover:text-accent-foreground"
												title="Undo last token"
											>
												←
											</button>
										{/if}
									</div>
									{#if isEditingThis}
										{#if needsMeasure}
											<!-- Picker dropped — the user drags measures (or
										     groups) from the Fact pane / Measure Groups
										     content directly into the formula chip area
										     above. -->
											<span
												class="text-[9px] tracking-wider uppercase italic"
												style:color="hsl(var(--muted-foreground))"
											>
												Drag a measure or group into the formula above.
											</span>
										{:else}
											<div class="flex items-center gap-1.5">
												<span
													class="text-[9px] tracking-wider uppercase"
													style:color="hsl(var(--muted-foreground))"
												>
													Operator
												</span>
												<select
													value=""
													onclick={(e) => e.stopPropagation()}
													onchange={(e) => {
														const v = e.currentTarget.value;
														if (v === '+' || v === '-' || v === '*' || v === '/') {
															addCalcOperator(c.id, v);
															e.currentTarget.value = '';
														}
													}}
													class="h-6 rounded border bg-elev-1 px-1 font-mono text-[11px]"
													style:border-color="hsl(var(--border))"
													aria-label="Operator"
												>
													<option value="" disabled selected>Choose…</option>
													<option value="+">+ (add)</option>
													<option value="-">− (subtract)</option>
													<option value="*">× (multiply)</option>
													<option value="/">÷ (divide)</option>
												</select>
												<button
													type="button"
													onclick={(e) => {
														e.stopPropagation();
														clearCalcTokens(c.id);
													}}
													class="ml-auto rounded px-1.5 py-0.5 text-[10px] text-muted-foreground hover:bg-accent hover:text-accent-foreground"
													title="Clear formula"
												>
													Clear
												</button>
											</div>
										{/if}
									{/if}
								{:else if isEditingThis}
									<textarea
										value={c.formula ?? ''}
										oninput={(e) => updateCalcFormula(c.id, e.currentTarget.value)}
										onclick={(e) => e.stopPropagation()}
										placeholder="e.g. IIF([Cost] = 0, NULL, [Profit] / [Cost])"
										rows="3"
										class="min-h-0 w-full resize-y rounded border bg-muted/40 px-1.5 py-1 font-mono text-[11px] focus:border-ring focus:ring-1 focus:ring-ring focus:outline-none"
										style:border-color="hsl(var(--border))"
										aria-label="Free-text formula"
									></textarea>
								{:else}
									<div
										class="rounded border bg-elev-1 px-1.5 py-1 font-mono text-[11px]"
										style:border-color="hsl(var(--border))"
										style:color={(c.formula ?? '') ? 'inherit' : 'hsl(var(--muted-foreground))'}
									>
										{c.formula || 'No formula yet.'}
									</div>
								{/if}
							</section>
						{/each}
					</div>
				{/if}
			</div>
		</section>
	{/if}
{/each}

{#snippet paneMeasureGroupContent(g: WorkbenchMeasureGroup | null)}
	<!-- Outer flex-[3] (vs the Name column's flex-1) shifts the horizontal
	     budget so Fact+Measures and Dimensions each get ~37.5% of the MG
	     pane instead of a flat 1/3.  The Name column (list of MG names)
	     doesn't need that much room; the two editor boxes do.

	     Layout: LEFT column stacks Fact Table (top) + Measures (bottom)
	     as two independent boxes with their own Confirm/Edit; RIGHT column
	     is the Dimensions box, unchanged shape from before.  Fact →
	     Measures is a sequential gate: measures locked until fact is
	     confirmed.  #972 fixed the visibility of CONFIRM; this refactor
	     fixes the model itself. -->
	<div class="flex min-h-0 min-w-0 flex-[3] flex-col gap-1">
		<span
			class="truncate text-[10px] font-semibold tracking-wider uppercase"
			style:color="hsl(var(--muted-foreground))"
		>
			Content
		</span>
		{#if !g}
			<div
				class="flex flex-1 items-center justify-center rounded border border-dashed p-3 text-center text-[11px]"
				style:border-color="hsl(var(--border))"
				style:color="hsl(var(--muted-foreground))"
			>
				Pick a group on the left.
			</div>
		{:else}
			{@const factTable = g.factTableId
				? (store.doc.tables.find((t) => t.id === g.factTableId) ?? null)
				: null}
			{@const dimLinks = g.dimensionLinks ?? []}
			{@const canConfirmFact = !!g.factTableId}
			{@const canConfirmMeasures = !!g.factConfirmed && g.measureColumns.length > 0}
			{@const factSaved = !!g.factConfirmed && !!factTable}
			{@const measuresSaved = !!g.measuresConfirmed && !!factTable && g.measureColumns.length > 0}
			<div
				class="flex min-h-0 flex-1 gap-2 overflow-hidden"
				data-testid="workbench-pane-measure-group-editor"
				data-group-id={g.id}
			>
				<!-- ── LEFT column: FACT TABLE (top) + MEASURES (bottom) ─── -->
				<div class="flex min-h-0 flex-1 flex-col gap-2">
					<!-- Box 1 — FACT TABLE. Scoped to canvas tables (#975):
					     `store.doc.tables` is the pool the user drew on the
					     schema canvas — that IS the fact-table candidate list.
					     Was reading from `store.sourceTables` (the whole
					     warehouse), which offered facts you hadn't even joined
					     into the schema. -->
					<section
						class="flex min-h-0 flex-col gap-2 rounded border bg-elev-2 p-2"
						style:border-color="hsl(var(--border))"
						data-testid="workbench-pane-measure-group-fact"
					>
						<div
							class="flex items-center justify-between gap-2 border-b pb-1.5"
							style:border-color="hsl(var(--border))"
						>
							<span
								class="flex items-center gap-1.5 text-[10px] font-semibold tracking-wider uppercase"
								style:color="hsl(var(--muted-foreground))"
							>
								Fact Table
								{#if !factSaved && store.doc.tables.length > 0}
									<!-- Count hint — signals a scrollable list at a
									     glance without waiting for the user to notice
									     the scrollbar. -->
									<span class="font-mono text-[9px] normal-case opacity-70">
										· {store.doc.tables.length} on canvas
									</span>
								{/if}
							</span>
							{#if factSaved}
								<button
									type="button"
									onclick={() => {
										// Rebinding wipes measures — warn if any are picked.
										// Native confirm() is fine for now; swap to a themed
										// dialog when the DS Dialog primitive is migrated in.
										const dirty = !!g.measuresConfirmed || g.measureColumns.length > 0;
										if (
											dirty &&
											!confirm(
												'Rebinding the fact table will clear your measures and captions. Continue?'
											)
										) {
											return;
										}
										g.factConfirmed = false;
										if (dirty) {
											g.measuresConfirmed = false;
											g.measureColumns = [];
											g.measureCaptions = {};
										}
									}}
									class="rounded-full border border-border px-2 py-0.5 text-[9px] font-semibold tracking-wider text-muted-foreground uppercase hover:bg-accent hover:text-accent-foreground"
									data-testid="workbench-mg-fact-edit"
								>
									Edit
								</button>
							{:else}
								<button
									type="button"
									disabled={!canConfirmFact}
									onclick={() => (g.factConfirmed = true)}
									class="rounded-full bg-primary px-3 py-1 text-[10px] font-bold tracking-wider text-primary-foreground uppercase shadow-sm transition-colors hover:bg-primary/90 disabled:cursor-not-allowed disabled:bg-muted disabled:text-muted-foreground disabled:shadow-none"
									data-testid="workbench-mg-fact-confirm"
								>
									Confirm
								</button>
							{/if}
						</div>
						{#if factSaved && factTable}
							<!-- Saved row — plain treatment (no red-tinted glow),
							     matches the Dimensions saved row shape. -->
							<div
								class="flex items-center gap-2 rounded border bg-card px-2 py-1.5 text-[11px]"
								style:border-color="hsl(var(--border))"
								data-testid="workbench-mg-fact-saved-row"
							>
								<Database class="h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
								<div class="flex min-w-0 flex-1 flex-col leading-tight">
									<span class="truncate font-medium">{factTable.name}</span>
									{#if factTable.schema}
										<span
											class="truncate font-mono text-[9px]"
											style:color="hsl(var(--muted-foreground))"
										>
											{factTable.schema}.{factTable.name}
										</span>
									{/if}
								</div>
								<span
									class="shrink-0 rounded border px-1.5 py-0 font-mono text-[8px] tracking-wider uppercase"
									style:border-color="hsl(var(--border))"
									style:color="hsl(var(--muted-foreground))"
								>
									TABLE
								</span>
							</div>
						{:else}
							<!-- Edit view — scrolling radio list of canvas tables. -->
							{#if store.doc.tables.length === 0}
								<p
									class="rounded border border-dashed p-2 text-center text-[11px]"
									style:border-color="hsl(var(--border))"
									style:color="hsl(var(--muted-foreground))"
								>
									Draw tables on the schema canvas first — they'll show here.
								</p>
							{:else}
								<ul
									class="workbench-scroll flex max-h-52 flex-col gap-0.5 overflow-y-auto pr-1"
									data-testid="workbench-mg-fact-radio-list"
								>
									{#each store.doc.tables as t (t.id)}
										{@const checked = g.factTableId === t.id}
										<li>
											<label
												class="flex w-full cursor-pointer items-center gap-1.5 rounded border px-2 py-1 text-[11px] transition-colors {checked
													? 'border-primary bg-primary/10'
													: 'border-border hover:bg-accent/40'}"
												style:border-color={checked ? '' : 'hsl(var(--border))'}
											>
												<input
													type="radio"
													class="h-3 w-3"
													name="mg-{g.id}-fact"
													{checked}
													onchange={() => {
														if (checked) return;
														g.factTableId = t.id;
														// Rebind clears any prior measure picks that
														// don't exist on the newly-picked fact table.
														const cols = new Set(t.columns.map((c) => c.name));
														g.measureColumns = g.measureColumns.filter((c) => cols.has(c));
														g.measureCaptions = Object.fromEntries(
															Object.entries(g.measureCaptions ?? {}).filter(([k]) => cols.has(k))
														);
													}}
													data-testid="workbench-mg-fact-radio"
													data-table-id={t.id}
												/>
												<Database
													class="h-3 w-3 shrink-0 text-muted-foreground"
													aria-hidden="true"
												/>
												<div class="flex min-w-0 flex-1 flex-col leading-tight">
													<span class="truncate font-mono">{t.name}</span>
													{#if t.schema}
														<span
															class="truncate font-mono text-[9px]"
															style:color="hsl(var(--muted-foreground))"
														>
															{t.schema}.{t.name}
														</span>
													{/if}
												</div>
											</label>
										</li>
									{/each}
								</ul>
							{/if}
						{/if}
					</section>

					<!-- Box 2 — MEASURES. Gated on Fact confirmed; shows an
					     "Pick a fact table first" empty state until then.
					     Edit view = scrolling checkbox list of the fact's
					     columns.  Saved view = rows matching the Dimensions
					     pattern (numbered badge + editable caption + muted
					     column name below + type badge + pencil/×).
					     Captions round-trip through `<Measure caption="…">`
					     in the emitted M4 XML. -->
					<section
						class="flex min-h-0 flex-1 flex-col gap-2 rounded border bg-elev-2 p-2 transition-opacity"
						style:border-color="hsl(var(--border))"
						class:opacity-55={!g.factConfirmed}
						data-testid="workbench-pane-measure-group-measures"
					>
						<div
							class="flex items-center justify-between gap-2 border-b pb-1.5"
							style:border-color="hsl(var(--border))"
						>
							<span
								class="flex items-center gap-1.5 text-[10px] font-semibold tracking-wider uppercase"
								style:color="hsl(var(--muted-foreground))"
							>
								<!-- Sigma moved here from per-row: every saved measure is
								     an aggregation, so the icon reads clearest as a mark on
								     the *section* rather than repeated on every row.
								     Reclaims horizontal space in the row. -->
								<Sigma class="h-3 w-3 shrink-0 text-primary" aria-hidden="true" />
								<span>Measures</span>
								{#if !measuresSaved && factTable && g.factConfirmed}
									<!-- Column count of the picked fact — signals list
									     length + indicates the picker will scroll. -->
									<span class="font-mono text-[9px] normal-case opacity-70">
										· {factTable.columns.length} columns
									</span>
								{:else if measuresSaved}
									<span class="font-mono text-[9px] normal-case opacity-70">
										· {g.measureColumns.length} picked
									</span>
								{/if}
							</span>
							{#if measuresSaved}
								<button
									type="button"
									onclick={() => (g.measuresConfirmed = false)}
									class="rounded-full border border-border px-2 py-0.5 text-[9px] font-semibold tracking-wider text-muted-foreground uppercase hover:bg-accent hover:text-accent-foreground"
									data-testid="workbench-mg-measures-edit"
								>
									Edit
								</button>
							{:else}
								<button
									type="button"
									disabled={!canConfirmMeasures}
									onclick={() => (g.measuresConfirmed = true)}
									class="rounded-full bg-primary px-3 py-1 text-[10px] font-bold tracking-wider text-primary-foreground uppercase shadow-sm transition-colors hover:bg-primary/90 disabled:cursor-not-allowed disabled:bg-muted disabled:text-muted-foreground disabled:shadow-none"
									data-testid="workbench-mg-measures-confirm"
								>
									Confirm
								</button>
							{/if}
						</div>
						{#if !g.factConfirmed}
							<div
								class="flex flex-1 items-center justify-center rounded border border-dashed p-3 text-center text-[11px] italic"
								style:border-color="hsl(var(--border))"
								style:color="hsl(var(--muted-foreground))"
							>
								Pick + confirm a Fact Table first.<br />Measures come from its columns.
							</div>
						{:else if measuresSaved && factTable}
							<!-- Saved rows — Dimensions-shape (numbered badge,
							     editable caption on top, real column name below,
							     type badge, inline pencil/×). -->
							<div
								class="workbench-scroll flex min-h-0 flex-1 flex-col gap-1 overflow-y-auto pr-1"
								data-testid="workbench-mg-measures-saved-list"
							>
								{#each g.measureColumns as col (col)}
									{@const caption = g.measureCaptions?.[col] ?? col}
									{@const isEditing = editingMeasureCaption === `${g.id}::${col}`}
									{@const storeMeasure = store.measures.find(
										(mm) => mm.tableId === g.factTableId && mm.columnName === col
									)}
									{@const isSelected = !!storeMeasure && isFocused('measure', storeMeasure.id)}
									{@const currentAgg = storeMeasure?.aggregator ?? 'sum'}
									{@const aggLabel =
										currentAgg === 'distinct-count'
											? 'Distinct count'
											: currentAgg.charAt(0).toUpperCase() + currentAgg.slice(1)}
									<!-- Row is a click-target: single-click selects the measure so
									     the Inspector Properties tab shows aggregator / formatString
									     (name / caption editable inline via the pencil).  Fixes
									     the #974 discoverability gap. -->
									<div
										role="button"
										tabindex="0"
										draggable="true"
										ondragstart={(e) => {
											// FACTS_MEASURE_MIME payload lets the MG Name column
											// (spawn-new-MG-from-measure) and the Calculations
											// formula drop zone (add [measure] token) both consume
											// this row as a drag source. Regression from the
											// 07756ed7 MG-editor rewrite — the previous chip-style
											// row was draggable; the row-with-inspector-click
											// replacement forgot to preserve draggable.
											if (!e.dataTransfer) return;
											e.dataTransfer.setData(FACTS_MEASURE_MIME, col);
											e.dataTransfer.effectAllowed = 'copy';
										}}
										onclick={(e) => {
											// Ignore clicks that bubbled from an inner control
											// (pencil / × / caption editor / input).  Only the
											// bare row area toggles selection.
											const t = e.target as HTMLElement;
											if (t.closest('button, input')) return;
											if (storeMeasure) {
												selectMgMeasure(storeMeasure.id);
											}
										}}
										onkeydown={(e) => {
											if (e.key === 'Enter' || e.key === ' ') {
												e.preventDefault();
												if (storeMeasure) {
													selectMgMeasure(storeMeasure.id);
												}
											}
										}}
										class="flex cursor-grab items-center gap-2 rounded border px-2 py-1.5 text-[11px] transition-colors active:cursor-grabbing {isSelected
											? 'workbench-row-selected'
											: 'border-border bg-card hover:bg-accent/30'}"
										style:border-color={isSelected ? '' : 'hsl(var(--border))'}
										title="Click to inspect · double-click name to rename · drag to a measure group or calc"
										data-testid="workbench-mg-measure-saved-row"
										data-column={col}
									>
										<div class="flex min-w-0 flex-1 flex-col leading-tight">
											{#if isEditing}
												<input
													type="text"
													class="w-full rounded border bg-background px-1 py-0.5 text-[12px] font-medium"
													style:border-color="hsl(var(--primary))"
													value={caption}
													autofocus
													onblur={(e) => {
														commitMeasureCaption(g, col, (e.target as HTMLInputElement).value);
													}}
													onkeydown={(e) => {
														if (e.key === 'Enter') {
															e.preventDefault();
															commitMeasureCaption(
																g,
																col,
																(e.currentTarget as HTMLInputElement).value
															);
														} else if (e.key === 'Escape') {
															e.preventDefault();
															editingMeasureCaption = null;
														}
													}}
													data-testid="workbench-mg-measure-caption-input"
												/>
											{:else}
												<!-- Caption is a plain span, not a button, so:
												     (a) single-click bubbles up to the row's
												     onclick → Inspector selection fires,
												     (b) double-click still enters inline
												     rename mode.  Cursor stays `pointer` from
												     the row so the whole area reads as one
												     clickable target with no dead zones. -->
												<span
													class="truncate font-medium select-none"
													ondblclick={() => (editingMeasureCaption = `${g.id}::${col}`)}
													data-testid="workbench-mg-measure-caption"
												>
													{caption}
												</span>
											{/if}
											<span
												class="truncate font-mono text-[9px] select-none"
												style:color="hsl(var(--muted-foreground))"
											>
												{col}
											</span>
										</div>
										<!-- Aggregator dropdown — inline, so users can flip
										     sum → count / avg / distinct-count without a
										     round-trip through the Inspector.  Common case
										     (e.g. `customer_id` shouldn't be SUM, needs
										     DISTINCT COUNT) fixed in one click.  Fixes the
										     #974 discoverability gap for aggregators.
										     Stops click propagation so opening this doesn't
										     fire the row's "click to inspect" handler. -->
										<SelectPrimitive.Root
											type="single"
											value={currentAgg}
											onValueChange={(v) => {
												const next = v as SchemaCanvasMeasure['aggregator'];
												if (storeMeasure) {
													store.updateMeasure(storeMeasure.id, { aggregator: next });
												} else if (g.factTableId) {
													store.addMeasure({
														tableId: g.factTableId,
														columnName: col,
														aggregator: next
													});
												}
											}}
										>
											<!-- `text-foreground` is explicit here so the label stays
											     readable when the parent row is selected (bg-success)
											     — otherwise Tailwind inherits `text-primary-foreground`
											     from the row and renders black on the dark trigger. -->
											<SelectPrimitive.Trigger
												onclick={(e: MouseEvent) => e.stopPropagation()}
												class="inline-flex h-6 w-24 shrink-0 items-center justify-between gap-1 rounded-md border border-border bg-background px-2 text-[11px] font-normal tracking-normal text-foreground capitalize hover:bg-accent focus-visible:border-ring focus-visible:ring-1 focus-visible:ring-ring focus-visible:outline-none"
												title="Aggregator — how this measure rolls up across rows"
												data-testid="workbench-mg-measure-aggregator"
											>
												<span class="truncate">{aggLabel}</span>
												<ChevronDown class="h-2.5 w-2.5 shrink-0 opacity-60" aria-hidden="true" />
											</SelectPrimitive.Trigger>
											<SelectPrimitive.Portal>
												<SelectPrimitive.Content
													class="z-50 flex min-w-48 flex-col overflow-hidden rounded-md border border-border bg-popover p-1 text-xs shadow-md"
													sideOffset={4}
												>
													{#each AGGREGATORS as agg (agg)}
														<SelectPrimitive.Item
															value={agg}
															class="flex cursor-pointer items-center gap-2 rounded px-2 py-1.5 outline-none data-[highlighted]:bg-accent data-[highlighted]:text-accent-foreground data-[state=checked]:font-semibold"
														>
															<span class="min-w-0 flex-1 truncate text-[11px] capitalize">
																{agg === 'distinct-count' ? 'Distinct count' : agg}
															</span>
															{#if currentAgg === agg}
																<Check class="size-3 shrink-0 text-primary" aria-hidden="true" />
															{/if}
														</SelectPrimitive.Item>
													{/each}
												</SelectPrimitive.Content>
											</SelectPrimitive.Portal>
										</SelectPrimitive.Root>
										<!-- Percentile value (0–100) — only for the `percentile`
										     aggregator; median is the implicit 50th and needs none.
										     Defaults to 50 when left blank. -->
										{#if currentAgg === 'percentile'}
											<input
												type="number"
												min="0"
												max="100"
												value={storeMeasure?.percentile ?? 50}
												onclick={(e) => e.stopPropagation()}
												oninput={(e) => {
													e.stopPropagation();
													if (!storeMeasure) return;
													const raw = e.currentTarget.value;
													const next =
														raw === '' ? undefined : Math.max(0, Math.min(100, Number(raw)));
													store.updateMeasure(storeMeasure.id, { percentile: next });
												}}
												class="h-6 w-12 shrink-0 rounded-md border border-border bg-background px-1 text-[11px] text-foreground tabular-nums focus-visible:border-ring focus-visible:ring-1 focus-visible:ring-ring focus-visible:outline-none"
												title="Percentile (0–100) — defaults to 50"
												aria-label="Percentile value"
												data-testid="workbench-mg-measure-percentile"
											/>
										{/if}
										<button
											type="button"
											onclick={(e) => {
												e.stopPropagation();
												editingMeasureCaption = `${g.id}::${col}`;
											}}
											class="shrink-0 rounded p-0.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground"
											title="Rename caption"
											aria-label="Rename caption"
											data-testid="workbench-mg-measure-rename"
										>
											<Pencil class="h-3 w-3" aria-hidden="true" />
										</button>
										<!-- No × here — removal happens through EDIT mode
										     (uncheck the column).  Protects against accidental
										     deletion + reclaims horizontal space. -->
									</div>
								{/each}
							</div>
						{:else if factTable}
							<!-- Bulk All / Clear — mirrors the Attributes picker
							     pattern.  Toggling each column individually on a
							     wide fact table (20+ cols) is friction; #1009
							     asked for these controls. -->
							{@const allMeasuresIn = factTable.columns.every((c) =>
								g.measureColumns.includes(c.name)
							)}
							<div class="flex shrink-0 items-center gap-1.5 text-[10px]">
								<button
									type="button"
									onclick={() => {
										const factId = g.factTableId;
										if (!factId) return;
										for (const col of factTable.columns) {
											if (!g.measureColumns.includes(col.name)) {
												g.measureColumns = [...g.measureColumns, col.name];
												if (
													!store.measures.find(
														(mm) => mm.tableId === factId && mm.columnName === col.name
													)
												) {
													store.addMeasure({ tableId: factId, columnName: col.name });
												}
											}
										}
									}}
									class="rounded px-1.5 py-0.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground disabled:opacity-50"
									disabled={allMeasuresIn}
									data-testid="workbench-mg-measures-all"
								>
									All
								</button>
								<button
									type="button"
									onclick={() => {
										const factId = g.factTableId;
										// Purge every store measure that belonged to this MG's
										// fact so the Inspector doesn't lag with orphans.
										if (factId) {
											for (const col of g.measureColumns) {
												const orphan = store.measures.find(
													(mm) => mm.tableId === factId && mm.columnName === col
												);
												if (orphan) store.removeMeasure(orphan.id);
											}
										}
										g.measureColumns = [];
									}}
									class="rounded px-1.5 py-0.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground disabled:opacity-50"
									disabled={g.measureColumns.length === 0}
									data-testid="workbench-mg-measures-clear"
								>
									Clear
								</button>
								<span class="ml-auto text-muted-foreground">
									{g.measureColumns.length} of {factTable.columns.length} selected
								</span>
							</div>
							<!-- Edit view — checkbox list of fact's columns.
							     `flex-1 min-h-0` so the list fills whatever vertical
							     space the box has, rather than capping at max-h-52
							     and leaving dead whitespace below. -->
							<ul
								class="workbench-scroll flex min-h-0 flex-1 flex-col gap-0.5 overflow-y-auto pr-1"
								data-testid="workbench-mg-measure-checklist"
							>
								{#each factTable.columns as col (col.name)}
									{@const checked = g.measureColumns.includes(col.name)}
									<li>
										<label
											class="flex w-full items-center gap-1.5 rounded border border-border px-2 py-1 text-[11px] transition-colors hover:bg-accent/40 {checked
												? 'cursor-grab active:cursor-grabbing'
												: 'cursor-pointer'}"
											style:border-color="hsl(var(--border))"
											draggable={checked}
											ondragstart={(e) => {
												// Checked-only drag source in edit mode — mirrors
												// the saved row so users can drag straight from the
												// checklist without needing to hit Confirm first.
												if (!checked || !e.dataTransfer) return;
												e.dataTransfer.setData(FACTS_MEASURE_MIME, col.name);
												e.dataTransfer.effectAllowed = 'copy';
											}}
											title={checked
												? 'Drag into another measure group or a calc formula'
												: 'Tick first, then drag'}
										>
											<input
												type="checkbox"
												class="h-3 w-3"
												{checked}
												onchange={() => {
													if (checked) {
														g.measureColumns = g.measureColumns.filter((c) => c !== col.name);
														// Keep `store.measures` in sync so the Inspector
														// stays consistent with what the MG holds.  If
														// the same column is used by another MG, this
														// will re-create on next tick — cheap.
														const orphan = store.measures.find(
															(mm) => mm.tableId === g.factTableId && mm.columnName === col.name
														);
														if (orphan) store.removeMeasure(orphan.id);
													} else {
														g.measureColumns = [...g.measureColumns, col.name];
														if (
															g.factTableId &&
															!store.measures.find(
																(mm) => mm.tableId === g.factTableId && mm.columnName === col.name
															)
														) {
															// Seeds default aggregator='sum' + name=column.
															// Editable later via the Inspector.
															store.addMeasure({
																tableId: g.factTableId,
																columnName: col.name
															});
														}
													}
												}}
												data-testid="workbench-mg-measure-checkbox"
												data-column={col.name}
											/>
											<Sigma class="h-3 w-3 shrink-0 text-primary" aria-hidden="true" />
											<span class="min-w-0 flex-1 truncate font-mono">{col.name}</span>
											{#if col.sqlType}
												<span
													class="shrink-0 font-mono text-[9px]"
													style:color="hsl(var(--muted-foreground))"
												>
													{col.sqlType}
												</span>
											{/if}
										</label>
									</li>
								{/each}
							</ul>
						{/if}
					</section>
				</div>

				<!-- ── RIGHT sub-column: DIMENSIONS ────────────────────────
				     Own Confirm at the top.  Saved + edit views are both
				     vertical stacks (NOT flex-wrap chips) — each dim is a
				     full-width row.  An MG without dimensionLinks gets the
				     warning icon on its Name row, so the visual gate is
				     global. -->
				<section
					class="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto rounded border bg-elev-2 p-2"
					style:border-color="hsl(var(--border))"
					data-testid="workbench-pane-measure-group-dims"
				>
					<!-- Sticky header — same rationale as Fact + Measures.  #972 -->
					<div
						class="sticky top-0 z-10 flex items-center justify-between gap-2 border-b bg-elev-2 pb-1.5"
						style:border-color="hsl(var(--border))"
					>
						<span
							class="text-[10px] font-semibold tracking-wider uppercase"
							style:color="hsl(var(--muted-foreground))"
						>
							Linked dimensions
						</span>
						{#if g.dimsConfirmed && dimLinks.length > 0}
							<button
								type="button"
								onclick={() => (g.dimsConfirmed = false)}
								class="rounded-full border border-border px-2 py-0.5 text-[9px] font-semibold tracking-wider text-muted-foreground uppercase hover:bg-accent hover:text-accent-foreground"
								data-testid="workbench-mg-dims-edit"
							>
								Edit
							</button>
						{:else}
							<button
								type="button"
								disabled={dimLinks.length === 0}
								onclick={() => (g.dimsConfirmed = true)}
								class="rounded-full bg-primary px-3 py-1 text-[10px] font-bold tracking-wider text-primary-foreground uppercase shadow-sm transition-colors hover:bg-primary/90 disabled:cursor-not-allowed disabled:bg-muted disabled:text-muted-foreground disabled:shadow-none"
								data-testid="workbench-mg-dims-confirm"
							>
								Confirm
							</button>
						{/if}
					</div>
					{#if g.dimsConfirmed && dimLinks.length > 0}
						<div class="flex flex-col gap-1">
							{#each dimLinks as link (link.dimensionId)}
								{@const dim = store.dimensions.find((d) => d.id === link.dimensionId)}
								{#if dim}
									{@const dimSourceId = dim.sourceTableId ?? dim.primaryKeyTableId}
									{@const dimTable = dimSourceId
										? (store.doc.tables.find((t) => t.id === dimSourceId) ?? null)
										: null}
									{@const isDegenerate =
										!!factTable && !!dimSourceId && dimSourceId === factTable.id}
									<div
										class="flex items-center gap-2 rounded border bg-card px-2 py-1.5 text-[11px]"
										style:border-color="hsl(var(--border))"
										data-testid="workbench-mg-dim-chip"
										data-dim-id={dim.id}
									>
										<Layers class="h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
										<div class="flex min-w-0 flex-1 flex-col leading-tight">
											<span class="truncate font-medium">
												{dim.name || 'Untitled dimension'}
											</span>
											{#if dimTable}
												<span
													class="truncate font-mono text-[9px]"
													style:color="hsl(var(--muted-foreground))"
												>
													{dimTable.schema ? `${dimTable.schema}.${dimTable.name}` : dimTable.name}
												</span>
											{:else}
												<span
													class="truncate font-mono text-[9px] italic"
													style:color="hsl(var(--muted-foreground))"
												>
													(unbound)
												</span>
											{/if}
										</div>
										<!-- Fact-side FK column.  Lives on the MG link
										     (`link.foreignKeyColumn`) → emitted as
										     `<ForeignKeyLink foreignKeyColumn="X"/>`.
										     The FK MUST be a column on this MG's fact
										     table — Mondrian schema constraint — so the
										     dropdown lists `factTable.columns`.  No
										     auto-suggest: user picks explicitly.
										     Special case: a dim whose source table IS the
										     MG's fact table is a *degenerate* dim — Mondrian
										     emits <FactLink> (no FK column at all). -->
										{#if !factTable}
											<span
												class="inline-flex shrink-0 items-center gap-1 rounded border border-warning/50 bg-warning/10 px-1.5 py-0.5 text-[10px] text-warning italic"
												title="Pick a fact table on this measure group first."
												data-testid="workbench-mg-dim-fk-nofact"
											>
												<AlertCircle class="h-3 w-3 shrink-0" aria-hidden="true" />
												Fact not set
											</span>
										{:else if isDegenerate}
											<span
												class="inline-flex shrink-0 items-center gap-1 rounded border border-primary/40 bg-primary/5 px-1.5 py-0.5 font-mono text-[10px] text-primary"
												title={`Degenerate dimension — "${dim.name}" lives on the fact table "${factTable.name}". Mondrian emits <FactLink dimension="${dim.name}"/> and no foreign key is needed.`}
												data-testid="workbench-mg-dim-fk-degenerate"
											>
												FactLink
											</span>
										{:else if !dimTable}
											<button
												type="button"
												onclick={() => {
													selectMgDimension(dim.id);
												}}
												class="inline-flex shrink-0 items-center gap-1 rounded border border-warning/50 bg-warning/10 px-1.5 py-0.5 text-[10px] text-warning transition-colors hover:border-warning hover:bg-warning/20"
												title={`"${dim.name || 'Untitled dimension'}" has no source table bound. Click to select it — then bind a table in the Inspector Properties tab.`}
												data-testid="workbench-mg-dim-fk-unbound"
											>
												<AlertCircle class="h-3 w-3 shrink-0" aria-hidden="true" />
												Dim unbound
											</button>
										{:else}
											<span
												class="flex shrink-0 items-center gap-1 text-[10px] text-muted-foreground"
												title={`Fact-side foreign key column · emitted as <ForeignKeyLink foreignKeyColumn="…" /> on this measure group. Must be a column on "${factTable.name}".`}
											>
												{#if !link.foreignKeyColumn}
													<button
														type="button"
														onclick={() => {
															selectMgDimension(dim.id);
														}}
														class="shrink-0 rounded p-0.5 text-warning hover:bg-warning/10"
														aria-label="FK not set — open Inspector"
														title={`No FK column set for "${dim.name || 'this dimension'}". Pick one from ${factTable.name} — or click to open the Inspector.`}
														data-testid="workbench-mg-dim-fk-missing"
													>
														<AlertCircle class="h-3 w-3 shrink-0" aria-hidden="true" />
													</button>
												{/if}
												<span>FK</span>
												<!-- Flat picker — `appearance-none` strips macOS's
												     glossy chrome bevel + gradient, and a hand-drawn
												     ChevronDown overlays via the wrapping <span>. -->
												<span class="relative shrink-0">
													<select
														value={link.foreignKeyColumn}
														onchange={(e) => {
															link.foreignKeyColumn = (e.currentTarget as HTMLSelectElement).value;
														}}
														class="h-5 w-full appearance-none rounded border bg-background px-1 pr-5 font-mono text-[10px] text-foreground focus:border-ring focus:ring-1 focus:ring-ring focus:outline-none"
														style:border-color="hsl(var(--border))"
														data-testid="workbench-mg-dim-fk-select"
														data-dim-id={dim.id}
													>
														<option value="" disabled={!!link.foreignKeyColumn}>
															— pick FK column —
														</option>
														{#each factTable.columns as col (col.name)}
															<option value={col.name}>{col.name}</option>
														{/each}
													</select>
													<ChevronDown
														class="pointer-events-none absolute top-1/2 right-1 size-3 -translate-y-1/2 text-muted-foreground"
														aria-hidden="true"
													/>
												</span>
											</span>
										{/if}
									</div>
								{/if}
							{/each}
						</div>
					{:else if store.dimensions.length === 0}
						<p
							class="rounded border border-dashed p-2 text-center text-[11px]"
							style:border-color="hsl(var(--border))"
							style:color="hsl(var(--muted-foreground))"
						>
							Define dimensions in the step above first.
						</p>
					{:else}
						<ul class="flex flex-col gap-0.5">
							{#each store.dimensions as dim (dim.id)}
								{@const linked = dimLinks.some((l) => l.dimensionId === dim.id)}
								<li>
									<label
										class="flex cursor-pointer items-center gap-1.5 rounded border px-2 py-1 text-[11px] transition-colors {linked
											? 'border-primary bg-primary/5'
											: 'border-border hover:bg-accent/40'}"
										style:border-color={linked ? '' : 'hsl(var(--border))'}
									>
										<input
											type="checkbox"
											class="h-3 w-3"
											checked={linked}
											onchange={() => {
												const current = g.dimensionLinks ?? [];
												if (linked) {
													g.dimensionLinks = current.filter((l) => l.dimensionId !== dim.id);
												} else {
													// If the dim lives on this MG's fact table, it's
													// degenerate — Mondrian wants <FactLink>, not
													// <ForeignKeyLink>.  Tag the new link accordingly
													// so no FK picker appears.
													const dimSrc = dim.sourceTableId ?? dim.primaryKeyTableId;
													const degenerate = !!factTable && dimSrc === factTable.id;
													g.dimensionLinks = [
														...current,
														{
															dimensionId: dim.id,
															foreignKeyColumn: '',
															linkKind: degenerate ? 'fact' : 'foreign-key'
														}
													];
												}
											}}
											data-testid="workbench-mg-dim-checkbox"
											data-dim-id={dim.id}
										/>
										<Layers class="h-3 w-3 shrink-0 text-primary" aria-hidden="true" />
										<span class="min-w-0 flex-1 truncate font-medium">
											{dim.name || 'Untitled dimension'}
										</span>
									</label>
								</li>
							{/each}
						</ul>
					{/if}
				</section>
			</div>
		{/if}
	</div>
{/snippet}
