import { describe, expect, test } from "vitest";
import {
  THEME_PRESETS,
  DEFAULT_TOKENS,
  presetByKey,
  resolveTokens,
  RADIUS_SCALE,
  SHADOW_SCALE,
  DENSITY_PAD,
} from "./appThemePresets";

describe("appThemePresets", () => {
  test("every preset supplies a complete token set", () => {
    const keys = Object.keys(DEFAULT_TOKENS) as (keyof typeof DEFAULT_TOKENS)[];
    for (const p of THEME_PRESETS) {
      for (const k of keys) {
        expect(p.tokens[k], `${p.key}.${k}`).toBeDefined();
      }
    }
  });

  test("presetByKey resolves known keys and ignores unknown", () => {
    expect(presetByKey("editorial")?.key).toBe("editorial");
    expect(presetByKey("nope")).toBeUndefined();
    expect(presetByKey(undefined)).toBeUndefined();
  });

  test("resolveTokens with no preset returns DEFAULT_TOKENS", () => {
    expect(resolveTokens({ mode: "light" })).toEqual(DEFAULT_TOKENS);
  });

  test("resolveTokens layers DEFAULTS < preset < explicit overrides", () => {
    const t = resolveTokens({ mode: "light", preset: "editorial", accent: "#123456" });
    expect(t.accent).toBe("#123456"); // explicit override wins
    expect(t.ground).toBe("#f2eee4"); // preset value where not overridden
    expect(t.accent2).toBe("#c85a3a"); // preset secondary
  });

  test("legacy bg/font fields map onto the new tokens", () => {
    const t = resolveTokens({ mode: "light", bg: "#010203", font: "serif-1" });
    expect(t.ground).toBe("#010203");
    expect(t.fontDisplay).toBe("serif-1");
    expect(t.fontBody).toBe("serif-1");
  });

  test("explicit token beats the legacy alias", () => {
    const t = resolveTokens({ mode: "light", bg: "#010203", ground: "#0a0b0c" });
    expect(t.ground).toBe("#0a0b0c");
  });

  test("form scales expose every named step", () => {
    for (const r of ["none", "sm", "md", "lg", "xl"] as const) expect(RADIUS_SCALE[r]).toBeDefined();
    for (const s of ["none", "sm", "md", "lg"] as const) expect(SHADOW_SCALE[s]).toBeDefined();
    for (const d of ["compact", "cozy", "comfortable"] as const) expect(DENSITY_PAD[d]).toBeDefined();
    expect(SHADOW_SCALE.none).toBe("none");
  });
});
