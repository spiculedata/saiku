<!--
  Inspector › Properties tab — extracted from WorkbenchView.svelte (#1039
  stage 2). Metadata inspector for the selected schema object; reads shell
  selection/cube state via props and routes mutations back through store
  methods + callbacks (mutation boundary unchanged). Only
  `selectedTreeMeasureGroupId` is written here, so it is `$bindable`.
-->
<script lang="ts">
	import { Sigma, Database, Hash, ListTree, Layers, ChevronDown, Plus } from '@lucide/svelte';
	import { Select as SelectPrimitive } from 'bits-ui';
	import { SchemaCanvasStore, dimKeyIdentity, resolveKeyAttribute } from './state.svelte.js';
	import type {
		SchemaCanvasDimension,
		SchemaCanvasHierarchy,
		SchemaCanvasLevel,
		SchemaCanvasMeasure
	} from './types.js';
	import type { WorkbenchCube, WorkbenchMeasureGroup } from './workbench-cubes';

	type SchemaCanvasAttribute = NonNullable<SchemaCanvasDimension['attributes']>[number];

	interface Props {
		store: SchemaCanvasStore;
		cubes: WorkbenchCube[];
		selectedCubeId: string;
		factsMeasureGroups: WorkbenchMeasureGroup[];
		selectedDimension: SchemaCanvasDimension | null;
		selectedHierarchy: SchemaCanvasHierarchy | null;
		selectedMeasure: SchemaCanvasMeasure | null;
		selectedAttribute: SchemaCanvasAttribute | null;
		selectedLevel: SchemaCanvasLevel | null;
		selectedTreeMeasureGroup: WorkbenchMeasureGroup | null;
		selectedTreeCube: WorkbenchCube | null;
		hierarchyExplicitlyClicked: boolean;
		renameCube: (id: string, name: string) => void;
		selectTreeMeasureGroup: (cubeId: string, mgId: string) => void;
		addMeasureGroupToCube: (cubeId: string) => void;
		renameHierarchy: (dimId: string, hierId: string, name: string) => void;
		commitHierarchyName: (dimId: string, hierId: string) => void;
		renameDimension: (id: string, name: string) => void;
		commitDimensionName: (id: string) => void;
		tableNameFor: (tableId: string) => string;
		columnTypeFor: (tableId: string, columnName: string) => string | undefined;
	}

	let {
		store,
		cubes,
		selectedCubeId,
		factsMeasureGroups,
		selectedDimension: dim,
		selectedHierarchy: hier,
		selectedMeasure: measure,
		selectedAttribute: attr,
		selectedLevel: level,
		selectedTreeMeasureGroup: treeMG,
		selectedTreeCube: treeCube,
		hierarchyExplicitlyClicked,
		renameCube,
		selectTreeMeasureGroup,
		addMeasureGroupToCube,
		renameHierarchy,
		commitHierarchyName,
		renameDimension,
		commitDimensionName,
		tableNameFor,
		columnTypeFor
	}: Props = $props();

	const AGGREGATORS: SchemaCanvasMeasure['aggregator'][] = [
		'sum',
		'count',
		'avg',
		'min',
		'max',
		'distinct-count',
		'median',
		'percentile'
	];

	// ── saiku.semantic.* annotation editing ──
	// Merge one key into an element's annotation map (blank ⇒ delete the key;
	// empty map ⇒ drop the field). Keyed by the bare suffix (e.g. 'description').
	function mergeAnn(
		current: Record<string, string> | undefined,
		key: string,
		value: string
	): Record<string, string> | undefined {
		const next = { ...(current ?? {}) };
		if (value.trim()) next[key] = value.trim();
		else delete next[key];
		return Object.keys(next).length > 0 ? next : undefined;
	}
	function setMeasureAnn(m: SchemaCanvasMeasure, key: string, value: string) {
		store.updateMeasure(m.id, { annotations: mergeAnn(m.annotations, key, value) });
	}
	function setDimAnn(d: SchemaCanvasDimension, key: string, value: string) {
		store.updateDimension(d.id, { annotations: mergeAnn(d.annotations, key, value) });
	}
	function setLevelAnn(
		dimId: string,
		hierId: string,
		lvl: { id: string; annotations?: Record<string, string> },
		key: string,
		value: string
	) {
		store.updateLevel(dimId, hierId, lvl.id, {
			annotations: mergeAnn(lvl.annotations, key, value)
		});
	}
</script>

{#snippet propField(
	label: string,
	value: string,
	onInput: (v: string) => void,
	onBlur?: () => void,
	placeholder?: string,
	testid?: string
)}
	<label class="flex flex-col gap-1">
		<span class="text-[10px] tracking-wide uppercase" style:color="hsl(var(--muted-foreground))">
			{label}
		</span>
		<input
			type="text"
			{value}
			{placeholder}
			oninput={(e) => onInput(e.currentTarget.value)}
			onblur={onBlur}
			class="h-7 rounded border border-border bg-background px-2 font-mono text-[11px] text-foreground focus-visible:border-ring focus-visible:ring-1 focus-visible:ring-ring focus-visible:outline-none"
			data-testid={testid}
		/>
	</label>
{/snippet}

