<!--
  Confirm cube canvas — purpose-built visualization of ONE cube.

    - Purple fact-table card(s) in the middle
    - Every linked dim card arrayed around the fact
    - Multiple measure groups sharing a fact table share a card (the
      fact card lists each MG name as a chip)
    - Multiple fact tables (a cube with MGs on different facts) each
      get their own cluster, stacked vertically
    - Edges labelled with the FK column
    - Degenerate dims get a FactLink badge (no external edge)

  No drag, no source sidebar, no pick mode.  Just the cube.
-->
<script lang="ts">
	import {
		SvelteFlow,
		Background,
		Controls,
		type Node,
		type Edge,
		MarkerType
	} from '@xyflow/svelte';
	import '@xyflow/svelte/dist/style.css';
	import { untrack } from 'svelte';
	import type { SchemaCanvasStore } from '../state.svelte';
	import FactCard from './FactCard.svelte';
	import DimCard from './DimCard.svelte';
	import FKLabelEdge from './FKLabelEdge.svelte';

	interface ConfirmMeasureGroup {
		id: string;
		name?: string;
		factTableId?: string | null;
		dimensionLinks?: Array<{
			dimensionId: string;
			foreignKeyColumn: string;
			linkKind?: 'foreign-key' | 'fact' | 'reference';
		}>;
	}

	interface Props {
		store: SchemaCanvasStore;
		cubeId: string | null;
		measureGroups: ConfirmMeasureGroup[];
		/** Bump counter from the parent — when it changes, reset node
		 *  positions to the computed layout (drops user drags). */
		resetSignal?: number;
		/** Bump counter from the parent — forces every downstream
		 *  derivation to re-run against the latest MG shape.  Belt-
		 *  and-braces refresh for FK / dim-link mutations that
		 *  Svelte 5's deep proxy doesn't propagate reliably through
		 *  the local MG state (#1088). */
		refreshSignal?: number;
	}

	let { store, cubeId, measureGroups, resetSignal = 0, refreshSignal = 0 }: Props = $props();

	const nodeTypes = { fact: FactCard, dim: DimCard };
	 
	const edgeTypes = { 'fk-label': FKLabelEdge } as any;

	function resolveTable(id: string | null | undefined) {
		if (!id) return null;
		return store.doc.tables.find((t) => t.id === id) ?? null;
	}
	function resolveDim(id: string) {
		return store.dimensions.find((d) => d.id === id) ?? null;
	}

	// Group MGs by their fact table so a fact shared across MGs shows
	// as ONE card with multiple MG chips (mirrors Mondrian semantics).
	const clusters = $derived.by(() => {
		// Void-read `refreshSignal` so a tab-entry bump forces this
		// derivation to re-run against the latest measureGroups shape
		// (#1088).  Otherwise deep FK / dim-link mutations upstream
		// don't reliably invalidate the derivation.
		void refreshSignal;
		const byFact = new Map<
			string,
			{
				factTable: ReturnType<typeof resolveTable>;
				mgs: ConfirmMeasureGroup[];
				dimLinks: Array<{
					mgId: string;
					dimensionId: string;
					foreignKeyColumn: string;
					linkKind: 'foreign-key' | 'fact' | 'reference';
				}>;
			}
		>();
		for (const mg of measureGroups) {
			const key = mg.factTableId ?? '__unbound__';
			if (!byFact.has(key)) {
				byFact.set(key, { factTable: resolveTable(mg.factTableId), mgs: [], dimLinks: [] });
			}
			const bucket = byFact.get(key)!;
			bucket.mgs.push(mg);
			for (const link of mg.dimensionLinks ?? []) {
				bucket.dimLinks.push({
					mgId: mg.id,
					dimensionId: link.dimensionId,
					foreignKeyColumn: link.foreignKeyColumn,
					linkKind: link.linkKind ?? 'foreign-key'
				});
			}
		}
		return [...byFact.entries()].map(([key, v]) => ({ key, ...v }));
	});

	// Layout: for each cluster, put the fact in the middle and stack
	// dims to the right in a vertical column (kept simple + readable).
	// Clusters themselves stack vertically with generous padding.
	const CLUSTER_V_GAP = 120;
	const FACT_X = 0;
	const DIM_X = 380;
	const DIM_STEP = 96;

	const nodes = $derived.by<Node[]>(() => {
		const out: Node[] = [];
		let yCursor = 0;
		for (const cluster of clusters) {
			const seenDimId = new Set<string>();
			// De-dupe dim links so a dim used by multiple MGs on the same
			// fact table renders once (the fact card already shows every
			// MG name).
			const uniqDims = cluster.dimLinks.filter((l) => {
				if (seenDimId.has(l.dimensionId)) return false;
				seenDimId.add(l.dimensionId);
				return true;
			});
			const nDims = Math.max(1, uniqDims.length);
			const clusterHeight = (nDims - 1) * DIM_STEP;
			const factY = yCursor + clusterHeight / 2;
			const factTable = cluster.factTable;
			const factName = factTable?.name ?? '(fact table not on canvas)';
			const factSubtitle = factTable?.schema ? `${factTable.schema}.${factTable.name}` : null;
			out.push({
				id: `fact:${cluster.key}`,
				type: 'fact',
				position: { x: FACT_X, y: factY },
				draggable: true,
				selectable: false,
				data: {
					tableName: factName,
					tableSubtitle: factSubtitle,
					mgNames: cluster.mgs.map((m) => m.name ?? 'Untitled MG')
				}
			});
			let dimY = yCursor;
			for (const l of uniqDims) {
				const dim = resolveDim(l.dimensionId);
				const dimSrcId = dim?.sourceTableId ?? dim?.primaryKeyTableId ?? null;
				const dimTable = resolveTable(dimSrcId);
				const isDegenerate =
					l.linkKind === 'fact' || (!!factTable && !!dimSrcId && dimSrcId === factTable.id);
				out.push({
					id: `dim:${cluster.key}:${l.dimensionId}`,
					type: 'dim',
					position: { x: DIM_X, y: dimY },
					draggable: true,
					selectable: false,
					data: {
						dimName: dim?.name ?? '(missing dim)',
						tableName: dimTable?.name ?? null,
						tableSubtitle: dimTable?.schema
							? `${dimTable.schema}.${dimTable.name}`
							: (dimTable?.name ?? null),
						isDegenerate,
						fkColumn: l.foreignKeyColumn || null,
						primaryKey: dim?.primaryKey ?? null
					}
				});
				dimY += DIM_STEP;
			}
			yCursor += Math.max(DIM_STEP, clusterHeight) + CLUSTER_V_GAP;
		}
		return out;
	});

	const edges = $derived.by<Edge[]>(() => {
		const out: Edge[] = [];
		for (const cluster of clusters) {
			const seen = new Set<string>();
			for (const l of cluster.dimLinks) {
				if (seen.has(l.dimensionId)) continue;
				seen.add(l.dimensionId);
				const dim = resolveDim(l.dimensionId);
				const dimSrcId = dim?.sourceTableId ?? dim?.primaryKeyTableId ?? null;
				const isDegenerate =
					l.linkKind === 'fact' ||
					(!!cluster.factTable && !!dimSrcId && dimSrcId === cluster.factTable.id);
				if (isDegenerate) {
					// FactLink dim — no external FK/PK.  Faint dashed grey
					// line to its MG's fact table so the user can tell
					// WHICH cube/MG this factlink belongs to when multiple
					// MGs are on different fact tables.  Label reads
					// "on fact" so the pattern is obvious in plain English.
					out.push({
						id: `e:factlink:${cluster.key}:${l.dimensionId}`,
						source: `fact:${cluster.key}`,
						target: `dim:${cluster.key}:${l.dimensionId}`,
						type: 'fk-label',
						data: { label: 'on fact' },
						style:
							'stroke: hsl(var(--muted-foreground)); stroke-width: 1; stroke-dasharray: 4 4; opacity: 0.5;',
						selectable: false,
						deletable: false
					});
					continue;
				}
				const label = `${l.foreignKeyColumn || '?'} → ${dim?.primaryKey ?? '?'}`;
				out.push({
					id: `e:${cluster.key}:${l.dimensionId}`,
					source: `fact:${cluster.key}`,
					target: `dim:${cluster.key}:${l.dimensionId}`,
					// Custom edge — renders the FK→PK label via foreignObject
					// with real CSS so the text is guaranteed readable on any
					// theme.  SvelteFlow's default edge label uses an SVG
					// <rect>+<text> that ignored our labelStyle/labelBgStyle
					// and rendered as an opaque white block covering the text.
					type: 'fk-label',
					data: { label },
					style: 'stroke: hsl(275 55% 60% / 0.7); stroke-width: 1.5;',
					markerEnd: { type: MarkerType.ArrowClosed, color: 'hsl(275 55% 60%)' },
					selectable: false,
					deletable: false
				});
			}
		}
		return out;
	});

	// Sync computed → SvelteFlow bindables.  Preserve user-dragged
	// positions on subsequent data changes; `resetSignal` (parent-owned
	// bump counter) resets to computed layout when it changes.
	let flowNodes = $state<Node[]>([]);
	let flowEdges = $state<Edge[]>([]);
	$effect(() => {
		const computed = nodes;
		const reset = resetSignal;
		untrack(() => {
			if (reset > 0) {
				flowNodes = computed;
				return;
			}
			const posById = new Map(flowNodes.map((n) => [n.id, n.position]));
			flowNodes = computed.map((n) => {
				const savedPos = posById.get(n.id);
				return savedPos ? { ...n, position: savedPos } : n;
			});
		});
	});
	$effect(() => {
		flowEdges = edges;
	});

	const hasContent = $derived(clusters.length > 0);
