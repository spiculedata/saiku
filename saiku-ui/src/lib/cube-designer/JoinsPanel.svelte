<!--
  Right-rail joins panel for the schema canvas: physical join groups
  (rename / focus / collapse / remove-endpoint), the active-set picks panel,
  the read-only semantic-joins accordion, and the Select / Reorganize / Delete
  bulk modes + their footer toolbars and the delete-all confirm modal.

  Presentational: reads/writes the shared SchemaCanvasStore directly, and owns
  the panel-local interaction state (select / reorganize / group-edit /
  collapse). Join-group derivations come in as props (shared with the toolbar
  counts). `onFlushEdges` lets delete-all clear the parent's bound SvelteFlow
  edges array synchronously — the one piece of parent state it can't own.
-->
<script lang="ts">
	import {
		ChevronRight,
		ChevronDown,
		Trash2,
		Pencil,
		ChevronsDownUp,
		ChevronsUpDown,
		EyeOff,
		MoreHorizontal
	} from '@lucide/svelte';
	import { SchemaCanvasStore } from './state.svelte.js';
	import type { JoinGroupRender } from './join-groups.js';
	import ActiveSetPanel from './ActiveSetPanel.svelte';
	import SemanticJoinsAccordion from './SemanticJoinsAccordion.svelte';

	interface Props {
		store: SchemaCanvasStore;
		joinGroups: JoinGroupRender[];
		physicalJoinGroups: JoinGroupRender[];
		semanticJoinGroups: JoinGroupRender[];
		physicalJoinCount: number;
		semanticJoinCount: number;
		joinsPanelOpen: boolean;
		/** Flush the parent's bound SvelteFlow edges array (delete-all only). */
		onFlushEdges: () => void;
	}

	let {
		store,
		joinGroups,
		physicalJoinGroups,
		semanticJoinGroups,
		physicalJoinCount,
		semanticJoinCount,
		joinsPanelOpen = $bindable(),
		onFlushEdges
	}: Props = $props();

	// Lookup table for join row labels — name resolution is cheap so we
	// just derive a map from id → table on every render.
	const tableById = $derived(new Map(store.doc.tables.map((t) => [t.id, t])));

	let editingGroupKey = $state<string | null>(null);
	let editingGroupDraft = $state('');
	let collapsedJoinGroups = $state<Set<string>>(new Set());
	// Default-collapse newly-seen groups so the panel reads as a compact
	// label list at first glance. Once the user expands one, our
	// "seenGroupKeys" tracker remembers it and won't re-collapse on the
	// next derivation pass.
	const seenGroupKeys = new Set<string>();
	$effect(() => {
		const next = new Set(collapsedJoinGroups);
		let changed = false;
		for (const g of joinGroups) {
			if (!seenGroupKeys.has(g.canonicalKey)) {
				seenGroupKeys.add(g.canonicalKey);
				next.add(g.canonicalKey);
				changed = true;
			}
		}
		if (changed) collapsedJoinGroups = next;
	});
	function toggleJoinGroupCollapse(canonicalKey: string) {
		const next = new Set(collapsedJoinGroups);
		if (next.has(canonicalKey)) next.delete(canonicalKey);
		else next.add(canonicalKey);
		collapsedJoinGroups = next;
	}
	// All-groups collapse state — when ANY group is collapsed, the
	// header button reads "expand all"; when nothing's collapsed it
	// reads "collapse all".
	const allGroupsExpanded = $derived(collapsedJoinGroups.size === 0);
	function toggleAllJoinGroups() {
		if (allGroupsExpanded) {
			collapsedJoinGroups = new Set(joinGroups.map((g) => g.canonicalKey));
		} else {
			collapsedJoinGroups = new Set();
		}
	}
	function startGroupEdit(group: JoinGroupRender) {
		editingGroupKey = group.canonicalKey;
		editingGroupDraft = group.label;
	}
	function commitGroupEdit() {
		if (editingGroupKey === null) return;
		store.renameJoinGroup(editingGroupKey, editingGroupDraft);
		editingGroupKey = null;
		editingGroupDraft = '';
	}
	function cancelGroupEdit() {
		editingGroupKey = null;
		editingGroupDraft = '';
	}

	// Bulk-select state inside the joins panel. Off by default; entering
	// select mode shows a checkbox on every row + a "Delete N" footer.
	let joinsSelectMode = $state(false);
	let joinsSelectedIds = $state<Set<string>>(new Set());
	function selectAllJoins() {
		joinsSelectedIds = new Set(store.doc.joins.map((j) => j.id));
	}
	function clearJoinSelection() {
		joinsSelectedIds = new Set();
	}
	function exitSelectMode() {
		joinsSelectMode = false;
		clearJoinSelection();
	}
	function deleteSelectedJoins() {
		if (joinsSelectedIds.size === 0) return;
		store.removeJoins(joinsSelectedIds);
		exitSelectMode();
	}
	function deleteAllJoins() {
		store.removeAllJoins();
		// Force-flush the bound edges array immediately.  The
		// edges-sync $effect will sync to []` next microtask anyway,
		// but SvelteFlow has been observed holding onto a previous
		// frame of edges if the same render tick also handles modal
		// dismissal — explicitly emptying the array here means there's
		// no window for a stale line to survive the click.
		onFlushEdges();
		exitSelectMode();
		confirmingDeleteAllJoins = false;
	}
	let confirmingDeleteAllJoins = $state(false);

	// Joins-panel ellipsis menu — Select / Reorganize / Delete-all entry points.
	let joinsMenuOpen = $state(false);

	// Reorganize mode — merge groups, or move endpoints in/out of a group.
	// Mutually exclusive with joinsSelectMode; entering one exits the other.
	let reorganizeMode = $state(false);
	let reorganizeSelectedGroups = $state<Set<string>>(new Set()); // group LABELS
	let reorganizeSelectedJoinIds = $state<Set<string>>(new Set());
	let mergeNamingActive = $state(false);
	let mergeNameInput = $state('');

	function enterSelectMode() {
		exitReorganize();
		joinsSelectMode = true;
	}
	function enterReorganize() {
		exitSelectMode();
		reorganizeMode = true;
		clearReorganizeSelection();
	}
	function clearReorganizeSelection() {
		reorganizeSelectedGroups = new Set();
		reorganizeSelectedJoinIds = new Set();
		mergeNamingActive = false;
		mergeNameInput = '';
	}
	function exitReorganize() {
		reorganizeMode = false;
		clearReorganizeSelection();
	}
	function toggleReorganizeGroup(label: string) {
		const next = new Set(reorganizeSelectedGroups);
		if (next.has(label)) next.delete(label);
		else next.add(label);
		reorganizeSelectedGroups = next;
	}
	// Per-join reorganize checkbox — the join list surfaces one row
	// per physical join now (see the pair-rendered <ul> below), so
	// each checkbox toggles exactly its join id in / out of the
	// selection set. selectedCanonicalKeys() still folds those ids
	// back into canonical keys for Merge and Ungroup, so the toolbar
	// semantics are unchanged.
	function toggleReorganizeJoin(joinId: string) {
		const next = new Set(reorganizeSelectedJoinIds);
		if (next.has(joinId)) next.delete(joinId);
		else next.add(joinId);
		reorganizeSelectedJoinIds = next;
	}
	// Canonical keys of every join implied by the current selection — from
	// selected group labels AND from selected endpoint rows. Used by both
	// Merge and Ungroup.
	function selectedCanonicalKeys(): Set<string> {
		const keys = new Set<string>();
		for (const g of joinGroups) {
			if (reorganizeSelectedGroups.has(g.label)) {
				keys.add(g.canonicalKey);
			}
		}
		for (const j of store.doc.joins) {
			if (reorganizeSelectedJoinIds.has(j.id)) {
				keys.add(SchemaCanvasStore.joinCanonicalKey(j));
			}
		}
		return keys;
	}
	// Merge needs 2+ DIFFERENT canonical keys picked — two endpoints in
	// the same group share one key and would merge into themselves.
	const canMerge = $derived.by(() => {
		// Touch reactive selections so $derived re-runs on toggle.
		void reorganizeSelectedGroups;
		void reorganizeSelectedJoinIds;
		return selectedCanonicalKeys().size >= 2;
	});
	const canUngroup = $derived(reorganizeSelectedJoinIds.size >= 1);
	function applyMerge() {
		const label = mergeNameInput.trim();
		if (!label) return;
		for (const k of selectedCanonicalKeys()) store.renameJoinGroup(k, label);
		exitReorganize();
	}
	function applyUngroup() {
		// Only strip canonical keys implied by endpoint selection — group
		// headers don't ungroup on their own (the spec wires Ungroup to
		// endpoint rows specifically). Empty label drops the rename so the
		// group reverts to its canonical key.
		const keys = new Set<string>();
		for (const j of store.doc.joins) {
			if (reorganizeSelectedJoinIds.has(j.id)) {
				keys.add(SchemaCanvasStore.joinCanonicalKey(j));
			}
		}
		for (const k of keys) store.renameJoinGroup(k, '');
		exitReorganize();
	}
