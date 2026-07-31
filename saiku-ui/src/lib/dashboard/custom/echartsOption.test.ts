import { describe, expect, it, test } from "vitest";
import fc from "fast-check";
import {
  applyDataToEchartsOption,
  validateEchartsOption,
  type EChartsDataProjection,
} from "./echartsOption";

describe("validateEchartsOption — accept", () => {
  it("accepts a plain bar option", () => {
    const opt = {
      title: { text: "Sales by month" },
      tooltip: { trigger: "axis" },
      legend: {},
      xAxis: { type: "category" },
      yAxis: { type: "value" },
      color: ["#123456", "#abcdef"],
      series: [{ type: "bar", name: "Units", stack: "total" }],
    };
    const r = validateEchartsOption(opt);
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.value).toEqual(opt);
  });

  it("accepts a plain line option with a string formatter template", () => {
    // A STRING formatter (ECharts template syntax) is safe — only FUNCTION
    // formatters are the exec vector.
    const opt = {
      xAxis: { type: "category" },
      yAxis: { type: "value" },
      tooltip: { trigger: "axis", formatter: "{b}: {c}" },
      series: [{ type: "line", name: "Revenue", smooth: true }],
    };
    expect(validateEchartsOption(opt).ok).toBe(true);
  });

  it("returns a fresh copy, not an alias of the input", () => {
    const opt = { series: [{ type: "bar" }] };
    const r = validateEchartsOption(opt);
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.value).not.toBe(opt);
  });

  it("accepts an inline data:image background", () => {
    const opt = {
      backgroundColor: {
        image: "data:image/png;base64,iVBORw0KGgoAAAANS",
      } as unknown,
      series: [{ type: "bar" }],
    };
    // backgroundColor object with a data:image is safe.
    expect(validateEchartsOption(opt).ok).toBe(true);
  });
});

describe("validateEchartsOption — reject (fail closed)", () => {
  it("rejects a non-object input", () => {
    expect(validateEchartsOption(null).ok).toBe(false);
    expect(validateEchartsOption("nope").ok).toBe(false);
    expect(validateEchartsOption([{ type: "bar" }]).ok).toBe(false);
    expect(validateEchartsOption(undefined).ok).toBe(false);
  });

  it("rejects an unknown top-level key", () => {
    const r = validateEchartsOption({ series: [], graphic: { type: "text" } });
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.error).toMatch(/graphic/);
  });

  it("rejects a function value at the top level", () => {
    const r = validateEchartsOption({ title: () => "evil", series: [] });
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.error).toMatch(/function/i);
  });

  it("rejects a function nested in tooltip.formatter", () => {
    const r = validateEchartsOption({
      tooltip: { trigger: "axis", formatter: () => "<script>" },
      series: [{ type: "bar" }],
    });
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.error).toMatch(/function/i);
  });

  it("rejects a remote url in a nested value", () => {
    const r = validateEchartsOption({
      series: [{ type: "bar", itemStyle: { color: "http://evil.example/x.png" } }],
    });
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.error).toMatch(/url/i);
  });

  it("rejects a remote url() in backgroundColor", () => {
    const r = validateEchartsOption({
      backgroundColor: "url(https://evil.example/bg.png)",
      series: [{ type: "bar" }],
    });
    expect(r.ok).toBe(false);
  });

  it("rejects a protocol-relative resource reference", () => {
    const r = validateEchartsOption({
      series: [{ type: "pie", symbol: "//evil.example/x" }],
    });
    expect(r.ok).toBe(false);
  });

  it("rejects a non-image data: URI", () => {
    const r = validateEchartsOption({
      series: [{ type: "bar", symbol: "data:text/html;base64,PHNjcmlwdD4=" }],
    });
    expect(r.ok).toBe(false);
  });

  it("rejects an unknown series field", () => {
    const r = validateEchartsOption({ series: [{ type: "bar", danger: 1 }] });
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.error).toMatch(/danger/);
  });

  it("rejects a prototype-pollution key", () => {
    const r = validateEchartsOption(JSON.parse('{"series":[],"__proto__":{"x":1}}'));
    expect(r.ok).toBe(false);
  });
});

/* ------------------------------------------------------------------ *
 * Property tests — for arbitrary objects that embed a function OR a   *
 * remote-url string at a random depth, the validator NEVER accepts.   *
 * ------------------------------------------------------------------ */

