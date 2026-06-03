<script lang="ts">
  import { untrack } from "svelte";
  import Modal from "$lib/components/Modal.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import type { ChartOptions, TrendLineMode } from "$lib/views/chartTypes";

  interface Props {
    initial: ChartOptions;
    open: boolean;
    onSave: (next: ChartOptions) => void;
    onCancel: () => void;
  }

  const TREND_MODES: { id: TrendLineMode; labelKey: string }[] = [
    { id: "none", labelKey: "modal.chart.trend.none" },
    { id: "linear", labelKey: "modal.chart.trend.linear" },
    { id: "ma", labelKey: "modal.chart.trend.ma" },
    { id: "wma", labelKey: "modal.chart.trend.wma" },
  ];

  const LEGEND_POSITIONS: ChartOptions["legendPosition"][] = [
    "top", "bottom", "left", "right",
  ];

  let { initial, open, onSave, onCancel }: Props = $props();
  let form = $state<ChartOptions>(untrack(() => ({ ...initial })));

  $effect(() => {
    if (open) form = { ...initial };
  });
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
</style>
