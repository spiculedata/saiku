/*
 * Tests for declarative value-axis formatting on the `echarts-option` tile.
 *
 * The gap this closes: the option validator rejects functions, and ECharts
 * numeric axis formatting REQUIRES one — so a hand-authored chart had no way to
 * turn "60,000" into "$60k". The pattern is declarative; the formatter is
 * compiled at render time.
 */

import { describe, expect, it } from "vitest";
import { applyValueAxisFormat, axisFormatterFor } from "./valueAxisFormat";

describe("axisFormatterFor", () => {
  it("compiles a compact-currency pattern", () => {
    const f = axisFormatterFor("$c0");
    expect(f(149_000)).toBe("$149K");
    expect(f(0)).toBe("$0");
  });

  it("compiles percent and bare-digit patterns", () => {
    expect(axisFormatterFor("1%")(0.156)).toBe("15.6%");
    expect(axisFormatterFor("2")(3.14159)).toBe("3.14");
  });

  it("renders non-finite input blank rather than NaN", () => {
    const f = axisFormatterFor("$c0");
    expect(f(Number.NaN)).toBe("");
    expect(f(Number.POSITIVE_INFINITY)).toBe("");
    expect(f("x" as unknown as number)).toBe("");
  });
});

describe("applyValueAxisFormat", () => {
  it("formats a value axis", () => {
    const option = { yAxis: { type: "value" } } as Record<string, unknown>;
    applyValueAxisFormat(option, "$c0");
    const fmt = (option.yAxis as Record<string, Record<string, unknown>>).axisLabel
      .formatter as (v: number) => string;
    expect(fmt(149_000)).toBe("$149K");
  });

  /* A category axis carries member captions; running them through a number
   * formatter would render every tick blank. */
  it("leaves a category axis alone", () => {
    const option = { xAxis: { type: "category" }, yAxis: { type: "value" } } as Record<string, unknown>;
    applyValueAxisFormat(option, "$c0");
    expect((option.xAxis as Record<string, unknown>).axisLabel).toBeUndefined();
  });

  it("leaves an untyped axis alone (ECharts defaults it to category)", () => {
    const option = { yAxis: { name: "Sales" } } as Record<string, unknown>;
    applyValueAxisFormat(option, "$c0");
    expect((option.yAxis as Record<string, unknown>).axisLabel).toBeUndefined();
  });

  it("formats every entry of an axis array", () => {
    const option = { yAxis: [{ type: "value" }, { type: "value" }] } as Record<string, unknown>;
    applyValueAxisFormat(option, "$c0");
    for (const axis of option.yAxis as Array<Record<string, Record<string, unknown>>>) {
      expect(typeof axis.axisLabel.formatter).toBe("function");
    }
  });

  it("formats a value axis declared on x (horizontal bars)", () => {
    const option = { xAxis: { type: "value" }, yAxis: { type: "category" } } as Record<string, unknown>;
    applyValueAxisFormat(option, "$c0");
    expect(typeof (option.xAxis as Record<string, Record<string, unknown>>).axisLabel.formatter).toBe(
      "function",
    );
  });

  /* A string template like "W{value}" is a legitimate declarative formatter the
   * validator already allows — never clobber one the author wrote. */
  it("never overwrites a formatter the author supplied", () => {
    const option = {
      yAxis: { type: "value", axisLabel: { formatter: "{value} u" } },
    } as Record<string, unknown>;
    applyValueAxisFormat(option, "$c0");
    expect((option.yAxis as Record<string, Record<string, unknown>>).axisLabel.formatter).toBe(
      "{value} u",
    );
  });

  it("preserves other axisLabel properties", () => {
    const option = {
      yAxis: { type: "value", axisLabel: { color: "#abc", margin: 12 } },
    } as Record<string, unknown>;
    applyValueAxisFormat(option, "$c0");
    const label = (option.yAxis as Record<string, Record<string, unknown>>).axisLabel;
    expect(label.color).toBe("#abc");
    expect(label.margin).toBe(12);
    expect(typeof label.formatter).toBe("function");
  });

  it("is a no-op for a blank or absent pattern", () => {
    const option = { yAxis: { type: "value" } } as Record<string, unknown>;
    applyValueAxisFormat(option, "");
    applyValueAxisFormat(option, undefined);
    applyValueAxisFormat(option, "   ");
    expect((option.yAxis as Record<string, unknown>).axisLabel).toBeUndefined();
  });

  it("tolerates an option with no axes at all (pie / graph)", () => {
    const option = { series: [{ type: "pie" }] } as Record<string, unknown>;
    expect(() => applyValueAxisFormat(option, "$c0")).not.toThrow();
  });
});
