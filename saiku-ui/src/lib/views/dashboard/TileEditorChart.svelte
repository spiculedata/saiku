<script lang="ts">
  /*
   * Chart-tile editor — extracted from TileEditorModal.svelte (saiku#1229).
   * Chart type picker + entry to the chart-options modal. State held as
   * $bindable() props so the parent stays the persistence boundary; the
   * chart-options modal itself is owned by the parent because it's a
   * separate top-level overlay.
   */
  import { CHART_TYPES } from "$lib/views/chartTypes";

  interface Props {
    chartType: string;
    chartOptionsTouched: boolean;
    onOpenChartOptions: () => void;
  }
  let {
    chartType = $bindable(),
    chartOptionsTouched,
    onOpenChartOptions,
  }: Props = $props();
</script>

<label class="field">
  <span>Chart type</span>
  <select bind:value={chartType}>
    {#each CHART_TYPES as ct (ct.id)}
      <option value={ct.id}>{ct.label} ({ct.group})</option>
    {/each}
  </select>
</label>
<!-- #1077: per-tile chart options via the reused workspace editor. -->
<div class="field">
  <span>Chart options</span>
  <button type="button" class="btn chart-opts-btn" onclick={onOpenChartOptions}>
    Title, axes, legend, dual-axis &amp; trend…
  </button>
  <span class="hint">
    {chartOptionsTouched ? "Customised for this tile." : "Using dashboard defaults."}
  </span>
</div>

<style>
  .chart-opts-btn {
    align-self: flex-start;
    text-align: left;
  }
</style>
