<script lang="ts">
  /*
   * KPI-tile editor — extracted from TileEditorModal.svelte (saiku#1229).
   * Measure picker + format + comparison + time-level picker + threshold
   * colouring. State is held as a single $bindable() kpiConfig object so
   * the parent persists the deeply-nested shape unchanged.
   *
   * Option lists (measures / dimensions / hierarchies / levels) are
   * passed in. The cube-metadata resolution stays in the parent — the
   * delegate is pure UI.
   */
  import type { KpiConfig } from "$lib/api/dashboards";

  interface Props {
    kpiConfig: KpiConfig;
    cubePicked: boolean;
    measures: { name: string; label: string }[];
    dimensions: string[];
    hierarchies: string[];
    levels: string[];
  }
  let {
    kpiConfig = $bindable(),
    cubePicked,
    measures,
    dimensions,
    hierarchies,
    levels,
  }: Props = $props();

  const showTimeLevel = $derived(
    kpiConfig.comparison === "prior-period" ||
      kpiConfig.comparison === "year-over-year" ||
      kpiConfig.sparkline,
  );
</script>

<label class="field">
  <span>Measure</span>
  <select
    value={kpiConfig.measure ?? ""}
    disabled={!cubePicked || measures.length === 0}
    onchange={(e) => {
      const picked = (e.target as HTMLSelectElement).value;
      const opt = measures.find((m) => m.name === picked);
      kpiConfig.measure = picked || undefined;
      kpiConfig.measureCaption = opt?.label;
    }}
  >
    <option value="">— pick a measure —</option>
    {#each measures as m (m.name)}
      <option value={m.name}>{m.label}</option>
    {/each}
  </select>
</label>

<label class="field">
  <span>Format</span>
  <select bind:value={kpiConfig.format}>
    <option value="number">Number</option>
    <option value="currency">Currency</option>
    <option value="percent">Percent</option>
    <option value="custom">Custom pattern</option>
  </select>
</label>
{#if kpiConfig.format === "custom"}
  <label class="field">
    <span>Custom pattern</span>
    <input
      type="text"
      bind:value={kpiConfig.customFormat}
      placeholder="e.g. $2 / 2% / 3"
    />
    <span class="hint">
      $N / €N / £N for currency, N% for percent, plain N for fractional digits.
    </span>
  </label>
{/if}

<fieldset class="size">
  <legend>Comparison</legend>
  <label class="field inline">
    <span>Mode</span>
    <select bind:value={kpiConfig.comparison}>
      <option value="none">None</option>
      <option value="prior-period">Prior period</option>
      <option value="year-over-year">Year over year</option>
      <option value="target">Target value</option>
    </select>
  </label>
  {#if kpiConfig.comparison === "target"}
    <label class="field inline">
      <span>Target</span>
      <input
        type="number"
        bind:value={kpiConfig.target}
        placeholder="e.g. 100000"
      />
    </label>
  {/if}
  <label class="field inline">
    <span>Direction</span>
    <select bind:value={kpiConfig.direction}>
      <option value="higher-is-better">Higher is better</option>
      <option value="lower-is-better">Lower is better</option>
    </select>
  </label>
</fieldset>

<label class="checkbox">
  <input type="checkbox" bind:checked={kpiConfig.sparkline} />
  <span>Sparkline (mini line chart under the number)</span>
</label>

{#if showTimeLevel}
  <fieldset class="size">
    <legend>Time level (for comparison + sparkline)</legend>
    <label class="field inline">
      <span>Dimension</span>
      <select bind:value={kpiConfig.timeLevel!.dimension} disabled={!cubePicked}>
        <option value="">— pick —</option>
        {#each dimensions as d (d)}
          <option value={d}>{d}</option>
        {/each}
      </select>
    </label>
    <label class="field inline">
      <span>Hierarchy</span>
      <select
        bind:value={kpiConfig.timeLevel!.hierarchy}
        disabled={!kpiConfig.timeLevel?.dimension}
      >
        <option value="">— pick —</option>
        {#each hierarchies as h (h)}
          <option value={h}>{h}</option>
        {/each}
      </select>
    </label>
    <label class="field inline">
      <span>Level</span>
      <select
        bind:value={kpiConfig.timeLevel!.level}
        disabled={!kpiConfig.timeLevel?.hierarchy}
      >
        <option value="">— pick —</option>
        {#each levels as l (l)}
          <option value={l}>{l}</option>
        {/each}
      </select>
    </label>
  </fieldset>
{/if}

<fieldset class="size">
  <legend>Threshold colouring (optional)</legend>
  <label class="field inline">
    <span>Red ≤ / ≥</span>
    <input
      type="number"
      bind:value={kpiConfig.thresholds!.red}
      placeholder="off"
    />
  </label>
  <label class="field inline">
    <span>Yellow</span>
    <input
      type="number"
      bind:value={kpiConfig.thresholds!.yellow}
      placeholder="off"
    />
  </label>
  <label class="field inline">
    <span>Green</span>
    <input
      type="number"
      bind:value={kpiConfig.thresholds!.green}
      placeholder="off"
    />
  </label>
</fieldset>

<style>
  /* Same look as the parent's checkbox affordance — duplicated rather
     than relying on parent-scoped CSS reaching child slots, which
     Svelte's scoped styles do not do. */
  .checkbox {
    display: inline-flex;
    align-items: center;
    gap: 0.375rem;
    font-size: 0.875rem;
    cursor: pointer;
  }
</style>
