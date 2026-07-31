/*
 * Unit tests for the single canonical chart-option builder (#1076).
 *
 * Covers all 15 chart types, both presentation modes (compact = dashboard
 * tiles, non-compact = workspace), and the features that previously only
 * existed on the workspace path (dual-axis split, trend lines) — now shared.
 */

import { describe, test, expect } from "vitest";
import {
  buildChartOption,
  brushOption,
  BRUSHABLE_CHART_TYPES,
  type ChartProjection,
} from "$lib/charts/build";
import { DEFAULT_CHART_OPTIONS, type ChartOptions } from "$lib/views/chartTypes";
import {
  DEFAULT_THEME_TOKENS,
  COLORBLIND_SAFE_COLORS,
  COLORBLIND_WATERFALL,
  NAMED_PALETTES,
  resolvePalette,
  type ThemeTokens,
} from "$lib/views/chartTheme";

/** Two row categories × two measures. */
function sample(): ChartProjection {
  return {
    rowCategories: ["1997", "1998"],
    columnCategories: ["Store Sales", "Unit Sales"],
    matrix: [
      [565238.13, 266773],
      [612482.65, 282417],
    ],
  };
}

/** Single measure (the small-multiples M=1 case). */
function singleMeasure(): ChartProjection {
  return {
    rowCategories: ["1997", "1998"],
    columnCategories: ["Store Sales"],
    matrix: [[1], [2]],
  };
}

function opts(over: Partial<ChartOptions> = {}): ChartOptions {
  return { ...DEFAULT_CHART_OPTIONS, ...over };
}

const ALL_TYPES = [
  "bar",
  "stackedBar",
  "line",
  "stackedLine",
  "area",
  "stackedArea",
  "pie",
  "donut",
  "heatmap",
  "radar",
  "scatter",
  "bubble",
  "treemap",
  "sunburst",
  "waterfall",
] as const;

describe("buildChartOption — coverage of all 15 types", () => {
  test("every supported type produces a non-null option with series (both modes)", () => {
    for (const t of ALL_TYPES) {
      for (const compact of [false, true]) {
        const opt = buildChartOption(sample(), t, opts(), undefined, { compact });
        expect(opt, `${t} compact=${compact}`).not.toBeNull();
        expect(Array.isArray((opt as Record<string, unknown>).series)).toBe(true);
      }
    }
  });

  test("snapshot of every type's structure (non-compact / workspace)", () => {
    const out: Record<string, unknown> = {};
    for (const t of ALL_TYPES) {
      out[t] = buildChartOption(sample(), t, opts(), undefined, { compact: false });
    }
    expect(out).toMatchSnapshot();
  });

  test("snapshot of every type's structure (compact / dashboard)", () => {
    const out: Record<string, unknown> = {};
    for (const t of ALL_TYPES) {
      out[t] = buildChartOption(sample(), t, opts(), undefined, { compact: true });
    }
    expect(out).toMatchSnapshot();
  });
});

describe("buildChartOption — cartesian (bar/line/area)", () => {
  test("bar emits a category xAxis + one series per measure", () => {
    const opt = buildChartOption(sample(), "bar", opts({ dualAxis: false })) as Record<string, unknown>;
    const xAxis = opt.xAxis as { data: string[]; type: string };
    expect(xAxis.type).toBe("category");
    expect(xAxis.data).toEqual(["1997", "1998"]);
    const series = opt.series as Array<{ name: string; type: string; data: (number | null)[] }>;
    expect(series).toHaveLength(2);
    expect(series[0].name).toBe("Store Sales");
    expect(series[0].type).toBe("bar");
    expect(series[0].data).toEqual([565238.13, 612482.65]);
    expect(series[1].data).toEqual([266773, 282417]);
  });

  test("single-axis stacked uses one 'total' stack group", () => {
    const opt = buildChartOption(sample(), "stackedBar", opts({ dualAxis: false })) as Record<string, unknown>;
    const series = opt.series as Array<{ stack?: string }>;
    expect(series.every((s) => s.stack === "total")).toBe(true);
  });

  test("line uses type=line, no areaStyle; area adds areaStyle", () => {
    const line = buildChartOption(sample(), "line", opts()) as Record<string, unknown>;
    const ls = line.series as Array<{ type: string; areaStyle?: object }>;
    expect(ls[0].type).toBe("line");
    expect(ls[0].areaStyle).toBeUndefined();
    const area = buildChartOption(sample(), "area", opts()) as Record<string, unknown>;
    const as = area.series as Array<{ areaStyle?: object }>;
    expect(as[0].areaStyle).toBeDefined();
  });

  test("compact draws missing cells as gaps (null); non-compact as 0", () => {
    const gappy: ChartProjection = {
      rowCategories: ["a", "b"],
      columnCategories: ["m"],
      matrix: [[null], [5]],
    };
    const compact = buildChartOption(gappy, "bar", opts({ dualAxis: false }), undefined, {
      compact: true,
    }) as Record<string, unknown>;
    const roomy = buildChartOption(gappy, "bar", opts({ dualAxis: false }), undefined, {
      compact: false,
    }) as Record<string, unknown>;
    expect((compact.series as Array<{ data: (number | null)[] }>)[0].data).toEqual([null, 5]);
    expect((roomy.series as Array<{ data: (number | null)[] }>)[0].data).toEqual([0, 5]);
  });
});

describe("buildChartOption — dual axis (#1076 brings this to both surfaces)", () => {
  // Unit Sales magnitudes here are >1% of Store Sales, so they stay on one axis;
  // a tiny second series drops to the right axis.
  const disparate: ChartProjection = {
    rowCategories: ["a", "b"],
    columnCategories: ["Big", "Tiny"],
    matrix: [
      [100000, 1],
      [120000, 2],
    ],
  };

  test("dualAxis=true splits a crushed low-magnitude series to a right axis", () => {
    const opt = buildChartOption(disparate, "bar", opts({ dualAxis: true })) as Record<string, unknown>;
    expect(Array.isArray(opt.yAxis)).toBe(true);
    const yAxis = opt.yAxis as Array<{ position: string }>;
    expect(yAxis).toHaveLength(2);
    expect(yAxis[0].position).toBe("left");
    expect(yAxis[1].position).toBe("right");
    const series = opt.series as Array<{ name: string; yAxisIndex: number }>;
    expect(series.find((s) => s.name === "Big")?.yAxisIndex).toBe(0);
    expect(series.find((s) => s.name === "Tiny")?.yAxisIndex).toBe(1);
  });

  test("dualAxis=false keeps a single y-axis", () => {
    const opt = buildChartOption(disparate, "bar", opts({ dualAxis: false })) as Record<string, unknown>;
    expect(Array.isArray(opt.yAxis)).toBe(false);
    const series = opt.series as Array<{ yAxisIndex: number }>;
    expect(series.every((s) => s.yAxisIndex === 0)).toBe(true);
  });

  test("explicit per-series pin overrides the auto decision", () => {
    const opt = buildChartOption(
      sample(),
      "bar",
      opts({ dualAxis: false, seriesAxis: { "Unit Sales": "right" } }),
    ) as Record<string, unknown>;
    const yAxis = opt.yAxis as Array<{ position: string }>;
    expect(Array.isArray(yAxis)).toBe(true);
    const series = opt.series as Array<{ name: string; yAxisIndex: number }>;
    expect(series.find((s) => s.name === "Unit Sales")?.yAxisIndex).toBe(1);
  });
});