{#snippet propTextarea(label: string, value: string, onInput: (v: string) => void)}
	<label class="col-span-full flex flex-col gap-1">
		<span class="text-[10px] tracking-wide uppercase" style:color="hsl(var(--muted-foreground))">
			{label}
		</span>
		<textarea
			{value}
			rows={2}
			oninput={(e) => onInput(e.currentTarget.value)}
			class="min-h-14 rounded border border-border bg-background p-2 text-[11px] text-foreground focus-visible:border-ring focus-visible:ring-1 focus-visible:ring-ring focus-visible:outline-none"
		></textarea>
	</label>
{/snippet}

{#snippet columnSelect(
	label: string,
	value: string,
	columns: { name: string }[],
	onChange: (v: string | undefined) => void,
	testid: string
)}
	<label class="flex flex-col gap-1">
		<span class="text-[10px] tracking-wide uppercase" style:color="hsl(var(--muted-foreground))">
			{label}
		</span>
		<select
			{value}
			onchange={(e) => onChange(e.currentTarget.value || undefined)}
			class="h-7 rounded border border-border bg-background px-2 font-mono text-[11px] text-foreground focus-visible:border-ring focus-visible:ring-1 focus-visible:ring-ring focus-visible:outline-none"
			data-testid={testid}
		>
			<option value="">—</option>
			{#each columns as col (col.name)}
				<option value={col.name}>{col.name}</option>
			{/each}
		</select>
	</label>
{/snippet}

{#snippet propGroupHeader(title: string, description: string)}
	<header class="flex flex-col gap-0.5">
		<span class="text-[11px] font-semibold tracking-wider text-foreground uppercase">
			{title}
		</span>
		{#if description}
			<span class="text-[10px] leading-snug" style:color="hsl(var(--muted-foreground))">
				{description}
			</span>
		{/if}
	</header>
{/snippet}

<div
	class="@container flex h-full flex-col gap-5 text-xs"
	data-testid="workbench-inspector-properties"
>
	{#if treeMG}
		<!-- <MeasureGroup> properties — metadata only.  Authoring lives
			     in the Measure Groups pane (Columns view); Properties is
			     just the thing's metadata, identical in shape to Measure /
			     Dimension props above.  Keeping Properties metadata-only
			     across both views means the inspector reads the same
			     regardless of which view the user is in. -->
		{@const treeMGFact = treeMG.factTableId
			? (store.doc.tables.find((t) => t.id === treeMG.factTableId)?.name ?? '—')
			: '—'}
		<header class="flex items-center gap-1.5">
			<Sigma class="h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
			<span
				class="text-[10px] font-semibold tracking-wider uppercase"
				style:color="hsl(var(--muted-foreground))"
			>
				&lt;MeasureGroup&gt;
			</span>
		</header>
		<div
			class="grid grid-cols-1 gap-3 @[500px]:grid-cols-2 @[800px]:grid-cols-3 @[1100px]:grid-cols-4"
		>
			{@render propField('name', treeMG.name, (v) => {
				treeMG.name = v;
			})}
			<div class="flex flex-col gap-1">
				<span
					class="text-[10px] tracking-wide uppercase"
					style:color="hsl(var(--muted-foreground))"
				>
					fact table
				</span>
				<span class="h-7 truncate rounded border border-border bg-muted/40 px-2 py-1 font-mono">
					{treeMGFact}
				</span>
			</div>
			<div class="flex flex-col gap-1">
				<span
					class="text-[10px] tracking-wide uppercase"
					style:color="hsl(var(--muted-foreground))"
				>
					measures
				</span>
				<span class="h-7 truncate rounded border border-border bg-muted/40 px-2 py-1 font-mono">
					{treeMG.measureColumns.length}
				</span>
			</div>
			<div class="flex flex-col gap-1">
				<span
					class="text-[10px] tracking-wide uppercase"
					style:color="hsl(var(--muted-foreground))"
				>
					dim links
				</span>
				<span class="h-7 truncate rounded border border-border bg-muted/40 px-2 py-1 font-mono">
					{(treeMG.dimensionLinks ?? []).length}
				</span>
			</div>
		</div>
		<p class="shrink-0 text-[10px] leading-snug" style:color="hsl(var(--muted-foreground))">
			Author this measure group in the Measure Groups pane (Columns view).
		</p>
	{:else if treeCube && !measure && !attr && !hier && !dim}
		<!-- <Cube> properties — surfaced when a cube root node is
			     selected with nothing more specific.  Rename input +
			     a compact list of MGs with quick add; the per-MG
			     editor lives one click away on the MG row.  Mirrors
			     the inspectorCubes affordances scoped to a single
			     cube so the user doesn't have to leave the Properties
			     tab to manage MGs. -->
		{@const cubeMGs = treeCube.id === selectedCubeId ? factsMeasureGroups : treeCube.measureGroups}
		{@const docCube = store.cubes.find((c) => c.id === treeCube.id)}
		{@const cubeMeasures = [...new Set(cubeMGs.flatMap((g) => g.measureColumns))]}
		<header class="flex items-center gap-1.5">
			<Database class="h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
			<span
				class="text-[10px] font-semibold tracking-wider uppercase"
				style:color="hsl(var(--muted-foreground))"
			>
				&lt;Cube&gt;
			</span>
		</header>
		<div
			class="grid grid-cols-1 gap-3 @[500px]:grid-cols-2 @[800px]:grid-cols-3 @[1100px]:grid-cols-4"
		>
			{@render propField('name', treeCube.name, (v) => renameCube(treeCube.id, v))}
			<div class="flex flex-col gap-1">
				<span
					class="text-[10px] tracking-wide uppercase"
					style:color="hsl(var(--muted-foreground))"
				>
					measure groups
				</span>
				<span class="h-7 truncate rounded border border-border bg-muted/40 px-2 py-1 font-mono">
					{(treeCube.id === selectedCubeId ? factsMeasureGroups : treeCube.measureGroups).length}
				</span>
			</div>
		</div>
		<div class="flex min-h-0 flex-1 flex-col gap-1.5 overflow-hidden">
			<div class="flex shrink-0 items-center justify-between gap-2">
				<span
					class="text-[10px] font-semibold tracking-wider uppercase"
					style:color="hsl(var(--muted-foreground))"
				>
					Measure groups
				</span>
				<button
					type="button"
					onclick={() => addMeasureGroupToCube(treeCube.id)}
					class="inline-flex shrink-0 items-center gap-1 rounded border px-2 py-0.5 text-[10px] font-semibold tracking-wider uppercase hover:bg-accent/40"
					style:border-color="hsl(var(--border))"
					data-testid="workbench-inspector-cube-mg-add"
				>
					<Plus class="h-2.5 w-2.5" aria-hidden="true" />
					Add
				</button>
			</div>
			<div
				class="flex min-h-0 flex-1 flex-col overflow-y-auto rounded border bg-elev-2"
				style:border-color="hsl(var(--border))"
			>
				{#if cubeMGs.length === 0}
					<p class="p-3 text-[11px]" style:color="hsl(var(--muted-foreground))">
						No measure groups yet.
					</p>
				{:else}
					{#each cubeMGs as mg (mg.id)}
						<button
							type="button"
							onclick={() => selectTreeMeasureGroup(treeCube.id, mg.id)}
							class="flex items-center gap-2 border-b px-2 py-1.5 text-left text-[11px] hover:bg-accent/40"
							style:border-color="hsl(var(--border))"
							data-testid="workbench-inspector-cube-mg-row"
							data-mg-id={mg.id}
						>
							<Sigma class="h-3 w-3 shrink-0 text-primary" aria-hidden="true" />
							<span class="min-w-0 flex-1 truncate font-medium">
								{mg.name || 'Untitled group'}
							</span>
							<span
								class="shrink-0 font-mono text-[9px]"
								style:color="hsl(var(--muted-foreground))"
							>
								{mg.measureColumns.length} m · {(mg.dimensionLinks ?? []).length} d
							</span>
						</button>
					{/each}
				{/if}
			</div>
		</div>
		<!-- Time calculations — declarative YoY / PoP / YTD / rolling metrics
		     (Mondrian 4 <TimeCalc>). Cube-scoped, edited on the doc via the
		     store; requires a Time dimension with a Years level in the cube. -->
		<div class="flex flex-col gap-2">
			<div class="flex items-center justify-between gap-2">
				<span class="text-[11px] font-semibold tracking-wider text-foreground uppercase">
					Time calculations
				</span>
				<button
					type="button"
					onclick={() => store.addTimeCalc(treeCube.id, { measure: cubeMeasures[0] ?? '' })}
					class="inline-flex shrink-0 items-center gap-1 rounded border px-2 py-0.5 text-[10px] font-semibold tracking-wider uppercase hover:bg-accent/40"
					style:border-color="hsl(var(--border))"
					data-testid="inspector-timecalc-add"
				>
					<Plus class="h-2.5 w-2.5" aria-hidden="true" />
					Add
				</button>
			</div>
			<p class="text-[10px] leading-snug" style:color="hsl(var(--muted-foreground))">
				YoY / PoP / YTD / rolling over a measure. Needs a Time dimension with a
				<span class="font-medium">Years</span> level (set level types on the dimension's hierarchy).
			</p>
			{#each docCube?.timeCalcs ?? [] as tc (tc.id)}
				<div
					class="flex flex-col gap-1.5 rounded border border-border bg-background p-2"
					data-testid="inspector-timecalc-row"
				>
					<div class="flex items-center gap-2">
						<input
							type="text"
							value={tc.name}
							placeholder="Name (e.g. Revenue YoY)"
							oninput={(e) =>
								store.updateTimeCalc(treeCube.id, tc.id, { name: e.currentTarget.value })}
							class="h-6 min-w-0 flex-1 rounded border border-border bg-background px-2 text-[11px] text-foreground focus-visible:border-ring focus-visible:ring-1 focus-visible:ring-ring focus-visible:outline-none"
						/>
						<button
							type="button"
							onclick={() => store.removeTimeCalc(treeCube.id, tc.id)}
							class="shrink-0 rounded p-0.5 text-muted-foreground hover:bg-accent hover:text-destructive"
							title="Remove"
							aria-label="Remove time calculation"
						>
							×
						</button>
					</div>
					<div class="flex gap-2">
						<select
							value={tc.type}
							onchange={(e) =>
								store.updateTimeCalc(treeCube.id, tc.id, {
									type: e.currentTarget.value as 'yoy' | 'pop' | 'ytd' | 'rolling'
								})}
							class="h-6 flex-1 rounded border border-border bg-background px-1 text-[10px] text-foreground"
							title="Metric type"
						>
							<option value="yoy">YoY (year-over-year)</option>
							<option value="pop">PoP (period-over-period)</option>
							<option value="ytd">YTD (year-to-date)</option>
							<option value="rolling">Rolling window</option>
						</select>
						<select
							value={tc.measure}
							onchange={(e) =>
								store.updateTimeCalc(treeCube.id, tc.id, { measure: e.currentTarget.value })}
							class="h-6 flex-1 rounded border border-border bg-background px-1 text-[10px] text-foreground"
							title="Measure"
						>
							{#if cubeMeasures.length === 0}
								<option value="">— add a measure first —</option>
							{/if}
							{#each cubeMeasures as m (m)}
								<option value={m}>{m}</option>
							{/each}
						</select>
					</div>
					{#if tc.type === 'rolling'}
						<div class="flex gap-2">
							<input
								type="number"
								min="1"
								value={tc.window ?? ''}
								placeholder="window (periods)"
								oninput={(e) => {
									const v = e.currentTarget.value;
									store.updateTimeCalc(treeCube.id, tc.id, {
										window: v === '' ? undefined : Math.max(1, Number(v))
									});
								}}
								class="h-6 flex-1 rounded border border-border bg-background px-2 text-[10px] text-foreground tabular-nums"
							/>
							<select
								value={tc.function ?? 'sum'}
								onchange={(e) =>
									store.updateTimeCalc(treeCube.id, tc.id, {
										function: e.currentTarget.value as 'sum' | 'avg'
									})}
								class="h-6 flex-1 rounded border border-border bg-background px-1 text-[10px] text-foreground"
								title="Window aggregation"
							>
								<option value="sum">sum</option>
								<option value="avg">avg</option>
							</select>
						</div>
					{/if}
					<input
						type="text"
						value={tc.formatString ?? ''}
						placeholder="Format string (e.g. 0.0%)"
						oninput={(e) =>
							store.updateTimeCalc(treeCube.id, tc.id, {
								formatString: e.currentTarget.value || undefined
							})}
						class="h-6 rounded border border-border bg-background px-2 text-[11px] text-foreground focus-visible:border-ring focus-visible:ring-1 focus-visible:ring-ring focus-visible:outline-none"
					/>
				</div>
			{/each}
		</div>
	{:else if measure}
		<!-- <Measure> properties — name, aggregator, formatString.  Column
			     binding shown read-only (set at drop time; changing column
			     means making a new measure). -->
		<header class="flex items-center gap-1.5">
			<Hash class="h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
			<span
				class="text-[10px] font-semibold tracking-wider uppercase"
				style:color="hsl(var(--muted-foreground))"
			>
				&lt;Measure&gt;
			</span>
		</header>
		<div
			class="grid grid-cols-1 gap-3 @[500px]:grid-cols-2 @[800px]:grid-cols-3 @[1100px]:grid-cols-4"
		>
			{@render propField('name', measure.name, (v) => store.updateMeasure(measure.id, { name: v }))}
			<div class="flex flex-col gap-1">
				<span
					class="text-[10px] tracking-wide uppercase"
					style:color="hsl(var(--muted-foreground))"
				>
					aggregator
				</span>
				<SelectPrimitive.Root
					type="single"
					value={measure.aggregator}
					onValueChange={(v) =>
						store.updateMeasure(measure.id, {
							aggregator: v as SchemaCanvasMeasure['aggregator']
						})}
				>
					<SelectPrimitive.Trigger
						class="inline-flex h-7 items-center justify-between gap-1 rounded-md border border-border bg-background px-2 text-left text-[11px] hover:bg-accent focus-visible:border-ring focus-visible:ring-1 focus-visible:ring-ring focus-visible:outline-none"
					>
						<span class="truncate">{measure.aggregator}</span>
						<ChevronDown class="h-3 w-3 shrink-0 opacity-60" aria-hidden="true" />
					</SelectPrimitive.Trigger>
					<SelectPrimitive.Portal>
						<SelectPrimitive.Content
							class="z-50 flex max-h-64 min-w-40 flex-col overflow-y-auto rounded-md border border-border bg-popover p-1 text-xs shadow-md"
							sideOffset={4}
						>
							{#each AGGREGATORS as agg (agg)}
								<SelectPrimitive.Item
									value={agg}
									class="cursor-pointer rounded px-2 py-1.5 outline-none data-[highlighted]:bg-accent data-[highlighted]:text-accent-foreground data-[state=checked]:font-semibold"
								>
									{agg}
								</SelectPrimitive.Item>
							{/each}
						</SelectPrimitive.Content>
					</SelectPrimitive.Portal>
				</SelectPrimitive.Root>
			</div>
			<div class="flex flex-col gap-1">
				<span
					class="text-[10px] tracking-wide uppercase"
					style:color="hsl(var(--muted-foreground))"
				>
					column
				</span>
				<span class="h-7 truncate rounded border border-border bg-muted/40 px-2 py-1 font-mono">
					{tableNameFor(measure.tableId)}.{measure.columnName}
				</span>
			</div>
			{@render propField(
				'formatString',
				measure.formatString ?? '',
				(v) => store.updateMeasure(measure.id, { formatString: v.trim() || undefined }),
				undefined,
				'$#,##0.00'
			)}
		</div>
		<!-- saiku.semantic.* annotations — optional AI/governance metadata. -->
		{@const mAnn = measure.annotations ?? {}}
		<div class="flex flex-col gap-2 border-t border-border pt-3">
			{@render propGroupHeader(
				'Annotations',
				'Optional AI & governance metadata (saiku.semantic.*).'
			)}
			{@render propField('description', mAnn.description ?? '', (v) =>
				setMeasureAnn(measure, 'description', v)
			)}
			{@render propField(
				'synonyms',
				mAnn.synonyms ?? '',
				(v) => setMeasureAnn(measure, 'synonyms', v),
				undefined,
				'revenue, sales, turnover'
			)}
			<div class="grid grid-cols-2 gap-2">
				{@render propField(
					'unit',
					mAnn.unit ?? '',
					(v) => setMeasureAnn(measure, 'unit', v),
					undefined,
					'USD, kWh, count'
				)}
				{@render propField(
					'currency',
					mAnn.currency ?? '',
					(v) => setMeasureAnn(measure, 'currency', v),
					undefined,
					'USD'
				)}
			</div>
			<label class="flex flex-col gap-1">
				<span
					class="text-[10px] tracking-wide uppercase"
					style:color="hsl(var(--muted-foreground))"
				>
					aggregation kind
				</span>
				<select
					value={mAnn.aggregation_kind ?? ''}
					onchange={(e) => setMeasureAnn(measure, 'aggregation_kind', e.currentTarget.value)}
					class="h-7 rounded border border-border bg-background px-1 text-[11px] text-foreground"
				>
					<option value="">—</option>
					<option value="sum">sum</option>
					<option value="count">count</option>
					<option value="distinct-count">distinct-count</option>
					<option value="non-additive">non-additive</option>
				</select>
			</label>
			<label class="flex items-center gap-2 text-[11px]">
				<input
					type="checkbox"
					checked={mAnn.pii === 'true'}
					onchange={(e) => setMeasureAnn(measure, 'pii', e.currentTarget.checked ? 'true' : '')}
					data-testid="inspector-measure-pii"
				/>
				<span>PII — redact values in AI / drillthrough / embed</span>
			</label>
		</div>
	{:else if level && dim && hier}
		<!-- <Level> properties — the user clicked a level chip in the Hierarchies
		     pane. Rename (logical name), caption (display label), the Time-grain
		     `levelType`, and the saiku.semantic.* annotations all live here. -->
		{@const lAnn = level.annotations ?? {}}
		<header class="flex items-center gap-1.5">
			<ListTree class="h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
			<span
				class="text-[10px] font-semibold tracking-wider uppercase"
				style:color="hsl(var(--muted-foreground))"
			>
				&lt;Level&gt; · {dim.name} / {hier.name}
			</span>
		</header>
		<div class="flex flex-col gap-2">
			<label class="flex flex-col gap-1 text-[10px] tracking-wide uppercase">
				<span style:color="hsl(var(--muted-foreground))">name</span>
				<input
					type="text"
					value={level.name}
					placeholder={level.columnName}
					oninput={(e) =>
						store.updateLevel(dim.id, hier.id, level.id, {
							name: e.currentTarget.value.trim() || level.columnName
						})}
					class="h-7 rounded border border-border bg-background px-2 text-[11px] text-foreground focus-visible:border-ring focus-visible:ring-1 focus-visible:ring-ring focus-visible:outline-none"
					data-testid="inspector-level-name"
				/>
			</label>
			<label class="flex flex-col gap-1 text-[10px] tracking-wide uppercase">
				<span style:color="hsl(var(--muted-foreground))">caption</span>
				<input
					type="text"
					value={level.caption ?? ''}
					placeholder="display label (optional)"
					oninput={(e) =>
						store.updateLevel(dim.id, hier.id, level.id, {
							caption: e.currentTarget.value.trim() || undefined
						})}
					class="h-7 rounded border border-border bg-background px-2 text-[11px] text-foreground focus-visible:border-ring focus-visible:ring-1 focus-visible:ring-ring focus-visible:outline-none"
					data-testid="inspector-level-caption"
				/>
			</label>
			<div class="flex flex-col gap-1">
				<span
					class="text-[10px] tracking-wide uppercase"
					style:color="hsl(var(--muted-foreground))"
				>
					column
				</span>
				<span
					class="h-7 truncate rounded border border-border bg-muted/40 px-2 py-1 font-mono text-[11px]"
				>
					{tableNameFor(level.tableId)}.{level.columnName}
				</span>
			</div>
			{#if dim.dimensionType === 'Time'}
				<label class="flex flex-col gap-1 text-[10px] tracking-wide uppercase">
					<span style:color="hsl(var(--muted-foreground))">level type (time grain)</span>
					<select
						value={level.levelType ?? ''}
						onchange={(e) => {
							const v = e.currentTarget.value;
							store.updateLevel(dim.id, hier.id, level.id, {
								levelType:
									v === ''
										? undefined
										: (v as 'TimeYears' | 'TimeQuarters' | 'TimeMonths' | 'TimeDays')
							});
						}}
						class="h-7 rounded border border-border bg-background px-2 text-[11px] text-foreground focus-visible:border-ring focus-visible:ring-1 focus-visible:ring-ring focus-visible:outline-none"
						data-testid="inspector-level-leveltype-single"
					>
						<option value="">— type —</option>
						<option value="TimeYears">Years</option>
						<option value="TimeQuarters">Quarters</option>
						<option value="TimeMonths">Months</option>
						<option value="TimeDays">Days</option>
					</select>
				</label>
			{/if}
		</div>
		<div class="flex flex-col gap-2 border-t border-border pt-2">
			<div
				class="text-[10px] font-medium tracking-wide uppercase"
				style:color="hsl(var(--muted-foreground))"
			>
				Annotations (AI &amp; governance)
			</div>
			<input
				type="text"
				value={lAnn.description ?? ''}
				placeholder="description"
				oninput={(e) => setLevelAnn(dim.id, hier.id, level, 'description', e.currentTarget.value)}
				class="h-7 rounded border border-border bg-background px-2 text-[11px] text-foreground"
			/>
			<input
				type="text"
				value={lAnn.synonyms ?? ''}
				placeholder="synonyms (csv)"
				oninput={(e) => setLevelAnn(dim.id, hier.id, level, 'synonyms', e.currentTarget.value)}
				class="h-7 rounded border border-border bg-background px-2 text-[11px] text-foreground"
			/>
			<div class="grid grid-cols-2 gap-1.5">
				<select
					value={lAnn.cardinality ?? ''}
					onchange={(e) =>
						setLevelAnn(dim.id, hier.id, level, 'cardinality', e.currentTarget.value)}
					class="h-7 rounded border border-border bg-background px-1 text-[11px] text-foreground"
					title="Member-count hint"
				>
					<option value="">cardinality —</option>
					<option value="low">low</option>
					<option value="medium">medium</option>
					<option value="high">high</option>
				</select>
				<select
					value={lAnn.grain ?? ''}
					onchange={(e) => setLevelAnn(dim.id, hier.id, level, 'grain', e.currentTarget.value)}
					class="h-7 rounded border border-border bg-background px-1 text-[11px] text-foreground"
					title="Time grain"
				>
					<option value="">grain —</option>
					<option value="year">year</option>
					<option value="quarter">quarter</option>
					<option value="month">month</option>
					<option value="week">week</option>
					<option value="day">day</option>
					<option value="hour">hour</option>
					<option value="minute">minute</option>
				</select>
			</div>
			<label class="flex items-center gap-2 text-[11px]">
				<input
					type="checkbox"
					checked={lAnn.pii === 'true'}
					onchange={(e) =>
						setLevelAnn(dim.id, hier.id, level, 'pii', e.currentTarget.checked ? 'true' : '')}
					data-testid="inspector-level-pii-single"
				/>
				<span>PII — redact members in AI / drillthrough / embed</span>
			</label>
		</div>
	{:else if attr && dim}
		<!-- <Attribute> properties (#959) — the logical name plus the optional M4
		     display / sort / caption column overrides and a description. The key
		     column is the attribute's identity (how levels + the fact link resolve
		     to it) so it stays read-only; re-pick the attribute to change it. -->
		{@const attrCols = store.doc.tables.find((t) => t.id === attr.tableId)?.columns ?? []}
		<header class="flex items-center gap-1.5">
			<Database class="h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
			<span
				class="text-[10px] font-semibold tracking-wider uppercase"
				style:color="hsl(var(--muted-foreground))"
			>
				&lt;Attribute&gt; · {dim.name}
			</span>
		</header>
		<div
			class="grid grid-cols-1 gap-3 @[500px]:grid-cols-2 @[800px]:grid-cols-3 @[1100px]:grid-cols-4"
		>
			{@render propField(
				'name',
				attr.name ?? attr.columnName,
				(v) =>
					store.updateAttribute(dim.id, attr.tableId, attr.columnName, {
						name: v.trim() || undefined
					}),
				undefined,
				attr.columnName,
				'inspector-attr-name'
			)}
			<div class="flex flex-col gap-1">
				<span
					class="text-[10px] tracking-wide uppercase"
					style:color="hsl(var(--muted-foreground))"
				>
					key column
				</span>
				<span class="h-7 truncate rounded border border-border bg-muted/40 px-2 py-1 font-mono">
					{tableNameFor(attr.tableId)}.{attr.columnName}
				</span>
			</div>
			<div class="flex flex-col gap-1">
				<span
					class="text-[10px] tracking-wide uppercase"
					style:color="hsl(var(--muted-foreground))"
				>
					sql type
				</span>
				<span class="h-7 truncate rounded border border-border bg-muted/40 px-2 py-1 font-mono">
					{columnTypeFor(attr.tableId, attr.columnName) || '—'}
				</span>
			</div>
			{@render columnSelect(
				'name column (display)',
				attr.nameColumn ?? '',
				attrCols,
				(v) => store.updateAttribute(dim.id, attr.tableId, attr.columnName, { nameColumn: v }),
				'inspector-attr-namecolumn'
			)}
			{@render columnSelect(
				'order by',
				attr.orderByColumn ?? '',
				attrCols,
				(v) => store.updateAttribute(dim.id, attr.tableId, attr.columnName, { orderByColumn: v }),
				'inspector-attr-orderby'
			)}
			{@render columnSelect(
				'caption column',
				attr.captionColumn ?? '',
				attrCols,
				(v) => store.updateAttribute(dim.id, attr.tableId, attr.columnName, { captionColumn: v }),
				'inspector-attr-captioncolumn'
			)}
		</div>
		{@render propTextarea('description', attr.description ?? '', (v) =>
			store.updateAttribute(dim.id, attr.tableId, attr.columnName, {
				description: v.trim() || undefined
			})
		)}
	{:else if hier && dim && hierarchyExplicitlyClicked}
		<header class="flex items-center gap-1.5">
			<ListTree class="h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
			<span
				class="text-[10px] font-semibold tracking-wider uppercase"
				style:color="hsl(var(--muted-foreground))"
			>
				&lt;Hierarchy&gt; · {dim.name}
			</span>
		</header>
		<div
			class="grid grid-cols-1 gap-3 @[500px]:grid-cols-2 @[800px]:grid-cols-3 @[1100px]:grid-cols-4"
		>
			{@render propField(
				'name',
				hier.name,
				(v) => renameHierarchy(dim.id, hier.id, v),
				() => commitHierarchyName(dim.id, hier.id)
			)}
			<div class="flex flex-col gap-1">
				<span
					class="text-[10px] tracking-wide uppercase"
					style:color="hsl(var(--muted-foreground))"
				>
					hasAll
				</span>
				<SelectPrimitive.Root
					type="single"
					value={String(hier.hasAll)}
					onValueChange={(v) =>
						store.updateHierarchy(dim.id, hier.id, {
							hasAll: v === 'true'
						})}
				>
					<SelectPrimitive.Trigger
						class="inline-flex h-7 items-center justify-between gap-1 rounded-md border border-border bg-background px-2 text-left text-[11px] hover:bg-accent focus-visible:border-ring focus-visible:ring-1 focus-visible:ring-ring focus-visible:outline-none"
					>
						<span class="truncate">{String(hier.hasAll)}</span>
						<ChevronDown class="h-3 w-3 shrink-0 opacity-60" aria-hidden="true" />
					</SelectPrimitive.Trigger>
					<SelectPrimitive.Portal>
						<SelectPrimitive.Content
							class="z-50 flex min-w-40 flex-col overflow-y-auto rounded-md border border-border bg-popover p-1 text-xs shadow-md"
							sideOffset={4}
						>
							{#each ['true', 'false'] as opt (opt)}
								<SelectPrimitive.Item
									value={opt}
									class="cursor-pointer rounded px-2 py-1.5 outline-none data-[highlighted]:bg-accent data-[highlighted]:text-accent-foreground data-[state=checked]:font-semibold"
								>
									{opt}
								</SelectPrimitive.Item>
							{/each}
						</SelectPrimitive.Content>
					</SelectPrimitive.Portal>
				</SelectPrimitive.Root>
			</div>
			{@render propField(
				'allMemberName',
				hier.allMemberName ?? '',
				(v) =>
					store.updateHierarchy(dim.id, hier.id, {
						allMemberName: v.trim() || undefined
					}),
				undefined,
				'(All)',
				'inspector-hierarchy-allmembername'
			)}
			{@render propField(
				'defaultMember',
				hier.defaultMember ?? '',
				(v) =>
					store.updateHierarchy(dim.id, hier.id, {
						defaultMember: v.trim() || undefined
					}),
				undefined,
				'[Customer].[All]'
			)}
			{@render propField(
				'caption',
				hier.caption ?? '',
				(v) => store.updateHierarchy(dim.id, hier.id, { caption: v.trim() || undefined }),
				undefined,
				'Display label',
				'inspector-hierarchy-caption'
			)}
			{@render propTextarea('description', hier.description ?? '', (v) =>
				store.updateHierarchy(dim.id, hier.id, {
					description: v.trim() || undefined
				})
			)}
		</div>
		<!-- Levels — with a per-level `levelType` picker for Time dimensions.
		     Marking Year/Quarter/Month/Day is the prerequisite for time
		     intelligence (a TimeYears level is required by <TimeCalc>). -->
		<div class="flex flex-col gap-1.5">
			<div
				class="text-[10px] font-medium tracking-wide uppercase"
				style:color="hsl(var(--muted-foreground))"
			>
				Levels ({hier.levels.length})
			</div>
			{#each hier.levels as lvl (lvl.id)}
				{@const lAnn = lvl.annotations ?? {}}
				<div class="flex flex-col gap-1.5 rounded border border-border bg-background px-2 py-1.5">
					<div class="flex items-center justify-between gap-2">
						<span class="min-w-0 flex-1 truncate text-[11px]" title={lvl.columnName}
							>{lvl.name}</span
						>
						{#if dim.dimensionType === 'Time'}
							<select
								class="h-6 shrink-0 rounded border border-border bg-background px-1 text-[10px] text-foreground focus-visible:border-ring focus-visible:ring-1 focus-visible:ring-ring focus-visible:outline-none"
								value={lvl.levelType ?? ''}
								onchange={(e) => {
									const v = e.currentTarget.value;
									store.updateLevel(dim.id, hier.id, lvl.id, {
										levelType:
											v === ''
												? undefined
												: (v as 'TimeYears' | 'TimeQuarters' | 'TimeMonths' | 'TimeDays')
									});
								}}
								title="Level type — mark the time grain so the engine can drive time intelligence"
								data-testid="inspector-level-leveltype"
							>
								<option value="">— type —</option>
								<option value="TimeYears">Years</option>
								<option value="TimeQuarters">Quarters</option>
								<option value="TimeMonths">Months</option>
								<option value="TimeDays">Days</option>
							</select>
						{/if}
					</div>
					<!-- Level saiku.semantic.* annotations (optional). -->
					<input
						type="text"
						value={lAnn.description ?? ''}
						placeholder="description"
						oninput={(e) => setLevelAnn(dim.id, hier.id, lvl, 'description', e.currentTarget.value)}
						class="h-6 rounded border border-border bg-background px-2 text-[10px] text-foreground"
					/>
					<input
						type="text"
						value={lAnn.synonyms ?? ''}
						placeholder="synonyms (csv)"
						oninput={(e) => setLevelAnn(dim.id, hier.id, lvl, 'synonyms', e.currentTarget.value)}
						class="h-6 rounded border border-border bg-background px-2 text-[10px] text-foreground"
					/>
					<div class="grid grid-cols-2 gap-1.5">
						<select
							value={lAnn.cardinality ?? ''}
							onchange={(e) =>
								setLevelAnn(dim.id, hier.id, lvl, 'cardinality', e.currentTarget.value)}
							class="h-6 rounded border border-border bg-background px-1 text-[10px] text-foreground"
							title="Member-count hint"
						>
							<option value="">cardinality —</option>
							<option value="low">low</option>
							<option value="medium">medium</option>
							<option value="high">high</option>
						</select>
						<select
							value={lAnn.grain ?? ''}
							onchange={(e) => setLevelAnn(dim.id, hier.id, lvl, 'grain', e.currentTarget.value)}
							class="h-6 rounded border border-border bg-background px-1 text-[10px] text-foreground"
							title="Time grain (drives date-filter + time-series inference)"
						>
							<option value="">grain —</option>
							<option value="year">year</option>
							<option value="quarter">quarter</option>
							<option value="month">month</option>
							<option value="week">week</option>
							<option value="day">day</option>
							<option value="hour">hour</option>
							<option value="minute">minute</option>
						</select>
					</div>
					<label class="flex items-center gap-2 text-[10px]">
						<input
							type="checkbox"
							checked={lAnn.pii === 'true'}
							onchange={(e) =>
								setLevelAnn(dim.id, hier.id, lvl, 'pii', e.currentTarget.checked ? 'true' : '')}
							data-testid="inspector-level-pii"
						/>
						<span>PII — redact members in AI / drillthrough / embed</span>
					</label>
				</div>
			{/each}
			{#if dim.dimensionType !== 'Time'}
				<p class="text-[10px]" style:color="hsl(var(--muted-foreground))">
					Set the dimension type to <span class="font-medium">Time</span> (on the dimension) to assign
					level types and unlock time intelligence.
				</p>
			{/if}
		</div>
	{:else if dim}
		{@const boundTable =
			(dim.sourceTableId ?? dim.primaryKeyTableId)
				? store.doc.tables.find((t) => t.id === (dim.sourceTableId ?? dim.primaryKeyTableId))
				: null}
		{@const isDegenerate = !boundTable}
		{@const dimHasHierarchies = dim.hierarchies.length > 0}
		{@const needsKey = !isDegenerate && dimHasHierarchies}
		{@const inspectorDimKey = dimKeyIdentity(dim)}
		{@const inspectorResolvedKey = resolveKeyAttribute(dim)}
		<!-- Alert / "required" pill fire when the schema carries a key
			     value the app can't tie back to a specific attribute — that
			     covers both "no value" (dimKey null) and "ambiguous value"
			     (dimKey present but resolveKeyAttribute returned null). -->
		{@const keyMissing = needsKey && !inspectorResolvedKey}
		{@const inspectorKeyAttr = inspectorResolvedKey?.attr ?? null}
		<!-- Select's active value must match one of the Select.Items —
			     items use attr.name ?? attr.columnName.  When resolution
			     failed but a raw value is stored, leave the value empty
			     so the trigger falls back to the "Unset (no key)" display
			     rather than silently locking to the wrong attribute. -->
		{@const inspectorSelectValue = inspectorKeyAttr
			? (inspectorKeyAttr.name ?? inspectorKeyAttr.columnName)
			: ''}
		<header class="flex items-center gap-1.5">
			<Layers class="h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
			<span
				class="text-[10px] font-semibold tracking-wider uppercase"
				style:color="hsl(var(--muted-foreground))"
			>
				&lt;Dimension&gt;
			</span>
		</header>
		<!-- Field groups (#1063) — Identity / Binding / Behavior sit
		     side-by-side as columns (wrap at narrow widths).  Fields
		     within each group stack vertically.  Documentation
		     (long-form textarea) drops to a full-width row below so
		     it gets breathing room to wrap. -->
		<div
			class="grid grid-cols-1 gap-x-8 gap-y-6 @[500px]:grid-cols-2 @[900px]:grid-cols-3"
			data-testid="workbench-inspector-groups"
		>
			<!-- Identity column -->
			<div class="flex flex-col gap-2">
				{@render propGroupHeader(
					'Identity',
					'What this dimension is called and how it displays to users.'
				)}
				<div class="flex flex-col gap-2">
					{@render propField(
						'name',
						dim.name,
						(v) => renameDimension(dim.id, v),
						() => commitDimensionName(dim.id)
					)}
					{@render propField(
						'caption',
						dim.caption ?? '',
						(v) => store.updateDimension(dim.id, { caption: v.trim() || undefined }),
						undefined,
						'Display label'
					)}
				</div>
			</div>

			<!-- Binding column -->
			<div class="flex flex-col gap-2">
				{@render propGroupHeader(
					'Binding',
					'Where this dimension comes from and which attribute is the join key.'
				)}
				<div class="flex flex-col gap-2">
					<!-- Table is read-only — the source is set by picking
					     a table in the Dimensions list. -->
					<div class="flex flex-col gap-1">
						<span
							class="text-[10px] tracking-wide uppercase"
							style:color="hsl(var(--muted-foreground))"
						>
							table
						</span>
						<span class="h-7 truncate rounded border border-border bg-muted/40 px-2 py-1 font-mono">
							{dim.sourceJoinGroupKey ?? boundTable?.name ?? '(unbound)'}
						</span>
					</div>
					<!--
						Primary key is a dim-level dropdown — pick which
						attribute carries the join column.  Mondrian's
						model: ONE attribute per dim is the key.
					-->
					<div class="flex flex-col gap-1">
						<span
							class="text-[10px] tracking-wide uppercase {keyMissing ? 'text-warning' : ''}"
							style:color={keyMissing ? undefined : 'hsl(var(--muted-foreground))'}
						>
							primary key
							{#if keyMissing}<span class="ml-1 normal-case">· required</span>{/if}
						</span>
						<SelectPrimitive.Root
							type="single"
							value={inspectorSelectValue}
							onValueChange={(v) => {
								const attr = (dim.attributes ?? []).find((a) => (a.name ?? a.columnName) === v);
								store.updateDimension(dim.id, {
									primaryKey: v || undefined,
									primaryKeyTableId: attr?.tableId,
									foreignKey: attr?.columnName || v || undefined
								});
							}}
						>
							<SelectPrimitive.Trigger
								class="inline-flex h-7 items-center justify-between gap-1 rounded-md border border-border bg-background px-2 text-left font-mono text-[11px] hover:bg-accent focus-visible:border-ring focus-visible:ring-1 focus-visible:ring-ring focus-visible:outline-none"
								data-testid="workbench-dim-primary-key-select"
							>
								<span class="truncate">
									{#if inspectorKeyAttr}
										{inspectorKeyAttr.name ?? inspectorKeyAttr.columnName}
									{:else if inspectorDimKey}
										<span class="text-warning">{inspectorDimKey}</span>
										<span class="ml-1 text-[10px] text-muted-foreground italic">
											· ambiguous — pick one
										</span>
									{:else}
										<span class="text-muted-foreground italic">Unset (no key)</span>
									{/if}
								</span>
								<ChevronDown class="h-3 w-3 shrink-0 opacity-60" aria-hidden="true" />
							</SelectPrimitive.Trigger>
							<SelectPrimitive.Portal>
								<SelectPrimitive.Content
									class="z-50 flex max-h-64 min-w-40 flex-col overflow-y-auto rounded-md border border-border bg-popover p-1 text-xs shadow-md"
									sideOffset={4}
								>
									<SelectPrimitive.Item
										value=""
										class="cursor-pointer rounded px-2 py-1.5 outline-none data-[highlighted]:bg-accent data-[highlighted]:text-accent-foreground data-[state=checked]:font-semibold"
									>
										<span class="text-muted-foreground italic">Unset (no key)</span>
									</SelectPrimitive.Item>
									{#each dim.attributes ?? [] as a, __pki (`${a.tableId}::${a.columnName}::${a.name ?? __pki}`)}
										{@const attrId = a.name ?? a.columnName}
										<SelectPrimitive.Item
											value={attrId}
											class="cursor-pointer rounded px-2 py-1.5 font-mono outline-none data-[highlighted]:bg-accent data-[highlighted]:text-accent-foreground data-[state=checked]:font-semibold"
										>
											{a.name ?? a.columnName}
										</SelectPrimitive.Item>
									{/each}
								</SelectPrimitive.Content>
							</SelectPrimitive.Portal>
						</SelectPrimitive.Root>
					</div>
				</div>
			</div>

			<!-- Behavior column -->
			<div class="flex flex-col gap-2">
				{@render propGroupHeader('Behavior', 'How Mondrian treats this dimension at query time.')}
				<div class="flex flex-col gap-2">
					<div class="flex flex-col gap-1">
						<span
							class="text-[10px] tracking-wide uppercase"
							style:color="hsl(var(--muted-foreground))"
						>
							type
						</span>
						<SelectPrimitive.Root
							type="single"
							value={dim.dimensionType ?? 'Standard'}
							onValueChange={(v) =>
								store.updateDimension(dim.id, {
									dimensionType: v as 'Standard' | 'Time' | 'Geographic'
								})}
						>
							<SelectPrimitive.Trigger
								class="inline-flex h-7 items-center justify-between gap-1 rounded-md border border-border bg-background px-2 text-left text-[11px] hover:bg-accent focus-visible:border-ring focus-visible:ring-1 focus-visible:ring-ring focus-visible:outline-none"
							>
								<span class="truncate">{dim.dimensionType ?? 'Standard'}</span>
								<ChevronDown class="h-3 w-3 shrink-0 opacity-60" aria-hidden="true" />
							</SelectPrimitive.Trigger>
							<SelectPrimitive.Portal>
								<SelectPrimitive.Content
									class="z-50 flex min-w-40 flex-col overflow-y-auto rounded-md border border-border bg-popover p-1 text-xs shadow-md"
									sideOffset={4}
								>
									{#each ['Standard', 'Time', 'Geographic'] as opt (opt)}
										<SelectPrimitive.Item
											value={opt}
											class="cursor-pointer rounded px-2 py-1.5 outline-none data-[highlighted]:bg-accent data-[highlighted]:text-accent-foreground data-[state=checked]:font-semibold"
										>
											{opt}
										</SelectPrimitive.Item>
									{/each}
								</SelectPrimitive.Content>
							</SelectPrimitive.Portal>
						</SelectPrimitive.Root>
					</div>
				</div>
			</div>
		</div>

		<!-- Documentation — full-width row below the 3-column grid
		     so the description textarea has room to wrap. -->
		<div class="flex flex-col gap-2">
			{@render propGroupHeader(
				'Documentation',
				'Free-form notes surfaced in the schema catalog and MCP tooling.'
			)}
			<div class="flex flex-col gap-2">
				<label class="flex flex-col gap-1">
					<span
						class="text-[10px] tracking-wide uppercase"
						style:color="hsl(var(--muted-foreground))"
					>
						description
					</span>
					<textarea
						value={dim.description ?? ''}
						rows={2}
						oninput={(e) =>
							store.updateDimension(dim.id, {
								description: e.currentTarget.value.trim() || undefined
							})}
						class="min-h-14 rounded border bg-background p-2 text-[11px]"
						style:border-color="hsl(var(--border))"
					></textarea>
				</label>
			</div>
		</div>
		{@const dAnn = dim.annotations ?? {}}
		<div class="flex flex-col gap-2 border-t border-border pt-3">
			{@render propGroupHeader('Annotations', 'Optional AI context (saiku.semantic.*).')}
			{@render propField(
				'AI description',
				dAnn.description ?? '',
				(v) => setDimAnn(dim, 'description', v),
				undefined,
				'Business meaning surfaced to the AI'
			)}
			{@render propField(
				'synonyms',
				dAnn.synonyms ?? '',
				(v) => setDimAnn(dim, 'synonyms', v),
				undefined,
				'store, location, site'
			)}
		</div>
		{#if needsKey && keyMissing}
			<p
				class="rounded border border-warning bg-warning/10 px-2 py-1 text-[10px] text-warning"
				data-testid="workbench-dim-key-warning"
			>
				Mondrian needs a join key for a dimension with hierarchies — the engine will reject this
				schema at load.
			</p>
		{/if}

		<!-- Used-in-cubes — cross-cube aggregation showing where this
			     dim is linked, with the FK column / link kind / via* for
			     each.  Read-only summary so the user can see at a glance
			     whether their multi-cube schema is consistent.  Editing
			     stays on the MG editor (single source of truth for FK). -->
		{@const usages = (() => {
			const out: Array<{
				cubeName: string;
				mgName: string;
				linkKind: 'foreign-key' | 'fact' | 'reference';
				fk: string;
				via?: string;
			}> = [];
			for (const c of cubes) {
				const mgs = c.id === selectedCubeId ? factsMeasureGroups : c.measureGroups;
				for (const mg of mgs) {
					for (const link of mg.dimensionLinks ?? []) {
						if (link.dimensionId !== dim.id) continue;
						out.push({
							cubeName: c.name,
							mgName: mg.name,
							linkKind: link.linkKind ?? 'foreign-key',
							fk: link.foreignKeyColumn,
							via:
								link.viaDimension && link.viaAttribute
									? `${link.viaDimension}.${link.viaAttribute}`
									: undefined
						});
					}
				}
			}
			return out;
		})()}
		<div class="flex flex-col gap-1.5">
			<span
				class="text-[10px] font-semibold tracking-wider uppercase"
				style:color="hsl(var(--muted-foreground))"
			>
				Used in cubes
				{#if usages.length > 0}
					<span class="font-mono normal-case">· {usages.length}</span>
				{/if}
			</span>
			{#if usages.length === 0}
				<p
					class="rounded border border-dashed p-2 text-center text-[10px]"
					style:border-color="hsl(var(--border))"
					style:color="hsl(var(--muted-foreground))"
				>
					This dimension isn't linked from any measure group yet.
				</p>
			{:else}
				<div
					class="grid grid-cols-[1fr_1fr_4.5rem_1fr] gap-x-2 gap-y-0.5 rounded border bg-elev-2 p-2 text-[10px]"
					style:border-color="hsl(var(--border))"
					data-testid="workbench-dim-used-in-cubes"
				>
					<span
						class="font-semibold tracking-wider uppercase"
						style:color="hsl(var(--muted-foreground))">Cube</span
					>
					<span
						class="font-semibold tracking-wider uppercase"
						style:color="hsl(var(--muted-foreground))">Measure group</span
					>
					<span
						class="font-semibold tracking-wider uppercase"
						style:color="hsl(var(--muted-foreground))">Kind</span
					>
					<span
						class="font-semibold tracking-wider uppercase"
						style:color="hsl(var(--muted-foreground))">Foreign key</span
					>
					{#each usages as u, i (i)}
						<span class="truncate font-medium">{u.cubeName}</span>
						<span class="truncate font-mono">{u.mgName}</span>
						<span
							class="truncate font-mono"
							style:color={u.linkKind === 'fact' || u.linkKind === 'reference'
								? 'hsl(var(--primary))'
								: undefined}
						>
							{u.linkKind}
						</span>
						<span class="truncate font-mono">
							{#if u.linkKind === 'fact'}
								—
							{:else if u.via}
								{u.via}
							{:else}
								{u.fk || '(not set)'}
							{/if}
						</span>
					{/each}
				</div>
			{/if}
		</div>

		<div class="flex flex-col gap-1 text-[10px]" style:color="hsl(var(--muted-foreground))">
			{(dim.attributes ?? []).length} attribute(s) · {dim.hierarchies.length} hierarchy/ies
		</div>
	{:else}
		<div class="flex h-full flex-col items-center justify-center gap-1 text-center">
			<p class="text-xs font-medium">Nothing selected</p>
			<p class="text-[11px]" style="color: hsl(var(--muted-foreground))">
				Click a dimension, hierarchy, attribute, or measure to inspect its properties.
			</p>
		</div>
	{/if}
</div>
