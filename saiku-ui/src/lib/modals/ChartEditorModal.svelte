<script lang="ts">
  import { untrack } from "svelte";
  import Modal from "$lib/components/Modal.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import type { ChartOptions, TrendLineMode, ChartColorRamp } from "$lib/views/chartTypes";
  import { PALETTE_IDS } from "$lib/views/chartTheme";

  interface Props {
    initial: ChartOptions;
    /** Current chart type — drives type-specific controls (issue #1071:
     *  the map colour-ramp + missing-data section shows only for "map").
     *  Undefined → no type-specific sections (back-compat for old callers). */
    chartType?: string;
    /** Series labels currently rendered on the chart (the column-category
     *  labels — typically measure names). Used to render the per-series
     *  Left/Right/Auto picker. Empty array → the picker section hides
     *  itself; the dual-axis auto toggle is still shown. */
    seriesNames?: string[];
    open: boolean;
    onSave: (next: ChartOptions) => void;
    onCancel: () => void;
  }

  type AxisPick = "auto" | "left" | "right";
  const AXIS_PICKS: AxisPick[] = ["auto", "left", "right"];

  const TREND_MODES: { id: TrendLineMode; labelKey: string }[] = [
    { id: "none", labelKey: "modal.chart.trend.none" },
    { id: "linear", labelKey: "modal.chart.trend.linear" },
    { id: "ma", labelKey: "modal.chart.trend.ma" },
    { id: "wma", labelKey: "modal.chart.trend.wma" },
  ];

  const LEGEND_POSITIONS: ChartOptions["legendPosition"][] = [
    "top", "bottom", "left", "right",
  ];

  // issue #1071: map colour ramps (must mirror COLOR_RAMPS in charts/build.ts).
  const COLOR_RAMP_IDS: ChartColorRamp[] = ["blues", "greens", "reds", "viridis", "diverging"];

  // issue #1081: named categorical palettes (single source: chartTheme.ts).
  const PALETTES = PALETTE_IDS;

  let { initial, chartType, seriesNames = [], open, onSave, onCancel }: Props = $props();
  let form = $state<ChartOptions>(untrack(() => ({ ...initial })));

  $effect(() => {
    if (open) form = { ...initial };
  });

  function axisPickFor(name: string): AxisPick {
    const v = form.seriesAxis?.[name];
    return v === "left" || v === "right" ? v : "auto";
  }

  function setAxisPick(name: string, pick: AxisPick): void {
    const next = { ...form.seriesAxis };
    if (pick === "auto") {
      delete next[name];
    } else {
      next[name] = pick;
    }
    form.seriesAxis = next;
  }

  // issue #1081: per-series colour override helpers. An absent / blank entry
  // means "use the palette cycle"; the <input type=color> defaults to a neutral
  // grey so the picker has a value, but we only persist a real override.
  const DEFAULT_PICKER = "#888888";

  function seriesColorFor(name: string): string {
    return form.seriesColors?.[name] ?? DEFAULT_PICKER;
  }

  function hasSeriesColor(name: string): boolean {
    return !!form.seriesColors?.[name];
  }

  function setSeriesColor(name: string, hex: string): void {
    form.seriesColors = { ...(form.seriesColors ?? {}), [name]: hex };
  }

  function clearSeriesColor(name: string): void {
    const next = { ...(form.seriesColors ?? {}) };
    delete next[name];
    form.seriesColors = next;
  }
</script>

