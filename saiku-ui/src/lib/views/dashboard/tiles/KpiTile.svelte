<script lang="ts">
  /*
   * KPI tile (issue #917).
   *
   * Renders a single big number from a one-measure query on the bound
   * cube. Optional features:
   *   - Format selection (number / currency / percent / custom pattern)
   *   - Prior-period comparison: a second-cell delta with ↑/↓ arrow,
   *     coloured by direction.
   *   - Target comparison: delta vs a static target value.
   *   - Sparkline: mini line chart of the configured time level under
   *     the number.
   *   - Threshold-based colouring of the main number using red /
   *     yellow / green bands.
   *
   * Query plumbing mirrors ChartTile / TableTile: an $effect keyed on
   * the active-filter set and resolved cube refetches via the AI Query
   * API. When comparison="prior-period" or sparkline is on, we issue a
   * "by time level" query so both features can read the same series;
   * otherwise a single-cell query is enough.
   */

  import { onDestroy, onMount } from "svelte";
  import * as echarts from "echarts";
  import { ArrowDownRight, ArrowUpRight, Minus } from "lucide-svelte";
  import type { CubeRef, DashboardTile, KpiConfig } from "$lib/api/dashboards";
  import {
    executeAiQuery,
    isAiCell,
    type AiCell,
    type AiQueryResponse,
  } from "$lib/api/aiQuery";
  import { activeFilters } from "$lib/stores/activeFilters.svelte";
  import {
    formatKpi,
    kpiDelta,
    kpiThresholdToken,
    lastAndPriorValues,
    type KpiDelta as KpiDeltaT,
  } from "$lib/dashboard/kpi";

  interface Props {
    tile: DashboardTile;
  }

  let { tile }: Props = $props();

  let loading = $state(false);
  let error = $state<string | null>(null);
  let response = $state<AiQueryResponse | null>(null);

  let sparkHost = $state<HTMLDivElement | null>(null);
  let spark: echarts.ECharts | null = null;
  let sparkResize: ResizeObserver | null = null;

  onMount(() => {
    if (sparkHost) {
      spark = echarts.init(sparkHost);
      sparkResize = new ResizeObserver(() => spark?.resize());
      sparkResize.observe(sparkHost);
    }
  });

  onDestroy(() => {
    sparkResize?.disconnect();
    spark?.dispose();
    spark = null;
  });

  // Derived: the KpiConfig with defaults filled in. Lets the renderer
  // treat the config as totally-defined.
  let kpi = $derived<Required<Pick<KpiConfig, "format" | "comparison" | "direction" | "sparkline">> & KpiConfig>({
    format: "number",
    comparison: "none",
    direction: "higher-is-better",
    sparkline: false,
    ...(tile.kpi ?? {}),
  });

  let cube = $derived<CubeRef | null>(tile.cube ?? null);

  // True when we need the by-time-level query (for sparkline or
  // prior-period delta); false when a single-cell query is enough.
  let wantsSeries = $derived(
    !!kpi.timeLevel && (kpi.sparkline === true || kpi.comparison === "prior-period"),
  );

  let lastQueryJson = $state<string>("");

  $effect(() => {
    const measure = kpi.measure;
    const c = cube;
    const series = wantsSeries;
    const tl = kpi.timeLevel;
    const active = activeFilters.all;
    if (!measure || !c) {
      response = null;
      return;
    }

    // Build the request body. Inline KPI tiles don't carry a TileQuery
    // — the cube + measure + (optional) time-level on rows is enough.
    type Filter = { dimension: string; hierarchy: string; level: string; members: string[] };
    const body: Record<string, unknown> = {
      cube: c,
      measures: [{ name: measure }],
      rows: series && tl ? [{ dimension: tl.dimension, hierarchy: tl.hierarchy, level: tl.level }] : [],
      filters: active.map(
        (f) =>
          ({
            dimension: f.filter.dimension,
            hierarchy: f.filter.hierarchy,
            level: f.filter.level,
            members: f.filter.members ?? [],
          }) satisfies Filter,
      ),
    };
    const json = JSON.stringify(body);
    if (json === lastQueryJson) return;
    lastQueryJson = json;

    loading = true;
    error = null;
    void (async () => {
      try {
        const r = await executeAiQuery(body, "records");
        response = r;
        if (r.status !== "SUCCESS") error = r.error ?? `Query failed: ${r.status}`;
      } catch (e: unknown) {
        error = e instanceof Error ? e.message : String(e);
        response = null;
      } finally {
        loading = false;
      }
    })();
  });

  /** The headline number's source value. For "prior-period" comparison
   *  we want the latest period (last row); otherwise the only cell. */
  let valueAndPrior = $derived.by<{ current: number | null; prior: number | null }>(() => {
    if (!response || response.status !== "SUCCESS") return { current: null, prior: null };
    if (wantsSeries) {
      const series = (response.data ?? []).map((row) => {
        // The single measure column — pick whichever value in the row is
        // an AiCell. There's only one per row for a one-measure query.
        for (const v of Object.values(row)) {
          if (isAiCell(v)) return { value: v.value };
        }
        return { value: null };
      });
      return lastAndPriorValues(series);
    }
    // Single-cell query: data has one row with one measure cell.
    const row = response.data?.[0];
    if (!row) return { current: null, prior: null };
    for (const v of Object.values(row)) {
      if (isAiCell(v)) return { current: v.value, prior: null };
    }
    return { current: null, prior: null };
  });

  let mainValue = $derived(valueAndPrior.current);

  let delta = $derived.by<KpiDeltaT | null>(() => {
    if (kpi.comparison === "prior-period") {
      return kpiDelta(valueAndPrior.current, valueAndPrior.prior, kpi.direction);
    }
    if (kpi.comparison === "target" && kpi.target != null) {
      return kpiDelta(mainValue, kpi.target, kpi.direction);
    }
    return null;
  });

  let mainColourToken = $derived(kpiThresholdToken(mainValue, kpi.thresholds, kpi.direction));

  let formattedMain = $derived(formatKpi(mainValue, kpi.format, kpi.customFormat));

  /** Re-render sparkline whenever the underlying series changes. */
  $effect(() => {
    if (!kpi.sparkline || !wantsSeries) {
      spark?.clear();
      return;
    }
    if (!spark || !response || response.status !== "SUCCESS") return;
    const values = (response.data ?? []).map((row) => {
      for (const v of Object.values(row)) {
        if (isAiCell(v)) return v.value ?? null;
      }
      return null;
    });
    spark.setOption(
      {
        animation: false,
        grid: { top: 4, right: 4, bottom: 4, left: 4 },
        xAxis: { type: "category", show: false, data: values.map((_, i) => i) },
        yAxis: { type: "value", show: false, scale: true },
        series: [
          {
            type: "line",
            data: values,
            smooth: true,
            showSymbol: false,
            lineStyle: { width: 1.5 },
          },
        ],
        tooltip: { show: false },
      },
      true,
    );
  });

  // Pretty-print the delta ratio for the comparison callout.
  let deltaLabel = $derived(formatDeltaLabel(delta));

  function formatDeltaLabel(d: KpiDeltaT | null): string {
    if (!d || d.ratio == null) return "";
    const pct = new Intl.NumberFormat(undefined, {
      style: "percent",
      maximumFractionDigits: 1,
      signDisplay: "always",
    }).format(d.ratio);
    if (kpi.comparison === "target") return `${pct} vs target`;
    return `${pct} vs prior`;
  }