</script>

<div class="relative h-full w-full" data-testid="confirm-cube-canvas">
	{#if !cubeId || !hasContent}
		<div
			class="flex h-full flex-col items-center justify-center gap-2 p-4 text-center"
			style:background-color="hsl(var(--muted)/0.2)"
		>
			<p class="text-sm font-medium">Nothing to confirm yet</p>
			<p class="max-w-md text-[11px] text-muted-foreground">
				Head to <span class="font-medium">Facts &amp; Measures</span> and set up a measure group — its
				fact table and linked dimensions will appear here.
			</p>
		</div>
	{:else}
		<!-- Reset positions button lives in the parent confirmCubeModel
		     wrapper now (bottom-left overlay of the model container). -->
		<SvelteFlow
			bind:nodes={flowNodes}
			bind:edges={flowEdges}
			{nodeTypes}
			{edgeTypes}
			fitView
			fitViewOptions={{ padding: 0.2 }}
			panOnDrag
			zoomOnScroll
			nodesDraggable
			nodesConnectable={false}
			edgesFocusable={false}
			proOptions={{ hideAttribution: true }}
			class="!bg-background"
		>
			<Background />
			<Controls position="bottom-right" orientation="vertical" showLock={false} />
		</SvelteFlow>
	{/if}
</div>
