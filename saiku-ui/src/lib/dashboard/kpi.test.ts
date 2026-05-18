/*
 * Unit tests for the KPI tile's pure helpers (#917).
 *
 * Locale-sensitive output (Intl.NumberFormat) is asserted with regex
 * rather than string equality so the suite is robust across runtime
 * locales (CI may differ from a developer laptop).
 */

import { describe, expect, it } from "vitest";

import { formatKpi, kpiDelta, kpiThresholdToken, lastAndPriorValues } from "./kpi";

describe("formatKpi", () => {
  it("renders null / undefined / NaN as an em-dash", () => {
    expect(formatKpi(null, "number")).toBe("—");
    expect(formatKpi(undefined, "number")).toBe("—");
    expect(formatKpi(Number.NaN, "number")).toBe("—");
  });

  it("formats whole numbers without trailing decimals by default", () => {
    expect(formatKpi(1234567, "number")).toMatch(/1[ ,.\s]?234[ ,.\s]?567/);
  });

  it("formats currency with a currency symbol and no fractional digits", () => {
    const out = formatKpi(1234, "currency");
    expect(out).toMatch(/1[ ,.\s]?234/);
    expect(out).toMatch(/[$€£¥]/); // some locale-appropriate currency mark
  });

  it("formats percent values with a % suffix", () => {
    expect(formatKpi(0.156, "percent")).toMatch(/15.6\s?%/);
  });

  it("custom % pattern routes through percent formatter with the right digits", () => {
    const out = formatKpi(0.1234, "custom", "2%");
    expect(out).toMatch(/12\.34\s?%/);
  });

  it("custom $N pattern routes through USD currency", () => {
    const out = formatKpi(99.5, "custom", "$2");
    expect(out).toMatch(/[$]\s?99\.50/);
  });

  it("custom bare-digit pattern fixes fractional digit count", () => {
    const out = formatKpi(3.14159, "custom", "3");
    expect(out).toMatch(/3\.142/);
  });

  it("falls back to plain number when format is undefined", () => {
    expect(formatKpi(42, undefined)).toMatch(/^42/);
  });
});

describe("kpiThresholdToken", () => {
  it("returns undefined when value is null", () => {
    expect(kpiThresholdToken(null, { red: 0, yellow: 50, green: 100 })).toBeUndefined();
  });

  it("returns undefined when no thresholds configured", () => {
    expect(kpiThresholdToken(42, undefined)).toBeUndefined();
    expect(kpiThresholdToken(42, {})).toBeUndefined();
  });

  it("higher-is-better: maps to green when value clears the green cutoff", () => {
    expect(kpiThresholdToken(120, { red: 0, yellow: 50, green: 100 })).toBe("--kpi-green");
    expect(kpiThresholdToken(100, { red: 0, yellow: 50, green: 100 })).toBe("--kpi-green");
  });

  it("higher-is-better: maps to yellow between yellow and green cutoffs", () => {
    expect(kpiThresholdToken(75, { red: 0, yellow: 50, green: 100 })).toBe("--kpi-yellow");
    expect(kpiThresholdToken(50, { red: 0, yellow: 50, green: 100 })).toBe("--kpi-yellow");
  });

  it("higher-is-better: maps to red below yellow cutoff", () => {
    expect(kpiThresholdToken(25, { red: 0, yellow: 50, green: 100 })).toBe("--kpi-red");
  });

  it("higher-is-better: out-of-bounds value falls into red (open-ended)", () => {
    expect(kpiThresholdToken(-5, { red: 0, yellow: 50, green: 100 })).toBe("--kpi-red");
  });

  it("returns undefined when value misses green+yellow and red isn't configured", () => {
    expect(kpiThresholdToken(25, { yellow: 50, green: 100 })).toBeUndefined();
  });

  it("lower-is-better: small values are green", () => {
    expect(
      kpiThresholdToken(5, { red: 100, yellow: 50, green: 10 }, "lower-is-better"),
    ).toBe("--kpi-green");
  });

  it("lower-is-better: large values are red", () => {
    expect(
      kpiThresholdToken(150, { red: 100, yellow: 50, green: 10 }, "lower-is-better"),
    ).toBe("--kpi-red");
  });
});

describe("kpiDelta", () => {
  it("returns null + flat when either input is null", () => {
    expect(kpiDelta(null, 100)).toEqual({ ratio: null, tone: "flat" });
    expect(kpiDelta(100, null)).toEqual({ ratio: null, tone: "flat" });
  });

  it("returns null + flat when baseline is zero (no division)", () => {
    expect(kpiDelta(100, 0)).toEqual({ ratio: null, tone: "flat" });
  });

  it("positive growth with higher-is-better is positive tone", () => {
    expect(kpiDelta(115, 100)).toEqual({ ratio: 0.15, tone: "positive" });
  });

  it("negative growth with higher-is-better is negative tone", () => {
    expect(kpiDelta(85, 100)).toEqual({ ratio: -0.15, tone: "negative" });
  });

  it("flat (no change) is flat regardless of direction", () => {
    expect(kpiDelta(100, 100)).toEqual({ ratio: 0, tone: "flat" });
    expect(kpiDelta(100, 100, "lower-is-better")).toEqual({ ratio: 0, tone: "flat" });
  });

  it("lower-is-better inverts the tone polarity", () => {
    expect(kpiDelta(85, 100, "lower-is-better")).toEqual({ ratio: -0.15, tone: "positive" });
    expect(kpiDelta(115, 100, "lower-is-better")).toEqual({ ratio: 0.15, tone: "negative" });
  });
});

describe("lastAndPriorValues", () => {
  it("returns {null, null} on empty input", () => {
    expect(lastAndPriorValues([])).toEqual({ current: null, prior: null });
  });

  it("returns current only when one row", () => {
    expect(lastAndPriorValues([{ value: 42 }])).toEqual({ current: 42, prior: null });
  });

  it("returns the last and second-to-last non-null values", () => {
    expect(
      lastAndPriorValues([{ value: 10 }, { value: 20 }, { value: 30 }]),
    ).toEqual({ current: 30, prior: 20 });
  });

  it("skips null cells so a blank row doesn't shift the baseline", () => {
    expect(
      lastAndPriorValues([{ value: 10 }, { value: null }, { value: 30 }]),
    ).toEqual({ current: 30, prior: 10 });
  });

  it("returns null for both when every cell is null", () => {
    expect(
      lastAndPriorValues([{ value: null }, { value: null }]),
    ).toEqual({ current: null, prior: null });
  });
});
