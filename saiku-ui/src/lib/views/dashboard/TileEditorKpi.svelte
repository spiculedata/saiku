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
  <span class="field__label">Measure</span>
  <select class="field__input"
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
  <span class="field__label">Format</span>
  <select class="field__input" bind:value={kpiConfig.format}>
    <option value="number">Number</option>
    <option value="currency">Currency</option>
    <option value="percent">Percent</option>
    <option value="custom">Custom pattern</option>
  </select>
</label>
{#if kpiConfig.format === "custom"}
  <label class="field">
    <span class="field__label">Custom pattern</span>
    <input class="field__input"
      type="text"
      bind:value={kpiConfig.customFormat}
      placeholder="e.g. $2 / 2% / 3"
    />
    <span class="hint">
      $N / €N / £N for currency, $cN for compact currency ($48.2K), N% for
      percent, plain N for fractional digits.
    </span>
  </label>
{/if}

<fieldset class="size">
  <legend>Comparison</legend>
  <label class="field flex-1">
    <span class="field__label">Mode</span>
    <select class="field__input" bind:value={kpiConfig.comparison}>
      <option value="none">None</option>
      <option value="prior-period">Prior period</option>
      <option value="year-over-year">Year over year</option>
      <option value="target">Target value</option>
    </select>
  </label>
  {#if kpiConfig.comparison === "target"}
    <label class="field flex-1">
      <span class="field__label">Target</span>
      <input class="field__input"
        type="number"
        bind:value={kpiConfig.target}
        placeholder="e.g. 100000"
      />
    </label>
  {/if}
  <label class="field flex-1">
    <span class="field__label">Direction</span>
    <select class="field__input" bind:value={kpiConfig.direction}>
      <option value="higher-is-better">Higher is better</option>
      <option value="lower-is-better">Lower is better</option>
    </select>
  </label>
  {#if kpiConfig.comparison !== "none"}
    <label class="field flex-1">
      <span class="field__label">Delta label</span>
      <input class="field__input" type="text" bind:value={kpiConfig.deltaSuffix} placeholder="vs prior" />
      <span class="hint">Overrides the callout suffix (e.g. “vs last Thu”, “vs 4-wk avg”).</span>
    </label>
  {/if}
</fieldset>

<label class="checkbox">
  <input type="checkbox" bind:checked={kpiConfig.sparkline} />
  <span>Sparkline (mini line chart under the number)</span>
</label>

{#if showTimeLevel}
  <label class="field">
    <span class="field__label">Incomplete trailing periods</span>
    <input
      class="field__input"
      type="number"
      min="0"
      max="12"
      bind:value={kpiConfig.partialTrailing} />
    <span class="hint">
      How many of the newest periods are still filling up. Their values are
      still shown in full — they are marked <em>partial</em> and the
      percentage comparison is withheld, because measuring a part-period
      against a whole one reports the calendar rather than the business.
      0 = every period is complete.
    </span>
  </label>
{/if}

{#if showTimeLevel}
  <fieldset class="size">
    <legend>Time level (for comparison + sparkline)</legend>
    <label class="field flex-1">
      <span class="field__label">Dimension</span>
      <select class="field__input" bind:value={kpiConfig.timeLevel!.dimension} disabled={!cubePicked}>
        <option value="">— pick —</option>
        {#each dimensions as d, _id (_id)}
          <option value={d}>{d}</option>
        {/each}
      </select>
    </label>
    <label class="field flex-1">
      <span class="field__label">Hierarchy</span>
      <select class="field__input"
        bind:value={kpiConfig.timeLevel!.hierarchy}
        disabled={!kpiConfig.timeLevel?.dimension}
      >
        <option value="">— pick —</option>
        {#each hierarchies as h, _ih (_ih)}
          <option value={h}>{h}</option>
        {/each}
      </select>
    </label>
    <label class="field flex-1">
      <span class="field__label">Level</span>
      <select class="field__input"
        bind:value={kpiConfig.timeLevel!.level}
        disabled={!kpiConfig.timeLevel?.hierarchy}
      >
        <option value="">— pick —</option>
        {#each levels as l, _il (_il)}
          <option value={l}>{l}</option>
        {/each}
      </select>
    </label>
  </fieldset>
{/if}

<fieldset class="size">
  <legend>Threshold colouring (optional)</legend>
  <label class="field flex-1">
    <span class="field__label">Red ≤ / ≥</span>
    <input class="field__input"
      type="number"
      bind:value={kpiConfig.thresholds!.red}
      placeholder="off"
    />
  </label>
  <label class="field flex-1">
    <span class="field__label">Yellow</span>
    <input class="field__input"
      type="number"
      bind:value={kpiConfig.thresholds!.yellow}
      placeholder="off"
    />
  </label>
  <label class="field flex-1">
    <span class="field__label">Green</span>
    <input class="field__input"
      type="number"
      bind:value={kpiConfig.thresholds!.green}
      placeholder="off"
    />
  </label>
</fieldset>

<style>
  /* saiku#1258: .field/.checkbox/.size/.hint styling now comes from the
     global app.css pattern + the parent modal's `.modal :global(...)` rules,
     so nothing needs duplicating here anymore. */
</style>
