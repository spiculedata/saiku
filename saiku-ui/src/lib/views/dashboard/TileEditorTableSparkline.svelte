<script lang="ts">
  /*
   * Table-tile sparkline column config — extracted from TileEditorModal.svelte
   * (saiku#1229; original feature saiku#920). Opt-in trailing "Trend" column
   * that draws a tiny inline chart per row from the row's numeric measure
   * cells. Geometry maths lives in $lib/dashboard/sparkline.ts; this is
   * config capture only.
   */
  import type { SparklineType } from "$lib/api/dashboards";

  interface Props {
    sparklineEnabled: boolean;
    sparklineType: SparklineType;
  }
  let {
    sparklineEnabled = $bindable(),
    sparklineType = $bindable(),
  }: Props = $props();
</script>

<fieldset class="cf-section">
  <legend>Sparkline column</legend>
  <span class="hint">
    Adds a trailing &ldquo;Trend&rdquo; column drawing a mini chart per row from
    that row's numeric measure values. Needs at least two measure columns to
    render; rows with fewer numeric values show a dash.
  </span>

  <label class="checkbox">
    <input type="checkbox" bind:checked={sparklineEnabled} />
    <span>Show sparkline column</span>
  </label>

  {#if sparklineEnabled}
    <label class="field flex-1">
      <span class="field__label">Style</span>
      <select class="field__input" bind:value={sparklineType}>
        <option value="line">Line</option>
        <option value="bar">Bar</option>
      </select>
    </label>
  {/if}
</fieldset>

<style>
  /* Duplicated from parent because Svelte's scoped CSS does not reach
     child component templates. */
  .cf-section {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
    border: 1px solid hsl(var(--border));
    border-radius: 4px;
    padding: 0.5rem 0.75rem;
    margin: 0;
  }
  .cf-section legend {
    font-size: 0.75rem;
    color: hsl(var(--fg-muted));
    text-transform: uppercase;
    letter-spacing: 0.04em;
    padding: 0 0.25rem;
  }
  /* saiku#1258: .checkbox now comes from the parent modal's
     `.modal :global(.checkbox)` rule. */
</style>
