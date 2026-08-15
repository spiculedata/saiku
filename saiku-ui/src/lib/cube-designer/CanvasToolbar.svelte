<!--
  Schema-canvas top toolbar — mode toggles (pick / auto-join / join-lines /
  cube-links / highlight-focus), edge-style radios, columns-shown stepper,
  expand/collapse, and the Arrange split-button + layout menu.

  Presentational: reads/writes the shared SchemaCanvasStore directly (the
  mutation boundary), owns only the transient toggle-flash + layout-menu
  state, and delegates the actual layout run to the parent via `onArrange`
  (shared with the DimSum arrange_canvas tool).
-->
<script lang="ts">
	import {
		Sparkles,
		AlertTriangle,
		Network,
		Grid3x3,
		Workflow as WorkflowIcon,
		ChevronDown,
		Star
	} from '@lucide/svelte';
	import type { SchemaCanvasStore } from './state.svelte.js';
	import type { LayoutMode } from './layout.js';
	import Tooltip from './primitives/tooltip.svelte';

	interface Props {
		store: SchemaCanvasStore;
		physicalJoinCount: number;
		semanticJoinCount: number;
		arranging: boolean;
		lastLayoutMode: LayoutMode;
		/** Run a layout — shared with the DimSum arrange_canvas tool, so it
		 *  lives in the parent (it reads the canvas region rect). */
		onArrange: (mode: LayoutMode) => void;
	}

	let { store, physicalJoinCount, semanticJoinCount, arranging, lastLayoutMode, onArrange }: Props =
		$props();

	const joinLinesOn = $derived(!store.joinsHidden);
	// Cube-link joins are only relevant when at least one such join exists.
	const hasCubeLinkJoins = $derived(
		store.doc.joins.some((j) => j.origin === 'cube-link' || j.origin === 'inferred-fk')
	);
	// Expand/Collapse toggle state — true when EVERY table has been
	// explicitly expanded (collapsed === false).
	const everyTableExpanded = $derived(
		store.doc.tables.length > 0 && store.doc.tables.every((t) => t.collapsed === false)
	);

	let layoutMenuOpen = $state(false);
	function handleMenuKey(e: KeyboardEvent) {
		if (e.key === 'Escape') layoutMenuOpen = false;
	}
	// Run a layout and close the menu — mirrors the old handleLayout, which
	// set layoutMenuOpen=false before awaiting applyLayout.
	function runLayout(mode: LayoutMode) {
		layoutMenuOpen = false;
		onArrange(mode);
	}

	const LAYOUT_CHOICES: Array<{
		mode: LayoutMode;
		label: string;
		hint: string;
		icon: typeof Sparkles;
	}> = [
		{
			mode: 'star',
			label: 'Star schema',
			hint: 'Fact at centre, dimensions in a ring',
			icon: Sparkles
		},
		{
			mode: 'layered',
			label: 'Layered',
			hint: 'Hierarchical top-down, follows joins',
			icon: WorkflowIcon
		},
		{
			mode: 'byJoins',
			label: 'By joins',
			hint: 'Concentric rings by hops from the fact',
			icon: Network
		},
		{
			mode: 'grid',
			label: 'Grid',
			hint: 'Even rows, alphabetical reset',
			icon: Grid3x3
		}
	];
</script>

<!-- Compact label-plus-switch toggle used for every on/off view option
     in the toolbar.  Kills the bordered-pill button treatment (which
     ate horizontal space with its own border + "· ON/OFF" text) in
     favour of a small track+dot slide — the animation IS the visual
     confirmation of the flip. -->
{#snippet toolbarSwitch(
	label: string,
	checked: boolean,
	onchange: () => void,
	testid: string,
	tooltip: string,
	disabled: boolean = false
)}
	<button
		type="button"
		role="switch"
		aria-checked={checked}
		aria-label={label}
		title={tooltip}
		onclick={onchange}
		{disabled}
		class="inline-flex h-6 shrink-0 cursor-pointer items-center gap-1.5 rounded px-1 text-xs font-medium whitespace-nowrap text-foreground focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary disabled:cursor-not-allowed disabled:opacity-50"
		data-testid={testid}
	>
		<span>{label}</span>
		<span
			class="relative inline-flex h-3.5 w-6 shrink-0 items-center rounded-full transition-colors"
			class:bg-success={checked}
			class:bg-muted={!checked}
		>
			<span
				class="pointer-events-none absolute inline-block h-2.5 w-2.5 rounded-full bg-background shadow transition-transform"
				class:translate-x-3={checked}
				class:translate-x-0.5={!checked}
				aria-hidden="true"
			></span>
		</span>
	</button>
{/snippet}