<Modal title={i18n.t("modal.chart.title")} {open} size="md" onClose={onCancel}>
  <div class="grid">
    <label class="field">
      <span class="field__label">{i18n.t("modal.chart.chartTitle")}</span>
      <input class="field__input" bind:value={form.title} placeholder={i18n.t("modal.chart.chartTitlePlaceholder")} />
    </label>

    {#if chartType === "map"}
      <!-- issue #1071: map-only options. Place names come from the row
           hierarchy; the active (first) measure drives the colour. -->
      <div class="map-opts">
        <span class="map-opts__title">{i18n.t("modal.chart.map.title")}</span>
        <div class="row">
          <label class="field field--grow">
            <span class="field__label">{i18n.t("modal.chart.map.colorRamp")}</span>
            <select class="field__input" bind:value={form.colorRamp}>
              {#each COLOR_RAMP_IDS as r}
                <option value={r}>{i18n.t(`modal.chart.map.ramp.${r}`)}</option>
              {/each}
            </select>
          </label>
          <label class="field field--grow">
            <span class="field__label">{i18n.t("modal.chart.map.missing")}</span>
            <select class="field__input" bind:value={form.mapMissing}>
              <option value="blank">{i18n.t("modal.chart.map.missing.blank")}</option>
              <option value="zero">{i18n.t("modal.chart.map.missing.zero")}</option>
            </select>
          </label>
        </div>
        <p class="hint">{i18n.t("modal.chart.map.hint")}</p>
      </div>
    {/if}

    {#if chartType !== "map"}
    <div class="row">
      <label class="field field--grow">
        <span class="field__label">{i18n.t("modal.chart.xAxis")}</span>
        <input class="field__input" bind:value={form.xAxisLabel} />
      </label>
      <label class="field field--grow">
        <span class="field__label">{i18n.t("modal.chart.yAxis")}</span>
        <input class="field__input" bind:value={form.yAxisLabel} />
      </label>
    </div>
    <div class="row">
      <label class="field field--grow">
        <span class="field__label">{i18n.t("modal.chart.legend")}</span>
        <label class="toggle">
          <input type="checkbox" bind:checked={form.showLegend} /> {i18n.t("modal.chart.showLegend")}
        </label>
      </label>
      <label class="field field--grow">
        <span class="field__label">{i18n.t("modal.chart.legendPosition")}</span>
        <select class="field__input" bind:value={form.legendPosition} disabled={!form.showLegend}>
          {#each LEGEND_POSITIONS as p}
            <option value={p}>{p}</option>
          {/each}
        </select>
      </label>
    </div>
    <div class="colours">
      <span class="colours__title">{i18n.t("modal.chart.colours")}</span>
      <label class="field field--grow">
        <span class="field__label">{i18n.t("modal.chart.palette")}</span>
        <select class="field__input" bind:value={form.palette}>
          {#each PALETTES as p}
            <option value={p}>{i18n.t(`modal.chart.palette.${p}`)}</option>
          {/each}
        </select>
      </label>
      {#if seriesNames.length > 0}
        <p class="hint">{i18n.t("modal.chart.seriesColors.hint")}</p>
        <div class="colours__list">
          {#each seriesNames as name (name)}
            <div class="colours__row">
              <span class="colours__name" title={name}>{name}</span>
              <input
                type="color"
                class="colours__pick"
                aria-label={name}
                value={seriesColorFor(name)}
                oninput={(e) => setSeriesColor(name, (e.currentTarget as HTMLInputElement).value)}
              />
              <button
                type="button"
                class="colours__reset"
                disabled={!hasSeriesColor(name)}
                title={i18n.t("modal.chart.seriesColors.reset")}
                onclick={() => clearSeriesColor(name)}
              >
                {i18n.t("modal.chart.seriesColors.reset")}
              </button>
            </div>
          {/each}
        </div>
      {/if}
    </div>
    <div class="row">
      <label class="field field--grow">
        <span class="field__label">{i18n.t("modal.chart.rollupRows")}</span>
        <label class="toggle" title={i18n.t("modal.chart.hideRollupRows.hint")}>
          <input type="checkbox" bind:checked={form.hideRollupRows} /> {i18n.t("modal.chart.hideRollupRows")}
        </label>
      </label>
    </div>
    <div class="row">
      <label class="field field--grow">
        <span class="field__label">{i18n.t("modal.chart.yAxes")}</span>
        <label class="toggle" title={i18n.t("modal.chart.dualAxis.hint")}>
          <input type="checkbox" bind:checked={form.dualAxis} /> {i18n.t("modal.chart.dualAxis")}
        </label>
      </label>
    </div>
    {#if seriesNames.length > 0}
      <div class="series-axis">
        <span class="series-axis__title">{i18n.t("modal.chart.seriesAxis")}</span>
        <p class="hint">{i18n.t("modal.chart.seriesAxis.hint")}</p>
        <div class="series-axis__list">
          {#each seriesNames as name (name)}
            <div class="series-axis__row">
              <span class="series-axis__name" title={name}>{name}</span>
              <select
                class="field__input series-axis__pick"
                value={axisPickFor(name)}
                onchange={(e) => setAxisPick(name, (e.currentTarget as HTMLSelectElement).value as AxisPick)}
              >
                {#each AXIS_PICKS as p}
                  <option value={p}>{i18n.t(`modal.chart.axis.${p}`)}</option>
                {/each}
              </select>
            </div>
          {/each}
        </div>
      </div>
    {/if}
    <div class="row">
      <label class="field field--grow">
        <span class="field__label">{i18n.t("modal.chart.trendLine")}</span>
        <select class="field__input" bind:value={form.trendLine}>
          {#each TREND_MODES as m}
            <option value={m.id}>{i18n.t(m.labelKey)}</option>
          {/each}
        </select>
      </label>
      <label class="field field--grow">
        <span class="field__label">{i18n.t("modal.chart.period")}</span>
        <input
          class="field__input"
          type="number"
          min="2"
          max="60"
          bind:value={form.trendPeriod}
          disabled={form.trendLine !== "ma" && form.trendLine !== "wma"}
        />
      </label>
    </div>
    <p class="hint">{i18n.t("modal.chart.trendHint")}</p>
    {/if}
  </div>

  {#snippet footer()}
    <button type="button" class="btn" onclick={onCancel}>{i18n.t("modal.cancel")}</button>
    <button type="button" class="btn btn--primary" onclick={() => onSave({ ...form })}>{i18n.t("modal.save")}</button>
  {/snippet}
</Modal>

<style>
  .grid { display: flex; flex-direction: column; gap: var(--space-3); }
  .row { display: flex; gap: var(--space-3); }
  .field--grow { flex: 1; }
  .toggle { display: inline-flex; gap: var(--space-2); align-items: center; color: var(--fg); }
  .hint { color: var(--fg-subtle); font-size: var(--fs-xs); margin: 0; }
  .map-opts { display: flex; flex-direction: column; gap: var(--space-2); padding: var(--space-2) var(--space-3); background: var(--bg-subtle); border-radius: var(--radius-sm); }
  .map-opts__title { font-size: var(--fs-sm); color: var(--fg-muted); }
  .series-axis { display: flex; flex-direction: column; gap: var(--space-2); padding: var(--space-2) var(--space-3); background: var(--bg-subtle); border-radius: var(--radius-sm); }
  .series-axis__title { font-size: var(--fs-sm); color: var(--fg-muted); }
  .series-axis__list { display: flex; flex-direction: column; gap: var(--space-2); }
  .series-axis__row { display: flex; align-items: center; gap: var(--space-3); }
  .series-axis__name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--fg); font-size: var(--fs-sm); }
  .series-axis__pick { width: 8rem; flex: 0 0 auto; }
  .colours { display: flex; flex-direction: column; gap: var(--space-2); padding: var(--space-2) var(--space-3); background: var(--bg-subtle); border-radius: var(--radius-sm); }
  .colours__title { font-size: var(--fs-sm); color: var(--fg-muted); }
  .colours__list { display: flex; flex-direction: column; gap: var(--space-2); }
  .colours__row { display: flex; align-items: center; gap: var(--space-3); }
  .colours__name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--fg); font-size: var(--fs-sm); }
  .colours__pick { flex: 0 0 auto; width: 2.5rem; height: 1.75rem; padding: 0; border: 1px solid var(--border); border-radius: var(--radius-sm); background: none; cursor: pointer; }
  .colours__reset { flex: 0 0 auto; font-size: var(--fs-xs); }
  .colours__reset:disabled { opacity: 0.4; cursor: default; }
</style>