</script>

{#if joinsPanelOpen}
	<!-- Floating card. Positioned by the right-side column wrapper in
	     SchemaCanvasView (`absolute top-2 right-2 bottom-2`, width
	     driven by `joinsPanelWidth`). Here we just fill the top half
	     of that column: `flex-1 min-h-0 w-full` lets DimSum share the
	     bottom half. `pointer-events-auto` guards against any future
	     ancestor going pointer-events-none. -->
	<aside
		class="pointer-events-auto relative flex min-h-0 w-full flex-1 flex-col overflow-hidden rounded border border-border bg-card shadow-md"
		aria-label="Joins panel"
		data-testid="canvas-joins-panel"
	>
		<!-- Splitter handle — drag the LEFT edge of the joins
		     panel to resize.  Mirrors the source-tables sidebar's
		     splitter.  Clamps at 220-560px so the panel can't
		     vanish or eat the canvas. -->
		<div
			role="separator"
			aria-orientation="vertical"
			aria-label="Resize joins panel"
			title="Drag to resize"
			class="absolute top-0 bottom-0 left-[-4px] z-20 flex w-2 cursor-ew-resize items-center justify-center text-muted-foreground/40 transition-colors hover:bg-primary/20 hover:text-foreground"
			onpointerdown={(e) => {
				e.preventDefault();
				const startX = e.clientX;
				const startW = store.joinsPanelWidth;
				const move = (ev: PointerEvent) => {
					const dx = startX - ev.clientX;
					const next = Math.max(220, Math.min(560, startW + dx));
					store.joinsPanelWidth = next;
				};
				const up = () => {
					window.removeEventListener('pointermove', move);
					window.removeEventListener('pointerup', up);
				};
				window.addEventListener('pointermove', move);
				window.addEventListener('pointerup', up);
			}}
			data-testid="canvas-joins-panel-splitter"
		>
			<span class="font-mono text-[8px] tracking-wider opacity-70">||</span>
		</div>
		<div
			class="flex items-center justify-between border-b border-border px-3 py-2 text-xs font-semibold tracking-wider uppercase"
			style:color="hsl(var(--muted-foreground))"
		>
			<span>Joins ({physicalJoinCount})</span>
			<div class="flex items-center gap-1">
				<!-- Expand/collapse-all groups — chevrons pointing
			     INWARD when groups are expanded (click → collapse
			     all), OUTWARD when at least one is collapsed
			     (click → expand all). Mirrors the table-card
			     chevron semantics. -->
				{#if joinGroups.length > 1}
					<button
						type="button"
						onclick={toggleAllJoinGroups}
						class="inline-flex h-5 w-5 items-center justify-center rounded transition-colors hover:bg-accent hover:text-accent-foreground"
						aria-label={allGroupsExpanded ? 'Collapse all groups' : 'Expand all groups'}
						title={allGroupsExpanded ? 'Collapse all groups' : 'Expand all groups'}
						data-testid="canvas-joins-panel-toggle-all-groups"
					>
						{#if allGroupsExpanded}
							<ChevronsDownUp class="h-3.5 w-3.5" aria-hidden="true" />
						{:else}
							<ChevronsUpDown class="h-3.5 w-3.5" aria-hidden="true" />
						{/if}
					</button>
				{/if}
				<!-- Eye icon = close the WHOLE joins panel (right
			     column). Re-open via the chevron-pill that
			     appears in the canvas top-right. The line
			     visibility toggle lives in the canvas
			     toolbar — these are two different things. -->
				<button
					type="button"
					onclick={() => (joinsPanelOpen = false)}
					class="inline-flex h-5 w-5 items-center justify-center rounded transition-colors hover:bg-accent hover:text-accent-foreground"
					aria-label="Hide joins panel"
					title="Hide the joins panel (use the chevron at top-right to re-open)"
					data-testid="canvas-joins-panel-close"
				>
					<EyeOff class="h-3.5 w-3.5" aria-hidden="true" />
				</button>
				<!-- Ellipsis menu — Select / Reorganize / Delete-all.
			     Same shape as the canvas import/export menus. -->
				{#if store.doc.joins.length > 0}
					<div class="relative">
						<button
							type="button"
							onclick={() => (joinsMenuOpen = !joinsMenuOpen)}
							aria-expanded={joinsMenuOpen}
							aria-haspopup="menu"
							class="inline-flex h-5 w-5 items-center justify-center rounded transition-colors hover:bg-accent hover:text-accent-foreground"
							aria-label="Joins panel actions"
							title="Joins panel actions"
							data-testid="canvas-joins-panel-menu-button"
						>
							<MoreHorizontal class="h-3.5 w-3.5" aria-hidden="true" />
						</button>
						{#if joinsMenuOpen}
							<!-- svelte-ignore a11y_no_static_element_interactions a11y_click_events_have_key_events -->
							<div class="fixed inset-0 z-30" onclick={() => (joinsMenuOpen = false)}></div>
							<div
								role="menu"
								class="absolute top-full right-0 z-40 mt-1 w-80 rounded-md border border-border bg-card p-1 normal-case shadow-lg"
								data-testid="canvas-joins-panel-menu"
							>
								<button
									type="button"
									role="menuitem"
									onclick={() => {
										joinsMenuOpen = false;
										enterSelectMode();
									}}
									class="flex w-full items-start gap-2 rounded px-2 py-1.5 text-left text-xs transition-colors hover:bg-accent hover:text-accent-foreground"
									data-testid="canvas-joins-panel-menu-select"
								>
									<span class="flex flex-1 flex-col gap-0.5">
										<span class="text-xs font-medium tracking-normal">Select joins</span>
										<span class="text-[10px] font-normal tracking-normal text-muted-foreground">
											Pick individual joins for bulk delete
										</span>
									</span>
								</button>
								<button
									type="button"
									role="menuitem"
									onclick={() => {
										joinsMenuOpen = false;
										enterReorganize();
									}}
									class="flex w-full items-start gap-2 rounded px-2 py-1.5 text-left text-xs transition-colors hover:bg-accent hover:text-accent-foreground"
									data-testid="canvas-joins-panel-menu-reorganize"
								>
									<span class="flex flex-1 flex-col gap-0.5">
										<span class="text-xs font-medium tracking-normal">Reorganize joins</span>
										<span class="text-[10px] font-normal tracking-normal text-muted-foreground">
											Merge groups, or move a join in or out of a group
										</span>
									</span>
								</button>
								<button
									type="button"
									role="menuitem"
									onclick={() => {
										joinsMenuOpen = false;
										confirmingDeleteAllJoins = true;
									}}
									class="flex w-full items-start gap-2 rounded px-2 py-1.5 text-left text-xs text-destructive transition-colors hover:bg-destructive hover:text-destructive-foreground"
									data-testid="canvas-joins-panel-menu-delete-all"
								>
									<span class="flex flex-1 flex-col gap-0.5">
										<span class="text-xs font-medium tracking-normal">Delete all joins</span>
										<span class="text-[10px] font-normal tracking-normal opacity-80">
											Remove every join from the canvas
										</span>
									</span>
								</button>
							</div>
						{/if}
					</div>
				{/if}
			</div>
		</div>

		<!-- Bulk-action toolbar — only visible in Select mode.
	     Entry-points (Select / Delete-all) live in the
	     ellipsis menu in the header. -->
		{#if joinsSelectMode && store.doc.joins.length > 0}
			<div
				class="flex items-center gap-1 border-b border-border bg-background px-2 py-1.5"
				data-testid="canvas-joins-panel-bulk-toolbar"
			>
				<button
					type="button"
					onclick={joinsSelectedIds.size === store.doc.joins.length
						? clearJoinSelection
						: selectAllJoins}
					class="inline-flex h-6 items-center justify-center rounded border border-input bg-background px-2 text-[11px] font-medium transition-colors hover:bg-accent hover:text-accent-foreground"
					data-testid="canvas-joins-panel-select-all"
				>
					{joinsSelectedIds.size === store.doc.joins.length ? 'None' : 'All'}
				</button>
				<button
					type="button"
					onclick={exitSelectMode}
					class="inline-flex h-6 flex-1 items-center justify-center rounded border border-input bg-background px-2 text-[11px] font-medium transition-colors hover:bg-accent hover:text-accent-foreground"
					data-testid="canvas-joins-panel-select-cancel"
				>
					Cancel
				</button>
				<button
					type="button"
					onclick={deleteSelectedJoins}
					disabled={joinsSelectedIds.size === 0}
					class="inline-flex h-6 items-center justify-center rounded border border-destructive/40 bg-destructive px-2 text-[11px] font-medium text-destructive-foreground transition-colors hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40"
					data-testid="canvas-joins-panel-delete-selected"
				>
					Delete {joinsSelectedIds.size}
				</button>
			</div>
		{/if}

		<ActiveSetPanel {store} />

		<div class="flex-1 overflow-y-auto p-2">
			<!-- The old "Link N similar with <colname>" purple button
			     that used to sit here got folded into the green CTA
			     up in the active-set panel (with a descriptor +
			     auto-join tip underneath).  One button, one place. -->
			{#if store.doc.joins.length === 0}
				<p
					class="rounded border border-dashed p-3 text-xs"
					style:border-color="hsl(var(--border))"
					style:color="hsl(var(--muted-foreground))"
					data-testid="canvas-joins-panel-empty"
				>
					No joins yet. Drag column-to-column on the canvas, or press
					<span class="font-mono">⌘J</span> to enter Create Joins Mode and click columns across tables.
				</p>
			{:else}
				<div class="space-y-3">
					{#each physicalJoinGroups as group (group.canonicalKey)}
						{@const isGroupCollapsed = collapsedJoinGroups.has(group.canonicalKey)}
						<section
							class="group/section space-y-1"
							data-testid="canvas-joins-panel-group"
							data-group-key={group.canonicalKey}
						>
							<header class="flex items-center gap-1.5 border-b border-border pb-1">
								{#if reorganizeMode}
									<input
										type="checkbox"
										class="h-3.5 w-3.5 shrink-0 cursor-pointer accent-primary"
										checked={reorganizeSelectedGroups.has(group.label)}
										onchange={() => toggleReorganizeGroup(group.label)}
										aria-label={`Select group ${group.label}`}
										data-testid="canvas-joins-panel-group-checkbox"
									/>
								{/if}
								<button
									type="button"
									onclick={() => toggleJoinGroupCollapse(group.canonicalKey)}
									class="inline-flex h-4 w-4 shrink-0 items-center justify-center rounded text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
									title={isGroupCollapsed ? 'Expand group' : 'Collapse group'}
									aria-expanded={!isGroupCollapsed}
									data-testid="canvas-joins-panel-group-toggle"
								>
									{#if isGroupCollapsed}
										<ChevronRight class="h-3 w-3" aria-hidden="true" />
									{:else}
										<ChevronDown class="h-3 w-3" aria-hidden="true" />
									{/if}
								</button>
								{#if editingGroupKey === group.canonicalKey}
									<!-- svelte-ignore a11y_autofocus -->
									<input
										type="text"
										bind:value={editingGroupDraft}
										onkeydown={(e) => {
											if (e.key === 'Enter') {
												e.preventDefault();
												commitGroupEdit();
											} else if (e.key === 'Escape') {
												e.preventDefault();
												cancelGroupEdit();
											}
										}}
										onblur={commitGroupEdit}
										class="h-6 flex-1 rounded border border-input bg-background px-2 text-xs"
										placeholder={group.canonicalKey}
										autofocus
										data-testid="canvas-joins-panel-group-input"
									/>
								{:else}
									{@const isGroupFocused =
										store.focusKind === 'group' && store.focusKey === group.label}
									<button
										type="button"
										onclick={() => store.focusGroup(group.label)}
										class="group/header flex flex-1 items-center gap-1.5 truncate text-left text-[11px] font-medium tracking-wide transition-colors"
										class:text-primary={isGroupFocused}
										class:text-foreground={!isGroupFocused}
										class:hover:text-primary={!isGroupFocused}
										title="Click to focus on these joins (click again to clear)"
										data-testid="canvas-joins-panel-group-label"
									>
										<span class="truncate font-mono">{group.label}</span>
									</button>
									<button
										type="button"
										onclick={() => startGroupEdit(group)}
										class="inline-flex h-4 w-4 shrink-0 items-center justify-center rounded text-muted-foreground opacity-0 transition-opacity group-hover/section:opacity-100 hover:bg-accent hover:text-accent-foreground"
										title="Rename group"
										aria-label="Rename group"
										data-testid="canvas-joins-panel-group-rename"
									>
										<Pencil class="h-3 w-3" aria-hidden="true" />
									</button>
									<span class="shrink-0 text-[10px] text-muted-foreground tabular-nums">
										{group.joins.length}
									</span>
								{/if}
							</header>
							{#if !isGroupCollapsed}
								<!-- Per-join pairs — each row is one physical join with
								     both endpoints spelled out (src.col ↔ tgt.col) so
								     ambiguous groupings (same source paired with two
								     different target columns) surface as two distinct
								     rows. Schema.table origin sits under each column
								     name in muted mono, mirroring the workbench
								     inspector caption/column pattern. Delete removes
								     the join outright; the reorganize checkbox toggles
								     that specific join id in the selection set. -->
								<ul class="space-y-1">
									{#each group.joins as j (j.id)}
										{@const srcTable = tableById.get(j.sourceTableId)}
										{@const tgtTable = tableById.get(j.targetTableId)}
										{@const srcName = srcTable?.name ?? '?'}
										{@const tgtName = tgtTable?.name ?? '?'}
										{@const srcSchema = srcTable?.schema ?? null}
										{@const tgtSchema = tgtTable?.schema ?? null}
										<li
											class="flex items-start gap-2 rounded border p-2 text-xs transition-colors hover:bg-accent"
											style:border-color="hsl(var(--border))"
											style:background="hsl(var(--background))"
											data-testid="canvas-joins-panel-join"
											data-join-id={j.id}
										>
											{#if reorganizeMode}
												<input
													type="checkbox"
													class="mt-0.5 h-3.5 w-3.5 shrink-0 cursor-pointer accent-primary"
													checked={reorganizeSelectedJoinIds.has(j.id)}
													onchange={() => toggleReorganizeJoin(j.id)}
													aria-label={`Select join ${srcName}.${j.sourceColumnName} to ${tgtName}.${j.targetColumnName}`}
													data-testid="canvas-joins-panel-join-checkbox"
												/>
											{:else}
												<button
													type="button"
													class="mt-0.5 shrink-0 rounded p-0.5 transition-colors hover:bg-destructive/10 hover:text-destructive"
													style:color="hsl(var(--muted-foreground))"
													aria-label={`Delete join ${srcName}.${j.sourceColumnName} to ${tgtName}.${j.targetColumnName}`}
													title="Delete this join"
													onclick={(e) => {
														e.stopPropagation();
														store.removeJoin(j.id);
													}}
													data-testid="canvas-joins-panel-join-delete"
												>
													<Trash2 class="h-3 w-3" aria-hidden="true" />
												</button>
											{/if}
											<div class="flex min-w-0 flex-1 items-center gap-2">
												<div class="flex min-w-0 flex-1 flex-col font-mono leading-tight">
													<span class="min-w-0 truncate">
														{srcName}<span class="text-muted-foreground">.</span
														>{j.sourceColumnName}
													</span>
													{#if srcSchema}
														<span
															class="min-w-0 truncate text-[10px] text-muted-foreground"
															title={`${srcSchema}.${srcName}`}
														>
															{srcSchema}
														</span>
													{/if}
												</div>
												<span class="shrink-0 text-muted-foreground select-none" aria-hidden="true"
													>↔</span
												>
												<div class="flex min-w-0 flex-1 flex-col font-mono leading-tight">
													<span class="min-w-0 truncate">
														{tgtName}<span class="text-muted-foreground">.</span
														>{j.targetColumnName}
													</span>
													{#if tgtSchema}
														<span
															class="min-w-0 truncate text-[10px] text-muted-foreground"
															title={`${tgtSchema}.${tgtName}`}
														>
															{tgtSchema}
														</span>
													{/if}
												</div>
											</div>
										</li>
									{/each}
								</ul>
							{/if}
						</section>
					{/each}
				</div>
			{/if}

			<SemanticJoinsAccordion {store} {semanticJoinGroups} {semanticJoinCount} />
		</div>

		<!-- Reorganize-mode footer toolbar — contextual: Merge
		     appears with 2+ selections, Ungroup with 1+ endpoint
		     rows. Inline name input takes over when "Merge into
		     one" is pressed. -->
		{#if reorganizeMode}
			<div
				class="flex shrink-0 flex-col gap-2 border-t border-border bg-background px-2 py-2"
				data-testid="canvas-joins-panel-reorganize-toolbar"
			>
				{#if mergeNamingActive}
					<input
						type="text"
						bind:value={mergeNameInput}
						placeholder="Name the merged group, e.g. Customer Key"
						onkeydown={(e) => {
							if (e.key === 'Enter') {
								e.preventDefault();
								applyMerge();
							} else if (e.key === 'Escape') {
								e.preventDefault();
								mergeNamingActive = false;
								mergeNameInput = '';
							}
						}}
						class="h-7 w-full rounded border border-input bg-card px-2 text-xs"
						data-testid="canvas-joins-panel-reorganize-merge-name"
					/>
					<div class="flex items-center gap-1">
						<button
							type="button"
							onclick={() => {
								mergeNamingActive = false;
								mergeNameInput = '';
							}}
							class="inline-flex h-6 flex-1 items-center justify-center rounded border border-input bg-background px-2 text-[11px] font-medium transition-colors hover:bg-accent hover:text-accent-foreground"
							data-testid="canvas-joins-panel-reorganize-merge-cancel"
						>
							Cancel
						</button>
						<button
							type="button"
							onclick={applyMerge}
							disabled={!mergeNameInput.trim()}
							class="inline-flex h-6 flex-1 items-center justify-center rounded bg-primary px-2 text-[11px] font-medium text-primary-foreground transition-opacity hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40"
							data-testid="canvas-joins-panel-reorganize-merge-confirm"
						>
							Confirm
						</button>
					</div>
				{:else}
					<div class="flex items-center gap-1">
						{#if canMerge}
							<button
								type="button"
								onclick={() => {
									mergeNamingActive = true;
									mergeNameInput = '';
								}}
								class="inline-flex h-6 flex-1 items-center justify-center rounded border border-input bg-background px-2 text-[11px] font-medium transition-colors hover:bg-accent hover:text-accent-foreground"
								data-testid="canvas-joins-panel-reorganize-merge"
							>
								Merge into one
							</button>
						{/if}
						{#if canUngroup}
							<button
								type="button"
								onclick={applyUngroup}
								class="inline-flex h-6 flex-1 items-center justify-center rounded border border-input bg-background px-2 text-[11px] font-medium transition-colors hover:bg-accent hover:text-accent-foreground"
								data-testid="canvas-joins-panel-reorganize-ungroup"
							>
								Ungroup
							</button>
						{/if}
						<button
							type="button"
							onclick={exitReorganize}
							class="inline-flex h-6 flex-1 items-center justify-center rounded border border-input bg-background px-2 text-[11px] font-medium transition-colors hover:bg-accent hover:text-accent-foreground"
							data-testid="canvas-joins-panel-reorganize-cancel"
						>
							Cancel
						</button>
					</div>
				{/if}
			</div>
		{/if}

		<!-- Pathfinder removed pending UX rework — see saiku-cloud#957.  Underlying
		     store API (pathfinderResult / armPathfinder / findPath /
		     clearPathfinder / applyPathfinderJoins) intentionally
		     left intact so the next iteration can drop a new UI
		     back in without touching the store. -->
	</aside>
{/if}

<!-- Delete-all-joins confirmation modal. Same shape as the host page's
     Reset modal so the two destructive actions feel consistent. -->
{#if confirmingDeleteAllJoins}
	<div
		class="fixed inset-0 z-50 flex items-center justify-center bg-background/80 p-4"
		role="dialog"
		aria-modal="true"
		aria-labelledby="delete-all-joins-title"
		data-testid="canvas-joins-delete-all-modal"
	>
		<div class="w-full max-w-md rounded-lg border border-border bg-card p-5 shadow-xl">
			<h2 id="delete-all-joins-title" class="text-base font-semibold">Delete every join?</h2>
			<p class="mt-2 text-sm text-muted-foreground">
				This will remove all {store.doc.joins.length} join{store.doc.joins.length === 1 ? '' : 's'} on
				this canvas. The tables themselves stay where they are — only the join wires get wiped, so you
				can re-draw them however you want.
			</p>
			<p class="mt-2 text-xs text-muted-foreground">This can't be undone.</p>
			<div class="mt-4 flex justify-end gap-2">
				<button
					type="button"
					onclick={() => (confirmingDeleteAllJoins = false)}
					class="inline-flex h-9 items-center justify-center rounded border border-input bg-background px-3 text-sm font-medium transition-colors hover:bg-accent hover:text-accent-foreground"
					data-testid="canvas-joins-delete-all-cancel"
				>
					Cancel
				</button>
				<button
					type="button"
					onclick={deleteAllJoins}
					class="inline-flex h-9 items-center justify-center gap-1.5 rounded bg-destructive px-3 text-sm font-medium text-destructive-foreground transition-opacity hover:opacity-90"
					data-testid="canvas-joins-delete-all-confirm"
				>
					<Trash2 class="h-3.5 w-3.5" aria-hidden="true" />
					Delete every join
				</button>
			</div>
		</div>
	</div>
{/if}
