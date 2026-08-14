/*
 * @vitest-environment jsdom
 *
 * Tests for the App Builder -> ECharts theme bridge.
 *
 * The regression these guard is concrete: FoodMart Ops' chart hard-coded
 * #2e5e43 / Georgia, so switching the app to the Dark Ops preset left a
 * dark-green title on a charcoal card. Charts must read the app's tokens, and a
 * hand-authored option must inherit them wherever it doesn't override them.
 */

import { describe, expect, it } from "vitest";
import {
  APP_CHART_VARS,
  PALETTE_SIZE,
  appChartPalette,
  appSequentialRamp,
  appChartTokens,
  appEchartsBase,
  mixHex,
  parseHex,
  readAppBrandTokens,
  withAppEchartsDefaults,
  type AppBrandTokens,
} from "./appChartTheme";
import { CHART_FALLBACK_COLORS } from "$lib/views/chartTheme";

const EDITORIAL: AppBrandTokens = {
  fg: "#1f3529",
  muted: "#8a7f68",
  surface: "#ffffff",
  ground: "#f2eee4",
  cardBorder: "#ece7db",
  accent: "#2e5e43",
  accent2: "#c85a3a",
  accentStrong: "#2e5e43",
  positive: "#2e5e43",
  danger: "#c85a3a",
  fontBody: "system-ui, sans-serif",
  fontDisplay: "Georgia, serif",
};

describe("parseHex / mixHex", () => {
  it("parses long and short hex", () => {
    expect(parseHex("#ffffff")).toEqual([255, 255, 255]);
    expect(parseHex("#000")).toEqual([0, 0, 0]);
    expect(parseHex("#2e5e43")).toEqual([46, 94, 67]);
  });

  it("rejects anything that isn't plain hex", () => {
    expect(parseHex("rgb(1,2,3)")).toBeNull();
    expect(parseHex("oklch(60% .1 20)")).toBeNull();
    expect(parseHex("")).toBeNull();
  });

  it("blends toward the target and clamps t", () => {
    expect(mixHex("#000000", "#ffffff", 0.5)).toBe("#808080");
    expect(mixHex("#000000", "#ffffff", 0)).toBe("#000000");
    expect(mixHex("#000000", "#ffffff", 5)).toBe("#ffffff");
  });

  it("returns the first colour untouched when either side is unparseable", () => {
    expect(mixHex("#123456", "rgb(0,0,0)", 0.5)).toBe("#123456");
  });
});

describe("appChartPalette", () => {
  it("leads with the brand colours, deduped", () => {
    const p = appChartPalette(EDITORIAL);
    // accent and positive/accentStrong are the same green in this preset.
    expect(p[0]).toBe("#2e5e43");
    expect(p[1]).toBe("#c85a3a");
    expect(new Set([p[0], p[1]]).size).toBe(2);
  });

  it("always fills the full palette slot count", () => {
    expect(appChartPalette(EDITORIAL)).toHaveLength(PALETTE_SIZE);
  });

  it("produces distinct entries so series stay tellable apart", () => {
    const p = appChartPalette(EDITORIAL);
    expect(new Set(p).size).toBe(PALETTE_SIZE);
  });

  it("extends with tints of the brand hues, not the stock ramp", () => {
    const p = appChartPalette(EDITORIAL);
    for (const c of p) expect(CHART_FALLBACK_COLORS).not.toContain(c);
  });

  it("falls back to the stock ramp when no brand colour parses", () => {
    const broken: AppBrandTokens = {
      ...EDITORIAL,
      accent: "",
      accent2: "",
      accentStrong: "",
      positive: "",
      danger: "",
    };
    expect(appChartPalette(broken)).toEqual(CHART_FALLBACK_COLORS);
  });
});

describe("appChartTokens", () => {
  it("maps brand tokens onto the chart token contract", () => {
    const t = appChartTokens(EDITORIAL);
    expect(t.fg).toBe(EDITORIAL.fg);
    expect(t.fgMuted).toBe(EDITORIAL.muted);
    expect(t.border).toBe(EDITORIAL.cardBorder);
    expect(t.accent).toBe(EDITORIAL.accent);
  });

  it("uses the card surface as the chart background, not the page ground", () => {
    const t = appChartTokens(EDITORIAL);
    expect(t.bg).toBe(EDITORIAL.surface);
    expect(t.bgMuted).toBe(EDITORIAL.ground);
  });
});