<!-- Toolbar wraps to a second row when the viewport can't fit the full
     control strip — beats cramming + truncating.  Quick fix; a follow-up
     could collapse low-priority toggles behind a `…` menu when the row
     overflows, but the simple wrap covers 90% of the real cases. -->
<div class="flex items-center justify-between gap-3 border-b border-border bg-card px-4 py-2">
	<div class="flex flex-1 flex-wrap items-center gap-x-3 gap-y-2">
		<span class="text-xs text-muted-foreground" data-testid="canvas-table-count">
			{store.doc.tables.length}
			{store.doc.tables.length === 1 ? 'table' : 'tables'} ·
			{physicalJoinCount}
			{physicalJoinCount === 1 ? 'join' : 'joins'}{semanticJoinCount > 0
				? ` · ${semanticJoinCount} semantic`
				: ''}
		</span>

		<!-- Pick-mode toggle — compact label + switch.  The animated
		     slide is the "just flipped" confirmation; no full pill
		     border needed. -->
		{@render toolbarSwitch(
			'Create Joins',
			store.pickModeActive,
			() => store.togglePickMode(),
			'canvas-pick-mode-toggle',
			'Turn on to click columns across tables and wire joins between them. ⌘+click also picks columns without turning the mode on. Shortcut: ⌘J.'
		)}

		<!-- Auto-join on drop toggle -->
		{@render toolbarSwitch(
			'Auto-join on drop',
			store.autoSuggestOnDrop,
			() => (store.autoSuggestOnDrop = !store.autoSuggestOnDrop),
			'canvas-auto-suggest-toggle',
			'When on, dropping a new table onto the canvas automatically wires joins for any columns whose names match tables already on the canvas.'
		)}

		<!-- Show/hide join LINES on the canvas. Distinct from the eye
		     icon in the joins-panel header, which hides the whole
		     right-hand panel. This one keeps the panel + data intact
		     and just stops drawing the curves + labels on the canvas. -->
		{@render toolbarSwitch(
			'Join lines',
			joinLinesOn,
			() => (store.joinsHidden = joinLinesOn),
			'canvas-toolbar-join-lines-toggle',
			joinLinesOn
				? 'Hide the arcs and labels drawn between joined columns. Joins stay in your data — the canvas just gets quieter. Pair with Highlight joins to see what is wired via green column rows instead.'
				: 'Draw the arcs and labels between joined columns on the canvas.',
			store.doc.joins.length === 0
		)}

		<!-- Cube-links toggle.  Shows the cube-side <ForeignKeyLink>
		     joins that the importer auto-inferred from MeasureGroups.
		     They're semantic, not physical, so they're hidden by
		     default; flipping ON renders them dashed + muted so the
		     visual distinction stays unmistakable.  Only enabled when
		     at least one cube-link join exists. -->
		{#if hasCubeLinkJoins}
			{@render toolbarSwitch(
				'Cube links',
				store.showCubeLinks,
				() => (store.showCubeLinks = !store.showCubeLinks),
				'canvas-toolbar-cube-links-toggle',
				store.showCubeLinks
					? 'Hide the dashed lines the importer inferred from cube dimension links. You just see the physical joins you drew.'
					: 'Show extra dashed lines for joins the importer inferred from cube dimension links. Read-only — remove them from the cube inspector, not the canvas.'
			)}
		{/if}

		<!-- Highlight joins toggle -->
		{@render toolbarSwitch(
			'Highlight joins',
			store.highlightFocusedColumns,
			() => {
				const next = !store.highlightFocusedColumns;
				store.highlightFocusedColumns = next;
				// Toggling OFF also drops any stale name-match highlight
				// so the user isn't left wondering why columns still glow.
				if (!next) {
					store.highlightedColumn = null;
					store.highlightOriginTableId = null;
					store.highlightSource = null;
				}
			},
			'canvas-highlight-focused-toggle',
			'Click a table to see which columns have joins.'
		)}

		{#if store.thresholdSoft}
			<span
				class="inline-flex items-center gap-1 rounded bg-warning/15 px-2 py-0.5 text-xs text-warning"
				data-testid="canvas-threshold-warning"
			>
				<AlertTriangle class="h-3.5 w-3.5" aria-hidden="true" />
				Many tables — consider grouping
			</span>
		{/if}
	</div>
	<div class="flex items-center gap-2">
		<!-- Join line style picker — icons-only, tooltips carry the name.
		     Each icon is an inline SVG that shows the actual shape of the
		     route it selects (curved bezier, rounded orthogonal, sharp
		     orthogonal), so the picker is self-explanatory at a glance.
		     Hidden entirely when Join lines is OFF — the picker only
		     affects lines that are being drawn. -->
		{#if joinLinesOn}
			<div
				class="flex h-6 rounded border border-border bg-card p-0.5 text-[11px]"
				role="radiogroup"
				aria-label="Join line style"
			>
				<Tooltip text="Curved bezier lines — the smoothest look, best for a small canvas.">
					<button
						type="button"
						role="radio"
						aria-checked={store.edgeStyle === 'bezier'}
						onclick={() => (store.edgeStyle = 'bezier')}
						aria-label="Curved lines"
						class="flex items-center justify-center rounded px-1.5 transition-colors"
						class:bg-primary={store.edgeStyle === 'bezier'}
						class:text-primary-foreground={store.edgeStyle === 'bezier'}
						class:text-foreground={store.edgeStyle !== 'bezier'}
						data-testid="canvas-edge-style-bezier"
					>
						<svg viewBox="0 0 20 12" class="h-3 w-5" aria-hidden="true">
							<path
								d="M1 6 C 6 6, 6 2, 10 2 S 14 10, 19 10"
								fill="none"
								stroke="currentColor"
								stroke-width="1.5"
								stroke-linecap="round"
							/>
						</svg>
					</button>
				</Tooltip>
				<Tooltip text="Rounded right-angle lines — orthogonal routing with soft corners.">
					<button
						type="button"
						role="radio"
						aria-checked={store.edgeStyle === 'smoothstep'}
						onclick={() => (store.edgeStyle = 'smoothstep')}
						aria-label="Rounded step lines"
						class="flex items-center justify-center rounded px-1.5 transition-colors"
						class:bg-primary={store.edgeStyle === 'smoothstep'}
						class:text-primary-foreground={store.edgeStyle === 'smoothstep'}
						class:text-foreground={store.edgeStyle !== 'smoothstep'}
						data-testid="canvas-edge-style-smoothstep"
					>
						<svg viewBox="0 0 20 12" class="h-3 w-5" aria-hidden="true">
							<path
								d="M1 10 H 8 Q 10 10 10 8 V 4 Q 10 2 12 2 H 19"
								fill="none"
								stroke="currentColor"
								stroke-width="1.5"
								stroke-linecap="round"
							/>
						</svg>
					</button>
				</Tooltip>
				<Tooltip text="Sharp right-angle lines — orthogonal routing with square corners.">
					<button
						type="button"
						role="radio"
						aria-checked={store.edgeStyle === 'step'}
						onclick={() => (store.edgeStyle = 'step')}
						aria-label="Step lines"
						class="flex items-center justify-center rounded px-1.5 transition-colors"
						class:bg-primary={store.edgeStyle === 'step'}
						class:text-primary-foreground={store.edgeStyle === 'step'}
						class:text-foreground={store.edgeStyle !== 'step'}
						data-testid="canvas-edge-style-step"
					>
						<svg viewBox="0 0 20 12" class="h-3 w-5" aria-hidden="true">
							<path
								d="M1 10 H 10 V 2 H 19"
								fill="none"
								stroke="currentColor"
								stroke-width="1.5"
								stroke-linecap="round"
							/>
						</svg>
					</button>
				</Tooltip>
			</div>
		{/if}

		<!-- Default-columns-shown — global preference for how many
		     rows each table card shows. 0 collapses every table to
		     its header; per-table chevron still overrides.  The
		     number input + stacked ▲/▼ stepper share one bordered
		     box so they read as a single control; the label sits
		     outside on the left. Native spin buttons are hidden so
		     only the custom stepper drives the value. -->
		<Tooltip text="How many columns each table card shows by default (0 = header only).">
			<div
				class="flex items-center gap-1.5 text-xs text-foreground"
				data-testid="canvas-default-columns"
			>
				<span>Show columns</span>
				<span class="flex h-6 items-stretch rounded border border-input bg-background">
					<input
						type="number"
						min="0"
						value={store.defaultColumnsShown}
						oninput={(e) => {
							const raw = Number.parseInt(e.currentTarget.value, 10);
							store.defaultColumnsShown = Number.isFinite(raw) ? Math.max(0, raw) : 0;
						}}
						class="nodrag h-full w-10 border-none bg-transparent px-1 text-center text-xs text-foreground outline-none [-moz-appearance:textfield] focus:outline-none [&::-webkit-inner-spin-button]:m-0 [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:m-0 [&::-webkit-outer-spin-button]:appearance-none"
						aria-label="Default columns shown"
					/>
					<span class="flex flex-col border-l border-input">
						<button
							type="button"
							onclick={() => (store.defaultColumnsShown = store.defaultColumnsShown + 1)}
							class="nodrag flex h-1/2 w-4 items-center justify-center text-[8px] leading-none text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
							title="Show one more column per table"
							aria-label="Increase columns"
						>
							▲
						</button>
						<button
							type="button"
							onclick={() =>
								(store.defaultColumnsShown = Math.max(0, store.defaultColumnsShown - 1))}
							class="nodrag flex h-1/2 w-4 items-center justify-center border-t border-input text-[8px] leading-none text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
							title="Show one fewer column per table"
							aria-label="Decrease columns"
						>
							▼
						</button>
					</span>
				</span>
			</div>
		</Tooltip>
		<!-- Single Expand/Collapse toggle. The Show input above tells
		     things WHAT to collapse to (e.g. 8 cols). The button
		     ALTERNATES between forcing every table to show ALL columns
		     and dropping the overrides so they all follow the global
		     default again. -->
		<button
			type="button"
			onclick={() => {
				if (everyTableExpanded) store.resetCollapseOverrides();
				else store.expandAllTables();
			}}
			disabled={store.doc.tables.length === 0}
			class="nodrag inline-flex h-7 items-center gap-1 rounded border border-input bg-background px-2 text-xs font-medium transition-colors hover:bg-accent hover:text-accent-foreground disabled:cursor-not-allowed disabled:opacity-50"
			title={everyTableExpanded
				? `Collapse to the current Show value`
				: `Show every column on every table`}
			data-testid="canvas-expand-collapse-toggle"
		>
			{everyTableExpanded ? 'Collapse all' : 'Expand all'}
		</button>

		<!-- Arrange split-button: click the left half to re-apply the
		     last layout, click the chevron to pick a different one. -->
		<div class="relative flex">
			<button
				type="button"
				onclick={() => runLayout(store.defaultLayoutMode)}
				disabled={arranging || store.doc.tables.length === 0}
				class="inline-flex h-8 items-center gap-1.5 rounded-l border border-r-0 border-input bg-background px-2.5 text-xs font-medium transition-colors hover:bg-accent hover:text-accent-foreground disabled:cursor-not-allowed disabled:opacity-50"
				data-testid="canvas-arrange"
				title={`Re-apply ${LAYOUT_CHOICES.find((c) => c.mode === store.defaultLayoutMode)?.label ?? 'layout'} (pinned default)`}
			>
				<Sparkles class="h-3.5 w-3.5" aria-hidden="true" />
				{arranging ? 'Arranging…' : 'Arrange'}
			</button>
			<button
				type="button"
				onclick={() => (layoutMenuOpen = !layoutMenuOpen)}
				onkeydown={handleMenuKey}
				disabled={arranging || store.doc.tables.length === 0}
				aria-expanded={layoutMenuOpen}
				aria-haspopup="menu"
				aria-label="Pick a layout"
				class="inline-flex h-8 items-center rounded-r border border-input bg-background px-1.5 text-xs transition-colors hover:bg-accent hover:text-accent-foreground disabled:cursor-not-allowed disabled:opacity-50"
				data-testid="canvas-arrange-menu"
			>
				<ChevronDown class="h-3.5 w-3.5" aria-hidden="true" />
			</button>
			{#if layoutMenuOpen}
				<!-- svelte-ignore a11y_no_static_element_interactions a11y_click_events_have_key_events -->
				<div class="fixed inset-0 z-30" onclick={() => (layoutMenuOpen = false)}></div>
				<div
					role="menu"
					class="absolute top-full right-0 z-40 mt-1 w-64 rounded-md border border-border bg-card p-1 shadow-lg"
					data-testid="canvas-arrange-menu-list"
				>
					<div
						class="border-b border-border px-2 py-1.5 text-[10px] font-medium tracking-wider text-muted-foreground uppercase"
					>
						Organise canvas
					</div>
					{#each LAYOUT_CHOICES as choice (choice.mode)}
						{@const Icon = choice.icon}
						{@const isActive = choice.mode === lastLayoutMode}
						{@const isDefault = choice.mode === store.defaultLayoutMode}
						<!-- Row = run-this-layout.  Pin icon on the right
						     = mark this layout as the default that the
						     main "Arrange" button applies.  Click on the
						     pin doesn't run the layout (stopPropagation),
						     and all layouts stay individually clickable
						     via the rest of the row. -->
						<div
							class="group relative flex w-full items-stretch rounded transition-colors hover:bg-accent hover:text-accent-foreground"
							class:bg-accent={isActive}
							class:text-accent-foreground={isActive}
						>
							<button
								type="button"
								role="menuitem"
								onclick={() => runLayout(choice.mode)}
								class="flex flex-1 items-start gap-2 rounded-l px-2 py-1.5 text-left text-xs"
								data-testid={`canvas-arrange-${choice.mode}`}
							>
								<Icon class="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
								<span class="flex flex-1 flex-col gap-0.5">
									<span class="font-medium">{choice.label}</span>
									<span class="text-[10px] text-muted-foreground">{choice.hint}</span>
								</span>
							</button>
							<button
								type="button"
								onclick={(e) => {
									e.stopPropagation();
									store.setDefaultLayoutMode(choice.mode);
								}}
								class="inline-flex w-8 shrink-0 items-center justify-center rounded-r transition-colors hover:bg-accent hover:text-accent-foreground"
								class:text-primary={isDefault}
								class:text-muted-foreground={!isDefault}
								title={isDefault
									? `${choice.label} is the default Arrange action`
									: `Make ${choice.label} the default Arrange action`}
								aria-label={isDefault ? 'Default layout' : 'Set as default layout'}
								aria-pressed={isDefault}
								data-testid={`canvas-arrange-pin-${choice.mode}`}
							>
								<Star
									class={`h-3.5 w-3.5 ${isDefault ? 'fill-current' : 'opacity-40 group-hover:opacity-100'}`}
									aria-hidden="true"
								/>
							</button>
						</div>
					{/each}
				</div>
			{/if}
		</div>
	</div>
</div>
