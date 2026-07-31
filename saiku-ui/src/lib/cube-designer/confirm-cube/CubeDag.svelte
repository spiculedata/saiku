<!--
  Semantic cube DAG — reflects the CUBE model, not the physical schema.

  Left → right columns:
    1. Fact tables (one card per unique fact-table on this cube)
    2. Measure Groups (each card lists its measures inline)
    3. Linked Dimensions (each card lists its hierarchies + levels inline;
       everything open by default — the whole cube reads at a glance)

  Edges are semantic:
    - fact → MG (which MG uses this fact table)
    - MG → dim (dim linked into this MG via a DimensionLink)

  No physical tables under dims (per Amelia's direction — dim card just
  shows the dim's own name, hierarchies, levels).
-->
<script lang="ts">
	import { SvelteFlow, Background, Controls, type Node, type Edge } from '@xyflow/svelte';
	import '@xyflow/svelte/dist/style.css';
	import { untrack } from 'svelte';
	import type { SchemaCanvasStore } from '../state.svelte';
	import CubeDagFact from './CubeDagFact.svelte';
	import CubeDagMG from './CubeDagMG.svelte';
	import CubeDagDim from './CubeDagDim.svelte';

	interface DagMeasureGroup {
		id: string;
		name?: string;
		factTableId?: string | null;
		measureColumns?: string[];
		dimensionLinks?: Array<{
			dimensionId: string;
			foreignKeyColumn: string;
			linkKind?: 'foreign-key' | 'fact' | 'reference';
		}>;
	}

	interface Props {
		store: SchemaCanvasStore;
		cubeId: string | null;
		measureGroups: DagMeasureGroup[];
		/** Bump counter from the parent — when it changes, reset node
		 *  positions to the computed layout (drops user drags). */
		resetSignal?: number;
		/** Bump counter from the parent — forces `clusters` to re-run
		 *  against the latest MG shape.  Handles the deep-FK mutation
		 *  path that Svelte 5's local-$state proxy doesn't reliably
		 *  invalidate through prop passthrough (#1088). */
		refreshSignal?: number;
	}

	let { store, cubeId, measureGroups, resetSignal = 0, refreshSignal = 0 }: Props = $props();

	const nodeTypes = { 'dag-fact': CubeDagFact, 'dag-mg': CubeDagMG, 'dag-dim': CubeDagDim };

	function tableById(id: string | null | undefined) {
		if (!id) return null;
		return store.doc.tables.find((t) => t.id === id) ?? null;
	}
	function dimById(id: string) {
		return store.dimensions.find((d) => d.id === id) ?? null;
	}

	// Column layout constants
	const FACT_X = 0;
	const MG_X = 340;
	const DIM_X = 700;
	const V_STEP = 40;

	// Rough per-node height so we can lay things out without measuring.
	// Cards are variable-height (each MG's measure list, each dim's
	// hierarchy list) — use a `base + rows * step` approximation.
	const FACT_BASE_H = 72;
	const MG_BASE_H = 68;
	const MG_MEASURE_H = 18;
	const DIM_BASE_H = 60;
	const DIM_HIER_HEAD_H = 24;
	const DIM_LEVEL_H = 18;

	interface FactCluster {
		key: string;
		factId: string | null;
		mgs: DagMeasureGroup[];
	}

	const clusters = $derived.by<FactCluster[]>(() => {
		// Void-read `refreshSignal` so a tab-entry bump forces this
		// derivation to re-run against the latest MG shape (#1088).
		void refreshSignal;
		const byFact = new Map<string, FactCluster>();
		for (const mg of measureGroups) {
			const key = mg.factTableId ?? '__unbound__';
			if (!byFact.has(key)) {
				byFact.set(key, { key, factId: mg.factTableId ?? null, mgs: [] });
			}
			byFact.get(key)!.mgs.push(mg);
		}
		return [...byFact.values()];
	});

	// Every dim linked into ANY of this cube's MGs (deduped, preserving
	// first-seen order — matches how the user encounters them).
	const linkedDimIds = $derived.by(() => {
		const seen = new Set<string>();
		const out: string[] = [];
		for (const mg of measureGroups) {
			for (const link of mg.dimensionLinks ?? []) {
				if (seen.has(link.dimensionId)) continue;
				seen.add(link.dimensionId);
				out.push(link.dimensionId);
			}
		}
		return out;
	});

	const nodes = $derived.by<Node[]>(() => {
		const out: Node[] = [];

		// Column 1 + 2: fact tables and their measure groups, clustered
		// so each fact's MGs stack next to it.
		let yCursor = 0;
		for (const cluster of clusters) {
			const factTable = tableById(cluster.factId);
			const factName = factTable?.name ?? '(fact table not on canvas)';
			const factSubtitle = factTable?.schema ? `${factTable.schema}.${factTable.name}` : null;

			// Compute the vertical span this cluster needs (max of the fact
			// card and the stack of MG cards).
			const clusterMgSpan = cluster.mgs.reduce(
				(acc, mg) => acc + (MG_BASE_H + (mg.measureColumns?.length ?? 0) * MG_MEASURE_H) + V_STEP,
				-V_STEP
			);
			const clusterHeight = Math.max(FACT_BASE_H, clusterMgSpan);

			const factY = yCursor + Math.max(0, (clusterHeight - FACT_BASE_H) / 2);
			out.push({
				id: `fact:${cluster.key}`,
				type: 'dag-fact',
				position: { x: FACT_X, y: factY },
				draggable: true,
				selectable: false,
				data: {
					tableName: factName,
					tableSubtitle: factSubtitle,
					mgNames: cluster.mgs.map((m) => m.name ?? 'Untitled MG')
				}
			});

			let mgY = yCursor;
			for (const mg of cluster.mgs) {
				const measures = mg.measureColumns ?? [];
				const height = MG_BASE_H + measures.length * MG_MEASURE_H;
				out.push({
					id: `mg:${mg.id}`,
					type: 'dag-mg',
					position: { x: MG_X, y: mgY },
					draggable: true,
					selectable: false,
					data: {
						name: mg.name ?? 'Untitled MG',
						measures
					}
				});
				mgY += height + V_STEP;
			}

			yCursor += clusterHeight + V_STEP * 2;
		}

		// Column 3: linked dimensions.  Each card lists its hierarchies +
		// levels inline (everything open by default).
		let dimY = 0;
		for (const dimId of linkedDimIds) {
			const dim = dimById(dimId);
			if (!dim) continue;
			const hiers = dim.hierarchies ?? [];
			const height =
				DIM_BASE_H +
				hiers.reduce((acc, h) => acc + DIM_HIER_HEAD_H + (h.levels?.length ?? 0) * DIM_LEVEL_H, 0);
			out.push({
				id: `dim:${dimId}`,
				type: 'dag-dim',
				position: { x: DIM_X, y: dimY },
				draggable: true,
				selectable: false,
				data: {
					name: dim.name ?? 'Untitled dim',
					hierarchies: hiers.map((h) => ({
						id: h.id,
						name: h.name ?? 'Untitled hierarchy',
						levels: (h.levels ?? []).map((l) => ({
							id: l.id,
							name: l.columnName
						}))
					}))
				}
			});
			dimY += height + V_STEP;
		}

		return out;
	});

	const edges = $derived.by<Edge[]>(() => {
		const out: Edge[] = [];
		// fact → MG
		for (const cluster of clusters) {
			for (const mg of cluster.mgs) {
				out.push({
					id: `e:fact-mg:${cluster.key}:${mg.id}`,
					source: `fact:${cluster.key}`,
					target: `mg:${mg.id}`,
					type: 'default',
					style: 'stroke: hsl(var(--muted-foreground)); stroke-width: 1.25; opacity: 0.7;',
					selectable: false,
					deletable: false
				});
			}
		}
		// MG → dim (via dimension links).  Degenerate 'fact' links get
		// a faint dashed "on fact" line so the semantic view mirrors
		// the physical view — otherwise degenerate dims float without
		// a visible tie to their MG.
		for (const mg of measureGroups) {
			for (const link of mg.dimensionLinks ?? []) {
				const kind = link.linkKind ?? 'foreign-key';
				if (kind === 'fact') {
					out.push({
						id: `e:mg-dim-onfact:${mg.id}:${link.dimensionId}`,
						source: `mg:${mg.id}`,
						target: `dim:${link.dimensionId}`,
						type: 'default',
						label: 'on fact',
						labelStyle: 'font-family: ui-monospace, monospace; font-size: 10px;',
						style:
							'stroke: hsl(var(--muted-foreground)); stroke-width: 1; stroke-dasharray: 4 4; opacity: 0.5;',
						selectable: false,
						deletable: false
					});
					continue;
				}
				out.push({
					id: `e:mg-dim:${mg.id}:${link.dimensionId}`,
					source: `mg:${mg.id}`,
					target: `dim:${link.dimensionId}`,
					type: 'default',
					style: 'stroke: hsl(275 55% 60% / 0.7); stroke-width: 1.5;',
					selectable: false,
					deletable: false
				});
			}
		}
		return out;
	});

	// Sync computed → SvelteFlow bindables.  Preserve user-dragged
	// positions on subsequent data changes; `resetSignal` is a
	// parent-owned bump counter — the parent's Reset positions button
	// changes it, next tick throws away the drags and re-lays-out.
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

	const hasContent = $derived(clusters.length > 0 || linkedDimIds.length > 0);
</script>

<div class="relative h-full w-full" data-testid="cube-dag">
	{#if !cubeId || !hasContent}
		<div
			class="flex h-full flex-col items-center justify-center gap-2 p-4 text-center"
			style:background-color="hsl(var(--muted)/0.2)"
		>
			<p class="text-sm font-medium">Nothing to map yet</p>
			<p class="max-w-md text-[11px] text-muted-foreground">
				Set up a measure group + link at least one dimension on <span class="font-medium"
					>Facts &amp; Measures</span
				>. The DAG will draw itself.
			</p>
		</div>
	{:else}
		<!-- Cube pill, Semantic/Physical toggle, and Reset positions all
		     live in the parent confirmCubeModel wrapper now. -->
		<SvelteFlow
			bind:nodes={flowNodes}
			bind:edges={flowEdges}
			{nodeTypes}
			fitView
			fitViewOptions={{ padding: 0.15 }}
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
