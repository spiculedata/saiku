/*
 * Unit tests for the colour-blind-safe / high-contrast palette overlay (#1091).
 *
 * applyColorBlindSafe() is the pure core: given a resolved token set and the
 * global preference flag, it either returns the tokens untouched (mode off) or
 * overlays the Okabe-Ito palette + the high-contrast flag (mode on). Both chart
 * surfaces (workspace + dashboard) feed the result into the shared builder, so
 * testing the overlay here covers the palette behaviour for both.
 */

import { describe, test, expect } from "vitest";
import {
  applyColorBlindSafe,
  COLORBLIND_SAFE_COLORS,
  COLORBLIND_WATERFALL,
  DEFAULT_THEME_TOKENS,
  CHART_FALLBACK_COLORS,
  type ThemeTokens,
} from "$lib/views/chartTheme";

describe("applyColorBlindSafe", () => {
  test("is a no-op when disabled (returns the same tokens, no high contrast)", () => {
    const result = applyColorBlindSafe(DEFAULT_THEME_TOKENS, false);
    expect(result).toBe(DEFAULT_THEME_TOKENS);
    expect(result.highContrast).toBe(false);
    expect(result.chartColors).toEqual(CHART_FALLBACK_COLORS);
  });

  test("overlays the Okabe-Ito palette and sets highContrast when enabled", () => {
    const result = applyColorBlindSafe(DEFAULT_THEME_TOKENS, true);
    expect(result.highContrast).toBe(true);
    expect(result.chartColors).toEqual(COLORBLIND_SAFE_COLORS);
  });

  test("preserves the non-palette tokens (fg/bg/border/accent) when enabled", () => {
    const dark: ThemeTokens = {
      ...DEFAULT_THEME_TOKENS,
      fg: "#e2e8f0",
      bg: "#0f172a",
      border: "#334155",
      accent: "#818cf8",
    };
    const result = applyColorBlindSafe(dark, true);
    // The pref composes orthogonally with light/dark — only the palette and the
    // contrast flag change; the surface colours come from the active theme.
    expect(result.fg).toBe("#e2e8f0");
    expect(result.bg).toBe("#0f172a");
    expect(result.border).toBe("#334155");
    expect(result.accent).toBe("#818cf8");
  });

  test("does not mutate the input tokens", () => {
    const input: ThemeTokens = { ...DEFAULT_THEME_TOKENS };
    const snapshot = { ...input, chartColors: [...input.chartColors] };
    applyColorBlindSafe(input, true);
    expect(input).toEqual(snapshot);
  });
});

describe("COLORBLIND_SAFE_COLORS (Okabe-Ito palette)", () => {
  test("has the canonical 8 entries", () => {
    expect(COLORBLIND_SAFE_COLORS).toHaveLength(8);
  });

  test("all entries are distinct 6-digit hex colours", () => {
    const set = new Set(COLORBLIND_SAFE_COLORS);
    expect(set.size).toBe(COLORBLIND_SAFE_COLORS.length);
    for (const c of COLORBLIND_SAFE_COLORS) {
      expect(c).toMatch(/^#[0-9a-f]{6}$/);
    }
  });
});

describe("COLORBLIND_WATERFALL", () => {
  test("positive and negative differ and are drawn from the safe palette", () => {
    expect(COLORBLIND_WATERFALL.positive).not.toBe(COLORBLIND_WATERFALL.negative);
    expect(COLORBLIND_SAFE_COLORS).toContain(COLORBLIND_WATERFALL.positive);
    expect(COLORBLIND_SAFE_COLORS).toContain(COLORBLIND_WATERFALL.negative);
  });
});