describe("buildChartOption — trend lines (#1076 brings this to both surfaces)", () => {
  const ts: ChartProjection = {
    rowCategories: ["a", "b", "c", "d"],
    columnCategories: ["m"],
    matrix: [[1], [2], [3], [4]],
  };

  test("linear trend appends a dashed line series labelled '(trend)'", () => {
    const opt = buildChartOption(ts, "line", opts({ trendLine: "linear" })) as Record<string, unknown>;
    const series = opt.series as Array<{ name: string; type: string; lineStyle?: { type: string } }>;
    // 1 data series + 1 trend series.
    expect(series).toHaveLength(2);
    const trend = series[1];
    expect(trend.name).toBe("m (trend)");
    expect(trend.type).toBe("line");
    expect(trend.lineStyle?.type).toBe("dashed");
  });

  test("moving average names the series with its period", () => {
    const opt = buildChartOption(ts, "line", opts({ trendLine: "ma", trendPeriod: 3 })) as Record<
      string,
      unknown
    >;
    const series = opt.series as Array<{ name: string }>;
    expect(series[1].name).toBe("m (MA3)");
  });

  test("trendLine=none adds no extra series", () => {
    const opt = buildChartOption(ts, "line", opts({ trendLine: "none" })) as Record<string, unknown>;
    expect((opt.series as unknown[]).length).toBe(1);
  });

  test("bar charts never get a trend series (line-only)", () => {
    const opt = buildChartOption(ts, "bar", opts({ trendLine: "linear" })) as Record<string, unknown>;
    expect((opt.series as unknown[]).length).toBe(1);
  });
});

describe("buildChartOption — title & legend", () => {
  test("non-compact honours a user title + legend position", () => {
    const opt = buildChartOption(sample(), "bar", opts({ title: "My chart", legendPosition: "bottom" })) as Record<
      string,
      unknown
    >;
    expect((opt.title as { text: string }).text).toBe("My chart");
    expect((opt.legend as { bottom: number }).bottom).toBe(10);
  });

  test("#1595 title sits in its own top band and the grid reserves room below it", () => {
    // Roomy chart: title pinned near the top, plot pushed below the title band.
    const roomy = buildChartOption(sample(), "bar", opts({ title: "My chart" })) as Record<string, unknown>;
    expect((roomy.title as { top: number }).top).toBe(8);
    expect((roomy.grid as { top: number }).top).toBe(50);

    // Compact tile WITH a title reserves the title band (was a flat 24 that
    // let the title overlap the top gridline / data).
    const compactTitled = buildChartOption(sample(), "bar", opts({ title: "My chart" }), undefined, {
      compact: true,
    }) as Record<string, unknown>;
    expect((compactTitled.grid as { top: number }).top).toBe(44);

    // Compact tile WITHOUT a title is unchanged (no wasted header band).
    const compactBare = buildChartOption(sample(), "bar", opts({ title: "" }), undefined, {
      compact: true,
    }) as Record<string, unknown>;
    expect((compactBare.grid as { top: number }).top).toBe(24);
  });

  test("compact legend defaults to a bottom scroll legend (legacy-tile parity)", () => {
    const opt = buildChartOption(sample(), "bar", opts({ legendPosition: "bottom" }), undefined, {
      compact: true,
    }) as Record<string, unknown>;
    const legend = opt.legend as { type: string; bottom: number };
    expect(legend.type).toBe("scroll");
    expect(legend.bottom).toBe(0);
  });

  test("compact legend now honors legendPosition (#1077) while staying a scroll legend", () => {
    const top = buildChartOption(sample(), "bar", opts({ legendPosition: "top" }), undefined, {
      compact: true,
    }) as Record<string, unknown>;
    expect((top.legend as { type: string; top: number }).type).toBe("scroll");
    expect((top.legend as { top: number }).top).toBe(0);
    const left = buildChartOption(sample(), "bar", opts({ legendPosition: "left" }), undefined, {
      compact: true,
    }) as Record<string, unknown>;
    expect((left.legend as { left: number; orient: string }).left).toBe(0);
    expect((left.legend as { orient: string }).orient).toBe("vertical");
  });

  test("compact legend hidden when showLegend is false", () => {
    const opt = buildChartOption(sample(), "bar", opts({ showLegend: false }), undefined, {
      compact: true,
    }) as Record<string, unknown>;
    expect((opt.legend as { show: boolean }).show).toBe(false);
  });

  test("compact pie merges no overall title — just per-measure cell titles", () => {
    const opt = buildChartOption(sample(), "pie", opts({ title: "" }), undefined, {
      compact: true,
    }) as Record<string, unknown>;
    const titles = opt.title as Array<{ text: string }>;
    expect(titles.map((t) => t.text)).toEqual(["Store Sales", "Unit Sales"]);
  });

  test("non-compact pie prepends the user title to the cell titles", () => {
    const opt = buildChartOption(sample(), "pie", opts({ title: "Overall" })) as Record<string, unknown>;
    const titles = opt.title as Array<{ text: string }>;
    expect(titles.map((t) => t.text)).toEqual(["Overall", "Store Sales", "Unit Sales"]);
  });
});

describe("buildChartOption — small multiples (pie/donut/treemap/sunburst)", () => {
  test("2-measure pie fans out into 2 series, each sliced by row category", () => {
    const opt = buildChartOption(sample(), "pie", opts()) as Record<string, unknown>;
    const series = opt.series as Array<{ type: string; name: string; data: { name: string; value: number }[] }>;
    expect(series).toHaveLength(2);
    expect(series.every((s) => s.type === "pie")).toBe(true);
    expect(series.map((s) => s.name)).toEqual(["Store Sales", "Unit Sales"]);
    expect(series[0].data.map((d) => d.name)).toEqual(["1997", "1998"]);
    expect(series[0].data[0].value).toBeCloseTo(565238.13, 2);
  });

  test("1-measure pie renders a single chart with a single title", () => {
    const opt = buildChartOption(singleMeasure(), "pie", opts()) as Record<string, unknown>;
    const series = opt.series as Array<{ type: string }>;
    expect(series).toHaveLength(1);
    expect((opt.title as unknown[]).length).toBe(1);
  });

  test("donut keeps a non-zero inner radius", () => {
    const opt = buildChartOption(sample(), "donut", opts()) as Record<string, unknown>;
    const series = opt.series as Array<{ radius: (string | number)[] }>;
    expect(Array.isArray(series[0].radius)).toBe(true);
    expect(parseFloat(String(series[0].radius[0]))).toBeGreaterThan(0);
  });

  test("treemap fans out one series per measure, items = rows", () => {
    const opt = buildChartOption(sample(), "treemap", opts()) as Record<string, unknown>;
    const series = opt.series as Array<{ type: string; data: { name: string; value: number }[] }>;
    expect(series).toHaveLength(2);
    expect(series.every((s) => s.type === "treemap")).toBe(true);
    expect(series[0].data[0].name).toBe("1997");
  });
});

describe("buildChartOption — matrix types (heatmap/radar/scatter/waterfall)", () => {
  test("heatmap emits [col,row,value] tuples + a visualMap", () => {
    const opt = buildChartOption(sample(), "heatmap", opts()) as Record<string, unknown>;
    const series = opt.series as Array<{ type: string; data: [number, number, number][] }>;
    expect(series[0].type).toBe("heatmap");
    expect(series[0].data).toHaveLength(4);
    expect(opt.visualMap).toBeDefined();
  });

  test("radar uses cols as indicators + rows as series entries", () => {
    const opt = buildChartOption(sample(), "radar", opts()) as Record<string, unknown>;
    const radar = opt.radar as { indicator: { name: string }[] };
    expect(radar.indicator.map((i) => i.name)).toEqual(["Store Sales", "Unit Sales"]);
    const series = opt.series as Array<{ data: unknown[] }>;
    expect(series[0].data).toHaveLength(2);
  });

  test("waterfall emits three stacked series (spacer + pos + neg)", () => {
    const opt = buildChartOption(sample(), "waterfall", opts()) as Record<string, unknown>;
    const series = opt.series as Array<{ stack: string }>;
    expect(series).toHaveLength(3);
    expect(series.every((s) => s.stack === "waterfall")).toBe(true);
  });
});

