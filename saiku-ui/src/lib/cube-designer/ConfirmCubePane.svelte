<!--
  Confirm-cube / Validate pane — extracted from WorkbenchView.svelte
  (audit finding #1039, stage 2 template split).

  Renders when `store.mode === 'validate'`: a left cube rail, a sub-tab
  strip (Model / Sample data / Try a query), and the Model surface
  (Semantic/Physical renderer + read-only cube outline tree).

  The Sample-data and Try-a-query tab bodies stay in the shell (they are
  shared with the inspector drawer) and are passed in as snippet props so
  they keep closing over the shell scope. Cube switching and the outline
  collapse-section toggle stay shell-owned and arrive as callbacks; the
  four writable UI flags (`validateTab`, `modelViewMode`, `modelResetTs`,
  `outlineCollapsed`) are `$bindable` so their shell-side persistence
  effects still fire.
-->
<script lang="ts">
	import type { Snippet } from 'svelte';
	import {
		Database,
		ChevronRight,
		ChevronDown,
		ChevronLeft,
		Layers,
		Sigma,
		ListTree,
		Rows3,
		EyeOff
	} from '@lucide/svelte';
	import ConfirmCubeCanvas from './confirm-cube/ConfirmCubeCanvas.svelte';
	import CubeDag from './confirm-cube/CubeDag.svelte';
	import type { SchemaCanvasStore } from './state.svelte.js';
	import type { WorkbenchCube, WorkbenchMeasureGroup } from './workbench-cubes';

	type ValidateTab = 'canvas' | 'sample-data' | 'try-query';
	type ModelViewMode = 'semantic' | 'physical';
	type OutlineSection = 'facts' | 'mgs' | 'dims';

	interface Props {
		store: SchemaCanvasStore;
		cubes: WorkbenchCube[];
		selectedCubeId: string;
		selectedCube: WorkbenchCube | undefined;
		factsMeasureGroups: WorkbenchMeasureGroup[];
		isSchemaSaved: boolean;
		isSchemaDirty: boolean;
		validateTab: ValidateTab;
		modelViewMode: ModelViewMode;
		modelResetTs: number;
		/** Bump counter from the parent — forces the Confirm cube's
		 *  model renderers to re-derive against the latest MG shape
		 *  after upstream FK / dim-link mutations (#1088). */
		refreshSignal?: number;
		outlineCollapsed: boolean;
		outlineCollapsedSections: Set<OutlineSection>;
		switchCube: (id: string) => void;
		toggleOutlineSection: (s: OutlineSection) => void;
		sampleDataTab: Snippet;
		tryQueryTab: Snippet;
	}

	let {
		store,
		cubes,
		selectedCubeId,
		selectedCube,
		factsMeasureGroups,
		isSchemaSaved,
		isSchemaDirty,
		validateTab = $bindable(),
		modelViewMode = $bindable(),
		modelResetTs = $bindable(),
		refreshSignal = 0,
		outlineCollapsed = $bindable(),
		outlineCollapsedSections,
		switchCube,
		toggleOutlineSection,
		sampleDataTab,
		tryQueryTab
	}: Props = $props();
</script>

<div
	class="flex h-full min-h-0 flex-1 flex-row gap-3 overflow-hidden p-3"
	data-testid="canvas-validate-view"
>
	<!-- LEFT: cube list.  Reuses the visual treatment of the cube-
	     manage rail on the Facts & Measures tab so authors read the
	     two rails as the same control. -->
	<div
		class="flex w-72 shrink-0 flex-col gap-1 overflow-hidden rounded border bg-elev-2"
		style:border-color="hsl(var(--border))"
		data-testid="canvas-validate-cubes-rail"
	>
		<div
			class="shrink-0 border-b px-2 py-1 text-[10px] font-semibold tracking-wider uppercase"
			style:border-color="hsl(var(--border))"
			style:color="hsl(var(--muted-foreground))"
		>
			Cubes · {cubes.length}
		</div>
		<div class="flex min-h-0 flex-1 flex-col overflow-y-auto">
			{#each cubes as c (c.id)}
				{@const active = c.id === selectedCubeId}
				{@const rowMGs = active ? factsMeasureGroups : (c.measureGroups ?? [])}
				{@const rowLinkedDimIds = new Set(
					rowMGs.flatMap((mg) => (mg.dimensionLinks ?? []).map((l) => l.dimensionId))
				)}
				{@const rowLinkedDims = store.dimensions.filter((d) => rowLinkedDimIds.has(d.id))}
				{@const rowMGCount = rowMGs.length}
				{@const rowDimCount = rowLinkedDims.length}
				{@const rowHierCount = rowLinkedDims.reduce(
					(acc, d) => acc + (d.hierarchies?.length ?? 0),
					0
				)}
				{@const rowLevelCount = rowLinkedDims.reduce(
					(acc, d) => acc + (d.hierarchies ?? []).reduce((s, h) => s + (h.levels?.length ?? 0), 0),
					0
				)}
				{@const revealClass = active ? 'inline-flex' : 'hidden group-hover/cuberow:inline-flex'}
				<!-- Cube row.  Each cube gets its own "Open in Saiku"
				     button on the right so the rail becomes the central
				     confirmation surface: pick a cube, see it in the
				     model tab, launch it in Studio.  Red dot on the
				     LEFT marks the active row. -->
				<div
					class="group/cuberow relative flex flex-col gap-0.5 border-b px-2 py-1.5 last:border-b-0 {active
						? 'bg-primary/10'
						: ''}"
					style:border-color="hsl(var(--border))"
					data-testid="canvas-validate-cube-item"
					data-cube-id={c.id}
				>
					<div class="flex items-center gap-1 pr-24">
						<span class="w-2 shrink-0 text-[10px] leading-none text-primary">
							{active ? '●' : ''}
						</span>
						<button
							type="button"
							onclick={() => switchCube(c.id)}
							class="min-w-0 flex-1 truncate text-left text-[11px] {active
								? 'font-semibold text-foreground'
								: 'text-muted-foreground hover:text-foreground'}"
							title={c.name || 'Untitled cube'}
						>
							{c.name || 'Untitled cube'}
						</button>
					</div>
					<!-- Open in Saiku — absolute-positioned so it's
					     vertically centred against the WHOLE row (name +
					     summary chips), not just the name row.  z-10 keeps
					     it above the row content on hover; the pr-24 on
					     the name row reserves horizontal space so long
					     cube names don't slide under the button. -->
					{#if isSchemaSaved && !isSchemaDirty}
						<!-- Enabled — full red CTA.  This IS the payoff
						     button: it takes all the authoring work into
						     Studio.  Solid primary fill + white text +
						     shadow. -->
						<a
							href="/saiku/launch"
							target="_blank"
							rel="noopener noreferrer"
							onclick={() => {
								if (c.id !== selectedCubeId) switchCube(c.id);
							}}
							class="{revealClass} absolute top-1/2 right-2 z-10 shrink-0 -translate-y-1/2 items-center gap-1 rounded-md border border-primary bg-primary px-2.5 py-1 text-[10px] font-semibold text-primary-foreground shadow-sm transition hover:bg-primary/90"
							data-testid="canvas-validate-open-in-saiku"
							data-cube-id={c.id}
							title={`Open "${c.name || 'Untitled cube'}" in the Saiku Studio UI`}
						>
							Open in Saiku
							<span aria-hidden="true">↗</span>
						</a>
					{:else}
						<span
							role="button"
							aria-disabled="true"
							tabindex={-1}
							class="{revealClass} pointer-events-auto absolute top-1/2 right-2 z-10 shrink-0 -translate-y-1/2 cursor-not-allowed items-center gap-1 rounded-md border border-border bg-card px-2 py-1 text-[10px] font-semibold text-muted-foreground opacity-60 shadow-sm"
							data-testid="canvas-validate-open-in-saiku-disabled"
							data-cube-id={c.id}
							title={isSchemaSaved
								? `You have unsaved changes.  Hit Save (top-right) — Mondrian schemas save as one file, so all cubes save together.  After save, this opens "${c.name || 'Untitled cube'}" in Studio.`
								: `Save the schema first via Save (top-right) — Mondrian schemas save as one file, so all cubes save together.  After save, this opens "${c.name || 'Untitled cube'}" in Studio.`}
						>
							Open in Saiku
							<span aria-hidden="true">↗</span>
						</span>
					{/if}
					<!-- Summary line: MG · linked dims · hierarchies · levels.
					     Icons match the ones used elsewhere on the pane
					     (Σ, Layers, ListTree) rendered in muted foreground
					     so the numbers do the talking. -->
					<div
						class="flex items-center gap-2 pl-3 font-mono text-[9px] text-muted-foreground"
						data-testid="canvas-validate-cube-summary"
					>
						<span
							class="inline-flex items-center gap-0.5 leading-none"
							title="{rowMGCount} measure group{rowMGCount === 1 ? '' : 's'}"
						>
							<Sigma class="h-2.5 w-2.5" aria-hidden="true" />
							{rowMGCount}
						</span>
						<span
							class="inline-flex items-center gap-0.5 leading-none"
							title="{rowDimCount} linked dimension{rowDimCount === 1 ? '' : 's'}"
						>
							<Layers class="h-2.5 w-2.5" aria-hidden="true" />
							{rowDimCount}
						</span>
						<span
							class="inline-flex items-center gap-0.5 leading-none"
							title="{rowHierCount} hierarch{rowHierCount === 1 ? 'y' : 'ies'}"
						>
							<ListTree class="h-2.5 w-2.5" aria-hidden="true" />
							{rowHierCount}
						</span>
						<span
							class="inline-flex items-center gap-0.5 leading-none"
							title="{rowLevelCount} level{rowLevelCount === 1 ? '' : 's'}"
						>
							<Rows3 class="h-2.5 w-2.5" aria-hidden="true" />
							{rowLevelCount}
						</span>
					</div>
				</div>
			{/each}
		</div>
		<!-- Legend for the per-cube summary icons above.  Pinned at
		     the bottom of the rail (outside the scroll region) so it
		     stays visible while the cube list scrolls. -->
		<div
			class="grid shrink-0 grid-cols-2 gap-x-3 gap-y-1 border-t px-2 py-1.5 text-[9px] text-muted-foreground"
			style:border-color="hsl(var(--border))"
			data-testid="canvas-validate-cubes-legend"
		>
			<span class="inline-flex items-center gap-1 leading-none">
				<Sigma class="h-2.5 w-2.5 shrink-0" aria-hidden="true" /> Measure Groups
			</span>
			<span class="inline-flex items-center gap-1 leading-none">
				<Layers class="h-2.5 w-2.5 shrink-0" aria-hidden="true" /> Dimensions
			</span>
			<span class="inline-flex items-center gap-1 leading-none">
				<ListTree class="h-2.5 w-2.5 shrink-0" aria-hidden="true" /> Hierarchies
			</span>
			<span class="inline-flex items-center gap-1 leading-none">
				<Rows3 class="h-2.5 w-2.5 shrink-0" aria-hidden="true" /> Levels
			</span>
		</div>
	</div>

	<!-- RIGHT: sub-tab strip + content area.  Aligns top-to-top with
	     the cubes rail — the old shared "Open in Saiku" action row
	     is gone; per-row buttons live in the cubes rail now. -->
	<div class="flex min-w-0 flex-1 flex-col gap-3 overflow-hidden">
		<div
			class="flex shrink-0 items-stretch overflow-hidden rounded border"
			style:border-color="hsl(var(--border))"
			role="tablist"
			aria-label="Confirm cube sub-view"
		>
			{#snippet vtab(t: ValidateTab, label: string, testid: string)}
				<!-- Sub-tab styling.
				     • Active: white surface + red text + red glow ring
				       (softer than the top-level red fill, louder than
				       the third-level Semantic/Physical toggle).
				     • Inactive: muted grey text on the muted rail
				       surface. -->
				<button
					type="button"
					role="tab"
					aria-selected={validateTab === t}
					onclick={() => (validateTab = t)}
					class="relative inline-flex min-w-0 flex-1 items-center justify-center gap-1 truncate border-l px-3 py-1.5 text-[11px] font-semibold tracking-wide first:border-l-0 {validateTab ===
					t
						? 'z-10 bg-white text-primary ring-2 ring-primary/50 ring-inset dark:bg-neutral-900'
						: 'bg-elev-2 text-muted-foreground hover:bg-accent/40'}"
					style:border-color="hsl(var(--border))"
					data-testid={testid}
				>
					{label}
				</button>
			{/snippet}
			{@render vtab('canvas', 'Model', 'canvas-validate-tab-canvas')}
			{@render vtab('sample-data', 'Sample data', 'canvas-validate-tab-sample-data')}
			{@render vtab('try-query', 'Try a query', 'canvas-validate-tab-try-query')}
		</div>
		<div class="flex min-h-0 flex-1 flex-col overflow-hidden">
			{#if validateTab === 'canvas'}
				{@render confirmCubeModel()}
			{:else if validateTab === 'sample-data'}
				{@render sampleDataTab()}
			{:else}
				{@render tryQueryTab()}
			{/if}
		</div>
	</div>
</div>

<!-- ── Confirm cube > Canvas sub-tab.  Read-only star diagram: the
     selected cube's fact table sits at the centre, each linked dim
     table radiates out around it.  Lines drawn as SVG so they scale
     with the layout.  Not editable — this is a "here's the shape of
     what you built" view; edits live on the Dim & Hier / Facts &
     Measures tabs. -->
{#snippet cubeOutlinePanel(cube: WorkbenchCube | undefined, mgs: WorkbenchMeasureGroup[])}
	<!-- Read-only cube outline.  Colored icons + collapsible sections —
	     no edit affordances (per Tom) but still a proper view of the
	     cube, not a stripped-down text dump. -->
	{#if !cube || mgs.length === 0}
		<p class="text-[11px] text-muted-foreground italic">
			Nothing to outline yet — pick a fact table + link a dimension in Facts &amp; Measures.
		</p>
	{:else}
		{@const factIds = new Set(mgs.map((mg) => mg.factTableId ?? '').filter((id) => id.length > 0))}
		{@const factTables = [...factIds]
			.map((id) => store.doc.tables.find((t) => t.id === id))
			.filter((t) => t !== undefined)}
		{@const linkedDimIds = new Set(
			mgs.flatMap((mg) => (mg.dimensionLinks ?? []).map((l) => l.dimensionId))
		)}
		{@const linkedDims = store.dimensions.filter((d) => linkedDimIds.has(d.id))}
		{@const factsOpen = !outlineCollapsedSections.has('facts')}
		{@const mgsOpen = !outlineCollapsedSections.has('mgs')}
		{@const dimsOpen = !outlineCollapsedSections.has('dims')}
		<div class="flex flex-col gap-3">
			<!-- Cube name -->
			<div class="truncate text-[12px] font-semibold text-foreground">
				{cube.name || 'Untitled cube'}
			</div>

			<!-- Fact Tables section -->
			{#if factTables.length > 0}
				<section class="flex flex-col gap-1">
					<button
						type="button"
						onclick={() => toggleOutlineSection('facts')}
						class="flex w-full items-center gap-1.5 text-left"
					>
						{#if factsOpen}
							<ChevronDown class="h-3 w-3 shrink-0 text-muted-foreground" aria-hidden="true" />
						{:else}
							<ChevronRight class="h-3 w-3 shrink-0 text-muted-foreground" aria-hidden="true" />
						{/if}
						<Database class="h-3 w-3 shrink-0 text-primary" aria-hidden="true" />
						<span class="text-[10px] font-semibold tracking-wider text-muted-foreground uppercase">
							Fact Tables
						</span>
						<span class="ml-auto font-mono text-[10px] text-muted-foreground">
							{factTables.length}
						</span>
					</button>
					{#if factsOpen}
						<ul class="flex flex-col gap-0.5 pl-6 font-mono text-[11px]">
							{#each factTables as t (t.id)}
								<li
									class="truncate text-foreground"
									title={t.schema ? `${t.schema}.${t.name}` : t.name}
								>
									{t.name}
								</li>
							{/each}
						</ul>
					{/if}
				</section>
			{/if}

			<!-- Measure Groups section -->
			<section class="flex flex-col gap-1">
				<button
					type="button"
					onclick={() => toggleOutlineSection('mgs')}
					class="flex w-full items-center gap-1.5 text-left"
				>
					{#if mgsOpen}
						<ChevronDown class="h-3 w-3 shrink-0 text-muted-foreground" aria-hidden="true" />
					{:else}
						<ChevronRight class="h-3 w-3 shrink-0 text-muted-foreground" aria-hidden="true" />
					{/if}
					<Sigma class="h-3 w-3 shrink-0 text-primary" aria-hidden="true" />
					<span class="text-[10px] font-semibold tracking-wider text-muted-foreground uppercase">
						Measure Groups
					</span>
					<span class="ml-auto font-mono text-[10px] text-muted-foreground">
						{mgs.length}
					</span>
				</button>
				{#if mgsOpen}
					<ul class="flex flex-col gap-2 pl-6">
						{#each mgs as mg (mg.id)}
							<li class="flex flex-col gap-0.5">
								<div
									class="flex items-center gap-1.5 truncate text-[11px] font-semibold text-foreground"
								>
									<Sigma class="h-2.5 w-2.5 shrink-0 text-primary" aria-hidden="true" />
									<span class="truncate">{mg.name ?? 'Untitled MG'}</span>
								</div>
								{#if (mg.measureColumns ?? []).length > 0}
									<ul class="flex flex-col gap-0 pl-5 font-mono text-[10px] text-muted-foreground">
										{#each mg.measureColumns ?? [] as m (m)}
											<li class="truncate" title={m}>{m}</li>
										{/each}
									</ul>
								{:else}
									<p class="pl-5 text-[10px] text-muted-foreground italic opacity-70">
										No measures picked
									</p>
								{/if}
							</li>
						{/each}
					</ul>
				{/if}
			</section>

			<!-- Dimensions section -->
			<section class="flex flex-col gap-1">
				<button
					type="button"
					onclick={() => toggleOutlineSection('dims')}
					class="flex w-full items-center gap-1.5 text-left"
				>
					{#if dimsOpen}
						<ChevronDown class="h-3 w-3 shrink-0 text-muted-foreground" aria-hidden="true" />
					{:else}
						<ChevronRight class="h-3 w-3 shrink-0 text-muted-foreground" aria-hidden="true" />
					{/if}
					<Layers class="h-3 w-3 shrink-0 text-primary" aria-hidden="true" />
					<span class="text-[10px] font-semibold tracking-wider text-muted-foreground uppercase">
						Dimensions
					</span>
					<span class="ml-auto font-mono text-[10px] text-muted-foreground">
						{linkedDims.length}
					</span>
				</button>
				{#if dimsOpen}
					<ul class="flex flex-col gap-2 pl-6">
						{#each linkedDims as d (d.id)}
							<li class="flex flex-col gap-0.5">
								<div
									class="flex items-center gap-1.5 truncate text-[11px] font-semibold text-foreground"
								>
									<Layers class="h-2.5 w-2.5 shrink-0 text-primary" aria-hidden="true" />
									<span class="truncate">{d.name ?? 'Untitled dim'}</span>
								</div>
								{#if (d.hierarchies ?? []).length > 0}
									<ul class="flex flex-col gap-1 pl-5">
										{#each d.hierarchies ?? [] as h (h.id)}
											<li class="flex flex-col gap-0">
												<div class="flex items-center gap-1.5 truncate text-[10px] text-foreground">
													<ListTree
														class="h-2.5 w-2.5 shrink-0 text-muted-foreground"
														aria-hidden="true"
													/>
													<span class="truncate">{h.name ?? 'Untitled hierarchy'}</span>
												</div>
												{#if (h.levels ?? []).length > 0}
													<ol
														class="flex flex-col gap-0 pl-5 font-mono text-[10px] text-muted-foreground"
													>
														{#each h.levels ?? [] as lvl, i (lvl.id)}
															<li class="truncate" title={lvl.columnName}>
																<span class="opacity-40">{i + 1}.</span>
																{lvl.columnName}
															</li>
														{/each}
													</ol>
												{/if}
											</li>
										{/each}
									</ul>
								{:else}
									<p class="pl-5 text-[10px] text-muted-foreground italic opacity-70">
										No hierarchies yet
									</p>
								{/if}
							</li>
						{/each}
					</ul>
				{/if}
			</section>
		</div>
	{/if}
{/snippet}

{#snippet confirmCubeModel()}
	<!-- Unified cube view — Semantic (DAG: fact → MG → dim →
	     hierarchies → levels) or Physical (fact table + dim tables
	     with FK↔PK edges).  Two lenses on the same cube; toggle
	     up top picks which renderer mounts.  Defaults to Semantic
	     per Amelia's preference. -->
	{@const cube = selectedCube}
	{@const cubeMGs =
		cube && cube.id === selectedCubeId ? factsMeasureGroups : (cube?.measureGroups ?? [])}
	<div
		class="relative flex min-h-0 flex-1 overflow-hidden rounded border bg-elev-2"
		style:border-color="hsl(var(--border))"
		data-testid="canvas-validate-model"
	>
		<!-- Top-left overlay: Cube · <name> pill + Semantic/Physical
		     toggle, side by side.  Sits above whichever renderer is
		     mounted so it visually anchors the model surface. -->
		<div class="absolute top-2 left-3 z-10 flex items-center gap-2">
			<div
				class="pointer-events-none rounded border bg-elev-2/90 px-2 py-1 font-mono text-[10px] tracking-wider"
				style:border-color="hsl(var(--border))"
				style:color="hsl(var(--muted-foreground))"
				data-testid="canvas-validate-cube-label"
			>
				<span class="uppercase">Cube</span>
				<span class="opacity-50">·</span>
				<span class="font-normal text-foreground normal-case">
					{cube?.name ?? 'Untitled cube'}
				</span>
			</div>
			<div
				class="flex items-stretch overflow-hidden rounded border bg-elev-2/90"
				style:border-color="hsl(var(--border))"
				role="tablist"
				aria-label="Cube view mode"
				data-testid="canvas-validate-model-toggle"
			>
				<!-- Semantic / Physical toggle — third-level nav, softest
				     hierarchy: both segments on the same card surface, only
				     the text color changes (foreground for active, muted
				     grey for inactive).  No red.  Red lives on the
				     top-level Schema Canvas → Confirm cube control. -->
				<button
					type="button"
					role="tab"
					aria-selected={modelViewMode === 'semantic'}
					onclick={() => (modelViewMode = 'semantic')}
					class="inline-flex items-center bg-card px-2 py-1 text-[10px] font-semibold tracking-wide transition-colors {modelViewMode ===
					'semantic'
						? 'text-foreground'
						: 'text-muted-foreground hover:text-foreground'}"
					data-testid="canvas-validate-model-semantic"
				>
					Semantic
				</button>
				<button
					type="button"
					role="tab"
					aria-selected={modelViewMode === 'physical'}
					onclick={() => (modelViewMode = 'physical')}
					class="inline-flex items-center border-l bg-card px-2 py-1 text-[10px] font-semibold tracking-wide transition-colors {modelViewMode ===
					'physical'
						? 'text-foreground'
						: 'text-muted-foreground hover:text-foreground'}"
					style:border-color="hsl(var(--border))"
					data-testid="canvas-validate-model-physical"
				>
					Physical
				</button>
			</div>
		</div>
		<!-- Reset positions moved to the bottom-left overlay.  Bumps a
		     signal both renderers watch to snap positions home. -->
		<button
			type="button"
			onclick={() => (modelResetTs = modelResetTs + 1)}
			class="absolute bottom-2 left-3 z-10 inline-flex items-center gap-1 rounded border border-border bg-card px-2 py-1 text-[10px] font-semibold tracking-wide text-muted-foreground uppercase shadow-sm hover:bg-accent hover:text-accent-foreground"
			data-testid="canvas-validate-model-reset"
			title="Restore the default layout — drops any drags you've done"
		>
			Reset positions
		</button>
		<!-- Force a full remount of the active renderer on cube switch. -->
		<div class="flex min-h-0 flex-1">
			<div class="relative flex min-h-0 flex-1 overflow-hidden">
				<!-- Re-open Tree View chip (floats top-right of the canvas
				     when the panel is hidden).  Same UX as the joins-panel
				     re-open pill on the schema canvas. -->
				{#if outlineCollapsed}
					<button
						type="button"
						onclick={() => (outlineCollapsed = false)}
						class="absolute top-2 right-2 z-10 inline-flex items-center gap-1 rounded border border-border bg-card px-2 py-1 text-[10px] font-semibold tracking-wide text-muted-foreground uppercase shadow-sm hover:bg-accent hover:text-accent-foreground"
						data-testid="canvas-validate-model-tree-panel-reopen"
						title="Show the tree view"
					>
						<ChevronLeft class="h-3 w-3" aria-hidden="true" />
						Tree View
					</button>
				{/if}
				{#key `${modelViewMode}::${cube?.id ?? '__none__'}`}
					{#if modelViewMode === 'semantic'}
						<CubeDag
							{store}
							cubeId={cube?.id ?? null}
							measureGroups={cubeMGs}
							resetSignal={modelResetTs}
							{refreshSignal}
						/>
					{:else}
						<ConfirmCubeCanvas
							{store}
							cubeId={cube?.id ?? null}
							measureGroups={cubeMGs}
							resetSignal={modelResetTs}
							{refreshSignal}
						/>
					{/if}
				{/key}
			</div>
			<!-- RIGHT: cube outline panel — read-only per Tom, styled with
			     colored icons + collapsible sections.  Collapse pattern
			     mirrors the schema canvas joins/source panels: the whole
			     panel disappears (freeing the canvas), and a floating
			     "Tree view" chip appears at the canvas top-right to
			     re-open. -->
			{#if !outlineCollapsed}
				<aside
					class="flex w-64 shrink-0 flex-col overflow-hidden border-l bg-elev-2/40 text-[11px]"
					style:border-color="hsl(var(--border))"
					data-testid="canvas-validate-model-tree-panel"
				>
					<header
						class="flex shrink-0 items-center justify-between gap-2 border-b bg-elev-2 px-2 py-1.5"
						style:border-color="hsl(var(--border))"
					>
						<span class="text-[10px] font-semibold tracking-wider text-muted-foreground uppercase">
							Tree View
						</span>
						<button
							type="button"
							onclick={() => (outlineCollapsed = true)}
							class="shrink-0 rounded p-0.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground"
							aria-label="Hide tree view — open more canvas space"
							title="Hide tree view (use the pill at top-right to re-open)"
							data-testid="canvas-validate-model-tree-panel-collapse"
						>
							<EyeOff class="h-3.5 w-3.5" aria-hidden="true" />
						</button>
					</header>
					<div class="min-h-0 flex-1 overflow-auto p-2">
						{@render cubeOutlinePanel(cube, cubeMGs)}
					</div>
				</aside>
			{/if}
		</div>
	</div>
{/snippet}