describe("readAppBrandTokens", () => {
  function mount(vars: Partial<Record<string, string>>): HTMLElement {
    const root = document.createElement("div");
    root.setAttribute("data-saiku-app", "preview");
    for (const [k, v] of Object.entries(vars)) root.style.setProperty(k, v as string);
    const tile = document.createElement("div");
    root.appendChild(tile);
    document.body.appendChild(root);
    return tile;
  }

  it("reads the tokens off the nearest app root", () => {
    const tile = mount({
      [APP_CHART_VARS.accent]: "#2e5e43",
      [APP_CHART_VARS.fg]: "#1f3529",
      [APP_CHART_VARS.muted]: "#8a7f68",
    });
    try {
      const b = readAppBrandTokens(tile);
      expect(b?.accent).toBe("#2e5e43");
      expect(b?.fg).toBe("#1f3529");
      expect(b?.muted).toBe("#8a7f68");
    } finally {
      tile.closest("[data-saiku-app]")?.remove();
    }
  });

  it("returns null outside an app so the global theme is used", () => {
    const loose = document.createElement("div");
    document.body.appendChild(loose);
    try {
      expect(readAppBrandTokens(loose)).toBeNull();
    } finally {
      loose.remove();
    }
  });

  it("returns null for a null element", () => {
    expect(readAppBrandTokens(null)).toBeNull();
  });

  it("returns null when the root declares no accent (not actually themed)", () => {
    const tile = mount({});
    try {
      expect(readAppBrandTokens(tile)).toBeNull();
    } finally {
      tile.closest("[data-saiku-app]")?.remove();
    }
  });
});

describe("withAppEchartsDefaults", () => {
  const base = appEchartsBase(appChartTokens(EDITORIAL), {
    body: EDITORIAL.fontBody,
    display: EDITORIAL.fontDisplay,
  });

  it("fills colours an author option never mentions", () => {
    const out = withAppEchartsDefaults({ series: [{ type: "line" }] }, base);
    expect((out.title as Record<string, Record<string, unknown>>).textStyle.color).toBe(
      EDITORIAL.fg,
    );
    expect(out.color).toEqual(appChartPalette(EDITORIAL));
  });

  it("never overrides a value the author set", () => {
    const out = withAppEchartsDefaults(
      { title: { text: "Sales", textStyle: { color: "#ff0000" } } },
      base,
    );
    const title = out.title as Record<string, unknown>;
    expect(title.text).toBe("Sales");
    expect((title.textStyle as Record<string, unknown>).color).toBe("#ff0000");
    // …while the sibling default the author stayed silent on still lands.
    expect((title as Record<string, Record<string, unknown>>).subtextStyle.color).toBe(
      EDITORIAL.muted,
    );
  });

  it("applies axis defaults to a single axis object", () => {
    const out = withAppEchartsDefaults({ xAxis: { type: "category" } }, base);
    const x = out.xAxis as Record<string, Record<string, unknown>>;
    expect(x.type).toBe("category");
    expect(x.axisLabel.color).toBe(EDITORIAL.muted);
  });

  it("applies axis defaults to every entry of an axis array", () => {
    const out = withAppEchartsDefaults({ yAxis: [{ type: "value" }, { type: "value" }] }, base);
    const y = out.yAxis as Array<Record<string, Record<string, unknown>>>;
    expect(y).toHaveLength(2);
    for (const axis of y) expect(axis.axisLabel.color).toBe(EDITORIAL.muted);
  });

  /* Mentioning a component in ECharts switches it ON. Seeding a legend the
   * author never asked for put a stray "Store Sales" label over the FoodMart
   * chart's x-axis; seeding axes onto a pie would draw axes through it. These
   * may only ever be decorated, never introduced. */
  it.each(["legend", "tooltip", "xAxis", "yAxis"])(
    "never introduces %s when the author didn't declare it",
    (component) => {
      const out = withAppEchartsDefaults({ series: [{ type: "pie" }] }, base);
      expect(out).not.toHaveProperty(component);
    },
  );

  it("still decorates those components once the author declares them", () => {
    const out = withAppEchartsDefaults({ legend: { top: 8 }, xAxis: { type: "category" } }, base);
    const legend = out.legend as Record<string, Record<string, unknown>>;
    expect(legend.top).toBe(8);
    expect(legend.textStyle.color).toBe(EDITORIAL.muted);
    expect((out.xAxis as Record<string, Record<string, unknown>>).axisLabel.color).toBe(
      EDITORIAL.muted,
    );
    expect(out).not.toHaveProperty("yAxis");
  });

  it("always applies the global palette and text style", () => {
    const out = withAppEchartsDefaults({ series: [{ type: "pie" }] }, base);
    expect(out.color).toEqual(appChartPalette(EDITORIAL));
    expect((out.textStyle as Record<string, unknown>).color).toBe(EDITORIAL.fg);
  });

  it("does not mutate its inputs", () => {
    const option = { title: { text: "x" } };
    const snapshot = JSON.stringify(option);
    const baseSnapshot = JSON.stringify(base);
    withAppEchartsDefaults(option, base);
    expect(JSON.stringify(option)).toBe(snapshot);
    expect(JSON.stringify(base)).toBe(baseSnapshot);
  });

  it("re-themes the same option when the brand changes", () => {
    const dark = appEchartsBase(
      appChartTokens({ ...EDITORIAL, fg: "#e6edf3", muted: "#8b97a6", accent: "#37c2c9" }),
      { body: "sans-serif", display: "sans-serif" },
    );
    const authored = { series: [{ type: "line" }] };
    const light = withAppEchartsDefaults(authored, base);
    const night = withAppEchartsDefaults(authored, dark);
    const colourOf = (o: Record<string, unknown>) =>
      (o.title as Record<string, Record<string, unknown>>).textStyle.color;
    expect(colourOf(light)).toBe("#1f3529");
    expect(colourOf(night)).toBe("#e6edf3");
  });
});