describe("buildChartOption — theme tokens", () => {
  const dark = {
    fg: "#e5e7eb",
    fgMuted: "#9ca3af",
    bg: "#0b1220",
    bgMuted: "#111827",
    border: "#334155",
    accent: "#818cf8",
    chartColors: ["#aaa111", "#bbb222"],
  };

  test("bar threads palette + text/axis/tooltip colours from tk", () => {
    const opt = buildChartOption(sample(), "bar", opts(), dark) as Record<string, unknown>;
    expect(opt.backgroundColor).toBe("transparent");
    expect(opt.color).toEqual(dark.chartColors);
    expect((opt.textStyle as { color: string }).color).toBe("#e5e7eb");
    const xAxis = opt.xAxis as { axisLabel: { color: string }; axisLine: { lineStyle: { color: string } } };
    expect(xAxis.axisLabel.color).toBe("#9ca3af");
    expect(xAxis.axisLine.lineStyle.color).toBe("#334155");
    const tooltip = opt.tooltip as { backgroundColor: string; textStyle: { color: string } };
    expect(tooltip.backgroundColor).toBe("#0b1220");
    expect(tooltip.textStyle.color).toBe("#e5e7eb");
  });

  test("defaults to the light fallback when tk is omitted", () => {
    const opt = buildChartOption(sample(), "bar", opts()) as Record<string, unknown>;
    expect((opt.textStyle as { color: string }).color).toBe("#0f172a");
  });
});

describe("buildChartOption — map (#1071)", () => {
  /** Countries on rows, one measure. */
  function geo(): ChartProjection {
    return {
      rowCategories: ["USA", "Canada", "Mexico"],
      columnCategories: ["Store Sales"],
      matrix: [[565238.13], [221001.5], [97863.2]],
    };
  }

  test("emits a single 'map' series on the world map", () => {
    const opt = buildChartOption(geo(), "map", opts()) as Record<string, unknown>;
    const series = opt.series as { type: string; map: string }[];
    expect(series).toHaveLength(1);
    expect(series[0].type).toBe("map");
    expect(series[0].map).toBe("world");
  });

  test("aliases OLAP captions to GeoJSON feature names; passes through matches", () => {
    const opt = buildChartOption(geo(), "map", opts()) as Record<string, unknown>;
    const data = (opt.series as { data: { name: string; value: number | null }[] }[])[0].data;
    expect(data.map((d) => d.name)).toEqual(["United States of America", "Canada", "Mexico"]);
    expect(data[0].value).toBeCloseTo(565238.13);
  });

  test("visualMap spans the present values and uses the chosen colour ramp", () => {
    const opt = buildChartOption(geo(), "map", opts({ colorRamp: "reds" })) as Record<string, unknown>;
    const vm = opt.visualMap as { min: number; max: number; inRange: { color: string[] } };
    expect(vm.min).toBeCloseTo(97863.2);
    expect(vm.max).toBeCloseTo(565238.13);
    expect(vm.inRange.color[0]).toBe("#fee0d2"); // reds ramp low end
  });

  test("mapMissing 'blank' leaves a null measure null; 'zero' coerces to 0", () => {
    const withGap: ChartProjection = {
      rowCategories: ["USA", "Canada"],
      columnCategories: ["Store Sales"],
      matrix: [[100], [null]],
    };
    const blank = buildChartOption(withGap, "map", opts({ mapMissing: "blank" })) as Record<string, unknown>;
    const zero = buildChartOption(withGap, "map", opts({ mapMissing: "zero" })) as Record<string, unknown>;
    const blankData = (blank.series as { data: { value: number | null }[] }[])[0].data;
    const zeroData = (zero.series as { data: { value: number | null }[] }[])[0].data;
    expect(blankData[1].value).toBeNull();
    expect(zeroData[1].value).toBe(0);
  });

  test("tooltip formatter HTML-escapes the (data-derived) region name (#1071 XSS hardening)", () => {
    const hostile: ChartProjection = {
      rowCategories: ['<img src=x onerror=alert(1)>'],
      columnCategories: ["Store Sales"],
      matrix: [[5]],
    };
    const opt = buildChartOption(hostile, "map", opts()) as Record<string, unknown>;
    const fmt = (opt.tooltip as { formatter: (p: unknown) => string }).formatter;
    const out = fmt({ name: '<img src=x onerror=alert(1)>', value: 5 });
    expect(out).not.toContain("<img");
    expect(out).toContain("&lt;img");
  });
});

describe("buildChartOption — dataZoom zoom + pan (#1080)", () => {
  // Cartesian families that get the visible bottom slider when there
  // are enough categories. Bar / line / area variants — long category
  // axes (time series, big region lists) benefit from panning.
  const SLIDER_CARTESIAN = [
    "bar",
    "stackedBar",
    "line",
    "stackedLine",
    "area",
    "stackedArea",
  ] as const;
  // Cartesian families that get ONLY inside-zoom (no visible slider).
  // saiku 2026-06 user feedback: scatter / bubble distributions don't
  // pan, waterfall is a single sequence, heatmap shows everything at
  // once + the slider competed visually with the visualMap legend.
  const INSIDE_ONLY_CARTESIAN = ["scatter", "bubble", "waterfall"] as const;
  // Types that never get dataZoom at all (slider or inside-zoom).
  // Heatmap was added to this group 2026-06 — the visualMap legend
  // is the navigation, panning the matrix isn't a sensible gesture.
  const NON_CARTESIAN = ["pie", "donut", "treemap", "sunburst", "heatmap", "radar", "map"] as const;

  // Big enough to trip the slider gate — the screenshot bug fix only emits
  // the visible bottom slider when there are enough categories that zooming
  // is actually useful (< ~12 categories = no slider). Both rows and cols
  // dense because scatter / bubble key off cols.length, the rest off rows.
  function denseSample(): ChartProjection {
    const rows = Array.from({ length: 20 }, (_, i) => `r${i}`);
    const cols = Array.from({ length: 20 }, (_, i) => `c${i}`);
    const matrix = rows.map((_, i) => cols.map((_, j) => 100 + i * 5 + j));
    return { rowCategories: rows, columnCategories: cols, matrix };
  }

  test("dense bar / line / area (roomy) gets an inside zoom + a bottom slider", () => {
    for (const t of SLIDER_CARTESIAN) {
      const opt = buildChartOption(denseSample(), t, opts(), undefined, {
        compact: false,
      }) as Record<string, unknown>;
      const dz = opt.dataZoom as Array<{ type: string; xAxisIndex: number }>;
      expect(Array.isArray(dz), `${t} dataZoom is array`).toBe(true);
      expect(
        dz.map((d) => d.type),
        `${t} has inside + slider`,
      ).toEqual(["inside", "slider"]);
      expect(dz.every((d) => d.xAxisIndex === 0)).toBe(true);
    }
  });

  test("scatter / bubble / waterfall never get the visible slider", () => {
    // Distributions / single-sequence layouts don't pan usefully — the
    // slider just adds clutter (user feedback 2026-06-07). Inside-zoom
    // stays armed for mouse-wheel users.
    for (const t of INSIDE_ONLY_CARTESIAN) {
      for (const make of [denseSample, sample]) {
        const opt = buildChartOption(make(), t, opts(), undefined, {
          compact: false,
        }) as Record<string, unknown>;
        const dz = opt.dataZoom as Array<{ type: string }>;
        expect(dz.map((d) => d.type), `${t} inside-only`).toEqual(["inside"]);
      }
    }
  });

  test("sparse bar / line / area (roomy) hides the slider — empty track would be a UI bug", () => {
    for (const t of SLIDER_CARTESIAN) {
      const opt = buildChartOption(sample(), t, opts(), undefined, {
        compact: false,
      }) as Record<string, unknown>;
      const dz = opt.dataZoom as Array<{ type: string }>;
      expect(dz.map((d) => d.type), `${t} sparse = inside only`).toEqual([
        "inside",
      ]);
    }
  });

  test("every cartesian type (compact) gets an inside zoom but NO slider", () => {
    for (const t of [...SLIDER_CARTESIAN, ...INSIDE_ONLY_CARTESIAN]) {
      const opt = buildChartOption(denseSample(), t, opts(), undefined, {
        compact: true,
      }) as Record<string, unknown>;
      const dz = opt.dataZoom as Array<{ type: string }>;
      expect(dz.map((d) => d.type), `${t} compact = inside only`).toEqual([
        "inside",
      ]);
    }
  });

  test("non-cartesian types never get dataZoom (roomy or compact)", () => {
    for (const t of NON_CARTESIAN) {
      for (const compact of [false, true]) {
        const proj = t === "map" ? { rowCategories: ["USA"], columnCategories: ["m"], matrix: [[5]] } : sample();
        const opt = buildChartOption(proj, t, opts(), undefined, { compact }) as Record<string, unknown>;
        expect(opt.dataZoom, `${t} compact=${compact} has no dataZoom`).toBeUndefined();
      }
    }
  });

  test("dataZoom uses filterMode 'none' so zooming never drops data from the series", () => {
    const opt = buildChartOption(sample(), "bar", opts(), undefined, { compact: false }) as Record<
      string,
      unknown
    >;
    const dz = opt.dataZoom as Array<{ filterMode: string }>;
    expect(dz.every((d) => d.filterMode === "none")).toBe(true);
  });
});

