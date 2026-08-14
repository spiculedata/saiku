<script lang="ts">
  /*
   * Embed variant of the `graph` custom tile (App Builder Phase 2, saiku#1441).
   * Token-scoped, read-only, self-contained: it renders inside the
   * <saiku-embed/> bundle, so it uses ONLY relative imports (no `$lib` alias),
   * modular ECharts (to keep the bundle lean — just the GraphChart series), and
   * takes its data as the `rows` prop that <EmbedGrid> fetches through the
   * guarded per-tile embed query path.
   *
   * The author's column mapping is validated with the SAME validator the in-app
   * tile uses (graphTile.ts is pure — no app stores, no `$lib`), then the
   * token-scoped rows are transformed into nodes + links exactly as the in-app
   * tile does.
   */

  import { onMount } from "svelte";
  import { use, init } from "echarts/core";
  import { GraphChart } from "echarts/charts";
  import { TooltipComponent, LegendComponent } from "echarts/components";
  import { CanvasRenderer } from "echarts/renderers";
  import type { EChartsType } from "echarts/types/dist/shared";
  import {
    recordsToGraph,
    validateGraphConfig,
    weightRange,
    nodeSize,
    graphLayoutBox,
    graphLabelExtent,
  } from "../../../../dashboard/custom/graphTile";

  // Register the module set once at module scope (ECharts dedupes repeats).
  use([GraphChart, TooltipComponent, LegendComponent, CanvasRenderer]);

  /** Minimal cell shape (matches the embed EmbedCell) — kept local so this
   *  component doesn't reach into the embed module graph. */
  interface Cell {
    value: number | null;
    formatted: string;
  }
  type Row = Record<string, Cell>;

  interface Props {
    tile: { custom?: { renderer: string; options?: Record<string, unknown> } };
    /** Token-scoped rows from <EmbedGrid>. undefined/null = still loading. */
    rows?: Row[] | null;
  }

  let { tile, rows }: Props = $props();

  let validation = $derived(validateGraphConfig(tile.custom?.options));
  let hasConfig = $derived(
    !!tile.custom?.options && Object.keys(tile.custom.options).length > 0,
  );

  let container = $state<HTMLDivElement | undefined>(undefined);
  let chart: EChartsType | null = null;

  onMount(() => {
    if (!container) return;
    chart = init(container);
    const observer = new ResizeObserver(() => chart?.resize());
    observer.observe(container);
    return () => {
      observer.disconnect();
      chart?.dispose();
      chart = null;
    };
  });


  $effect(() => {
    if (!chart) return;
    if (!validation.ok) {
      chart.clear();
      return;
    }
    const graph = recordsToGraph(rows ?? [], validation.value);
    const option = {
      tooltip: { trigger: "item" },
      series: [
        {
          type: "graph",
          layout: validation.value.layout ?? "force",
          // saiku#1793: reserve room for the outward-drawn node labels, or the
          // ring fills the container and every outer label clips at the edge.
          ...graphLayoutBox(validation.value.layout),
          roam: true,
          draggable: true,
          label: { show: true, position: "right", ...graphLabelExtent() },
          force: { repulsion: 140, edgeLength: 90, gravity: 0.08 },
          circular: { rotateLabel: true },
          emphasis: { focus: "adjacency" },
          lineStyle: { color: "source", curveness: 0.1 },
          data: (() => {
            // Sizing is relative to the weights this graph actually carries
            // (saiku#1755), so the scale works whatever the measure's units.
            const range = weightRange(graph.nodes);
            return graph.nodes.map((n) => ({
              id: n.id,
              name: n.name,
              value: n.value,
              symbolSize: nodeSize(n.value, range),
            }));
          })(),
          links: graph.links.map((l) => ({
            source: l.source,
            target: l.target,
            value: l.value,
          })),
        },
      ],
    };
    chart.setOption(option, { notMerge: true });
  });
</script>

{#if !hasConfig}
  <div class="state muted">No graph mapping configured.</div>
{:else if !validation.ok}
  <div class="state error" role="alert">Invalid graph config: {validation.error}</div>
{:else if rows === undefined || rows === null}
  <div class="state muted">Loading…</div>
{:else}
  <div bind:this={container} class="chart" role="img" aria-label="Saiku embed graph"></div>
{/if}

<style>
  .chart {
    width: 100%;
    height: 100%;
    min-height: 240px;
  }
  .state {
    padding: 12px;
    font-family: system-ui, sans-serif;
    font-size: 13px;
  }
  .state.muted {
    color: var(--saiku-embed-muted, #6b7280);
  }
  .state.error {
    color: var(--saiku-embed-error, #b91c1c);
  }
</style>
