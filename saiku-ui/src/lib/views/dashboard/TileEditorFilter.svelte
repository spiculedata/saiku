<script lang="ts">
  /*
   * Filter-tile editor — extracted from TileEditorModal.svelte (saiku#1229).
   * Widget picker + dimension/hierarchy/level + cascading-select config.
   * Option lists (dimensions / hierarchies / levels) are passed in as
   * arrays so the delegate doesn't need direct access to cube metadata —
   * the parent resolves them once via cubeMetadata + handles the cascade
   * filtering by current selections.
   *
   * Issue #922 — cascading-select widget extracted here per #1229's split.
   */
  import type { FilterWidget } from "$lib/api/dashboards";

  interface Props {
    widget: FilterWidget;
    filterTarget: { dimension: string; hierarchy: string; level: string };
    cascadeStartLevel: string;
    cascadeDepth: number;
    /** True when a cube is picked; disables the pickers otherwise. */
    cubePicked: boolean;
    dimensions: string[];
    hierarchies: string[];
    levels: string[];
  }
  let {
    widget = $bindable(),
    filterTarget = $bindable(),
    cascadeStartLevel = $bindable(),
    cascadeDepth = $bindable(),
    cubePicked,
    dimensions,
    hierarchies,
    levels,
  }: Props = $props();
</script>

<label class="field">
  <span class="field__label">Widget</span>
  <select class="field__input" bind:value={widget}>
    <option value="single-select">single-select</option>
    <option value="multi-select">multi-select</option>
    <option value="date-range">date-range</option>
    <option value="cascading-select">cascading-select</option>
  </select>
</label>
<label class="field">
  <span class="field__label">Dimension</span>
  <select class="field__input" bind:value={filterTarget.dimension} disabled={!cubePicked}>
    <option value="">— pick —</option>
    {#each dimensions as d, _id (_id)}
      <option value={d}>{d}</option>
    {/each}
  </select>
</label>
<label class="field">
  <span class="field__label">Hierarchy</span>
  <select class="field__input" bind:value={filterTarget.hierarchy} disabled={!filterTarget.dimension}>
    <option value="">— pick —</option>
    {#each hierarchies as h, _ih (_ih)}
      <option value={h}>{h}</option>
    {/each}
  </select>
</label>
<label class="field">
  <span class="field__label">Level</span>
  <select class="field__input" bind:value={filterTarget.level} disabled={!filterTarget.hierarchy}>
    <option value="">— pick —</option>
    {#each levels as l, _il (_il)}
      <option value={l}>{l}</option>
    {/each}
  </select>
</label>
<!-- issue #922: cascading-select config. -->
{#if widget === "cascading-select"}
  <fieldset class="size">
    <legend>Cascade (walk the hierarchy level-by-level)</legend>
    <label class="field flex-1">
      <span class="field__label">Start level</span>
      <select class="field__input" bind:value={cascadeStartLevel} disabled={!filterTarget.hierarchy}>
        <option value="">— use level above —</option>
        {#each levels as l, _il (_il)}
          <option value={l}>{l}</option>
        {/each}
      </select>
    </label>
    <label class="field flex-1">
      <span class="field__label">Depth</span>
      <input class="field__input" type="number" min="1" max="6" bind:value={cascadeDepth} />
    </label>
  </fieldset>
  <span class="hint">
    Renders one dropdown per level from the start level down. Picking a
    parent reveals its children; choosing &ldquo;All&rdquo; resets the levels below.
  </span>
{/if}