describe("buildChartOption — cartesian sparkline tooltip (#1087)", () => {
  function tipFormatter(opt: Record<string, unknown>) {
    const tip = opt.tooltip as { formatter?: (p: unknown) => string };
    return tip.formatter;
  }

  test("roomy bar chart gets a function formatter returning an <svg> sparkline + value", () => {
    const opt = buildChartOption(sample(), "bar", opts(), undefined, { compact: false }) as Record<
      string,
      unknown
    >;
    const fmt = tipFormatter(opt);
    expect(typeof fmt).toBe("function");
    const out = fmt!([
      { axisValueLabel: "1997", seriesName: "Store Sales", dataIndex: 0, value: 565238.13, color: "#abc" },
      { axisValueLabel: "1997", seriesName: "Unit Sales", dataIndex: 0, value: 266773, color: "#def" },
    ]);
    expect(out).toContain("<svg");
    expect(out).toContain("Store Sales");
    expect(out).toContain("565238.13"); // existing value text is kept
  });

  test("compact tiles do NOT get the sparkline formatter (no room)", () => {
    const opt = buildChartOption(sample(), "bar", opts(), undefined, { compact: true }) as Record<
      string,
      unknown
    >;
    expect(tipFormatter(opt)).toBeUndefined();
  });

  test("single-category charts get no sparkline formatter (no trend to draw)", () => {
    const oneRow: ChartProjection = {
      rowCategories: ["1997"],
      columnCategories: ["Store Sales"],
      matrix: [[5]],
    };
    const opt = buildChartOption(oneRow, "line", opts(), undefined, { compact: false }) as Record<
      string,
      unknown
    >;
    expect(tipFormatter(opt)).toBeUndefined();
  });

  test("SECURITY: formatter HTML-escapes a hostile series name (innerHTML, un-escaped by ECharts)", () => {
    const hostile: ChartProjection = {
      rowCategories: ["1997", "1998"],
      columnCategories: ['<img src=x onerror=alert(1)>', "Unit Sales"],
      matrix: [
        [1, 2],
        [3, 4],
      ],
    };
    const opt = buildChartOption(hostile, "line", opts(), undefined, { compact: false }) as Record<
      string,
      unknown
    >;
    const fmt = tipFormatter(opt)!;
    const out = fmt([
      { axisValueLabel: "1997", seriesName: '<img src=x onerror=alert(1)>', dataIndex: 0, value: 1 },
    ]);
    expect(out).not.toContain("<img");
    expect(out).toContain("&lt;img");
  });

  test("SECURITY: formatter HTML-escapes a hostile category (axis) label", () => {
    const opt = buildChartOption(sample(), "bar", opts(), undefined, { compact: false }) as Record<
      string,
      unknown
    >;
    const fmt = tipFormatter(opt)!;
    const out = fmt([
      { axisValueLabel: '<script>alert(1)</script>', seriesName: "Store Sales", dataIndex: 0, value: 1 },
    ]);
    expect(out).not.toContain("<script>alert");
    expect(out).toContain("&lt;script&gt;");
  });
});

describe("buildChartOption — number formatting (#1082)", () => {
  function yAxisOf(opt: Record<string, unknown>): { axisLabel?: { formatter?: (v: number) => string } } {
    const y = opt.yAxis;
    return Array.isArray(y) ? (y[0] as { axisLabel?: { formatter?: (v: number) => string } }) : (y as never);
  }

  test("no numberFormat → value axis keeps a plain axisLabel (no formatter)", () => {
    const opt = buildChartOption(sample(), "bar", opts({ dualAxis: false })) as Record<string, unknown>;
    const y = yAxisOf(opt);
    expect(y.axisLabel?.formatter).toBeUndefined();
  });

  test("numberFormat sets a value-axis formatter that applies prefix/decimals", () => {
    const opt = buildChartOption(
      sample(),
      "bar",
      opts({ dualAxis: false, numberFormat: { prefix: "£", decimals: 0, thousands: true } }),
    ) as Record<string, unknown>;
    const y = yAxisOf(opt);
    expect(typeof y.axisLabel?.formatter).toBe("function");
    const out = y.axisLabel!.formatter!(1234567);
    expect(out.startsWith("£")).toBe(true);
    expect(out.replace(/[^0-9]/g, "")).toBe("1234567");
  });

  test("dual-axis formats BOTH value axes", () => {
    const disparate: ChartProjection = {
      rowCategories: ["a", "b"],
      columnCategories: ["Big", "Tiny"],
      matrix: [
        [100000, 1],
        [120000, 2],
      ],
    };
    const opt = buildChartOption(
      disparate,
      "bar",
      opts({ dualAxis: true, numberFormat: { suffix: "u" } }),
    ) as Record<string, unknown>;
    const y = opt.yAxis as Array<{ axisLabel?: { formatter?: (v: number) => string } }>;
    expect(y).toHaveLength(2);
    expect(y[0].axisLabel?.formatter?.(5)).toBe("5u");
    expect(y[1].axisLabel?.formatter?.(5)).toBe("5u");
  });

  test("tooltip value text is formatted (compact tile, no sparkline)", () => {
    const opt = buildChartOption(sample(), "bar", opts({ numberFormat: { prefix: "$", decimals: 0 } }), undefined, {
      compact: true,
    }) as Record<string, unknown>;
    const fmt = (opt.tooltip as { formatter?: (p: unknown) => string }).formatter;
    expect(typeof fmt).toBe("function");
    const out = fmt!([{ axisValueLabel: "1997", seriesName: "Store Sales", dataIndex: 0, value: 565238.13 }]);
    expect(out).toContain("$565238");
    expect(out).not.toContain("<svg"); // compact tiles never draw the sparkline
  });

  test("formatted tooltip value is still HTML-escaped (innerHTML safety)", () => {
    const opt = buildChartOption(
      sample(),
      "bar",
      opts({ numberFormat: { prefix: "<b>", suffix: "</b>" } }),
      undefined,
      { compact: true },
    ) as Record<string, unknown>;
    const fmt = (opt.tooltip as { formatter: (p: unknown) => string }).formatter;
    const out = fmt([{ axisValueLabel: "1997", seriesName: "Store Sales", dataIndex: 0, value: 5 }]);
    expect(out).not.toContain("<b>5</b>");
    expect(out).toContain("&lt;b&gt;");
  });

  test("cartesian series carry a label.formatter only when numberFormat is set", () => {
    const plain = buildChartOption(sample(), "bar", opts()) as Record<string, unknown>;
    expect((plain.series as Record<string, unknown>[])[0].label).toBeUndefined();
    const fmtd = buildChartOption(sample(), "bar", opts({ numberFormat: { suffix: "%" } })) as Record<
      string,
      unknown
    >;
    const label = (fmtd.series as Array<{ label?: { formatter?: (p: { value: number }) => string } }>)[0].label;
    expect(typeof label?.formatter).toBe("function");
    expect(label!.formatter!({ value: 42 })).toBe("42%");
  });

  test("roomy mode keeps the sparkline formatter AND formats the value", () => {
    const opt = buildChartOption(
      sample(),
      "bar",
      opts({ numberFormat: { abbreviate: true } }),
      undefined,
      { compact: false },
    ) as Record<string, unknown>;
    const fmt = (opt.tooltip as { formatter: (p: unknown) => string }).formatter;
    const out = fmt([{ axisValueLabel: "1997", seriesName: "Store Sales", dataIndex: 0, value: 565238.13 }]);
    expect(out).toContain("<svg"); // sparkline preserved in roomy mode
    expect(out).toContain("565.2k"); // value abbreviated
  });
});