/* ====================================================================
 * saiku#1799 — the magnitude ramp and the sign colours a heatmap / waterfall
 * needs, derived from the brand rather than shipped as literals.
 * ==================================================================== */
describe("appSequentialRamp", () => {
  const brand = (over: Partial<AppBrandTokens> = {}): AppBrandTokens => ({
    fg: "#17241d",
    muted: "#93876c",
    surface: "#ffffff",
    ground: "#faf7f2",
    cardBorder: "#e6e0d4",
    accent: "#b4542e",
    accent2: "#2f6f5c",
    accentStrong: "#8c3f22",
    positive: "#2e7d55",
    danger: "#c0492b",
    fontBody: "sans-serif",
    fontDisplay: "serif",
    ...over,
  });

  it("runs from near-surface to the accent at full strength", () => {
    const ramp = appSequentialRamp(brand());
    expect(ramp).not.toBeNull();
    expect(ramp).toHaveLength(3);
    // The high end IS the accent; the low end is close to the card it sits on.
    expect(ramp?.[ramp.length - 1]).toBe("#b4542e");
    expect(ramp?.[0]).not.toBe(ramp?.[ramp.length - 1]);
  });

  it("blends toward the card, so a dark app's low end goes dark not white", () => {
    const light = appSequentialRamp(brand())?.[0] ?? "";
    const dark = appSequentialRamp(brand({ surface: "#101816" }))?.[0] ?? "";
    const lum = (hex: string) => (parseHex(hex) ?? [0, 0, 0]).reduce((a, b) => a + b, 0);
    expect(lum(light)).toBeGreaterThan(lum(dark));
  });

  it("declines to invent a ramp when the accent isn't a parseable hex", () => {
    expect(appSequentialRamp(brand({ accent: "oklch(0.6 0.2 30)" }))).toBeNull();
  });

  it("appChartTokens carries the ramp and the sign colours through", () => {
    const tk = appChartTokens(brand());
    expect(tk.positive).toBe("#2e7d55");
    expect(tk.danger).toBe("#c0492b");
    expect(tk.sequentialRamp?.[2]).toBe("#b4542e");
  });

  it("leaves them unset when the brand names nothing parseable, so the builder's fallbacks stand", () => {
    const tk = appChartTokens(brand({ positive: "", danger: "", accent: "#b4542e" }));
    expect(tk.positive).toBeUndefined();
    expect(tk.danger).toBeUndefined();
  });
});
