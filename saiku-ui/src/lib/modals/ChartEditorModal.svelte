<script lang="ts">
  import { untrack } from "svelte";
  import Modal from "$lib/components/Modal.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import type { ChartOptions, TrendLineMode, ChartColorRamp } from "$lib/views/chartTypes";

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

  // issue #1082: number-format controls. The field is optional on ChartOptions
  // (undefined = inert), so bind through local accessors that read with inert
  // defaults and write a fresh numberFormat object.
  const nfPrefix = $derived(form.numberFormat?.prefix ?? "");
  const nfSuffix = $derived(form.numberFormat?.suffix ?? "");
  // Decimals: "" means auto (null). Keep it as a string for the blank-able input.
  const nfDecimals = $derived(
    form.numberFormat?.decimals === null || form.numberFormat?.decimals === undefined
      ? ""
      : String(form.numberFormat.decimals),
  );
  const nfThousands = $derived(form.numberFormat?.thousands ?? false);
  const nfAbbreviate = $derived(form.numberFormat?.abbreviate ?? false);

  function setNumberFormat(patch: Partial<NonNullable<ChartOptions["numberFormat"]>>): void {
    form.numberFormat = { ...form.numberFormat, ...patch };
  }

  function onDecimalsInput(raw: string): void {
    const trimmed = raw.trim();
    if (trimmed === "") {
      setNumberFormat({ decimals: null });
      return;
    }
    const n = Number(trimmed);
    setNumberFormat({ decimals: Number.isFinite(n) ? Math.max(0, Math.floor(n)) : null });
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
    <div class="row">
      <label class="field field--grow">
        <span class="field__label">{i18n.t("modal.chart.rollupRows")}</span>
        <label class="toggle" title={i18n.t("modal.chart.hideRollupRows.hint")}>
          <input type="checkbox" bind:checked={form.hideRollupRows} /> {i18n.t("modal.chart.hideRollupRows")}
        </label>
      </label>
    </div>
    <!-- Auto dual-axis is meaningless with a single measure (nothing to
         split to the right axis); hide the toggle in that case rather
         than letting users tick a no-op. -->
    {#if seriesNames.length >= 2}
      <div class="row">
        <label class="field field--grow">
          <span class="field__label">{i18n.t("modal.chart.yAxes")}</span>
          <label class="toggle" title={i18n.t("modal.chart.dualAxis.hint")}>
            <input type="checkbox" bind:checked={form.dualAxis} /> {i18n.t("modal.chart.dualAxis")}
          </label>
        </label>
      </div>
    {/if}
    <!-- Per-series Left/Right override only makes sense with ≥2 measures —
         a single series has nothing to balance against the dominant axis,
         so the section is hidden rather than dangled as a useless picker. -->
    {#if seriesNames.length >= 2}
      <div class="series-axis">
        <span class="series-axis__title">{i18n.t("modal.chart.seriesAxis")}</span>
        <p class="hint">{i18n.t("modal.chart.seriesAxis.hint")}</p>
        <div class="series-axis__list">
          <!-- Defensive: use index keys because seriesNames is derived
               from cellset columnCategories which can hold duplicates
               when a query has multi-measure × multi-hierarchy cols
               (Svelte 5 hard-fails on each_key_duplicate). -->
          {#each seriesNames as name, ni (ni)}
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
    <!-- #1083 (relocated 2026-06-07): client-side category sort + top-N.
         Reordering / trimming happens on the projection without re-querying;
         lives in chart options so the choice persists with the tile rather
         than reverting on reload. -->
    <div class="row">
      <label class="field field--grow">
        <span class="field__label">{i18n.t("modal.chart.sort")}</span>
        <select class="field__input" bind:value={form.sortDirection}>
          <option value="none">{i18n.t("modal.chart.sort.none")}</option>
          <option value="asc">{i18n.t("modal.chart.sort.asc")}</option>
          <option value="desc">{i18n.t("modal.chart.sort.desc")}</option>
        </select>
      </label>
      <label class="field field--grow">
        <span class="field__label">{i18n.t("modal.chart.topN")}</span>
        <select
          class="field__input"
          value={form.topN ?? ""}
          onchange={(e) => {
            const v = (e.currentTarget as HTMLSelectElement).value;
            form.topN = v === "" ? null : Number(v);
          }}
        >
          <option value="">{i18n.t("modal.chart.topN.all")}</option>
          <option value="5">Top 5</option>
          <option value="10">Top 10</option>
          <option value="20">Top 20</option>
          <option value="50">Top 50</option>
        </select>
      </label>
    </div>

    <!-- issue #1082: number formatting for value text (axis labels, tooltip
         values, data labels). All controls optional; blank/off = raw values. -->
    <div class="number-format">
      <span class="number-format__title">{i18n.t("modal.chart.numberFormat")}</span>
      <div class="row">
        <label class="field field--grow">
          <span class="field__label">{i18n.t("modal.chart.numberFormat.prefix")}</span>
          <input
            class="field__input"
            value={nfPrefix}
            placeholder={i18n.t("modal.chart.numberFormat.prefixPlaceholder")}
            oninput={(e) => setNumberFormat({ prefix: (e.currentTarget as HTMLInputElement).value })}
          />
        </label>
        <label class="field field--grow">
          <span class="field__label">{i18n.t("modal.chart.numberFormat.suffix")}</span>
          <input
            class="field__input"
            value={nfSuffix}
            placeholder={i18n.t("modal.chart.numberFormat.suffixPlaceholder")}
            oninput={(e) => setNumberFormat({ suffix: (e.currentTarget as HTMLInputElement).value })}
          />
        </label>
        <label class="field field--grow">
          <span class="field__label">{i18n.t("modal.chart.numberFormat.decimals")}</span>
          <input
            class="field__input"
            type="number"
            min="0"
            max="20"
            value={nfDecimals}
            placeholder={i18n.t("modal.chart.numberFormat.decimalsAuto")}
            oninput={(e) => onDecimalsInput((e.currentTarget as HTMLInputElement).value)}
          />
        </label>
      </div>
      <div class="row">
        <label class="field field--grow">
          <label class="toggle">
            <input
              type="checkbox"
              checked={nfThousands}
              onchange={(e) => setNumberFormat({ thousands: (e.currentTarget as HTMLInputElement).checked })}
            />
            {i18n.t("modal.chart.numberFormat.thousands")}
          </label>
        </label>
        <label class="field field--grow">
          <label class="toggle" title={i18n.t("modal.chart.numberFormat.abbreviate.hint")}>
            <input
              type="checkbox"
              checked={nfAbbreviate}
              onchange={(e) => setNumberFormat({ abbreviate: (e.currentTarget as HTMLInputElement).checked })}
            />
            {i18n.t("modal.chart.numberFormat.abbreviate")}
          </label>
        </label>
      </div>
      <p class="hint">{i18n.t("modal.chart.numberFormat.hint")}</p>
    </div>
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
  .number-format { display: flex; flex-direction: column; gap: var(--space-2); padding: var(--space-2) var(--space-3); background: var(--bg-subtle); border-radius: var(--radius-sm); }
  .number-format__title { font-size: var(--fs-sm); color: var(--fg-muted); }
</style>