// ---------------------------------------------------------------------------
// #1081: per-series colour override + theme-aware named palettes.
// Precedence: per-series override > colour-blind-safe (highContrast) > named
// palette > theme default (tk.chartColors).
// ---------------------------------------------------------------------------
describe("buildChartOption — named palettes + series colours (#1081)", () => {
  test("default palette uses the theme palette (tk.chartColors) — legacy unchanged", () => {
    const opt = buildChartOption(sample(), "bar", opts({ palette: "default" })) as Record<string, unknown>;
    expect(opt.color).toEqual(DEFAULT_THEME_TOKENS.chartColors);
  });

  test("omitting palette is identical to 'default' (back-compat)", () => {
    const noPalette = opts();
    delete (noPalette as Partial<ChartOptions>).palette;
    const opt = buildChartOption(sample(), "bar", noPalette) as Record<string, unknown>;
    expect(opt.color).toEqual(DEFAULT_THEME_TOKENS.chartColors);
  });

  test("option.color equals the resolved named palette for a given id", () => {
    for (const id of Object.keys(NAMED_PALETTES)) {
      const opt = buildChartOption(sample(), "bar", opts({ palette: id })) as Record<string, unknown>;
      expect(opt.color, id).toEqual(NAMED_PALETTES[id]);
      // resolvePalette is the single source — keep them aligned.
      expect(opt.color).toEqual(resolvePalette(id, DEFAULT_THEME_TOKENS));
    }
  });

  test("an unknown palette id falls back to the theme default palette", () => {
    const opt = buildChartOption(sample(), "bar", opts({ palette: "made-up" })) as Record<string, unknown>;
    expect(opt.color).toEqual(DEFAULT_THEME_TOKENS.chartColors);
  });

  test("named palette is theme-aware (resolves against the passed tokens for 'default')", () => {
    const dark: ThemeTokens = { ...DEFAULT_THEME_TOKENS, chartColors: ["#111", "#222"] };
    const opt = buildChartOption(sample(), "bar", opts({ palette: "default" }), dark) as Record<string, unknown>;
    expect(opt.color).toEqual(["#111", "#222"]);
  });

  test("a seriesColors override lands on the correct cartesian series (by name)", () => {
    const opt = buildChartOption(
      sample(),
      "bar",
      opts({ seriesColors: { "Unit Sales": "#ff0000" } }),
    ) as Record<string, unknown>;
    const series = opt.series as Array<{ name: string; color?: string }>;
    expect(series.find((s) => s.name === "Store Sales")?.color).toBeUndefined();
    expect(series.find((s) => s.name === "Unit Sales")?.color).toBe("#ff0000");
  });

  test("no seriesColors → no series carries an explicit colour (palette cycle drives it)", () => {
    const opt = buildChartOption(sample(), "bar", opts()) as Record<string, unknown>;
    const series = opt.series as Array<{ color?: string }>;
    expect(series.every((s) => s.color === undefined)).toBe(true);
  });

  test("pie applies the override at the data-item (slice) level by name", () => {
    const opt = buildChartOption(
      sample(),
      "pie",
      opts({ seriesColors: { "1998": "#00ff00" } }),
    ) as Record<string, unknown>;
    const data = (opt.series as Array<{ data: { name: string; itemStyle?: { color?: string } }[] }>)[0].data;
    expect(data.find((d) => d.name === "1997")?.itemStyle).toBeUndefined();
    expect(data.find((d) => d.name === "1998")?.itemStyle?.color).toBe("#00ff00");
  });

  test("scatter applies the override to the matching (row-named) series", () => {
    const opt = buildChartOption(
      sample(),
      "scatter",
      opts({ seriesColors: { "1998": "#0000ff" } }),
    ) as Record<string, unknown>;
    const series = opt.series as Array<{ name: string; color?: string }>;
    expect(series.find((s) => s.name === "1997")?.color).toBeUndefined();
    expect(series.find((s) => s.name === "1998")?.color).toBe("#0000ff");
  });

  test("colour-blind-safe (highContrast) overrides the named palette", () => {
    const hc: ThemeTokens = {
      ...DEFAULT_THEME_TOKENS,
      chartColors: COLORBLIND_SAFE_COLORS,
      highContrast: true,
    };
    const opt = buildChartOption(sample(), "bar", opts({ palette: "vibrant" }), hc) as Record<string, unknown>;
    // cb-safe wins: the cycle is the Okabe-Ito palette, NOT "vibrant".
    expect(opt.color).toEqual(COLORBLIND_SAFE_COLORS);
    expect(opt.color).not.toEqual(NAMED_PALETTES.vibrant);
  });

  test("per-series override still wins for that one series even with cb-safe on", () => {
    const hc: ThemeTokens = {
      ...DEFAULT_THEME_TOKENS,
      chartColors: COLORBLIND_SAFE_COLORS,
      highContrast: true,
    };
    const opt = buildChartOption(
      sample(),
      "bar",
      opts({ palette: "vibrant", seriesColors: { "Store Sales": "#abcdef" } }),
      hc,
    ) as Record<string, unknown>;
    const series = opt.series as Array<{ name: string; color?: string }>;
    // Cycle is still cb-safe, but the explicit override paints its one series.
    expect(opt.color).toEqual(COLORBLIND_SAFE_COLORS);
    expect(series.find((s) => s.name === "Store Sales")?.color).toBe("#abcdef");
    expect(series.find((s) => s.name === "Unit Sales")?.color).toBeUndefined();
  });
});

describe("resolvePalette (#1081)", () => {
  test("'default' / undefined return the theme palette", () => {
    expect(resolvePalette("default", DEFAULT_THEME_TOKENS)).toEqual(DEFAULT_THEME_TOKENS.chartColors);
    expect(resolvePalette(undefined, DEFAULT_THEME_TOKENS)).toEqual(DEFAULT_THEME_TOKENS.chartColors);
  });

  test("a named id returns its fixed colour array", () => {
    expect(resolvePalette("cool", DEFAULT_THEME_TOKENS)).toEqual(NAMED_PALETTES.cool);
  });

  test("an unknown id falls back to the theme palette", () => {
    expect(resolvePalette("nope", DEFAULT_THEME_TOKENS)).toEqual(DEFAULT_THEME_TOKENS.chartColors);
  });
});

