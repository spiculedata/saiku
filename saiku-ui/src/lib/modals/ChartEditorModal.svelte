<script lang="ts">
  import { untrack } from "svelte";
  import Modal from "$lib/components/Modal.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import type { ChartOptions, TrendLineMode } from "$lib/views/chartTypes";

  interface Props {
    initial: ChartOptions;
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

  let { initial, seriesNames = [], open, onSave, onCancel }: Props = $props();
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
</script>

<Modal title={i18n.t("modal.chart.title")} {open} size="md" onClose={onCancel}>
  <div class="grid">
    <label class="field">
      <span class="field__label">{i18n.t("modal.chart.chartTitle")}</span>
      <input class="field__input" bind:value={form.title} placeholder={i18n.t("modal.chart.chartTitlePlaceholder")} />
    </label>
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
  .series-axis { display: flex; flex-direction: column; gap: var(--space-2); padding: var(--space-2) var(--space-3); background: var(--bg-subtle); border-radius: var(--radius-sm); }
  .series-axis__title { font-size: var(--fs-sm); color: var(--fg-muted); }
  .series-axis__list { display: flex; flex-direction: column; gap: var(--space-2); }
  .series-axis__row { display: flex; align-items: center; gap: var(--space-3); }
  .series-axis__name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--fg); font-size: var(--fs-sm); }
  .series-axis__pick { width: 8rem; flex: 0 0 auto; }
</style>
