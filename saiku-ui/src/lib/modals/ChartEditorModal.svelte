<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import type { ChartOptions, TrendLineMode } from "$lib/views/chartTypes";

  interface Props {
    initial: ChartOptions;
    open: boolean;
    onSave: (next: ChartOptions) => void;
    onCancel: () => void;
  }

  const TREND_MODES: { id: TrendLineMode; label: string }[] = [
    { id: "none", label: "None" },
    { id: "linear", label: "Linear regression" },
    { id: "ma", label: "Moving average" },
    { id: "wma", label: "Weighted moving average" },
  ];

  const LEGEND_POSITIONS: ChartOptions["legendPosition"][] = [
    "top", "bottom", "left", "right",
  ];

  let { initial, open, onSave, onCancel }: Props = $props();
  let form = $state<ChartOptions>({ ...initial });

  $effect(() => {
    if (open) form = { ...initial };
  });
</script>

<Modal title="Chart editor" {open} size="md" onClose={onCancel}>
  <div class="grid">
    <label class="field">
      <span class="field__label">Title</span>
      <input class="field__input" bind:value={form.title} placeholder="Chart title" />
    </label>
    <div class="row">
      <label class="field field--grow">
        <span class="field__label">X axis label</span>
        <input class="field__input" bind:value={form.xAxisLabel} />
      </label>
      <label class="field field--grow">
        <span class="field__label">Y axis label</span>
        <input class="field__input" bind:value={form.yAxisLabel} />
      </label>
    </div>
    <div class="row">
      <label class="field field--grow">
        <span class="field__label">Legend</span>
        <label class="toggle">
          <input type="checkbox" bind:checked={form.showLegend} /> Show legend
        </label>
      </label>
      <label class="field field--grow">
        <span class="field__label">Legend position</span>
        <select class="field__input" bind:value={form.legendPosition} disabled={!form.showLegend}>
          {#each LEGEND_POSITIONS as p}
            <option value={p}>{p}</option>
          {/each}
        </select>
      </label>
    </div>
    <div class="row">
      <label class="field field--grow">
        <span class="field__label">Trend line</span>
        <select class="field__input" bind:value={form.trendLine}>
          {#each TREND_MODES as m}
            <option value={m.id}>{m.label}</option>
          {/each}
        </select>
      </label>
      <label class="field field--grow">
        <span class="field__label">Period (for moving averages)</span>
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
    <p class="hint">
      Trend lines currently render on line/stacked-line/area charts using the first measure column.
    </p>
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