describe("buildChartOption — reference lines & bands (#1079)", () => {
  type MarkLine = { symbol: unknown; label: Record<string, unknown>; data: Array<Record<string, unknown>> };
  type MarkArea = { label: Record<string, unknown>; data: Array<Array<Record<string, unknown>>> };

  function firstSeriesMarkLine(opt: Record<string, unknown>): MarkLine | undefined {
    const series = opt.series as Array<{ markLine?: MarkLine }>;
    return series.map((s) => s.markLine).find((m) => m != null);
  }
  function firstSeriesMarkArea(opt: Record<string, unknown>): MarkArea | undefined {
    const series = opt.series as Array<{ markArea?: MarkArea }>;
    return series.map((s) => s.markArea).find((m) => m != null);
  }

  test("no referenceLines → no markLine on any series (legacy unchanged)", () => {
    const opt = buildChartOption(sample(), "bar", opts()) as Record<string, unknown>;
    const series = opt.series as Array<{ markLine?: unknown; markArea?: unknown }>;
    expect(series.every((s) => s.markLine === undefined)).toBe(true);
    expect(series.every((s) => s.markArea === undefined)).toBe(true);
  });

  test("explicitly empty referenceLines adds no markLine", () => {
    const opt = buildChartOption(sample(), "bar", opts({ referenceLines: [] })) as Record<string, unknown>;
    expect(firstSeriesMarkLine(opt)).toBeUndefined();
  });

  test("a y-axis reference line attaches markLine data with yAxis + label + colour", () => {
    const opt = buildChartOption(
      sample(),
      "line",
      opts({ referenceLines: [{ axis: "y", value: 300000, label: "Target", color: "#ff0000" }] }),
    ) as Record<string, unknown>;
    const ml = firstSeriesMarkLine(opt)!;
    expect(ml).toBeDefined();
    expect(ml.data).toHaveLength(1);
    expect(ml.data[0].yAxis).toBe(300000);
    expect(ml.data[0].xAxis).toBeUndefined();
    expect(ml.data[0].name).toBe("Target");
    expect((ml.data[0].lineStyle as { color: string }).color).toBe("#ff0000");
  });

  test("an x-axis reference line uses xAxis (category index), not yAxis", () => {
    const opt = buildChartOption(
      sample(),
      "bar",
      opts({ referenceLines: [{ axis: "x", value: 1, label: "Launch" }] }),
    ) as Record<string, unknown>;
    const ml = firstSeriesMarkLine(opt)!;
    expect(ml.data[0].xAxis).toBe(1);
    expect(ml.data[0].yAxis).toBeUndefined();
    expect(ml.data[0].name).toBe("Launch");
  });

  test("colour defaults to the theme fgMuted token when omitted", () => {
    const opt = buildChartOption(sample(), "bar", opts({ referenceLines: [{ axis: "y", value: 5 }] })) as Record<
      string,
      unknown
    >;
    const ml = firstSeriesMarkLine(opt)!;
    expect((ml.data[0].lineStyle as { color: string }).color).toBe(DEFAULT_THEME_TOKENS.fgMuted);
  });

  test("markLine rides on the first measure series only (not every series)", () => {
    const opt = buildChartOption(
      sample(),
      "bar",
      opts({ referenceLines: [{ axis: "y", value: 1 }] }),
    ) as Record<string, unknown>;
    const series = opt.series as Array<{ name: string; markLine?: unknown }>;
    const withMark = series.filter((s) => s.markLine !== undefined);
    expect(withMark).toHaveLength(1);
    expect(withMark[0].name).toBe("Store Sales");
  });

  test("compact mode still renders the line but hides its label text", () => {
    const opt = buildChartOption(
      sample(),
      "bar",
      opts({ referenceLines: [{ axis: "y", value: 5, label: "T" }] }),
      undefined,
      { compact: true },
    ) as Record<string, unknown>;
    const ml = firstSeriesMarkLine(opt)!;
    expect(ml.data).toHaveLength(1);
    expect((ml.label as { show: boolean }).show).toBe(false);
  });

  test("a reference band attaches markArea data pairs spanning [from, to]", () => {
    const opt = buildChartOption(
      sample(),
      "bar",
      opts({ referenceBands: [{ axis: "y", from: 100, to: 200, label: "Range" }] }),
    ) as Record<string, unknown>;
    const ma = firstSeriesMarkArea(opt)!;
    expect(ma.data).toHaveLength(1);
    const [start, end] = ma.data[0];
    expect(start.yAxis).toBe(100);
    expect(end.yAxis).toBe(200);
    expect(start.name).toBe("Range");
  });

  test("an x-axis band uses xAxis on both endpoints", () => {
    const opt = buildChartOption(
      sample(),
      "bar",
      opts({ referenceBands: [{ axis: "x", from: 0, to: 1 }] }),
    ) as Record<string, unknown>;
    const ma = firstSeriesMarkArea(opt)!;
    const [start, end] = ma.data[0];
    expect(start.xAxis).toBe(0);
    expect(end.xAxis).toBe(1);
  });

  test("non-cartesian types never get markLine/markArea (e.g. pie)", () => {
    const opt = buildChartOption(
      sample(),
      "pie",
      opts({ referenceLines: [{ axis: "y", value: 5 }], referenceBands: [{ axis: "y", from: 0, to: 1 }] }),
    ) as Record<string, unknown>;
    const series = opt.series as Array<{ markLine?: unknown; markArea?: unknown }>;
    expect(series.every((s) => s.markLine === undefined && s.markArea === undefined)).toBe(true);
  });

  test("trend overlay series stay markLine-free; the data series carries the ref line", () => {
    const ts: ChartProjection = {
      rowCategories: ["a", "b", "c", "d"],
      columnCategories: ["m"],
      matrix: [[1], [2], [3], [4]],
    };
    const opt = buildChartOption(
      ts,
      "line",
      opts({ trendLine: "linear", referenceLines: [{ axis: "y", value: 2 }] }),
    ) as Record<string, unknown>;
    const series = opt.series as Array<{ name: string; markLine?: unknown }>;
    expect(series).toHaveLength(2); // data + trend
    expect(series[0].markLine).toBeDefined(); // data series carries it
    expect(series[1].name).toBe("m (trend)");
    expect(series[1].markLine).toBeUndefined(); // trend overlay is mark-free
  });
});

