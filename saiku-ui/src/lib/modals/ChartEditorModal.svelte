<script lang="ts">
  import { untrack } from "svelte";
  import Modal from "$lib/components/Modal.svelte";
  import { Button } from "$lib/components/ui";
  import { i18n } from "$lib/stores/i18n.svelte";
  import type {
    ChartOptions,
    TrendLineMode,
    ChartColorRamp,
    ReferenceLine,
    ReferenceBand,
    ChartConditionalFormat,
    ChartCondRule,
    ChartCondOp,
    ComboSeriesType,
  } from "$lib/views/chartTypes";
  import { PALETTE_IDS } from "$lib/views/chartTheme";
  // #1084: which chart kinds support conditional formatting (gates the section).
  import { CONDITIONAL_FORMAT_CHART_TYPES } from "$lib/charts/build";

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
  const COLOR_RAMP_IDS: ChartColorRamp[] = ["theme", "blues", "greens", "reds", "viridis", "diverging"];

  // issue #1081: named categorical palettes (single source: chartTheme.ts).
  const PALETTES = PALETTE_IDS;

  let { initial, chartType, seriesNames = [], open, onSave, onCancel }: Props = $props();

  // Deep-copy the reference arrays so editing the working copy never mutates the
  // caller's `initial` options object (#1079). Spreading alone shares the array.
  function cloneForm(src: ChartOptions): ChartOptions {
    return {
      ...src,
      referenceLines: (src.referenceLines ?? []).map((l) => ({ ...l })),
      referenceBands: (src.referenceBands ?? []).map((b) => ({ ...b })),
      // #1084: deep-copy the conditional-format bands + their rules so editing
      // the working copy never mutates the caller's options.
      conditionalFormat: (src.conditionalFormat ?? []).map((c) => ({
        ...c,
        rules: c.rules.map((r) => ({ ...r, value: Array.isArray(r.value) ? ([...r.value] as [number, number]) : r.value })),
      })),
    };
  }

  let form = $state<ChartOptions>(untrack(() => cloneForm(initial)));

  $effect(() => {
    if (open) form = cloneForm(initial);
  });

  // issue #1079: reference lines / bands are only meaningful on cartesian
  // charts (bar/line/area/scatter family). Hide the section for the
  // proportional / matrix / map types where markLine/markArea don't apply.
  const REF_CARTESIAN = new Set([
    "bar",
    "stackedBar",
    "line",
    "stackedLine",
    "area",
    "stackedArea",
    "scatter",
    "bubble",
    "waterfall",
  ]);
  const showRefSection = $derived(chartType === undefined || REF_CARTESIAN.has(chartType));

  function addRefLine(): void {
    form.referenceLines = [...(form.referenceLines ?? []), { axis: "y", value: 0 }];
  }
  function removeRefLine(idx: number): void {
    form.referenceLines = (form.referenceLines ?? []).filter((_, i) => i !== idx);
  }
  function updateRefLine(idx: number, patch: Partial<ReferenceLine>): void {
    form.referenceLines = (form.referenceLines ?? []).map((l, i) => (i === idx ? { ...l, ...patch } : l));
  }

  function addRefBand(): void {
    form.referenceBands = [...(form.referenceBands ?? []), { axis: "y", from: 0, to: 0 }];
  }
  function removeRefBand(idx: number): void {
    form.referenceBands = (form.referenceBands ?? []).filter((_, i) => i !== idx);
  }
  function updateRefBand(idx: number, patch: Partial<ReferenceBand>): void {
    form.referenceBands = (form.referenceBands ?? []).map((b, i) => (i === idx ? { ...b, ...patch } : b));
  }

  function numVal(e: Event): number {
    const v = parseFloat((e.currentTarget as HTMLInputElement).value);
    return Number.isFinite(v) ? v : 0;
  }

  /* --- issue #1084: conditional formatting (bar / stackedBar) ------------- */
  // Shown only for brushable bar kinds with named series to target. Each series
  // (measure) maps to one band keyed by its index; rules recolour matching bars.
  const showCondFormat = $derived(
    chartType !== undefined && CONDITIONAL_FORMAT_CHART_TYPES.has(chartType) && seriesNames.length > 0,
  );
  const COND_OPS: { id: ChartCondOp; labelKey: string }[] = [
    { id: "gt", labelKey: "modal.chart.cond.op.gt" },
    { id: "gte", labelKey: "modal.chart.cond.op.gte" },
    { id: "lt", labelKey: "modal.chart.cond.op.lt" },
    { id: "lte", labelKey: "modal.chart.cond.op.lte" },
    { id: "between", labelKey: "modal.chart.cond.op.between" },
  ];
  const COND_DEFAULT_COLOR = "#e2483d";

  function condBand(mi: number): ChartConditionalFormat | undefined {
    return (form.conditionalFormat ?? []).find((c) => c.measureIndex === mi);
  }
  /** Mutate (or create) the band for a measure index, then drop empty bands. */
  function editBand(mi: number, mut: (b: ChartConditionalFormat) => ChartConditionalFormat): void {
    const list = (form.conditionalFormat ?? []).map((c) => ({
      ...c,
      rules: c.rules.map((r) => ({ ...r })),
    }));
    const idx = list.findIndex((c) => c.measureIndex === mi);
    const next = mut(idx >= 0 ? list[idx] : { measureIndex: mi, rules: [] });
    if (idx >= 0) list[idx] = next;
    else list.push(next);
    // Tidy: keep only bands that actually carry rules or a fallback colour.
    form.conditionalFormat = list.filter((c) => c.rules.length > 0 || !!c.fallbackColor);
  }
  function addCondRule(mi: number): void {
    editBand(mi, (b) => ({ ...b, rules: [...b.rules, { op: "gt", value: 0, color: COND_DEFAULT_COLOR }] }));
  }
  function removeCondRule(mi: number, ri: number): void {
    editBand(mi, (b) => ({ ...b, rules: b.rules.filter((_, i) => i !== ri) }));
  }
  function updateCondRule(mi: number, ri: number, patch: Partial<ChartCondRule>): void {
    editBand(mi, (b) => ({ ...b, rules: b.rules.map((r, i) => (i === ri ? { ...r, ...patch } : r)) }));
  }
  /** Switching to/from `between` reshapes `value` (single number ↔ tuple). */
  function changeCondOp(mi: number, ri: number, op: ChartCondOp): void {
    const cur = condBand(mi)?.rules[ri];
    let value: number | [number, number] = cur?.value ?? 0;
    if (op === "between" && !Array.isArray(value)) value = [typeof value === "number" ? value : 0, 0];
    if (op !== "between" && Array.isArray(value)) value = value[0] ?? 0;
    updateCondRule(mi, ri, { op, value });
  }
  function setCondFallback(mi: number, color: string): void {
    editBand(mi, (b) => ({ ...b, fallbackColor: color || undefined }));
  }
  function tupleAt(value: number | [number, number] | undefined, i: 0 | 1): number {
    return Array.isArray(value) ? (value[i] ?? 0) : i === 0 && typeof value === "number" ? value : 0;
  }

  // issue #1089: per-series chart-type override (combo). Only on cartesian
  // kinds that go through the multi-series builder, and only with ≥2 series.
  const COMBO_CHART_TYPES = new Set([
    "bar",
    "stackedBar",
    "line",
    "stackedLine",
    "area",
    "stackedArea",
  ]);
  const COMBO_TYPES: ComboSeriesType[] = ["bar", "line", "area", "scatter"];
  const showComboTypes = $derived(
    chartType !== undefined && COMBO_CHART_TYPES.has(chartType) && seriesNames.length >= 2,
  );
  function seriesTypeFor(name: string): ComboSeriesType | "default" {
    return form.seriesType?.[name] ?? "default";
  }
  function setSeriesType(name: string, t: ComboSeriesType | "default"): void {
    const next = { ...(form.seriesType ?? {}) };
    if (t === "default") delete next[name];
    else next[name] = t;
    form.seriesType = next;
  }

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
  <div class="flex flex-col gap-3">
    <label class="field">
      <span class="field__label">{i18n.t("modal.chart.chartTitle")}</span>
      <input class="field__input" bind:value={form.title} placeholder={i18n.t("modal.chart.chartTitlePlaceholder")} />
      <!-- saiku#1776: this title is drawn INSIDE the plot, on top of the tile's
           own header — set both and the heading renders twice. The two fields
           live in different editors, so say so here rather than let authors
           discover it after saving. -->
      <span class="hint">
        {i18n.t(
          "modal.chart.chartTitleHint",
          "Drawn inside the chart, beneath the tile's own title. Leave blank to show just the tile title.",
        )}
      </span>
    </label>

    {#if chartType === "map"}
      <!-- issue #1071: map-only options. Place names come from the row
           hierarchy; the active (first) measure drives the colour. -->
      <div class="map-opts">
        <span class="text-sm text-fg-muted">{i18n.t("modal.chart.map.title")}</span>
        <div class="flex gap-3">
          <label class="field flex-1">
            <span class="field__label">{i18n.t("modal.chart.map.colorRamp")}</span>
            <select class="field__input" bind:value={form.colorRamp}>
              {#each COLOR_RAMP_IDS as r}
                <option value={r}>{i18n.t(`modal.chart.map.ramp.${r}`)}</option>
              {/each}
            </select>
          </label>
          <label class="field flex-1">
            <span class="field__label">{i18n.t("modal.chart.map.missing")}</span>
            <select class="field__input" bind:value={form.mapMissing}>
              <option value="blank">{i18n.t("modal.chart.map.missing.blank")}</option>
              <option value="zero">{i18n.t("modal.chart.map.missing.zero")}</option>
            </select>
          </label>
        </div>
        <p class="text-fg-subtle text-xs m-0">{i18n.t("modal.chart.map.hint")}</p>
      </div>
    {/if}

    <!-- saiku#1775: the heatmap paints from `colorRamp` too, but the control was
         gated to maps — so every heatmap was stuck on the "blues" default with no
         way to move it, and read as ignoring the app theme. Same picker, without
         the map-only "missing data" option beside it. -->
    {#if chartType === "heatmap"}
      <label class="field">
        <span class="field__label">{i18n.t("modal.chart.map.colorRamp")}</span>
        <select class="field__input" bind:value={form.colorRamp}>
          {#each COLOR_RAMP_IDS as r}
            <option value={r}>{i18n.t(`modal.chart.map.ramp.${r}`)}</option>
          {/each}
        </select>
      </label>
    {/if}

    {#if chartType !== "map"}
    <div class="flex gap-3">
      <label class="field flex-1">
        <span class="field__label">{i18n.t("modal.chart.xAxis")}</span>
        <input class="field__input" bind:value={form.xAxisLabel} />
      </label>
      <label class="field flex-1">
        <span class="field__label">{i18n.t("modal.chart.yAxis")}</span>
        <input class="field__input" bind:value={form.yAxisLabel} />
      </label>
    </div>
    <div class="flex gap-3">
      <label class="field flex-1">
        <span class="field__label">{i18n.t("modal.chart.legend")}</span>
        <label class="inline-flex gap-2 items-center text-fg">
          <input type="checkbox" bind:checked={form.showLegend} /> {i18n.t("modal.chart.showLegend")}
        </label>
      </label>
      <label class="field flex-1">
        <span class="field__label">{i18n.t("modal.chart.legendPosition")}</span>
        <select class="field__input" bind:value={form.legendPosition} disabled={!form.showLegend}>
          {#each LEGEND_POSITIONS as p}
            <option value={p}>{p}</option>
          {/each}
        </select>
      </label>
    </div>
    <div class="colours">
      <span class="text-sm text-fg-muted">{i18n.t("modal.chart.colours")}</span>
      <label class="field flex-1">
        <span class="field__label">{i18n.t("modal.chart.palette")}</span>
        <select class="field__input" bind:value={form.palette}>
          {#each PALETTES as p}
            <option value={p}>{i18n.t(`modal.chart.palette.${p}`)}</option>
          {/each}
        </select>
      </label>
      {#if seriesNames.length > 0}
        <p class="text-fg-subtle text-xs m-0">{i18n.t("modal.chart.seriesColors.hint")}</p>
        <div class="flex flex-col gap-2">
          {#each seriesNames as name (name)}
            <div class="flex items-center gap-3">
              <span class="flex-1 overflow-hidden text-ellipsis whitespace-nowrap text-fg text-sm" title={name}>{name}</span>
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
    <div class="flex gap-3">
      <label class="field flex-1">
        <span class="field__label">{i18n.t("modal.chart.rollupRows")}</span>
        <label class="inline-flex gap-2 items-center text-fg" title={i18n.t("modal.chart.hideRollupRows.hint")}>
          <input type="checkbox" bind:checked={form.hideRollupRows} /> {i18n.t("modal.chart.hideRollupRows")}
        </label>
      </label>
    </div>
    <!-- Auto dual-axis is meaningless with a single measure (nothing to
         split to the right axis); hide the toggle in that case rather
         than letting users tick a no-op. -->
    {#if seriesNames.length >= 2}
      <div class="flex gap-3">
        <label class="field flex-1">
          <span class="field__label">{i18n.t("modal.chart.yAxes")}</span>
          <label class="inline-flex gap-2 items-center text-fg" title={i18n.t("modal.chart.dualAxis.hint")}>
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
        <span class="text-sm text-fg-muted">{i18n.t("modal.chart.seriesAxis")}</span>
        <p class="text-fg-subtle text-xs m-0">{i18n.t("modal.chart.seriesAxis.hint")}</p>
        <div class="flex flex-col gap-2">
          <!-- Defensive: use index keys because seriesNames is derived
               from cellset columnCategories which can hold duplicates
               when a query has multi-measure × multi-hierarchy cols
               (Svelte 5 hard-fails on each_key_duplicate). -->
          {#each seriesNames as name, ni (ni)}
            <div class="flex items-center gap-3">
              <span class="flex-1 overflow-hidden text-ellipsis whitespace-nowrap text-fg text-sm" title={name}>{name}</span>
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
    <!-- issue #1089: per-series chart-type override (combo charts). Cartesian
         multi-series only — pick a type per series (or "Default" = chart type). -->
    {#if showComboTypes}
      <div class="series-axis">
        <span class="text-sm text-fg-muted">{i18n.t("modal.chart.seriesType", "Series type (combo)")}</span>
        <p class="text-fg-subtle text-xs m-0">
          {i18n.t(
            "modal.chart.seriesType.hint",
            "Give a series its own chart type — e.g. revenue as bars, growth-rate as a line.",
          )}
        </p>
        <div class="flex flex-col gap-2">
          {#each seriesNames as name, ni (ni)}
            <div class="flex items-center gap-3">
              <span class="flex-1 overflow-hidden text-ellipsis whitespace-nowrap text-fg text-sm" title={name}>{name}</span>
              <select
                class="field__input series-axis__pick"
                value={seriesTypeFor(name)}
                onchange={(e) =>
                  setSeriesType(name, (e.currentTarget as HTMLSelectElement).value as ComboSeriesType | "default")}
              >
                <option value="default">{i18n.t("modal.chart.seriesType.default", "Default")}</option>
                {#each COMBO_TYPES as ct}
                  <option value={ct}>{i18n.t(`modal.chart.seriesType.${ct}`, ct)}</option>
                {/each}
              </select>
            </div>
          {/each}
        </div>
      </div>
    {/if}
    <div class="flex gap-3">
      <label class="field flex-1">
        <span class="field__label">{i18n.t("modal.chart.trendLine")}</span>
        <select class="field__input" bind:value={form.trendLine}>
          {#each TREND_MODES as m}
            <option value={m.id}>{i18n.t(m.labelKey)}</option>
          {/each}
        </select>
      </label>
      <label class="field flex-1">
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
    <p class="text-fg-subtle text-xs m-0">{i18n.t("modal.chart.trendHint")}</p>
    <!-- #1083 (relocated 2026-06-07): client-side category sort + top-N.
         Reordering / trimming happens on the projection without re-querying;
         lives in chart options so the choice persists with the tile rather
         than reverting on reload. -->
    <div class="flex gap-3">
      <label class="field flex-1">
        <span class="field__label">{i18n.t("modal.chart.sort")}</span>
        <select class="field__input" bind:value={form.sortDirection}>
          <option value="none">{i18n.t("modal.chart.sort.none")}</option>
          <option value="asc">{i18n.t("modal.chart.sort.asc")}</option>
          <option value="desc">{i18n.t("modal.chart.sort.desc")}</option>
        </select>
      </label>
      <label class="field flex-1">
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
      <span class="text-sm text-fg-muted">{i18n.t("modal.chart.numberFormat")}</span>
      <div class="flex gap-3">
        <label class="field flex-1">
          <span class="field__label">{i18n.t("modal.chart.numberFormat.prefix")}</span>
          <input
            class="field__input"
            value={nfPrefix}
            placeholder={i18n.t("modal.chart.numberFormat.prefixPlaceholder")}
            oninput={(e) => setNumberFormat({ prefix: (e.currentTarget as HTMLInputElement).value })}
          />
        </label>
        <label class="field flex-1">
          <span class="field__label">{i18n.t("modal.chart.numberFormat.suffix")}</span>
          <input
            class="field__input"
            value={nfSuffix}
            placeholder={i18n.t("modal.chart.numberFormat.suffixPlaceholder")}
            oninput={(e) => setNumberFormat({ suffix: (e.currentTarget as HTMLInputElement).value })}
          />
        </label>
        <label class="field flex-1">
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
      <div class="flex gap-3">
        <label class="field flex-1">
          <label class="inline-flex gap-2 items-center text-fg">
            <input
              type="checkbox"
              checked={nfThousands}
              onchange={(e) => setNumberFormat({ thousands: (e.currentTarget as HTMLInputElement).checked })}
            />
            {i18n.t("modal.chart.numberFormat.thousands")}
          </label>
        </label>
        <label class="field flex-1">
          <label class="inline-flex gap-2 items-center text-fg" title={i18n.t("modal.chart.numberFormat.abbreviate.hint")}>
            <input
              type="checkbox"
              checked={nfAbbreviate}
              onchange={(e) => setNumberFormat({ abbreviate: (e.currentTarget as HTMLInputElement).checked })}
            />
            {i18n.t("modal.chart.numberFormat.abbreviate")}
          </label>
        </label>
      </div>
      <p class="text-fg-subtle text-xs m-0">{i18n.t("modal.chart.numberFormat.hint")}</p>
    </div>

    {#if showRefSection}
      <div class="ref">
        <span class="text-sm text-fg-muted">{i18n.t("modal.chart.refLines")}</span>
        <p class="text-fg-subtle text-xs m-0">{i18n.t("modal.chart.refLines.hint")}</p>
        <div class="flex flex-col gap-2">
          {#each form.referenceLines ?? [] as line, i (i)}
            <div class="flex items-center gap-2">
              <select
                class="field__input ref__axis"
                value={line.axis}
                onchange={(e) => updateRefLine(i, { axis: (e.currentTarget as HTMLSelectElement).value as "x" | "y" })}
                aria-label={i18n.t("modal.chart.refLines.axis")}
              >
                <option value="y">{i18n.t("modal.chart.refLines.axis.y")}</option>
                <option value="x">{i18n.t("modal.chart.refLines.axis.x")}</option>
              </select>
              <input
                class="field__input ref__value"
                type="number"
                value={line.value}
                oninput={(e) => updateRefLine(i, { value: numVal(e) })}
                placeholder={i18n.t("modal.chart.refLines.value")}
                aria-label={i18n.t("modal.chart.refLines.value")}
              />
              <input
                class="field__input ref__label"
                type="text"
                value={line.label ?? ""}
                oninput={(e) => updateRefLine(i, { label: (e.currentTarget as HTMLInputElement).value })}
                placeholder={i18n.t("modal.chart.refLines.label")}
                aria-label={i18n.t("modal.chart.refLines.label")}
              />
              <input
                class="ref__color"
                type="color"
                value={line.color ?? "#888888"}
                oninput={(e) => updateRefLine(i, { color: (e.currentTarget as HTMLInputElement).value })}
                aria-label={i18n.t("modal.chart.refLines.color")}
                title={i18n.t("modal.chart.refLines.color")}
              />
              <Button variant="outline" size="sm" class="ref__remove" onclick={() => removeRefLine(i)}>{i18n.t("modal.chart.refLines.remove")}
              </Button>
            </div>
          {/each}
        </div>
        <Button variant="outline" size="sm" class="ref__add" onclick={addRefLine}>{i18n.t("modal.chart.refLines.add")}</Button>

        <span class="text-sm text-fg-muted">{i18n.t("modal.chart.refBands")}</span>
        <p class="text-fg-subtle text-xs m-0">{i18n.t("modal.chart.refBands.hint")}</p>
        <div class="flex flex-col gap-2">
          {#each form.referenceBands ?? [] as band, i (i)}
            <div class="flex items-center gap-2">
              <select
                class="field__input ref__axis"
                value={band.axis}
                onchange={(e) => updateRefBand(i, { axis: (e.currentTarget as HTMLSelectElement).value as "x" | "y" })}
                aria-label={i18n.t("modal.chart.refLines.axis")}
              >
                <option value="y">{i18n.t("modal.chart.refLines.axis.y")}</option>
                <option value="x">{i18n.t("modal.chart.refLines.axis.x")}</option>
              </select>
              <input
                class="field__input ref__value"
                type="number"
                value={band.from}
                oninput={(e) => updateRefBand(i, { from: numVal(e) })}
                placeholder={i18n.t("modal.chart.refBands.from")}
                aria-label={i18n.t("modal.chart.refBands.from")}
              />
              <input
                class="field__input ref__value"
                type="number"
                value={band.to}
                oninput={(e) => updateRefBand(i, { to: numVal(e) })}
                placeholder={i18n.t("modal.chart.refBands.to")}
                aria-label={i18n.t("modal.chart.refBands.to")}
              />
              <input
                class="ref__color"
                type="color"
                value={band.color ?? "#888888"}
                oninput={(e) => updateRefBand(i, { color: (e.currentTarget as HTMLInputElement).value })}
                aria-label={i18n.t("modal.chart.refLines.color")}
                title={i18n.t("modal.chart.refLines.color")}
              />
              <Button variant="outline" size="sm" class="ref__remove" onclick={() => removeRefBand(i)}>{i18n.t("modal.chart.refLines.remove")}
              </Button>
            </div>
          {/each}
        </div>
        <Button variant="outline" size="sm" class="ref__add" onclick={addRefBand}>{i18n.t("modal.chart.refBands.add")}</Button>
      </div>
    {/if}

    <!-- issue #1084: conditional formatting — recolour bars by value. -->
    {#if showCondFormat}
      <div class="cond">
        <span class="text-sm text-fg-muted">{i18n.t("modal.chart.cond.title", "Conditional formatting")}</span>
        <p class="text-fg-subtle text-xs m-0">{i18n.t("modal.chart.cond.hint", "Recolour bars by value — the first matching rule per bar wins.")}</p>
        {#each seriesNames as sname, mi (mi)}
          <div class="cond__measure">
            <span class="text-sm font-semibold text-fg">{sname}</span>
            {#each condBand(mi)?.rules ?? [] as rule, ri (ri)}
              <div class="flex items-center gap-2">
                <select
                  class="field__input cond__op"
                  value={rule.op}
                  onchange={(e) => changeCondOp(mi, ri, (e.currentTarget as HTMLSelectElement).value as ChartCondOp)}
                  aria-label={i18n.t("modal.chart.cond.op", "Operator")}
                >
                  {#each COND_OPS as o}
                    <option value={o.id}>{i18n.t(o.labelKey)}</option>
                  {/each}
                </select>
                {#if rule.op === "between"}
                  <input
                    class="field__input cond__num"
                    type="number"
                    value={tupleAt(rule.value, 0)}
                    oninput={(e) => updateCondRule(mi, ri, { value: [numVal(e), tupleAt(rule.value, 1)] })}
                    aria-label={i18n.t("modal.chart.cond.min", "Min")}
                  />
                  <input
                    class="field__input cond__num"
                    type="number"
                    value={tupleAt(rule.value, 1)}
                    oninput={(e) => updateCondRule(mi, ri, { value: [tupleAt(rule.value, 0), numVal(e)] })}
                    aria-label={i18n.t("modal.chart.cond.max", "Max")}
                  />
                {:else}
                  <input
                    class="field__input cond__num"
                    type="number"
                    value={typeof rule.value === "number" ? rule.value : 0}
                    oninput={(e) => updateCondRule(mi, ri, { value: numVal(e) })}
                    aria-label={i18n.t("modal.chart.cond.value", "Value")}
                  />
                {/if}
                <input
                  class="cond__color"
                  type="color"
                  value={rule.color}
                  oninput={(e) => updateCondRule(mi, ri, { color: (e.currentTarget as HTMLInputElement).value })}
                  aria-label={i18n.t("modal.chart.cond.color", "Colour")}
                  title={i18n.t("modal.chart.cond.color", "Colour")}
                />
                <Button variant="outline" size="sm" class="cond__remove" onclick={() => removeCondRule(mi, ri)}>{i18n.t("modal.chart.cond.remove", "Remove")}
                </Button>
              </div>
            {/each}
            <div class="flex items-center gap-3">
              <Button variant="outline" size="sm" class="cond__add" onclick={() => addCondRule(mi)}>{i18n.t("modal.chart.cond.add", "Add rule")}
              </Button>
              {#if (condBand(mi)?.rules.length ?? 0) > 0}
                <label class="inline-flex items-center gap-2 text-xs text-fg-muted">
                  <span>{i18n.t("modal.chart.cond.fallback", "Fallback")}</span>
                  <input
                    class="cond__color"
                    type="color"
                    value={condBand(mi)?.fallbackColor ?? "#cccccc"}
                    onchange={(e) => setCondFallback(mi, (e.currentTarget as HTMLInputElement).value)}
                    aria-label={i18n.t("modal.chart.cond.fallback", "Fallback")}
                  />
                </label>
              {/if}
            </div>
          </div>
        {/each}
      </div>
    {/if}
    {/if}
  </div>

  {#snippet footer()}
    <Button variant="outline" onclick={onCancel}>{i18n.t("modal.cancel")}</Button>
    <Button onclick={() => onSave({ ...form })}>{i18n.t("modal.save")}</Button>
  {/snippet}
</Modal>

<style>
.map-opts { display: flex; flex-direction: column; gap: var(--space-2); padding: var(--space-2) var(--space-3); background: hsl(var(--bg-subtle)); border-radius: var(--radius-sm); }
  .series-axis { display: flex; flex-direction: column; gap: var(--space-2); padding: var(--space-2) var(--space-3); background: hsl(var(--bg-subtle)); border-radius: var(--radius-sm); }
  .series-axis__pick { width: 8rem; flex: 0 0 auto; }
  .number-format { display: flex; flex-direction: column; gap: var(--space-2); padding: var(--space-2) var(--space-3); background: hsl(var(--bg-subtle)); border-radius: var(--radius-sm); }
  .colours { display: flex; flex-direction: column; gap: var(--space-2); padding: var(--space-2) var(--space-3); background: hsl(var(--bg-subtle)); border-radius: var(--radius-sm); }
  .colours__pick { flex: 0 0 auto; width: 2.5rem; height: 1.75rem; padding: 0; border: 1px solid hsl(var(--border)); border-radius: var(--radius-sm); background: none; cursor: pointer; }
  .colours__reset { flex: 0 0 auto; font-size: var(--fs-xs); }
  .colours__reset:disabled { opacity: 0.4; cursor: default; }
  .ref { display: flex; flex-direction: column; gap: var(--space-2); padding: var(--space-2) var(--space-3); background: hsl(var(--bg-subtle)); border-radius: var(--radius-sm); }
  .ref__axis { width: 8.5rem; flex: 0 0 auto; }
  .ref__value { width: 6rem; flex: 0 0 auto; }
  .ref__label { flex: 1; min-width: 4rem; }
  .ref__color { width: 2.25rem; height: 2rem; flex: 0 0 auto; padding: 0; border: 1px solid hsl(var(--border)); border-radius: var(--radius-sm); background: none; }
  /* #1084: conditional formatting */
  .cond { display: flex; flex-direction: column; gap: var(--space-2); padding: var(--space-2) var(--space-3); background: hsl(var(--bg-subtle)); border-radius: var(--radius-sm); }
  .cond__measure { display: flex; flex-direction: column; gap: var(--space-2); padding-top: var(--space-2); border-top: 1px solid hsl(var(--border)); }
  .cond__op { width: 7rem; flex: 0 0 auto; }
  .cond__num { width: 5.5rem; flex: 0 0 auto; }
  .cond__color { width: 2.25rem; height: 2rem; flex: 0 0 auto; padding: 0; border: 1px solid hsl(var(--border)); border-radius: var(--radius-sm); background: none; cursor: pointer; }
</style>