</script>

{#if !tile.cube || !kpi.measure}
  <div class="placeholder">KPI tile has no measure — open ⚙ to pick a cube + measure.</div>
{:else}
  <div class="kpi-tile" data-tone={delta?.tone ?? "flat"}>
    {#if loading && response == null}
      <div class="loading">Loading…</div>
    {:else if error}
      <div class="error">{error}</div>
    {:else}
      <div class="value" style={mainColourToken ? `color: var(${mainColourToken});` : ""}>
        {formattedMain}
      </div>
      {#if delta && delta.ratio != null}
        <div class="delta" data-tone={delta.tone}>
          {#if delta.tone === "positive"}
            <ArrowUpRight size={14} aria-hidden="true" />
          {:else if delta.tone === "negative"}
            <ArrowDownRight size={14} aria-hidden="true" />
          {:else}
            <Minus size={14} aria-hidden="true" />
          {/if}
          <span>{deltaLabel}</span>
        </div>
      {:else if kpi.measureCaption}
        <div class="caption">{kpi.measureCaption}</div>
      {/if}
      {#if kpi.sparkline && wantsSeries}
        <div class="spark" bind:this={sparkHost} aria-hidden="true"></div>
      {/if}
    {/if}
  </div>
{/if}

<style>
  .kpi-tile {
    --kpi-red: var(--danger, #c00);
    --kpi-yellow: var(--warning, #c79a00);
    --kpi-green: var(--success, #1b8a3a);
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    height: 100%;
    padding: 0.5rem;
    text-align: center;
    gap: 0.25rem;
  }
  .value {
    /* 4-6x chart-tile font size per the issue spec — clamp scales it
       responsively so the number stays readable in 2-row tiles. */
    font-size: clamp(1.75rem, 4.5cqi + 0.5rem, 4rem);
    font-weight: 600;
    line-height: 1.1;
    letter-spacing: -0.01em;
    color: var(--fg);
    container-type: inline-size;
  }
  .delta {
    display: inline-flex;
    align-items: center;
    gap: 0.25rem;
    font-size: 0.8125rem;
    color: var(--fg-muted);
  }
  .delta[data-tone="positive"] {
    color: var(--success, #1b8a3a);
  }
  .delta[data-tone="negative"] {
    color: var(--danger, #c00);
  }
  .caption {
    font-size: 0.75rem;
    color: var(--fg-muted);
    text-transform: uppercase;
    letter-spacing: 0.04em;
  }
  .spark {
    width: 100%;
    height: 36px;
    flex-shrink: 0;
  }
  .placeholder,
  .loading,
  .error {
    padding: 0.75rem 1rem;
    color: var(--fg-muted);
    font-size: 0.8125rem;
    text-align: center;
  }
  .error {
    color: var(--danger);
    background: color-mix(in srgb, var(--danger) 12%, transparent);
    border-radius: 4px;
  }
</style>