describe("buildChartOption — rejection", () => {
  test("returns null for unknown chart kinds", () => {
    expect(buildChartOption(sample(), "made-up")).toBeNull();
    expect(buildChartOption(sample(), "")).toBeNull();
  });

  test("returns null when the projection has no rows", () => {
    expect(buildChartOption({ rowCategories: [], columnCategories: [], matrix: [] }, "bar")).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// #1091: high-contrast / colour-blind-safe mode is driven purely by the
// ThemeTokens the builder receives (resolveThemeTokens() sets highContrast +
// swaps the palette when the global pref is on). Here we feed the builder a
// high-contrast token set directly and assert the contrast-specific output.
// ---------------------------------------------------------------------------
describe("buildChartOption — high contrast (#1091)", () => {
  const SAFE_PALETTE = COLORBLIND_SAFE_COLORS;
  const hcTokens: ThemeTokens = {
    ...DEFAULT_THEME_TOKENS,
    chartColors: SAFE_PALETTE,
    highContrast: true,
  };

  test("uses the colour-blind-safe palette as the series colour cycle", () => {
    const opt = buildChartOption(sample(), "bar", opts(), hcTokens) as Record<string, unknown>;
    expect(opt.color).toEqual(SAFE_PALETTE);
  });

  test("thickens line strokes vs the default tokens", () => {
    const def = buildChartOption(sample(), "line", opts()) as Record<string, unknown>;
    const hc = buildChartOption(sample(), "line", opts(), hcTokens) as Record<string, unknown>;
    const defSeries = (def.series as Record<string, unknown>[])[0];
    const hcSeries = (hc.series as Record<string, unknown>[])[0];
    // Default line series carry no explicit lineStyle.width; high contrast adds one.
    expect(defSeries.lineStyle).toBeUndefined();
    expect((hcSeries.lineStyle as { width: number }).width).toBeGreaterThan(2);
  });

  test("gives bar series a same-bg border in high contrast", () => {
    const def = buildChartOption(sample(), "bar", opts()) as Record<string, unknown>;
    const hc = buildChartOption(sample(), "bar", opts(), hcTokens) as Record<string, unknown>;
    const defSeries = (def.series as Record<string, unknown>[])[0];
    const hcSeries = (hc.series as Record<string, unknown>[])[0];
    expect(defSeries.itemStyle).toBeUndefined();
    expect((hcSeries.itemStyle as { borderWidth: number }).borderWidth).toBeGreaterThan(0);
  });

  test("waterfall swaps the hard-coded blue/red for the safe pos/neg colours", () => {
    const wf: ChartProjection = {
      rowCategories: ["Start", "Up", "Down"],
      columnCategories: ["Delta"],
      matrix: [[100], [40], [-30]],
    };
    const def = buildChartOption(wf, "waterfall", opts()) as Record<string, unknown>;
    const hc = buildChartOption(wf, "waterfall", opts(), hcTokens) as Record<string, unknown>;
    const defPos = (def.series as { itemStyle?: { color?: string } }[])[1].itemStyle?.color;
    const defNeg = (def.series as { itemStyle?: { color?: string } }[])[2].itemStyle?.color;
    const hcPos = (hc.series as { itemStyle?: { color?: string } }[])[1].itemStyle?.color;
    const hcNeg = (hc.series as { itemStyle?: { color?: string } }[])[2].itemStyle?.color;
    // Default still uses the legacy blue/red…
    expect(defPos).toBe("#4c9ee6");
    expect(defNeg).toBe("#e66c6c");
    // …high contrast uses the Okabe-Ito-derived pair instead.
    expect(hcPos).toBe(COLORBLIND_WATERFALL.positive);
    expect(hcNeg).toBe(COLORBLIND_WATERFALL.negative);
    expect(hcPos).not.toBe(hcNeg);
  });
});

describe("buildChartOption — x-axis label width per axis", () => {
  // saiku 2026-06-07 user feedback "thats not even fucking angled and
  // still cut off and is literally the only value on the axis" — root
  // cause was catLabel computing width against max(rows, cols), so a
  // single-category x-axis got cramped to the 40px MIN clamp just
  // because the y side had ~50 series.
  //
  // Each chart family asserts that the x-axis label width tracks ITS
  // OWN category count, not the bigger of the two.

  function lopsided(xCount: number, yCount: number): ChartProjection {
    return {
      rowCategories: Array.from({ length: yCount }, (_, i) => `r${i}`),
      columnCategories: Array.from({ length: xCount }, (_, i) => `c${i}`),
      matrix: Array.from({ length: yCount }, () =>
        Array.from({ length: xCount }, (_, j) => 100 + j),
      ),
    };
  }

  test("scatter with 3 x-categories + 50 y-series gets a roomy x-label width", () => {
    // chartWidth=1400, axisCategoryCount=3 → per=466 → clamped MAX 160.
    // Old behaviour: max(3, 50) = 50, per=28, clamped MIN 40 → "CA /…".
    const opt = buildChartOption(lopsided(3, 50), "scatter", opts(), undefined, {
      compact: false,
      chartWidth: 1400,
    }) as Record<string, unknown>;
    const xAxis = opt.xAxis as { axisLabel: { width: number } };
    expect(xAxis.axisLabel.width).toBeGreaterThanOrEqual(120);
  });

  test("bar with 50 x-categories gets a cramped (rotated) x-label width", () => {
    const opt = buildChartOption(lopsided(2, 50), "bar", opts(), undefined, {
      compact: false,
      chartWidth: 1400,
    }) as Record<string, unknown>;
    const xAxis = opt.xAxis as { axisLabel: { width: number; rotate: number } };
    // Rotate kicks in past 8 categories on the x-axis; rotated labels
    // get the 160px width budget (they read diagonally).
    expect(xAxis.axisLabel.rotate).toBe(30);
    expect(xAxis.axisLabel.width).toBe(160);
  });
});

// ---------------------------------------------------------------------------
// #1596: axis TITLES. Charts were rendering with bare axis numbers and no
// measure/dimension name. The value axis defaults to the measure name(s)
// (columnCategories); the category axis defaults to the row dimension name the
// caller resolves (projection.categoryAxisName). An explicit user label
// (xAxisLabel / yAxisLabel) always overrides the derived default. Auto-derived
// titles are roomy-mode only (tiles stay tile-tight); overrides win in both.
// ---------------------------------------------------------------------------
describe("buildChartOption — axis titles (#1596)", () => {
  function withDimension(): ChartProjection {
    return { ...sample(), categoryAxisName: "Year" };
  }
  const yAxisOf = (opt: Record<string, unknown>) =>
    (Array.isArray(opt.yAxis) ? opt.yAxis[0] : opt.yAxis) as {
      name?: string;
      nameLocation?: string;
      nameRotate?: number;
      nameGap?: number;
    };
  const xAxisOf = (opt: Record<string, unknown>) =>
    opt.xAxis as { name?: string; nameLocation?: string; nameGap?: number };

  test("value axis defaults to the measure name(s) (roomy bar)", () => {
    const opt = buildChartOption(sample(), "bar", opts()) as Record<string, unknown>;
    expect(yAxisOf(opt).name).toBe("Store Sales, Unit Sales");
    // Rendered as a centred, vertical axis title.
    expect(yAxisOf(opt).nameLocation).toBe("middle");
    expect(yAxisOf(opt).nameRotate).toBe(90);
  });

  test("category axis defaults to the query's row dimension name (roomy)", () => {
    const opt = buildChartOption(withDimension(), "bar", opts()) as Record<string, unknown>;
    expect(xAxisOf(opt).name).toBe("Year");
    expect(xAxisOf(opt).nameLocation).toBe("middle");
  });

  test("no categoryAxisName + no override → category axis stays untitled", () => {
    const opt = buildChartOption(sample(), "bar", opts()) as Record<string, unknown>;
    expect(xAxisOf(opt).name).toBeUndefined();
  });

  test("explicit yAxisLabel override wins over the derived measure name", () => {
    const opt = buildChartOption(
      withDimension(),
      "bar",
      opts({ yAxisLabel: "Revenue (£)" }),
    ) as Record<string, unknown>;
    expect(yAxisOf(opt).name).toBe("Revenue (£)");
  });

  test("explicit xAxisLabel override wins over the derived dimension name", () => {
    const opt = buildChartOption(
      withDimension(),
      "bar",
      opts({ xAxisLabel: "Fiscal Year" }),
    ) as Record<string, unknown>;
    expect(xAxisOf(opt).name).toBe("Fiscal Year");
  });

  test("grid grows left (value title) and bottom (category title) when titles are drawn", () => {
    const bare = buildChartOption(sample(), "bar", opts({ dualAxis: false })) as Record<string, unknown>;
    // sample() has a measure name (value title) but no dimension name.
    const withDim = buildChartOption(withDimension(), "bar", opts({ dualAxis: false })) as Record<
      string,
      unknown
    >;
    const bareGrid = bare.grid as { left: number; bottom: number };
    const dimGrid = withDim.grid as { left: number; bottom: number };
    // Value title present in both → left already padded beyond the legacy 60.
    expect(bareGrid.left).toBeGreaterThan(60);
    // Adding a category title grows the bottom margin.
    expect(dimGrid.bottom).toBeGreaterThan(bareGrid.bottom);
  });

  test("dual-axis: only the LEFT value axis carries the measure title", () => {
    const disparate: ChartProjection = {
      rowCategories: ["a", "b"],
      columnCategories: ["Big", "Tiny"],
      matrix: [
        [100000, 1],
        [120000, 2],
      ],
    };
    const opt = buildChartOption(disparate, "bar", opts({ dualAxis: true })) as Record<string, unknown>;
    const yAxis = opt.yAxis as Array<{ name?: string }>;
    expect(yAxis[0].name).toBe("Big, Tiny"); // left value title
    expect(yAxis[1].name).toBeUndefined(); // right axis stays untitled
  });

  test("waterfall gets the value + category titles too", () => {
    const wf: ChartProjection = {
      rowCategories: ["Start", "Up", "Down"],
      columnCategories: ["Delta"],
      matrix: [[100], [40], [-30]],
      categoryAxisName: "Stage",
    };
    const opt = buildChartOption(wf, "waterfall", opts()) as Record<string, unknown>;
    expect((opt.yAxis as { name?: string }).name).toBe("Delta");
    expect((opt.xAxis as { name?: string }).name).toBe("Stage");
  });

  test("compact tiles do NOT auto-derive titles, but honour explicit overrides", () => {
    const auto = buildChartOption(withDimension(), "bar", opts(), undefined, {
      compact: true,
    }) as Record<string, unknown>;
    // Tile-tight: no derived measure / dimension title.
    expect(yAxisOf(auto).name).toBeUndefined();
    expect(xAxisOf(auto).name).toBeUndefined();

    const overridden = buildChartOption(
      withDimension(),
      "bar",
      opts({ xAxisLabel: "Yr", yAxisLabel: "Sales" }),
      undefined,
      { compact: true },
    ) as Record<string, unknown>;
    expect(yAxisOf(overridden).name).toBe("Sales");
    expect(xAxisOf(overridden).name).toBe("Yr");
  });

  test("heatmap/scatter keep override-only titles (no misleading auto measure/dimension name)", () => {
    // Heatmap: both axes are categorical (value is the colour) — a measure/
    // dimension title would be wrong, so only explicit overrides apply.
    const heat = buildChartOption(withDimension(), "heatmap", opts()) as Record<string, unknown>;
    expect((heat.xAxis as { name?: string }).name).toBeUndefined();
    expect((heat.yAxis as { name?: string }).name).toBeUndefined();
    // Scatter's x-axis is the measures, not the row dimension → no auto title.
    const scat = buildChartOption(withDimension(), "scatter", opts()) as Record<string, unknown>;
    expect((scat.xAxis as { name?: string }).name).toBeUndefined();
  });
});

describe("brushOption / BRUSHABLE_CHART_TYPES — cross-filter (#1085)", () => {
  test("brushable cartesian kinds return a lineX single-selection brush", () => {
    for (const kind of ["bar", "stackedBar", "line", "stackedLine", "area", "stackedArea"]) {
      const b = brushOption(kind);
      expect(b, kind).not.toBeNull();
      expect(b!.brushType).toBe("lineX");
      expect(b!.brushMode).toBe("single");
      expect(b!.xAxisIndex).toBe(0);
      expect(b!.removeOnClick).toBe(true); // a plain click clears the selection
    }
  });

  test("non-brushable kinds (no single category x-axis) return null", () => {
    for (const kind of ["pie", "donut", "treemap", "sunburst", "heatmap", "radar", "scatter", "bubble", "map", "made-up"]) {
      expect(brushOption(kind), kind).toBeNull();
    }
  });

  test("BRUSHABLE_CHART_TYPES membership matches brushOption non-null", () => {
    expect(BRUSHABLE_CHART_TYPES.has("bar")).toBe(true);
    expect(BRUSHABLE_CHART_TYPES.has("pie")).toBe(false);
    expect(BRUSHABLE_CHART_TYPES.has("map")).toBe(false);
  });
});

describe("buildChartOption — conditional formatting (#1084)", () => {
  // sample(): Store Sales = [565238.13, 612482.65]; Unit Sales = [266773, 282417].
  const cf = [
    {
      measureIndex: 0,
      rules: [{ op: "gt" as const, value: 600000, color: "#00ff00" }],
      fallbackColor: "#888888",
    },
  ];

  test("recolours matching bars on the targeted measure; fallback on the rest", () => {
    const opt = buildChartOption(sample(), "bar", opts({ dualAxis: false, conditionalFormat: cf })) as Record<
      string,
      unknown
    >;
    const series = opt.series as Array<{ data: unknown[] }>;
    // Store Sales (measure 0): 565238 < 600000 → fallback grey; 612482 > 600000 → green.
    expect(series[0].data[0]).toEqual({ value: 565238.13, itemStyle: { color: "#888888" } });
    expect(series[0].data[1]).toEqual({ value: 612482.65, itemStyle: { color: "#00ff00" } });
    // Unit Sales (measure 1): no band → plain numbers, untouched.
    expect(series[1].data).toEqual([266773, 282417]);
  });

  test("no conditionalFormat → plain numeric data (unchanged)", () => {
    const opt = buildChartOption(sample(), "bar", opts({ dualAxis: false })) as Record<string, unknown>;
    const series = opt.series as Array<{ data: unknown[] }>;
    expect(series[0].data).toEqual([565238.13, 612482.65]);
  });

  test("line charts ignore conditional formatting (bar/stackedBar only in Phase 1)", () => {
    const opt = buildChartOption(sample(), "line", opts({ dualAxis: false, conditionalFormat: cf })) as Record<
      string,
      unknown
    >;
    const series = opt.series as Array<{ data: unknown[] }>;
    // line keeps a continuous stroke → raw numbers, no per-point itemStyle.
    expect(series[0].data).toEqual([565238.13, 612482.65]);
  });
});

describe("buildChartOption — combo charts / per-series type (#1089)", () => {
  // sample(): series 0 = Store Sales, series 1 = Unit Sales.
  test("per-series type override mixes bar + line on one bar chart", () => {
    const opt = buildChartOption(
      sample(),
      "bar",
      opts({ dualAxis: false, seriesType: { "Unit Sales": "line" } }),
    ) as Record<string, unknown>;
    const series = opt.series as Array<{ name: string; type: string }>;
    expect(series.find((s) => s.name === "Store Sales")?.type).toBe("bar"); // default
    expect(series.find((s) => s.name === "Unit Sales")?.type).toBe("line"); // override
  });

  test("'area' override → line type + areaStyle; 'scatter' → scatter type", () => {
    const opt = buildChartOption(
      sample(),
      "bar",
      opts({ dualAxis: false, seriesType: { "Store Sales": "area", "Unit Sales": "scatter" } }),
    ) as Record<string, unknown>;
    const series = opt.series as Array<{ name: string; type: string; areaStyle?: object }>;
    const ss = series.find((s) => s.name === "Store Sales")!;
    expect(ss.type).toBe("line");
    expect(ss.areaStyle).toBeDefined();
    expect(series.find((s) => s.name === "Unit Sales")?.type).toBe("scatter");
  });

  test("no seriesType → all series keep the chart-level type (unchanged)", () => {
    const opt = buildChartOption(sample(), "bar", opts({ dualAxis: false })) as Record<string, unknown>;
    const series = opt.series as Array<{ type: string }>;
    expect(series.every((s) => s.type === "bar")).toBe(true);
  });

  test("combo composes with dualAxis (each axis keeps its own series + type)", () => {
    const opt = buildChartOption(
      sample(),
      "bar",
      opts({ dualAxis: false, seriesAxis: { "Unit Sales": "right" }, seriesType: { "Unit Sales": "line" } }),
    ) as Record<string, unknown>;
    const series = opt.series as Array<{ name: string; type: string; yAxisIndex: number }>;
    const us = series.find((s) => s.name === "Unit Sales")!;
    expect(us.type).toBe("line");
    expect(us.yAxisIndex).toBe(1); // right axis
  });
});