// A first segment under a top-level key WITHOUT a per-series field allowlist, so
// the deep scanner (not the allowlist) is what must catch the hostile leaf.
const scanContainerKey = fc.constantFrom("tooltip", "title", "grid", "legend", "textStyle", "aria");
const safeKey = fc
  .string({ minLength: 1, maxLength: 8 })
  .filter((s) => !["__proto__", "constructor", "prototype"].includes(s) && !s.includes("."));

/** Nest a leaf value under a path of object keys: ["a","b"] + leaf → {a:{b:leaf}}. */
function nest(path: string[], leaf: unknown): Record<string, unknown> {
  return path.reduceRight<Record<string, unknown>>(
    (acc, key) => ({ [key]: acc }),
    leaf as Record<string, unknown>,
  );
}

const pathArb = fc
  .tuple(scanContainerKey, fc.array(safeKey, { maxLength: 4 }))
  .map(([first, rest]) => [first, ...rest]);

describe("validateEchartsOption — properties", () => {
  test("NEVER accepts an option with a function at any depth", () => {
    fc.assert(
      fc.property(pathArb, fc.func(fc.integer()), (path, fn) => {
        const opt = nest(path, fn);
        return validateEchartsOption(opt).ok === false;
      }),
      { numRuns: 500 },
    );
  });

  test("NEVER accepts an option with a remote-url string at any depth", () => {
    const remoteUrl = fc.oneof(
      fc.webUrl().filter((u) => /^https?:\/\//i.test(u)),
      fc.constant("http://evil.example/x"),
      fc.constant("https://evil.example/y.png"),
      fc.string().map((s) => `https://evil.example/${s}`),
    );
    fc.assert(
      fc.property(pathArb, remoteUrl, (path, url) => {
        const opt = nest(path, url);
        return validateEchartsOption(opt).ok === false;
      }),
      { numRuns: 500 },
    );
  });

  test("NEVER accepts a protocol-relative url as a whole string value at any depth", () => {
    fc.assert(
      fc.property(pathArb, fc.string(), (path, tail) => {
        const opt = nest(path, `//evil.example/${tail}`);
        return validateEchartsOption(opt).ok === false;
      }),
      { numRuns: 300 },
    );
  });
});

/* ------------------------------------------------------------------ *
 * applyDataToEchartsOption — data merge                               *
 * ------------------------------------------------------------------ */

const projection: EChartsDataProjection = {
  categories: ["Jan", "Feb", "Mar"],
  series: [
    { name: "Units", data: [10, 20, 30] },
    { name: "Revenue", data: [1, 2, 3] },
  ],
};

describe("applyDataToEchartsOption", () => {
  it("fills xAxis categories and series data on a bar option", () => {
    const merged = applyDataToEchartsOption({ series: [{ type: "bar", name: "Units" }] }, projection);
    expect((merged.xAxis as { data: string[] }).data).toEqual(["Jan", "Feb", "Mar"]);
    const series = merged.series as Array<{ type: string; data: unknown[] }>;
    expect(series[0].data).toEqual([10, 20, 30]);
  });

  it("synthesises one bar series per measure when none declared", () => {
    const merged = applyDataToEchartsOption({}, projection);
    const series = merged.series as Array<{ type: string; name: string; data: unknown[] }>;
    expect(series).toHaveLength(2);
    expect(series[0]).toMatchObject({ type: "bar", name: "Units", data: [10, 20, 30] });
    expect(series[1]).toMatchObject({ type: "bar", name: "Revenue", data: [1, 2, 3] });
  });

  it("builds {name,value} pairs for a pie series and skips the axes", () => {
    const merged = applyDataToEchartsOption({ series: [{ type: "pie" }] }, projection);
    expect(merged.xAxis).toBeUndefined();
    const series = merged.series as Array<{ type: string; data: Array<{ name: string; value: number }> }>;
    expect(series[0].data).toEqual([
      { name: "Jan", value: 10 },
      { name: "Feb", value: 20 },
      { name: "Mar", value: 30 },
    ]);
  });

  it("does not mutate the input option", () => {
    const input = { series: [{ type: "bar" }] };
    applyDataToEchartsOption(input, projection);
    expect(input.series[0]).toEqual({ type: "bar" });
  });
});
